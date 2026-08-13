package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Held against a brute-force walk of all twenty-five blocks, which is what the client actually does, over a biome
 * field that changes every cell and is different along x than along z.
 *
 * <p>That is the whole point of testing this: the four-sample form is only worth having if it is the twenty-five
 * sample one, and every way of getting it wrong - a weight off by one, a cell picked on the wrong side of the
 * boundary, the two axes swapped - is a blend that still looks like a blend.
 */
class BiomeBlendTest {

    /**
     * A colour per 4x4 cell, standing in for a biome field where every cell is a different biome.
     *
     * <p>Deliberately not symmetric in x and z: a blend that has the two axes the wrong way round comes out right
     * on any field that is, and wrong on every real world.
     */
    private static int cellColor(int cellX, int cellZ) {
        int scrambled = cellX * 374761393 + cellZ * 668265263;
        scrambled ^= scrambled >>> 13;
        return 0xFF000000
                | Math.floorMod(scrambled, 256) << 16
                | Math.floorMod(scrambled >> 8, 256) << 8
                | Math.floorMod(scrambled >> 16, 256);
    }

    /** The client's own loop: every block of the square, channels summed and then divided. */
    private static int walked(int x, int z) {
        int red = 0;
        int green = 0;
        int blue = 0;
        for (int offsetZ = -BiomeBlend.RADIUS; offsetZ <= BiomeBlend.RADIUS; offsetZ++) {
            for (int offsetX = -BiomeBlend.RADIUS; offsetX <= BiomeBlend.RADIUS; offsetX++) {
                int color = cellColor(x + offsetX >> 2, z + offsetZ >> 2);
                red += color >> 16 & 0xFF;
                green += color >> 8 & 0xFF;
                blue += color & 0xFF;
            }
        }

        int samples = (BiomeBlend.RADIUS * 2 + 1) * (BiomeBlend.RADIUS * 2 + 1);
        return 0xFF000000 | red / samples << 16 | green / samples << 8 | blue / samples;
    }

    /** The four cells the square covers, mixed by their shares of it. */
    private static int sampled(int x, int z) {
        int west = BiomeBlend.low(x) >> 2;
        int east = BiomeBlend.high(x) >> 2;
        int north = BiomeBlend.low(z) >> 2;
        int south = BiomeBlend.high(z) >> 2;

        return BiomeBlend.mix(cellColor(west, north), cellColor(east, north),
                cellColor(west, south), cellColor(east, south),
                BiomeBlend.weight(x), BiomeBlend.weight(z));
    }

    /** Across two cells in each direction and through zero, so every alignment and both signs are covered. */
    @Test
    void fourSamplesAreTheTwentyFiveTheClientWalks() {
        for (int z = -9; z <= 9; z++) {
            for (int x = -9; x <= 9; x++) {
                assertEquals(walked(x, z), sampled(x, z), "at " + x + ", " + z);
            }
        }
    }

    /** Nearly every block in a frame, and the one case that has to come back untouched rather than nearly right. */
    @Test
    void oneBiomeAcrossTheSquareComesOutUnchanged() {
        for (int x = -9; x <= 9; x++) {
            assertEquals(0xFF91BD59, BiomeBlend.mix(0xFF91BD59, 0xFF91BD59, 0xFF91BD59, 0xFF91BD59,
                    BiomeBlend.weight(x), BiomeBlend.weight(-x)));
        }
    }

    /**
     * Both samples inside the square, which is what keeps a blend at the edge of a capture from reading a cell
     * nothing was copied for.
     */
    @Test
    void bothSamplesLieInsideTheSquare() {
        for (int middle = -9; middle <= 9; middle++) {
            assertTrue(BiomeBlend.low(middle) >= middle - BiomeBlend.RADIUS, "low at " + middle);
            assertTrue(BiomeBlend.high(middle) <= middle + BiomeBlend.RADIUS, "high at " + middle);
        }
    }

    /** Two different cells every time, and weights that count the blocks in each. */
    @Test
    void theSquareAlwaysStraddlesExactlyOneBoundary() {
        for (int middle = -9; middle <= 9; middle++) {
            assertEquals((BiomeBlend.low(middle) >> 2) + 1, BiomeBlend.high(middle) >> 2, "cells at " + middle);

            int inLow = 0;
            for (int offset = -BiomeBlend.RADIUS; offset <= BiomeBlend.RADIUS; offset++) {
                if (middle + offset >> 2 == BiomeBlend.low(middle) >> 2) {
                    inLow++;
                }
            }
            assertEquals(inLow, BiomeBlend.weight(middle), "weight at " + middle);
        }
    }
}
