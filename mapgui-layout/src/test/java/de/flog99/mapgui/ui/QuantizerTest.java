package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantizerTest {

    /**
     * A gray ramp four steps apart, plus three colors, with nothing at index 0 - which is how the real palette
     * is arranged and what lets that index mean "transparent". Deliberately sparse: a ramp between two of these
     * grays has nowhere to land, which is the whole reason any of this exists.
     */
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

    private static final Dither[] ORDERED = {Dither.ORDERED, Dither.ORDERED_FINE, Dither.BLUE_NOISE};
    private static final Dither[] DIFFUSING = {Dither.FLOYD_STEINBERG, Dither.ATKINSON, Dither.SIERRA_LITE};

    /** Halfway between two palette grays, so neither is right and nearest-entry matching has to be wrong. */
    private static final int MIDTONE = 0xFF606060;

    @Test
    void everyModeAnswersForTheFamilyItBelongsTo() {
        assertFalse(Dither.NONE.diffuses());
        for (Dither mode : ORDERED) {
            assertFalse(mode.diffuses(), mode + " is an ordered mode");
            assertFalse(Quantizer.of(BASE, mode).diffuses(), mode + " needs no rect");
        }
        for (Dither mode : DIFFUSING) {
            assertTrue(mode.diffuses(), mode + " needs a whole rect");
            assertTrue(Quantizer.of(BASE, mode).diffuses(), mode + " needs a whole rect");
        }
    }

    @Test
    void anOrderedModeIsDeterministicAtEveryPosition() {
        for (Dither mode : ORDERED) {
            Palette palette = Quantizer.of(BASE, mode).perPixel();
            for (int y = 0; y < 20; y++) {
                for (int x = 0; x < 20; x++) {
                    assertEquals(palette.index(MIDTONE, x, y), palette.index(MIDTONE, x, y),
                            mode + " must answer the same way twice at (" + x + "," + y + ")"
                    );
                }
            }
        }
    }

    /** The mechanism itself: an ordered mode is a function of position, so it has to vary with position. */
    @Test
    void anOrderedModeVariesWithPositionAndNoneDoesNot() {
        Palette plain = Quantizer.of(BASE, Dither.NONE).perPixel();
        byte flat = plain.index(MIDTONE, 0, 0);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(flat, plain.index(MIDTONE, x, y), "NONE snaps, wherever the pixel is");
            }
        }

        for (Dither mode : ORDERED) {
            Palette palette = Quantizer.of(BASE, mode).perPixel();
            Set<Byte> seen = new HashSet<>();
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) seen.add(palette.index(MIDTONE, x, y));
            }
            assertTrue(seen.size() > 1, mode + " picked the same entry everywhere, so it is not dithering");
        }
    }

    /**
     * A diffusing mode stands in {@link Dither#ORDERED_FINE} where there is no rect, and says so. The tile is
     * the 8x8 one, which shows as more than sixteen distinct patterns across sixteen columns.
     */
    @Test
    void aDiffusingModeStandsInAnOrderedOneForThePerPixelPath() {
        Palette fine = Quantizer.of(BASE, Dither.ORDERED_FINE).perPixel();

        for (Dither mode : DIFFUSING) {
            Palette standIn = Quantizer.of(BASE, mode).perPixel();
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    assertEquals(fine.index(MIDTONE, x, y), standIn.index(MIDTONE, x, y),
                            mode + " should stand in the fine ordered tile at (" + x + "," + y + ")"
                    );
                }
            }
        }
    }

    /**
     * What error diffusion is for: the average of what was drawn is the color that was asked for, even though
     * no single pixel can be. Nearest-entry matching is out by the whole of the gap, every pixel, forever.
     */
    @Test
    void aDiffusingModeConservesTheTotalError() {
        int size = 32;
        int[] argb = new int[size * size];
        Arrays.fill(argb, MIDTONE);
        int wanted = MIDTONE & 0xFF;

        int flat = Math.abs(meanOf(quantized(Dither.NONE, argb, size, size), 0) - wanted);
        assertTrue(flat > 20, "nearest-entry matching should be out by most of the gap, was " + flat);

        for (Dither mode : DIFFUSING) {
            int mean = meanOf(quantized(mode, argb, size, size), 0);
            assertTrue(Math.abs(mean - wanted) <= 4,
                    mode + " should average out to " + wanted + " across the rect, got " + mean
            );
        }
    }

    /** And it has to do it on a ramp too, which is the content it actually meets. */
    @Test
    void aDiffusingModeConservesTheTotalErrorAcrossARamp() {
        int size = 32;
        int[] argb = new int[size * size];
        long wanted = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int level = 255 * x / (size - 1);
                argb[y * size + x] = 0xFF000000 | level << 16 | level << 8 | level;
                wanted += level;
            }
        }
        int mean = (int) (wanted / argb.length);

        for (Dither mode : DIFFUSING) {
            int got = meanOf(quantized(mode, argb, size, size), 0);
            assertTrue(Math.abs(got - mean) <= 4,
                    mode + " should average out to " + mean + " across the ramp, got " + got
            );
        }
    }

    /**
     * The halo rule, half one. Error must not be pulled out of a see-through pixel: a transparent pixel still
     * carries whatever color was underneath its alpha, and reading it would smear an invisible color into the
     * picture next to it.
     */
    @Test
    void diffusionPullsNoErrorOutOfATransparentPixel() {
        for (Dither mode : DIFFUSING) {
            byte[] besideGreen = quantized(mode, halfTransparent(0x0000FF00), 16, 16);
            byte[] besideWhite = quantized(mode, halfTransparent(0x00FFFFFF), 16, 16);

            assertArrayEquals(besideGreen, besideWhite,
                    mode + " read the color hidden under a transparent pixel"
            );
        }
    }

    /**
     * The halo rule, half two. Error must not be pushed into one either: whatever the opaque half was short of
     * has to die at the edge rather than turning the hole's first pixels into a rim of it.
     *
     * <p>Held by comparing against the same opaque half with nothing beside it at all. If error crossed the
     * edge in either direction the two would differ, and the transparent pixels would stop being transparent.
     */
    @Test
    void diffusionPushesNoErrorIntoATransparentPixel() {
        for (Dither mode : DIFFUSING) {
            byte[] beside = quantized(mode, halfTransparent(0x00000000), 16, 16);

            int[] alone = new int[8 * 16];
            Arrays.fill(alone, MIDTONE);
            byte[] onItsOwn = quantized(mode, alone, 8, 16);

            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 8; x++) {
                    assertEquals(onItsOwn[y * 8 + x], beside[y * 16 + x],
                            mode + " drew the opaque half differently for having a hole next to it, at ("
                                    + x + "," + y + ")"
                    );
                }
                for (int x = 8; x < 16; x++) {
                    assertEquals(Quantizer.TRANSPARENT, beside[y * 16 + x],
                            mode + " grew a halo into the transparent half at (" + x + "," + y + ")"
                    );
                }
            }
        }
    }

    /** The same rule for the ordered family, which shares the region path. */
    @Test
    void anOrderedModeLeavesATransparentPixelAlone() {
        for (Dither mode : ORDERED) {
            byte[] indices = quantized(mode, halfTransparent(0x00FFFFFF), 16, 16);
            for (int y = 0; y < 16; y++) {
                for (int x = 8; x < 16; x++) {
                    assertEquals(Quantizer.TRANSPARENT, indices[y * 16 + x], mode + " matched a see-through pixel");
                }
            }
        }
    }

    /** The region path and the per-pixel path are the same answer for an ordered mode, pixel for pixel. */
    @Test
    void theRegionPathAgreesWithThePerPixelPathForEveryOrderedMode() {
        int size = 16;
        int[] argb = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int level = 255 * (x + y) / (2 * size - 2);
                argb[y * size + x] = 0xFF000000 | level << 16 | (255 - level) << 8 | 96;
            }
        }

        for (Dither mode : ORDERED) {
            Quantizer quantizer = Quantizer.of(BASE, mode);
            byte[] region = new byte[argb.length];
            quantizer.quantize(argb, size, size, region);

            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    assertEquals(quantizer.perPixel().index(argb[y * size + x], x, y), region[y * size + x],
                            mode + " disagreed with itself at (" + x + "," + y + ")"
                    );
                }
            }
        }
    }

    /** Every mode has to pick something the palette can actually draw. */
    @Test
    void everyChosenIndexIsARealEntry() {
        Set<Byte> allowed = new HashSet<>();
        for (byte entry : BASE.entries()) allowed.add(entry);
        assertFalse(allowed.contains((byte) 0), "index 0 is not an entry of this palette");

        int size = 16;
        int[] argb = new int[size * size];
        for (int i = 0; i < argb.length; i++) {
            argb[i] = 0xFF000000 | (i * 7919) & 0xFFFFFF;
        }

        for (Dither mode : Dither.values()) {
            byte[] indices = quantized(mode, argb, size, size);
            for (byte index : indices) {
                assertTrue(allowed.contains(index), mode + " picked an index that is not in the palette");
            }
        }
    }

    /** The blue noise tile is aperiodic, which is the one thing it exists to be. */
    @Test
    void blueNoiseHasNoGridInIt() {
        OrderedMatrix noise = OrderedMatrix.blueNoise();
        assertEquals(256, noise.levels());

        Set<Integer> levels = new HashSet<>();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) levels.add(noise.threshold(x, y));
        }
        assertEquals(256, levels.size(), "every level should appear exactly once in the tile");

        // A Bayer tile of any size repeats every four pixels in both axes; blue noise must not.
        int matches = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if (noise.threshold(x, y) == noise.threshold(x + 4, y)) matches++;
            }
        }
        assertTrue(matches < 16, "the blue noise tile lines up with a four-pixel grid " + matches + " times of 256");
    }

    /** Generated, so it has to come out the same every time or a render is not reproducible. */
    @Test
    void blueNoiseIsTheSameTileEveryTime() {
        assertEquals(OrderedMatrix.blueNoise().threshold(3, 7), OrderedMatrix.blueNoise().threshold(3, 7));
        assertEquals(16, OrderedMatrix.bayer4().levels());
        assertEquals(64, OrderedMatrix.bayer8().levels());
    }

    /** 16 wide by 16 tall: the left half opaque, the right half see-through over the given hidden color. */
    private static int[] halfTransparent(int hidden) {
        int[] argb = new int[16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                argb[y * 16 + x] = x < 8 ? MIDTONE : hidden;
            }
        }
        return argb;
    }

    private static byte[] quantized(Dither mode, int[] argb, int width, int height) {
        byte[] out = new byte[argb.length];
        Quantizer.of(BASE, mode).quantize(argb, width, height, out);
        return out;
    }

    /** The mean of one channel of what was actually drawn, skipping transparent pixels. */
    private static int meanOf(byte[] indices, int transparent) {
        long total = 0;
        int counted = 0;
        for (byte index : indices) {
            if (index == transparent) continue;

            total += BASE.color(index).getBlue();
            counted++;
        }
        return (int) (total / counted);
    }
}
