package de.flog99.mapgui.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Comparing a rendered pixel against the texture patch it came from, without asserting how bright it ended up.
 *
 * <p>Needed because an entity is shaded like anything else in the picture: the side of a head is multiplied by the
 * face-direction factor and by the light where it stands, so a patch painted pure red arrives as a darker red. What
 * these tests are about is <i>which</i> patch was hit, so the brightness is divided out and only the ratio between
 * the channels is compared - which is what identifies the patch.
 */
final class Patches {

    /** Rounding through two multiplies and a truncation, so an exact match is not on offer. */
    private static final int TOLERANCE = 4;

    private Patches() {
    }

    static void assertPatch(int expected, int actual, String message) {
        assertEquals(0xFF, actual >>> 24, message + " (should be opaque)");

        int[] wanted = normalize(expected);
        int[] got = normalize(actual);
        for (int channel = 0; channel < 3; channel++) {
            if (Math.abs(wanted[channel] - got[channel]) > TOLERANCE) {
                // One assert on the whole color rather than per channel, so a failure prints both in full.
                assertEquals(String.format("#%06X", expected & 0xFFFFFF), String.format("#%06X", actual & 0xFFFFFF), message);
            }
        }
    }

    static void assertNotPatch(int expected, int actual, String message) {
        assertNotEquals(java.util.Arrays.toString(normalize(expected)), java.util.Arrays.toString(normalize(actual)), message);
    }

    /** The color with its brightest channel taken up to full, which leaves the ratio and drops the shading. */
    private static int[] normalize(int argb) {
        int red = argb >> 16 & 0xFF;
        int green = argb >> 8 & 0xFF;
        int blue = argb & 0xFF;

        int brightest = Math.max(red, Math.max(green, blue));
        if (brightest == 0) return new int[]{0, 0, 0};

        return new int[]{red * 255 / brightest, green * 255 / brightest, blue * 255 / brightest};
    }
}
