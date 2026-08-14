package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skipping empty space has to be free, and free means byte for byte.
 *
 * <p>Every optimization that guesses at what a ray would have found is a quality regression waiting to be noticed by
 * somebody else, so this asserts the only thing that settles it: the same scene traced with {@link EmptySpace} and
 * without it comes out as the same pixels. The scenes are chosen to be the shapes a skip could plausibly get wrong -
 * air under stone, an overhang, an island with nothing above or below it, water, cross planes, a mob standing in the
 * air a ray is skipping through, and a camera pointed at nothing at all.
 *
 * <p>Every case also asserts the skip <i>happened</i>, by counting what the trace asked the world. Two identical
 * frames prove nothing if the second one walked every block of the first.
 */
class EmptySkipTest {

    private static final int SIZE = 32;
    private static final BakedState.Alpha OPAQUE = BakedState.Alpha.OPAQUE;

    private static TestWorld base() {
        return new TestWorld()
                .texture("stone", TestWorld.solid(0xFF808080))
                .texture("dirt", TestWorld.solid(0xFF7A5230))
                .texture("grass", TestWorld.solid(0xFF60A040))
                .texture("leaf", TestWorld.halfClear(0xFF208020))
                .texture("flower", TestWorld.solid(0xFFE0C020))
                .texture("water", TestWorld.solid(0x802040C0))
                .texture("skin", Texture.opaqueOf(64, 64, skin()));
    }

    /** A rolling surface with soil under it, which is the shape a ray hugging the ground has to walk. */
    private static TestWorld terrain(TestWorld world, int from, int to) {
        for (int x = from; x <= to; x++) {
            for (int z = from; z <= to; z++) {
                int height = (int) Math.round(2 * Math.sin(x * 0.3) + 2 * Math.cos(z * 0.25));
                world.cube(x, height, z, "grass", OPAQUE);
                world.cube(x, height - 1, z, "dirt", OPAQUE);
                world.cube(x, height - 2, z, "stone", OPAQUE);
            }
        }
        return world;
    }

    private static int[] rendered(TestWorld world, CameraView view, List<EntitySnapshot> entities, boolean skip) {
        int[] out = new int[SIZE * SIZE];
        new RayCaster(world, skip).render(world, view, entities, SIZE, SIZE, out);
        return out;
    }

    private static CameraView looking(double x, double y, double z, float yaw, float pitch, int range) {
        return new CameraView(x, y, z, yaw, pitch, CameraView.DEFAULT_FOV, range);
    }

    /**
     * Renders each view twice and holds the two frames to being the same pixels.
     *
     * @return the frame, so a case can go on to assert there was something in it
     */
    private static int[] identical(String what, TestWorld world, List<EntitySnapshot> entities, CameraView... views) {
        int[] skipped = null;
        int walkedReads = 0;
        int skippedReads = 0;

        for (CameraView view : views) {
            int before = world.reads();
            int[] walked = rendered(world, view, entities, false);
            walkedReads += world.reads() - before;

            before = world.reads();
            skipped = rendered(world, view, entities, true);
            skippedReads += world.reads() - before;

            assertArrayEquals(walked, skipped, "skipping empty space changed the picture: " + what
                    + ", from " + view.x() + "," + view.y() + "," + view.z()
                    + " at yaw " + view.yaw() + " pitch " + view.pitch());
        }

        // Counted over the whole scene rather than per view, because a single ray can spend its length inside
        // occupied cells and skip nothing - which is allowed, where skipping a block that holds something is not.
        assertTrue(skippedReads < walkedReads, "the skip did nothing in " + what + ", so the frames matching proves"
                + " nothing (" + skippedReads + " block reads against " + walkedReads + ")");
        return skipped;
    }

    /** A frame with one color in it can be identical for the wrong reason, so the scenes are checked for content. */
    private static void assertPainted(int[] frame, String what) {
        assertTrue(Arrays.stream(frame).distinct().count() > 2, "expected something to look at in " + what);
        assertTrue(Arrays.stream(frame).anyMatch(pixel -> pixel != TestWorld.SKY), "all sky in " + what);
    }

