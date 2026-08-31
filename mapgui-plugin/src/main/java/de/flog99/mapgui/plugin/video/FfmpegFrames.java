package de.flog99.mapgui.plugin.video;

import de.flog99.mapgui.media.Frames;
import de.flog99.mapgui.ui.Quantizer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Decoding a whole file into memory with FFmpeg, for the two cases that are not streaming.
 *
 * <p>{@link #still} is a picture ImageIO would not read - WebP, AVIF, HEIC - and, because a decoder does not
 * care how many frames there turn out to be, an animated WebP or an APNG as well. {@link #clip} is a file
 * somebody downloaded to play more than once, sampled down to what a wall can actually show.
 *
 * <p>Separate from {@link FfmpegSource} rather than a mode of it, because these are the opposite trade: a source
 * holds one frame and is told its size up front so nothing full size is ever built, and this holds every frame
 * and so has to be capped. The cap is the whole reason this class is careful - {@code Frames} is one byte per
 * pixel, which is a quarter of packed RGB and still 64 KB a frame at 256 pixels.
 *
 * <p>Never loaded unless {@link VideoNatives#available()}, which is what keeps FFmpeg's classes off the path of
 * a server that never asked for them.
 */
final class FfmpegFrames {

    /**
     * The most frames a still is allowed to have.
     *
     * <p>An animated WebP is a short loop by construction, so this is a guard against a video someone named
     * {@code .webp} rather than a limit anybody meets. Truncated rather than refused: a loop that plays its
     * first minute is closer to what was wanted than a blank wall.
     */
    private static final int STILL_MAX_FRAMES = 600;

    /** What a single frame lasts, so a clock mapped onto it divides by something. */
    private static final int STILL_MS = 100;

    /** A picture, or a short animation, at its own pace. */
    static Frames still(Path file, Quantizer quantizer, int maxSize) throws IOException {
        return decode(file, quantizer, maxSize, 0, STILL_MAX_FRAMES, false, percent -> { });
    }

    /**
     * A downloaded clip, sampled down to {@code fps}.
     *
     * <p>Sampled rather than kept whole because the wall cannot show more: at 30 fps a two minute clip is 3600
     * frames and 230 MB of heap at 256 pixels, and the wall would draw one frame in three of it. At the wall's
     * own rate it is a third of that and identical on screen.
     *
     * @param quantizer what matches every frame to the palette, applied once here rather than once per repaint
     * @param maxFrames refused rather than truncated past this, because a clip cut off halfway is a bug report
     *                  and a refusal with a reason is an answer
     * @param progress  0 to 100 as the decode runs, on this thread
     */
    static Frames clip(Path file, Quantizer quantizer, int maxSize, int fps, int maxFrames, IntConsumer progress)
            throws IOException {
        return decode(file, quantizer, maxSize, Math.max(1, fps), maxFrames, true, progress);
    }

    private static Frames decode(Path file, Quantizer quantizer, int maxSize, int fps, int maxFrames,
                                 boolean refuseAtCap, IntConsumer progress) throws IOException {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file.toFile());
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();

            List<byte[]> frames = new ArrayList<>();
            List<Integer> starts = new ArrayList<>();
            int width = 0;
            int height = 0;
            long total = grabber.getLengthInTime();
            int step = fps > 0 ? Math.max(1, 1000 / fps) : 0;
            int wantedAt = 0;
            int reported = -1;

            while (true) {
                Frame picture = grabber.grabImage();
                if (picture == null) break;

                int at = (int) (grabber.getTimestamp() / 1000);
                // Sampling by timestamp rather than by counting frames: a variable frame rate file, and every
                // HLS recording is one, would otherwise be sped up or slowed down by whatever its average was.
                if (step > 0 && at < wantedAt) continue;

                BufferedImage image = converter.getBufferedImage(picture);
                if (image == null) continue;

                if (frames.size() >= maxFrames) {
                    if (!refuseAtCap) break;

                    throw new IOException("it is longer than " + maxFrames + " frames at " + fps + " fps, which is"
                            + " as much as MapGUI will hold in memory - raise media.download.max-frames, or play"
                            + " it as a stream instead of downloading it");
                }

                Pixels.Shrunk shrunk = Pixels.shrink(image, maxSize);
                width = shrunk.width();
                height = shrunk.height();
                frames.add(StillImage.indices(shrunk, quantizer));
                starts.add(step > 0 ? wantedAt : at);
                wantedAt += step;

                if (total > 0) {
                    int percent = Math.clamp(grabber.getTimestamp() * 100 / total, 0, 100);
                    if (percent != reported) {
                        reported = percent;
                        progress.accept(percent);
                    }
                }
            }

            if (frames.isEmpty()) throw new IOException("FFmpeg opened it but found no pictures in it");

            int[] startsMs = new int[starts.size()];
            for (int i = 0; i < startsMs.length; i++) startsMs[i] = starts.get(i);
            return MemoryFrames.timed(width, height, frames, startsMs, STILL_MS);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // JavaCV throws its own Exception subclasses for a missing codec, a file it cannot open and a
            // native library that failed to load, none of them an IOException.
            throw new IOException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
    }

    private FfmpegFrames() {
    }
}
