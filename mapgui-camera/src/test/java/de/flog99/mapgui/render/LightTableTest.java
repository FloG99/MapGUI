package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one thing the shadow lift may not do, whatever it is tuned to.
 *
 * <p>Nothing here pins a brightness. The lift is a setting - {@code camera.shadow-lift} - so it is not this file's to
 * have an opinion about; what must hold is the shape, and the shape is easy to break by turning it up: the client's own
 * curve is nearly flat across the bottom, so past a certain lift an unlit block draws brighter than a torchlit one.
 *
 * <p>Held across the whole range an admin can set rather than at the default alone, which is the point of a setting
 * having a clamp: every value the clamp allows has to produce a table that means something.
 */
class LightTableTest {

    /** Overworld, Nether and End, since ambient light changes where the curve starts and so where it could invert. */
    private static final float[] AMBIENTS = {0f, 0.1f, 0.25f};

    /** Off, the default, and the far end of what {@code camera.shadow-lift} is clamped to. */
    private static final float[] LIFTS = {0f, 0.08f, RayCaster.SHADOW_LIFT, 0.3f, 0.5f};

    @Test
    void moreLightIsNeverDarker() {
        for (float ambient : AMBIENTS) {
            for (float lift : LIFTS) {
                float[] table = RayCaster.lightTable(ambient, lift);

                for (int level = 1; level < table.length; level++) {
                    assertTrue(table[level] >= table[level - 1],
                            "ambient " + ambient + " at lift " + lift + ": light " + level + " draws at " + table[level]
                                    + ", darker than light " + (level - 1) + " at " + table[level - 1]);
                }
            }
        }
    }

    /**
     * The lift lands where it was aimed: on the dark end and almost nowhere else.
     *
     * <p>Which is what makes it safe to turn up. At the default an unlit block goes from 0.03 - the client's own figure,
     * and four of 255 on stone, which reads as a hole rather than as a dark room - to about a third of the way up, while
     * a well lit block moves by less than a hundredth. A lift that brightened the top would be a floor under everything
     * and a flat picture.
     */
    @Test
    void theLiftIsSpentOnTheDarkEnd() {
        float[] off = RayCaster.lightTable(0, 0);
        float[] lifted = RayCaster.lightTable(0, RayCaster.SHADOW_LIFT);

        assertTrue(lifted[0] > off[0] + 0.1f,
                "an unlit block should come up off black, but went from " + off[0] + " to " + lifted[0]);
        assertTrue(lifted[0] < 0.35f, "and not so far that a dark room reads as a lit one: " + lifted[0]);
        assertTrue(lifted[11] - off[11] < 0.02f,
                "a lit block should barely move, but went from " + off[11] + " to " + lifted[11]);
    }

    /**
     * And the other half of the shape: the lift is for the dark end, so full light has to come out where the
     * client's own curve leaves it. A lift that raised this would be a floor under everything.
     */
    @Test
    void fullLightIsLeftWhereTheClientPutIt() {
        for (float ambient : AMBIENTS) {
            for (float lift : LIFTS) {
                float[] table = RayCaster.lightTable(ambient, lift);

                assertTrue(table[15] > 0.98f && table[15] <= 1f,
                        "ambient " + ambient + " at lift " + lift + ": full light came out at " + table[15]);
            }
        }
    }
}