    /**
     * A room hollowed out of stone, which is the case a heightfield cannot do and this has to.
     *
     * <p>The interior is wider than a cell, so the trace really is skipping cells while inside it, and the walls,
     * the pillar and the block of stone hanging in the middle all have to arrive anyway.
     */
    @Test
    void aCaveUnderSolidStoneRendersTheSame() {
        TestWorld world = base();
        for (int x = -24; x <= 24; x++) {
            for (int z = -24; z <= 24; z++) {
                world.cube(x, -1, z, "stone", OPAQUE);
                world.cube(x, 33, z, "stone", OPAQUE);
            }
        }
        for (int y = 0; y <= 32; y++) {
            for (int x = -24; x <= 24; x++) {
                world.cube(x, y, -24, "stone", OPAQUE);
                world.cube(x, y, 24, "grass", OPAQUE);
            }
            for (int z = -23; z <= 23; z++) {
                world.cube(-24, y, z, "stone", OPAQUE);
                world.cube(24, y, z, "dirt", OPAQUE);
            }
        }

        // Geometry standing in the middle of the empty part, so a cell that gets skipped is next to one that must not.
        for (int y = 0; y <= 20; y++) {
            world.cube(6, y, 10, "dirt", OPAQUE);
        }
        for (int x = -8; x <= -4; x++) {
            for (int y = 12; y <= 16; y++) {
                for (int z = 2; z <= 6; z++) {
                    world.cube(x, y, z, "stone", OPAQUE);
                }
            }
        }

        int[] frame = identical("a cave", world, List.of(),
                looking(0.5, 8, 0.5, 0, 0, 64),
                looking(0.5, 8, 0.5, 45, -20, 64),
                looking(0.5, 20, 0.5, 200, 30, 64),
                looking(-20.5, 2, -20.5, 40, -10, 64));
        assertPainted(frame, "a cave");
    }

    /** Terrain that hangs over itself: below the shelf is air the skip must not fill in and must not swallow. */
    @Test
    void anOverhangRendersTheSame() {
        TestWorld world = terrain(base(), -30, 30);

        for (int x = -10; x <= 10; x++) {
            for (int y = 1; y <= 18; y++) {
                world.cube(x, y, 20, "stone", OPAQUE);
            }
            for (int z = 4; z <= 19; z++) {
                world.cube(x, 18, z, "stone", OPAQUE);
            }
        }

        int[] frame = identical("an overhang", world, List.of(),
                looking(0.5, 6, 0.5, 0, 0, 96),
                looking(0.5, 6, 0.5, 0, -25, 96),
                looking(0.5, 24, 0.5, 0, 20, 96),
                looking(0.5, 6, 30.5, 180, 5, 96));
        assertPainted(frame, "an overhang");
    }

    /** Sky above it, sky under it, and nothing joining it to the ground - the shape an LOD would lose. */
    @Test
    void aFloatingIslandRendersTheSame() {
        TestWorld world = terrain(base(), -30, 30);

        for (int x = -9; x <= 9; x++) {
            for (int z = 12; z <= 30; z++) {
                boolean edge = Math.abs(x) > 6 || z < 15 || z > 27;
                if (edge) continue;

                world.cube(x, 40, z, "stone", OPAQUE);
                world.cube(x, 41, z, "grass", OPAQUE);
                if ((x * 5 + z) % 7 == 0) {
                    world.turnedPlane(x, 42, z, "leaf", 45);
                }
            }
        }

        int[] frame = identical("a floating island", world, List.of(),
                looking(0.5, 6, 0.5, 0, -35, 96),
                looking(0.5, 60, 0.5, 0, 30, 96),
                looking(0.5, 41, -20.5, 0, 0, 96),
                looking(0.5, 20, 0.5, 10, -20, 96));
        assertPainted(frame, "a floating island");
    }

