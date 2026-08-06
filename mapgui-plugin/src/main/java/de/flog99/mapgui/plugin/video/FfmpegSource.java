package de.flog99.mapgui.plugin.video;

import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.media.LiveSource;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * A video file or a live stream, decoded by FFmpeg on a thread of its own.
 *
 * <p>Anything FFmpeg can open works the same way: an mp4 on disk, an HTTP url, an HLS playlist, an RTMP or
 * RTSP feed. What it is only decides whether the end of it means anything.
 *
 * <p>Three things happen off the main thread, and they are the three expensive ones: demuxing, decoding, and
 * scaling to the size the wall wants. What crosses back is one finished array of palette indices per frame,
 * published whole so a painting main thread either sees the last picture or this one and never half of each.
 *
 * <p>Scaling is FFmpeg's, not ours: the grabber is told the size up front, so a 1080p stream is scaled down
 * inside the decoder and no full-size image is ever built. Quantizing is a table lookup per pixel, which is
 * the only reason it is affordable at all.
 */
public final class FfmpegSource implements LiveSource {

    private final String source;
    private final int width;
    private final int height;
    private final boolean loop;
    private final Thread thread;

    /** Replaced whole, never written into, which is what makes reading it from another thread safe. */
    private volatile byte @Nullable [] frame;
    private volatile boolean running = true;

    @Nullable
    private volatile String error;

    /**
     * @param source what FFmpeg should open - a file path or a url
     * @param width  the size to decode to, which should be the size it will be drawn at
     * @param loop   start again at the end, which is what a file wants and a stream cannot do
     */
    public FfmpegSource(String source, int width, int height, boolean loop) {
        this.source = source;
        this.width = width;
        this.height = height;
        this.loop = loop;

        this.thread = new Thread(this::decode, "MapGUI-video");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public byte @Nullable [] frame() {
        return frame;
    }

    @Override
    public boolean running() {
        return running;
    }

    @Override
    @Nullable
    public String error() {
        return error;
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }

    private void decode() {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(source);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {

            // Scaled by the decoder, so nothing full size is ever allocated.
            grabber.setImageWidth(width);
            grabber.setImageHeight(height);
            // Anything but TCP loses packets on a busy server and shows it as smeared frames.
            grabber.setOption("rtsp_transport", "tcp");
            grabber.start();

            int[] argb = new int[width * height];
            long frameMs = Math.max(1, Math.round(1000 / Math.max(1, grabber.getFrameRate())));
            long next = System.currentTimeMillis();

            while (running && !Thread.currentThread().isInterrupted()) {
                Frame picture = grabber.grabImage();

                if (picture == null) {
                    if (!loop) break;

                    grabber.setTimestamp(0);
                    continue;
                }

                BufferedImage image = converter.getBufferedImage(picture);
                if (image == null) continue;

                byte[] indices = new byte[width * height];
                if (!quantize(image, indices)) {
                    image.getRGB(0, 0, width, height, argb, 0, width);
                    MapColors.INSTANCE.quantize(argb, indices);
                }
                frame = indices;

                // Decoding a file runs as fast as the disk allows, which would play an hour of video in a
                // minute. A live stream paces itself and never waits here.
                next += frameMs;
                long sleep = next - System.currentTimeMillis();
                if (sleep > 0) {
                    Thread.sleep(sleep);
                } else {
                    next = System.currentTimeMillis();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Anything at all: a missing file, a codec FFmpeg was not built with, a stream that went away.
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            running = false;
        }
    }

    /**
     * Quantizes straight off the raster, which is the layout the decoder hands back for ordinary video.
     *
     * <p>Worth the special case because the general way round - {@code BufferedImage.getRGB} - runs every pixel
     * through the image's colour model one call at a time, and at 256x256 and 30 fps that is two million of
     * them a second. Reading the bytes and packing them here is the same arithmetic without the dispatch.
     *
     * <p>Anything else, including a padded scanline, falls through to the slow path rather than guessing.
     *
     * @return false if this image is not laid out the way it expects, in which case nothing was written
     */
    private static boolean quantize(BufferedImage image, byte[] out) {
        if (image.getType() != BufferedImage.TYPE_3BYTE_BGR) return false;

        byte[] bgr = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        if (bgr.length != out.length * 3) return false;

        for (int pixel = 0, at = 0; pixel < out.length; pixel++, at += 3) {
            int rgb = (bgr[at + 2] & 0xFF) << 16 | (bgr[at + 1] & 0xFF) << 8 | (bgr[at] & 0xFF);
            out[pixel] = MapColors.INSTANCE.index(rgb);
        }
        return true;
    }
}
