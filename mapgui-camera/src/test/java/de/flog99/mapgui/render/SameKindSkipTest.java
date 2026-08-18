package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inside of a body of one material is crossed without being examined, and the frame comes out the same.
 *
 * <p>Why there is anything to skip: water is translucent, so a ray does not stop at the surface - it walks down through
 * every block of the ocean to the seabed - and every one of those blocks draws <b>nothing</b>, because the face it would
 * draw is culled against the identical water behind it. Measured at 256x256 over eighteen blocks of standing water:
 * <b>157 ms</b> against 16 ms for the same scene in stone, and 113 ms once the interior stopped being examined.
 *
 * <p>So this renders every awkward shape both ways and compares the frames, which is the same thing {@code EmptySkipTest}
 * does for empty space and for the same reason: an optimisation that draws by <b>not looking</b> is only correct if the
 * picture is identical, and the failure mode is a hole in something you can see through rather than an exception.
 *
 * <p>The shapes that matter are the ones where it must <b>not</b> fire: leaves, whose faces carry no {@code cullface} and
 * are drawn however many of them are stacked; a waterlogged stair, which is two elements rather than one; and a slab,
 * which is not a full cube. Each of those is a hole in the picture if the skip is taken.
 */
class SameKindSkipTest {

    private static final int SIZE = 96;

    private static final int RANGE = 96;

    /** Sea level at 62 over a floor, deep enough that a ray crosses many blocks of it. */
    private static TestWorld sea(int deep) {
        TestWorld world = textured();
        for (int x = -40; x <= 40; x++) {
            for (int z = 0; z <= 80; z++) {
                world.cube(x, 62 - deep, z, "stone", BakedState.Alpha.OPAQUE);
                for (int y = 63 - deep; y <= 62; y++) {
                    world.fluid(x, y, z, "water");
                }
            }
        }
        return world;
    }

    private static TestWorld textured() {
        return new TestWorld()
                .texture("stone", TestWorld.solid(0xFF808080))
                .texture("water", TestWorld.solid(0x7F3040CF))
                .texture("glass", TestWorld.solid(0x60FFFFFF))
                .texture("leaf", TestWorld.halfClear(0xFF2F6F2F));
    }

    private static CameraView over(double pitch) {
        return new CameraView(0, 64, 0, 0, (float) pitch, 70, RANGE, false, null, null, null);
    }

    /** @param skipping whether the caster is allowed to cross the inside of one material without examining it */
    private static int[] rendered(TestWorld world, CameraView view, boolean skipping) {
        int[] out = new int[SIZE * SIZE];
        new RayCaster(world, Canopy.DEFAULT, RayCaster.SHADOW_LIFT, true, skipping)
                .render(world, view, List.of(), SIZE, SIZE, out);
        return out;
    }

    private static void bothWaysAgree(TestWorld world, CameraView view, String what) {
        assertArrayEquals(rendered(world, view, false), rendered(world, view, true),
                what + " came out differently when the inside of it was crossed unexamined");
    }

    @Test
    void anOceanLooksTheSameCrossedEitherWay() {
        for (int deep : new int[]{1, 2, 6, 18}) {
            for (double pitch : new double[]{-6, 20, 60, 89}) {
                bothWaysAgree(sea(deep), over(pitch), deep + " blocks of water at pitch " + pitch);
            }
        }
    }

    /** Glass is the other everyday case: translucent, a full cube, and its faces cull against their own kind. */
    @Test
    void aWallOfGlassLooksTheSameEitherWay() {
        TestWorld world = textured();
        for (int x = -20; x <= 20; x++) {
            for (int y = 55; y <= 70; y++) {
                for (int z = 20; z <= 26; z++) {
                    world.cube(x, y, z, "glass", BakedState.Alpha.TRANSLUCENT);
                }
                world.cube(x, y, 40, "stone", BakedState.Alpha.OPAQUE);
            }
        }
        bothWaysAgree(world, over(0), "a wall of glass seven blocks thick");
    }

    /**
     * Leaves must not be skipped, and this is the test that says so.
     *
     * <p>A leaf face carries no {@code cullface} - the client draws every layer of a canopy, which is exactly why you can
     * see into one - so an identical leaf block behind another hides nothing. A skip taken here is a hole through the tree.
     */
    @Test
    void aCanopyIsNotSkipped() {
        TestWorld world = textured();
        for (int x = -20; x <= 20; x++) {
            for (int y = 60; y <= 70; y++) {
                for (int z = 10; z <= 30; z++) {
                    world.leafCube(x, y, z, "leaf");
                }
                world.cube(x, y, 40, "stone", BakedState.Alpha.OPAQUE);
            }
        }

        CameraView view = over(0);
        int[] skipping = rendered(world, view, true);
        assertArrayEquals(rendered(world, view, false), skipping, "a canopy was drawn as though it were one leaf thick");

        // And it really is a canopy rather than an accidentally empty scene, or the comparison above proves nothing.
        int drawn = 0;
        for (int pixel : skipping) {
            if (pixel != TestWorld.SKY) {
                drawn++;
            }
        }
        assertTrue(drawn > SIZE * SIZE / 2, "the scene should be full of leaves, and only " + drawn + " pixels were drawn");
    }

    /** Two elements in one state, so the walk consults more than the face it came in through. */
    @Test
    void waterloggedBlocksAreNotSkipped() {
        TestWorld world = textured();
        for (int x = -20; x <= 20; x++) {
            for (int y = 58; y <= 66; y++) {
                for (int z = 10; z <= 20; z++) {
                    world.waterlogged(x, y, z, "stone", "water");
                }
                world.cube(x, y, 40, "stone", BakedState.Alpha.OPAQUE);
            }
        }
        bothWaysAgree(world, over(0), "a run of waterlogged blocks");
    }

    /**
     * Not a full cube, so the grid walk has not tested its geometry and the face it reports is not the whole answer.
     *
     * <p>A field of tall grass, which is what this shape really is: a zero-thickness plane inside each block. The ray
     * meets the plane rather than the block's side, so what a neighbour hides is not a question the entered face can
     * answer, and a skip taken here draws one blade where there are twenty.
     */
    @Test
    void zeroThicknessPlanesAreNotSkipped() {
        TestWorld world = textured();
        for (int x = -20; x <= 20; x++) {
            for (int y = 58; y <= 66; y++) {
                for (int z = 10; z <= 30; z++) {
                    world.plane(x, y, z, "leaf");
                }
                world.cube(x, y, 40, "stone", BakedState.Alpha.OPAQUE);
            }
        }
        bothWaysAgree(world, over(0), "a field of planes");
    }
}