    /** Water, and a waterlogged block whose fluid is listed after its geometry, seen across skipped air. */
    @Test
    void waterAndAWaterloggedBlockRenderTheSame() {
        TestWorld world = terrain(base(), -30, 30);

        for (int x = -6; x <= 6; x++) {
            for (int z = 20; z <= 32; z++) {
                world.fluid(x, 3, z, "water");
                world.fluid(x, 2, z, "water");
            }
        }
        world.waterlogged(0, 4, 24, "stone", "water");
        world.waterlogged(-3, 4, 27, "grass", "water");

        int[] frame = identical("water", world, List.of(),
                looking(0.5, 6, 0.5, 0, -5, 96),
                looking(0.5, 6, 0.5, 0, 10, 96),
                looking(0.5, 12, 40.5, 180, 20, 96),
                looking(-6.5, 4, 26.5, 90, 0, 96));
        assertPainted(frame, "water");
    }

    /** Cross planes and a flowerbed, which are drawn out of blocks the heightmap would rather forget. */
    @Test
    void crossPlanePlantsRenderTheSame() {
        TestWorld world = terrain(base(), -30, 30);

        for (int x = -20; x <= 20; x++) {
            for (int z = 4; z <= 30; z++) {
                int height = (int) Math.round(2 * Math.sin(x * 0.3) + 2 * Math.cos(z * 0.25));
                if ((x * 7 + z) % 5 == 0) {
                    world.turnedPlane(x, height + 1, z, "leaf", 45);
                } else if ((x * 3 + z * 2) % 11 == 0) {
                    world.plane(x, height + 1, z, "flower");
                } else if ((x + z * 5) % 13 == 0) {
                    world.floorPlane(x, height + 1, z, "flower");
                }
            }
        }

        int[] frame = identical("plants", world, List.of(),
                looking(0.5, 6, 0.5, 0, 0, 96),
                looking(0.5, 4, 0.5, 0, 15, 96),
                looking(0.5, 8, 0.5, 30, -10, 96));
        assertPainted(frame, "plants");
    }

    /**
     * A mob standing in the air, in the part of the world the skip crosses without looking.
     *
     * <p>Entities are not in the voxel grid at all - they are a second pass with its own depth bookkeeping - so this
     * is the case where a skip could plausibly lose one, and the frame with the mob has to differ from the frame
     * without it or the check would be empty.
     */
    @Test
    void aMobInOpenAirIsStillFound() {
        TestWorld world = terrain(base(), -30, 30);

        // Both stand well clear of the ground, in the cells a ray crosses without looking at them, and neither is so
        // far off that it lands between two pixels of a 32 wide frame - a mob nothing renders would prove nothing.
        List<EntitySnapshot> mobs = List.of(
                EntitySnapshot.box(0.5, 20, 14.5, 0, 0, 3, 3, "skin"),
                EntitySnapshot.player(0.5, 12, 6.5, 0, 0, 0, false, SkinLayers.ALL, "skin"));

        CameraView[] views = {
                looking(0.5, 6, 0.5, 0, -35, 96),
                looking(0.5, 6, 0.5, 0, -50, 96),
                looking(0.5, 40, 0.5, 0, 35, 96)
        };

        int[] frame = identical("a mob in open air", world, mobs, views);
        assertPainted(frame, "a mob in open air");

        for (CameraView view : views) {
            assertFalse(Arrays.equals(rendered(world, view, List.of(), true), rendered(world, view, mobs, true)),
                    "no mob was drawn from " + view + ", so nothing here says the skip kept one");
        }
    }

    /** Straight up at nothing, which is the ray the whole structure exists for. */
    @Test
    void lookingUpAtEmptySkyRendersTheSame() {
        TestWorld world = terrain(base(), -30, 30);
        CameraView up = looking(0.5, 20, 0.5, 0, -90, 128);

        int[] frame = identical("empty sky", world, List.of(), up);
        for (int pixel : frame) {
            assertEquals(TestWorld.SKY, pixel, "looking up at nothing should be all sky");
        }
    }

