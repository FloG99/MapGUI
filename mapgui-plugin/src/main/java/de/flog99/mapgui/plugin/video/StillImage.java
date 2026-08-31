package de.flog99.mapgui.plugin.video;

import de.flog99.mapgui.media.Frames;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A still picture as a one-frame {@link Frames}, which is what {@link de.flog99.mapgui.media.Picture} draws.
 *
 * <p><b>ImageIO first, always.</b> PNG, JPEG, BMP and WBMP need nothing at all and have always worked, so they
 * must keep working on a server with {@code media.ffmpeg} off - which is why this is the order rather than a
 * preference. FFmpeg is asked only about what ImageIO refused: WebP, AVIF, HEIC, and the JPEG XL of some future
 * build. Animated WebP and APNG fall out of that for free, since FFmpeg hands back every frame it finds and
 * nothing here insists on one.
 *
 * <p>GIF is deliberately not routed here even though ImageIO reads it: {@link de.flog99.mapgui.media.GifFrames}
 * composites frame disposal, which {@code ImageIO.read} does not, so a GIF read as a still is a fragment on a
 * black background.
 */
public final class StillImage {

    /**
     * Reads {@code file}, whatever it is.
     *
     * @param maxSize longest edge to keep it at. A frame is one byte per pixel from here on
     * @throws IOException if nothing available can read it. The message names {@code media.ffmpeg} when that is
     *                     the missing piece, because needing a video decoder for a picture is surprising enough
     *                     that an admin should not have to guess
     */
    public static Frames read(Path file, int maxSize) throws IOException {
        String refused = null;
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image != null) {
                Pixels.Shrunk shrunk = Pixels.shrink(image, maxSize);
                return MemoryFrames.single(shrunk.width(), shrunk.height(), Pixels.quantize(shrunk.argb()));
            }
            refused = "no reader for it";
        } catch (IOException | RuntimeException e) {
            // A reader that recognised the file and then failed on it, which FFmpeg may still manage - a
            // truncated JPEG being the everyday case.
            refused = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }

        if (!VideoNatives.available()) {
            throw new IOException("Java cannot read this image (" + refused + "), and FFmpeg is not loaded."
                    + " WebP, AVIF and HEIC need it: set media.ffmpeg: true in config.yml and restart."
                    + " PNG, JPEG, BMP and GIF never do.");
        }
        return FfmpegFrames.still(file, maxSize);
    }

    private StillImage() {
    }
}
