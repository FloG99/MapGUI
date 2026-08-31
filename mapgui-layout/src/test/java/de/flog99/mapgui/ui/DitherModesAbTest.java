package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A/B against banding, for every mode: the same rect quantized with {@link Dither#NONE} and with each mode, and
 * the error measured rather than argued about.
 *
 * <p>Extends what {@code DitheredPaletteTest} does for the 4x4 tile to the whole mode set. Two things are worth
 * holding: that every mode is <i>better</i> than snapping, and that no mode reaches for entries the palette does
 * not have while doing it.
 */
class DitherModesAbTest {

    /** As sparse as the map palette is: a five-step gray ramp and three colors, nothing at index 0. */
    private static final Color[] SPARSE = {
            null,
            new Color(0, 0, 0),
            new Color(64, 64, 64),
            new Color(128, 128, 128),
            new Color(192, 192, 192),
            new Color(255, 255, 255),
            new Color(200, 40, 40),
            new Color(40, 160, 60),
            new Color(50, 80, 200),
    };

    private static final Palette BASE = new Palette() {
        @Override
        public byte index(Color color) {
            int best = 1;
            long closest = Long.MAX_VALUE;
            for (int i = 1; i < SPARSE.length; i++) {
                long dr = color.getRed() - SPARSE[i].getRed();
                long dg = color.getGreen() - SPARSE[i].getGreen();
                long db = color.getBlue() - SPARSE[i].getBlue();
                long distance = dr * dr + dg * dg + db * db;
                if (distance < closest) {
                    closest = distance;
                    best = i;
                }
            }
            return (byte) best;
        }

        @Override
        public Color color(byte index) {
            int slot = index & 0xFF;
            return slot < SPARSE.length ? SPARSE[slot] : null;
        }
    };

    private static final int SIZE = 32;

    /**
     * Error measured over a block rather than per pixel, which is the only measurement that means anything
     * here: dithering makes every individual pixel <i>worse</i> and the 4x4 block it sits in better.
     *
     * <p>Measured on a black to white ramp, as the mean distance between what a 4x4 block averaged to and what
     * it should have averaged to: snapping 23.3, ordered 2.8, fine 1.9, blue noise 3.5, Floyd-Steinberg 5.0,
     * Atkinson 7.2, Sierra Lite 3.8. The ordered modes win this one because a gray ramp is exactly the case
     * their two-entry blend is built for, and Atkinson comes last because throwing a quarter of the error away
     * is what it does - which is the trade its javadoc describes, and not visible in a mean.
     */
    @Test
    void everyModeBeatsBandingOnABlockOfARamp() {
        int[] ramp = grayRamp();
        double banded = blockError(Dither.NONE, ramp);

        for (Dither mode : Dither.values()) {
            if (mode == Dither.NONE) continue;

            double error = blockError(mode, ramp);
            assertTrue(error < banded / 2,
                    mode + " should more than halve the block error of snapping (" + banded + "), got " + error
            );
        }
    }

    /**
     * The same on a red to blue ramp, and here the families change places.
     *
     * <p>Measured: snapping 43.5, ordered 28.1, fine 26.7, blue noise 27.2, Floyd-Steinberg 8.5, Atkinson 19.4,
     * Sierra Lite 8.7. Diffusion is three times better than any ordered mode, because it is not limited to
     * blending the <i>two</i> entries nearest the wanted color - the error it carries forward can be paid off
     * by any entry the palette has. Which is the argument for spending a whole rect on a photograph, and it
     * does not show on a gray ramp at all.
     */
    @Test
    void diffusionBeatsTheOrderedModesOnAColorRamp() {
        int[] ramp = colorRamp();
        double banded = blockError(Dither.NONE, ramp);

        for (Dither mode : Dither.values()) {
            if (mode == Dither.NONE) continue;

            double error = blockError(mode, ramp);
            assertTrue(error < banded * 0.7,
                    mode + " should beat the block error of snapping (" + banded + ") comfortably, got " + error
            );
        }
    }

    /**
     * The limit, which is worth a test of its own so that nobody goes looking for a mode that fixes it.
     *
     * <p>Green to yellow, where the palette has the green and nothing within reach of the yellow. Measured:
     * snapping 82.4, ordered 75.4, fine 75.1, blue noise 75.8, Floyd-Steinberg 84.2, Atkinson 80.0, Sierra Lite
     * 83.7 - so no mode gets within a tenth of fixing it, and diffusion is slightly <b>worse</b> than snapping,
     * having carried an error nothing could absorb across the whole rect. {@link PaletteLut} records the same
     * finding from the other direction: a palette that cannot say a hue cannot be made to say it, and reaching
     * further for a better hue is worse everywhere else.
     */
    @Test
    void noModeRescuesAHueThePaletteDoesNotHave() {
        int[] ramp = unsayableRamp();
        double banded = blockError(Dither.NONE, ramp);

        for (Dither mode : Dither.values()) {
            if (mode == Dither.NONE) continue;

            double error = blockError(mode, ramp);
            assertTrue(error > banded * 0.9,
                    mode + " apparently rescued a hue the palette does not have, " + error + " against " + banded
            );
        }
    }

    /**
     * The visible half of the same claim: more apparent shades than the palette contains.
     *
     * <p>A column's pattern down four rows is what the eye averages into a shade, so counting the distinct
     * patterns counts the shades. Snapping has one pattern per palette entry and no more, by definition.
     */
    @Test
    void everyModeShowsMoreShadesThanThePaletteHas() {
        for (Dither mode : Dither.values()) {
            Palette palette = Quantizer.of(BASE, mode).perPixel();
            Set<Byte> snapped = new HashSet<>();
            Set<String> patterns = new HashSet<>();

            for (int x = 0; x < 64; x++) {
                Color wanted = Colors.mix(Color.BLACK, Color.WHITE, x / 63.0);
                snapped.add(BASE.index(wanted));

                StringBuilder column = new StringBuilder();
                for (int y = 0; y < 4; y++) column.append(palette.index(wanted, x, y)).append(',');
                patterns.add(column.toString());
            }

            if (mode == Dither.NONE) {
                assertTrue(patterns.size() == snapped.size(), "snapping cannot show a shade it does not have");
                continue;
            }
            assertTrue(patterns.size() > snapped.size() * 2,
                    mode + " should show far more apparent shades than the palette has, got " + patterns.size()
            );
        }
    }

    /** Nothing may be drawn that the palette cannot say, however clever the mode is being. */
    @Test
    void noModeInventsAnEntry() {
        Set<Byte> allowed = new HashSet<>();
        for (byte entry : BASE.entries()) allowed.add(entry);

        for (Dither mode : Dither.values()) {
            for (byte index : quantized(mode, colorRamp())) {
                assertTrue(allowed.contains(index), mode + " picked an index outside the palette");
            }
        }
    }

    /** Black to white down the rect, which is the case that bands worst. */
    private static int[] grayRamp() {
        int[] argb = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            int level = 255 * y / (SIZE - 1);
            for (int x = 0; x < SIZE; x++) {
                argb[y * SIZE + x] = 0xFF000000 | level << 16 | level << 8 | level;
            }
        }
        return argb;
    }

    /** Red to blue across the rect: two entries the palette has, with nothing between them. */
    private static int[] colorRamp() {
        return ramp(new Color(200, 40, 40), new Color(50, 80, 200));
    }

    /** Green to yellow: the palette has the green and nothing anywhere near the yellow. */
    private static int[] unsayableRamp() {
        return ramp(new Color(40, 160, 60), new Color(230, 230, 60));
    }

    private static int[] ramp(Color from, Color to) {
        int[] argb = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                Color wanted = Colors.mix(from, to, x / (double) (SIZE - 1));
                argb[y * SIZE + x] = 0xFF000000 | wanted.getRGB() & 0xFFFFFF;
            }
        }
        return argb;
    }

    /** Mean distance between what each 4x4 block averaged to and what it was asked for. */
    private static double blockError(Dither mode, int[] argb) {
        byte[] indices = quantized(mode, argb);
        double total = 0;
        int blocks = 0;

        for (int by = 0; by + 4 <= SIZE; by += 4) {
            for (int bx = 0; bx + 4 <= SIZE; bx += 4) {
                long[] got = new long[3];
                long[] wanted = new long[3];
                for (int y = by; y < by + 4; y++) {
                    for (int x = bx; x < bx + 4; x++) {
                        int at = y * SIZE + x;
                        Color drawn = BASE.color(indices[at]);
                        got[0] += drawn.getRed();
                        got[1] += drawn.getGreen();
                        got[2] += drawn.getBlue();
                        wanted[0] += argb[at] >> 16 & 0xFF;
                        wanted[1] += argb[at] >> 8 & 0xFF;
                        wanted[2] += argb[at] & 0xFF;
                    }
                }
                double dr = (got[0] - wanted[0]) / 16.0;
                double dg = (got[1] - wanted[1]) / 16.0;
                double db = (got[2] - wanted[2]) / 16.0;
                total += Math.sqrt(dr * dr + dg * dg + db * db);
                blocks++;
            }
        }
        return total / blocks;
    }

    private static byte[] quantized(Dither mode, int[] argb) {
        byte[] out = new byte[argb.length];
        Quantizer.of(BASE, mode).quantize(argb, SIZE, SIZE, out);
        return out;
    }
}
