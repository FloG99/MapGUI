package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leaves closing up with distance.
 *
 * <p>The texture here is entirely transparent, so every ray through the block goes through a gap and nothing is
 * being measured except the fill. Its average color is green, which is what a real leaf texture's drawn texels
 * average to and what the gap is filled with.
 */
class CanopyFillTest {

    private static final int LEAF_GREEN = 0xFF3C7A28;

    /** Every texel clear, average green: a block that is nothing but gap. */
    private static Texture allGap() {
        return new Texture(16, 16, new int[256], BakedState.Alpha.CUTOUT, LEAF_GREEN);
    }

    private static int pixel(TestWorld world, double distance) {
        return pixel(world, distance, Canopy.DEFAULT);
    }

    private static int pixel(TestWorld world, double distance, Canopy canopy) {
        // Looking down +Z from the middle of a block, so the near face of the block at z is distance z - 0.5 away.
        CameraView view = new CameraView(0.5, 0.5, 0.5, 0, 0, CameraView.DEFAULT_FOV, 256);
        int[] out = new int[1];
        new RayCaster(world, canopy).render(world, view, 1, 1, out);
        return out[0];
    }

    private static int leavesAt(double distance) {
        return leavesAt(distance, Canopy.DEFAULT);
    }

    private static int leavesAt(double distance, Canopy canopy) {
        TestWorld world = new TestWorld()
                .texture("leaf", allGap())
                .leafCube(0, 0, (int) Math.round(distance + 0.5), "leaf");
        return pixel(world, distance, canopy);
    }

    /** The same distance, but a solid block of the leaf's average color rather than leaves. */
    private static int solidAt(double distance) {
        TestWorld world = new TestWorld()
                .texture("solid", TestWorld.solid(LEAF_GREEN))
                .cube(0, 0, (int) Math.round(distance + 0.5), "solid", BakedState.Alpha.OPAQUE);
        return pixel(world, distance);
    }

    /** How far one color is from another, summed over the channels. */
    private static int apart(int argb, int from) {
        return Math.abs((argb >> 16 & 0xFF) - (from >> 16 & 0xFF))
                + Math.abs((argb >> 8 & 0xFF) - (from >> 8 & 0xFF))
                + Math.abs((argb & 0xFF) - (from & 0xFF));
    }

    /**
     * The default closes from the lens out, so even a tree at arm's length has its gaps a little shut - a twentieth
     * of the way at two blocks. Still nearly all sky, which is what keeps a canopy overhead from reading as a lid.
     */
    @Test
    void byDefaultTheGapsCloseFromZero() {
        assertNotEquals(TestWorld.SKY, leavesAt(2), "the default fills from the lens rather than from a near distance");
        assertTrue(apart(leavesAt(2), TestWorld.SKY) < apart(leavesAt(2), solidAt(2)),
                "but only a little: a tree you are standing next to is still mostly sky through the gaps");
    }

    /** A near distance leaves what is inside it alone, which is what the setting is for. */
    @Test
    void aNearDistanceKeepsCloseLeavesClear() {
        Canopy from16 = new Canopy(16, 50);

        assertEquals(TestWorld.SKY, leavesAt(4, from16), "a tree inside the near distance keeps its gaps");
        assertEquals(TestWorld.SKY, leavesAt(14.5, from16));
        assertNotEquals(TestWorld.SKY, leavesAt(20, from16), "and past it they start closing");
    }

    /** Equal ends are a switch at that one distance, and never a division by zero. */
    @Test
    void oneDistanceIsAHardEdge() {
        Canopy at30 = new Canopy(30, 30);

        assertEquals(TestWorld.SKY, leavesAt(20, at30));
        assertEquals(solidAt(40), leavesAt(40, at30));
    }

    /** Pushed past anything a capture can reach, leaves are the cutout they are on disk at every distance. */
    @Test
    void turnedOffTheyNeverFill() {
        assertEquals(TestWorld.SKY, leavesAt(180, Canopy.OFF));
    }

    /**
     * The point of the whole thing: past the far distance a canopy is a surface, and the pixel is the leaf color
     * with no sky left in it at all. Asserted against an opaque block of that same color at that same distance,
     * so it pins the filled leaf as being genuinely solid rather than merely close to it.
     */
    @Test
    void beyondFiftyBlocksLeavesAreOpaque() {
        for (double distance : new double[]{Canopy.DEFAULT.far(), 60, 100, 180}) {
            assertEquals(solidAt(distance), leavesAt(distance),
                    "leaves " + distance + " blocks out should draw as solid leaf color");
        }
    }

    /**
     * Between the two the gap is part shut, so the pixel is part sky and part leaf. Measured as how far it is from
     * a solid canopy rather than by any one channel, since which way a channel moves is only a fact about the sky
     * color the scene happens to have.
     */
    @Test
    void betweenTheTwoItCloses() {
        int solid = solidAt(45);

        assertNotEquals(TestWorld.SKY, leavesAt(20), "past the near distance some of the gap is filled");

        int previous = Integer.MAX_VALUE;
        for (double distance : new double[]{20, 27, 33, 40, 45, Canopy.DEFAULT.far()}) {
            int apart = apart(leavesAt(distance), solid);
            assertTrue(apart < previous, "at " + distance + " blocks the canopy should be nearer solid than before");
            previous = apart;
        }

        assertEquals(0, previous, "and shut by the far distance");
    }

    /** Only leaves. A cutout that is not foliage keeps its gaps at any distance - bars, ladders, a torch. */
    @Test
    void otherCutoutsAreUnaffected() {
        TestWorld world = new TestWorld()
                .texture("leaf", allGap())
                .cube(0, 0, 81, "leaf", BakedState.Alpha.CUTOUT);

        assertEquals(TestWorld.SKY, pixel(world, 80));
    }
}
