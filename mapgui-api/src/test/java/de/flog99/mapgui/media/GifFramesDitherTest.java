package de.flog99.mapgui.media;

import de.flog99.mapgui.ui.Dither;
import de.flog99.mapgui.ui.Palette;
import de.flog99.mapgui.ui.Quantizer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Dithering an animation happens at decode, because by paint time its pixels are palette indices. Which puts
 * the error diffusion family in reach of a transparent GIF, and that is the thing to hold: a hole in the picture
 * must not grow a rim of whatever color bordered it.
 */
class GifFramesDitherTest {

    /**
     * Two entries, neither of them able to say the color in the GIF - so every opaque pixel leaves a large
     * residual for diffusion to carry, which is what makes a leak across the transparent edge visible.
     */
    private static final Palette PALETTE = new Palette() {
        @Override
        public byte index(Color color) {
            return (byte) (color.getRed() > 127 ? 10 : 20);
        }

        @Override
        public Color color(byte index) {
            return switch (index & 0xFF) {
                case 10 -> Color.RED;
                case 20 -> Color.BLUE;
                default -> null;
            };
        }
    };

    private static final Dither[] DIFFUSING = {Dither.FLOYD_STEINBERG, Dither.ATKINSON, Dither.SIERRA_LITE};

    /**
     * A 4x1 GIF whose left half is a dark red the palette cannot say and whose right half is see-through,
     * written through the indexed color model that is how a GIF carries transparency.
     *
     * <p>One row rather than two deliberately: ImageIO's GIF writer does not carry the transparent index into
     * the second row of an image this small, so a two-row fixture would be testing the writer. The vertical
     * half of the same rule is held at the quantizer, over a 16x16 rect, by {@code QuantizerTest}.
     */
    private static byte[] halfTransparentGif() throws IOException {
        byte[] reds = {(byte) 128, 0};
        byte[] greens = {0, 0};
        byte[] blues = {0, 0};
        // The second entry is the transparent one.
        IndexColorModel colors = new IndexColorModel(2, 2, reds, greens, blues, 1);

        BufferedImage image = new BufferedImage(4, 1, BufferedImage.TYPE_BYTE_INDEXED, colors);
        for (int x = 0; x < 4; x++) image.getRaster().setSample(x, 0, 0, x < 2 ? 0 : 1);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "gif", out);
        return out.toByteArray();
    }

    /**
     * The bug this guards: error pushed into a see-through pixel, or pulled out of one, turns the first pixels
     * of a hole into a halo of the color next to it. Held for every diffusing mode, since each has its own
     * kernel and each could get it wrong on its own.
     */
    @Test
    void aTransparentHalfGrowsNoHalo() throws IOException {
        for (Dither mode : DIFFUSING) {
            GifFrames frames = GifFrames.read(new ByteArrayInputStream(halfTransparentGif()), Quantizer.of(PALETTE, mode));
            byte[] pixels = frames.pixels(0);

            assertEquals(4, frames.width());
            assertEquals(1, frames.height());

            for (int x = 0; x < 2; x++) {
                assertNotEquals(Frames.TRANSPARENT, pixels[x], mode + " left an opaque pixel undrawn at " + x);
            }
            for (int x = 2; x < 4; x++) {
                assertEquals(Frames.TRANSPARENT, pixels[x], mode + " grew a halo into the see-through half at " + x);
            }
        }
    }

    /** And the mode has to actually be doing something, or the test above would pass on a no-op. */
    @Test
    void diffusionChangesWhatTheOpaqueHalfIsMatchedTo() throws IOException {
        byte[] snapped = GifFrames.read(new ByteArrayInputStream(halfTransparentGif()), Quantizer.of(PALETTE)).pixels(0);
        byte[] diffused = GifFrames.read(
                new ByteArrayInputStream(halfTransparentGif()),
                Quantizer.of(PALETTE, Dither.FLOYD_STEINBERG)
        ).pixels(0);

        assertEquals(snapped[0], diffused[0], "the first pixel has no error to carry into it yet");
        assertNotEquals(snapped[1], diffused[1], "the second should have taken the first pixel's residual");
    }
}
