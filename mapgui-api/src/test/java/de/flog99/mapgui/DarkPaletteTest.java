package de.flog99.mapgui;

import org.bukkit.map.MapPalette;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the palette does with dark colours, which is where it used to add red that was not there.
 *
 * <p>Reported as "it turns stuff a bit red when it's dark", worst on leaves. Two things were doing it, and both are about
 * the bottom of the range being crowded: the palette's dark neutrals are four apart - (13,13,13), (17,17,17), (21,21,21),
 * (25,25,25) - and one of the two darkest entries of all, TERRACOTTA_BLACK at rgb(19,11,8), is warm.
 *
 * <ul>
 *   <li>The lookup table rounded each channel <b>down</b> to an eight-wide cell, which is wider than those gaps, so every
 *       dark colour was dragged toward the darkest entries there are. Fixed with a second, finer table over the dark
 *       corner of the cube - see {@code PaletteLut.DARK_TOP}.
 *   <li>Vanilla's matcher itself prefers the warm black down there: it counts green four times and lets blue error off
 *       lightly, which is a fair way to compare two colours and the wrong way to compare two <i>greys</i>. So the dark
 *       table measures distance straight instead.
 * </ul>
 *
 * <p>Stated against the colour being drawn rather than against another matcher, since the point is what comes out, not
 * whose formula it agrees with.
 */
@SuppressWarnings("removal")
class DarkPaletteTest {

    /** The top of the range the finer table covers, matching {@code PaletteLut.DARK_TOP}. */
    private static final int DARK = 64;

    private static Color drawn(int r, int g, int b) {
        return MapColors.INSTANCE.color(MapColors.INSTANCE.index(new Color(r, g, b)));
    }

    /** What the old path gave: five bits, rounded down, then vanilla's own matcher. */
    private static Color asItWas(int r, int g, int b) {
        Color rounded = new Color((r >> 3) * 255 / 31, (g >> 3) * 255 / 31, (b >> 3) * 255 / 31);
        return MapColors.INSTANCE.color(MapPalette.matchColor(rounded));
    }

    /** Red above blue, which is what "a bit red" means when the thing being drawn is grey. */
    private static int warmth(Color of) {
        return of.getRed() - of.getBlue();
    }

    private static long apart(Color from, int r, int g, int b) {
        long dr = from.getRed() - r;
        long dg = from.getGreen() - g;
        long db = from.getBlue() - b;
        return dr * dr + dg * dg + db * db;
    }

    /**
     * A grey stays grey, all the way down to almost nothing.
     *
     * <p>The plainest form of the complaint: stone in a dim room is neutral, and nothing about drawing it on a map should
     * make it warm. This failed at grey 32 before - it came out rgb(40,28,24), a brown.
     */
    @Test
    void darkGreysStayGrey() {
        for (int level = 4; level < DARK; level++) {
            Color painted = drawn(level, level, level);

            assertTrue(Math.abs(warmth(painted)) <= 4,
                    "grey " + level + " came out at " + painted + ", which is not grey");
        }
    }

    /**
     * Nothing <b>near-neutral</b> comes out warm.
     *
     * <p>The general form of the grey test, and the shape of the bug rather than one case of it: a cast is a lean in one
     * direction. Near-neutral rather than everything, because a colour the palette cannot say has to land on whatever is
     * nearest and that may be warmer than it - a saturated dark blue lands on a grey, since there is no dark blue entry at
     * all, and nothing about that is a cast.
     */
    @Test
    void nothingNearlyNeutralComesOutWarm() {
        int worst = 0;
        String at = "";

        for (int r = 0; r < DARK; r++) {
            for (int g = 0; g < DARK; g++) {
                for (int b = 0; b < DARK; b++) {
                    // Within a few of each other on every channel: dim stone, deepslate, a shadowed path.
                    if (Math.abs(r - b) > 4 || Math.abs(g - b) > 4 || Math.abs(r - g) > 4) continue;

                    int warmth = warmth(drawn(r, g, b));
                    if (warmth > worst) {
                        worst = warmth;
                        at = "rgb(" + r + "," + g + "," + b + ") -> " + drawn(r, g, b);
                    }
                }
            }
        }

        assertTrue(worst <= 6, "a near-neutral dark colour came out " + worst + " warm, at " + at);
    }

    /**
     * The hue it draws a dark colour with is far closer than the old rounding managed.
     *
     * <p>The regression test for the rounding itself, measured where rounding actually decided the answer: on colours near
     * the neutral ramp, which is what a dim room is mostly made of. Stated as <b>colour</b> error rather than distance,
     * since distance in the dark is dominated by colours the palette cannot say at all - a saturated dark blue is far from
     * every entry however it is rounded.
     */
    @Test
    void theHueIsFarCloserThanTheOldRoundingManaged() {
        long now = 0;
        long before = 0;
        int counted = 0;

        for (int r = 0; r < DARK; r++) {
            for (int g = 0; g < DARK; g++) {
                for (int b = 0; b < DARK; b++) {
                    if (Math.abs(r - b) > 8 || Math.abs(g - b) > 8 || Math.abs(r - g) > 8) continue;

                    now += hueOff(drawn(r, g, b), r, g, b);
                    before += hueOff(asItWas(r, g, b), r, g, b);
                    counted++;
                }
            }
        }

        assertTrue(now * 3 < before * 2, "the hue should be much closer than rounding down to five bits was, but averaged "
                + now / counted + " against " + before / counted);
    }

    /**
     * And it does not reach for a brighter entry to get the hue right, which is the risk of weighing colour more.
     *
     * <p>A dark thing has to stay dark: getting the shade a little wrong is what this trades away, and "a little" has to
     * stay little or the fix for a red cast becomes a fix that glows.
     */
    @Test
    void nothingDarkIsDrawnMuchBrighterThanItIs() {
        int worst = 0;
        String at = "";

        for (int r = 0; r < DARK; r += 2) {
            for (int g = 0; g < DARK; g += 2) {
                for (int b = 0; b < DARK; b += 2) {
                    Color painted = drawn(r, g, b);
                    int brighter = (painted.getRed() + painted.getGreen() + painted.getBlue() - r - g - b) / 3;

                    if (brighter > worst) {
                        worst = brighter;
                        at = "rgb(" + r + "," + g + "," + b + ") -> " + painted;
                    }
                }
            }
        }

        assertTrue(worst <= 20, "a dark colour was drawn " + worst + " brighter than it is, at " + at);
    }

    /** How far off the colour is, brightness taken out - the pair of differences a hue lives in. */
    private static long hueOff(Color painted, int r, int g, int b) {
        long redGreen = (painted.getRed() - r) - (painted.getGreen() - g);
        long greenBlue = (painted.getGreen() - g) - (painted.getBlue() - b);
        return redGreen * redGreen + greenBlue * greenBlue;
    }

    /** A full search over the palette, which is what the table is an approximation of. */
    private static Color nearest(int r, int g, int b) {
        Color found = null;
        long best = Long.MAX_VALUE;

        for (byte entry : MapColors.INSTANCE.entries()) {
            Color candidate = MapColors.INSTANCE.color(entry);
            if (candidate == null) continue;

            long distance = apart(candidate, r, g, b);
            if (distance < best) {
                best = distance;
                found = candidate;
            }
        }
        return found;
    }
}
