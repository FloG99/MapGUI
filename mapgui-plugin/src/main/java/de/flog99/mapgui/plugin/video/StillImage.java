package de.flog99.mapgui.plugin.video;

import de.flog99.mapgui.media.Frames;
import de.flog99.mapgui.ui.Quantizer;

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
 *
 * <p><b>The quantizer is taken rather than assumed</b>, for the reason a GIF's is: a still is decoded once, so
 * this is the only place its dithering can be set, and the whole picture is here at once - which is what error
 * diffusion needs and a painter matching one pixel at a time can never offer. A photograph is the content that
 * gains most from it.
 */
public final class StillImage {

    /**
     * Reads {@code file}, whatever it is.
     *
     * @param quantizer what matches it to the palette, and so what decides its dithering
     * @param maxSize   longest edge to keep it at. A frame is one byte per pixel from here on
     * @throws IOException if nothing available can read it. The message names {@code media.ffmpeg} when that is
     *                     the missing piece, because needing a video decoder for a picture is surprising enough
     *                     that an admin should not have to guess
     */
    public static Frames read(Path file, Quantizer quantizer, int maxSize) throws IOException {
        String refused = null;
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image != null) {
                Pixels.Shrunk shrunk = Pixels.shrink(image, maxSize);
                return MemoryFrames.single(shrunk.width(), shrunk.height(), indices(shrunk, quantizer));
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
        return FfmpegFrames.still(file, quantizer, maxSize);
    }

    /** One frame's palette indices. Package-private so {@link FfmpegFrames} matches its frames the same way. */
    static byte[] indices(Pixels.Shrunk shrunk, Quantizer quantizer) {
        byte[] out = new byte[shrunk.width() * shrunk.height()];
        quantizer.quantize(shrunk.argb(), shrunk.width(), shrunk.height(), out);
        return out;
    }

    private StillImage() {
    }
}
