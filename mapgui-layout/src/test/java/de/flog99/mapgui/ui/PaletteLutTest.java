package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A palette of two colors and nothing between them, which is the situation the real one is in more often than it
 * looks: 244 entries over the whole cube leaves plenty of colors a long way from all of them.
 */
class PaletteLutTest {

    private static final byte WARM = 1;
    private static final byte COOL = 2;

    /** Two dark greens either side of neutral, standing in for the warm-skewed ones the map palette actually has. */
    private static final class TwoGreens implements Palette {

        @Override
        public byte index(Color color) {
            return distance(color, color(WARM)) <= distance(color, color(COOL)) ? WARM : COOL;
        }

        @Override
        public Color color(byte index) {
            return index == WARM ? new Color(70, 75, 35) : new Color(20, 75, 60);
        }

        @Override
        public byte[] entries() {
            return new byte[]{WARM, COOL};
        }

        private static int distance(Color from, Color to) {
            int red = from.getRed() - to.getRed();
            int green = from.getGreen() - to.getGreen();
            int blue = from.getBlue() - to.getBlue();
            return red * red + green * green + blue * blue;
        }
    }

    private static int[] flat(int argb, int pixels) {
        int[] field = new int[pixels * pixels];
        Arrays.fill(field, argb);
        return field;
    }

    private static byte[] quantized(int[] field) {
        byte[] out = new byte[field.length];
        new PaletteLut(new TwoGreens()).quantize(field, out);
        return out;
    }

    private static Set<Byte> distinct(byte[] indices) {
        Set<Byte> seen = new HashSet<>();
        for (byte index : indices) {
            seen.add(index);
        }
        return seen;
    }

    /**
     * One color in, one index out, however far that index is from the color.
     *
     * <p>This pins the absence of dithering, which was added twice and taken out twice. Both times the arithmetic said
     * it helped and the picture said otherwise: over 128 pixels the pattern reads as grain, and a slightly wrong flat
     * tone beats an obviously stippled one. Anything that spreads a flat surface across two entries again should fail
     * here and be looked at rather than shipped.
     */
    @Test
    void aFlatSurfaceComesOutFlat() {
        assertEquals(1, distinct(quantized(flat(0xFF2F4B2F, 16))).size(),
                "a single color must map to a single index, even sitting between two entries");
    }

    @Test
    void theNearestEntryIsTheOneChosen() {
        // Nearer the cool green than the warm one, so it has to be the cool one.
        assertEquals(Set.of(COOL), distinct(quantized(flat(0xFF19503C, 16))));
        assertEquals(Set.of(WARM), distinct(quantized(flat(0xFF464B23, 16))));
    }

    /** Retaking the same shot has to give the same bytes, or a still on a wall appears to move. */
    @Test
    void theSameFrameQuantizesTheSameWayTwice() {
        int[] field = flat(0xFF2F4B2F, 16);

        assertArrayEquals(quantized(field), quantized(field));
    }
}
