package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one thing the shadow lift may not do, whatever it is tuned to.
 *
 * <p>Nothing here pins a brightness. The lift is a number somebody picked by looking at captures and it should stay
 * free to move - what must hold is the shape, and the shape is easy to break by moving it: the client's own curve is
 * nearly flat across the bottom, so past a certain lift an unlit block draws brighter than a torchlit one.
 */
class LightTableTest {

    /** Overworld, Nether and End, since ambient light changes where the curve starts and so where it could invert. */
    private static final float[] AMBIENTS = {0f, 0.1f, 0.25f};

    @Test
    void moreLightIsNeverDarker() {
        for (float ambient : AMBIENTS) {
            float[] table = RayCaster.lightTable(ambient);

            for (int level = 1; level < table.length; level++) {
                assertTrue(table[level] >= table[level - 1],
                        "ambient " + ambient + ": light " + level + " draws at " + table[level]
                                + ", darker than light " + (level - 1) + " at " + table[level - 1]);
            }
        }
    }

    /**
     * And the other half of the shape: the lift is for the dark end, so full light has to come out where the
     * client's own curve leaves it. A lift that raised this would be a floor under everything.
     */
    @Test
    void fullLightIsLeftWhereTheClientPutIt() {
        for (float ambient : AMBIENTS) {
            float[] table = RayCaster.lightTable(ambient);

            assertTrue(table[15] > 0.98f && table[15] <= 1f,
                    "ambient " + ambient + ": full light came out at " + table[15]);
        }
    }
}