    /** The far range, where the coarse cells are what a ray spends its length in. */
    @Test
    void aLongSightLineOverTerrainRendersTheSame() {
        TestWorld world = terrain(base(), -100, 100);
        for (int x = -40; x <= 40; x += 7) {
            for (int y = 1; y <= 12; y++) {
                world.cube(x, y, 60, "stone", OPAQUE);
            }
        }

        int[] frame = identical("a long sight line", world, List.of(),
                looking(0.5, 6, 0.5, 0, 0, 192),
                looking(0.5, 6, 0.5, 0, -8, 192),
                looking(0.5, 40, 0.5, 0, 12, 192));
        assertPainted(frame, "a long sight line");
    }

    /** The structure itself: an empty cell is skippable, a cell with anything in it is not, whatever else is around. */
    @Test
    void onlyEmptyCellsAreSkippable() {
        EmptySpace space = EmptySpace.over(0, 0, 0, 255, 255, 255)
                .occupied(8, 8, 8)
                .occupied(200, 200, 200)
                .build();

        assertEquals(0, space.shiftAt(0, 0, 0), "a cell holding a block is never skippable");
        assertEquals(0, space.shiftAt(15, 15, 15), "and that is the whole cell, not just the block");
        assertEquals(EmptySpace.CELL, space.shiftAt(16, 0, 0), "the cell next to it is empty but its parent is not");
        assertTrue(space.shiftAt(128, 16, 16) > EmptySpace.CELL, "clear space should coarsen");
        assertEquals(0, space.shiftAt(-1, 0, 0), "outside what was measured is walked rather than guessed at");
        assertEquals(0, EmptySpace.NONE.shiftAt(0, 0, 0), "nothing is skippable with the structure turned off");
    }

    /**
     * The invariant itself, checked against the blocks rather than through a picture.
     *
     * <p>A frame coming out the same proves the skip did not lose anything the camera was pointed at. This proves the
     * stronger thing that has to be true of the structure whatever a camera is pointed at: if it hands back a cell,
     * nothing that was ever marked is inside that cell.
     */
    @Test
    void aCellCalledEmptyHoldsNothing() {
        java.util.Random random = new java.util.Random(20260803);
        int[][] placed = new int[500][];
        EmptySpace.Builder building = EmptySpace.over(-160, -64, -160, 159, 191, 159);
        for (int block = 0; block < placed.length; block++) {
            placed[block] = new int[]{random.nextInt(-160, 160), random.nextInt(-64, 192), random.nextInt(-160, 160)};
            building.occupied(placed[block][0], placed[block][1], placed[block][2]);
        }

        EmptySpace space = building.build();
        int cells = 0;
        for (int sample = 0; sample < 4000; sample++) {
            int x = random.nextInt(-160, 160);
            int y = random.nextInt(-64, 192);
            int z = random.nextInt(-160, 160);

            int shift = space.shiftAt(x, y, z);
            if (shift == 0) continue;

            cells++;
            int size = 1 << shift;
            int fromX = x & -size;
            int fromY = y & -size;
            int fromZ = z & -size;
            for (int[] block : placed) {
                boolean inside = block[0] >= fromX && block[0] < fromX + size
                        && block[1] >= fromY && block[1] < fromY + size
                        && block[2] >= fromZ && block[2] < fromZ + size;
                assertFalse(inside, "a block at " + Arrays.toString(block) + " is inside the " + size
                        + " block cell at " + fromX + "," + fromY + "," + fromZ + " that was called empty");
            }
        }
        assertTrue(cells > 1000, "the sample should have found plenty of empty cells, found " + cells);
    }

    private static int[] skin() {
        int[] argb = new int[64 * 64];
        Arrays.fill(argb, 0xFFC08040);
        return argb;
    }
}
