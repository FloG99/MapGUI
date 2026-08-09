package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client's own corner averaging, which is what tilts a stream and what keeps two blocks of it watertight. */
class FluidSurfaceTest {

    /** Our depths are whole sixteenths where the client's are ninths, so the two agree to about a sixteenth. */
    private static final double ROUNDING = 0.02;

    private static final int SOURCE = 14;

    private static final class Pond implements VoxelSource {

        private final Map<Long, BakedState> blocks = new HashMap<>();

        Pond water(int x, int y, int z, int sixteenths) {
            blocks.put(key(x, y, z), new BakedState(List.of(), sixteenths == 16, BakedState.Alpha.TRANSLUCENT,
                    true, false, sixteenths));
            return this;
        }

        Pond stone(int x, int y, int z) {
            blocks.put(key(x, y, z), new BakedState(List.of(), true, BakedState.Alpha.OPAQUE));
            return this;
        }

        private static long key(int x, int y, int z) {
            return (long) x << 40 ^ (long) y << 20 ^ z;
        }

        @Override
        public BakedState stateAt(int x, int y, int z) {
            return blocks.getOrDefault(key(x, y, z), BakedState.EMPTY);
        }

        @Override
        public int lightAt(int x, int y, int z) {
            return 15;
        }

        @Override
        public int tintAt(int x, int y, int z, int index) {
            return 0xFFFFFFFF;
        }

        @Override
        public Sky sky() {
            return Sky.flat(0);
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 320;
        }
    }

    /**
     * The whole reason the face between two blocks of one fluid can be dropped. If the two ever worked out different
     * numbers for a corner they share, their surfaces would part along that edge and the gap would be straight
     * through to the riverbed - which is exactly what a flat box per block looked like.
     */
    @Test
    void twoBlocksAgreeOnTheCornerTheyShare() {
        Pond world = new Pond().water(0, 0, 0, SOURCE).water(1, 0, 0, 10).water(2, 0, 0, 6).stone(0, 0, 1);

        int west = FluidSurface.corners(world, world.stateAt(0, 0, 0), 0, 0, 0);
        int east = FluidSurface.corners(world, world.stateAt(1, 0, 0), 1, 0, 0);

        // The west block's east edge is the east block's west edge, north corner and south corner both.
        assertEquals(FluidSurface.northEast(west), FluidSurface.northWest(east), 0, "the northern shared corner");
        assertEquals(FluidSurface.southEast(west), FluidSurface.southWest(east), 0, "the southern shared corner");
    }

    /** A block of fluid with nothing beside it is drawn lower than its own depth, which is what makes a puddle a puddle. */
    @Test
    void aLoneSourceAveragesDownAgainstTheAirAroundIt() {
        Pond world = new Pond().water(0, 0, 0, SOURCE);

        int corners = FluidSurface.corners(world, world.stateAt(0, 0, 0), 0, 0, 0);

        // Its own depth counts ten times, and the two empty neighbours once each.
        assertEquals(SOURCE / 16f * 10 / 12, FluidSurface.northWest(corners), ROUNDING, "pulled down by the air");
        assertTrue(FluidSurface.northWest(corners) < SOURCE / 16f, "and below the depth it stands at");
    }

    /** In the middle of a pool there is nothing to pull it down, so the surface is flat at the depth a source stands. */
    @Test
    void aPoolOfSourcesStaysLevel() {
        Pond world = new Pond();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                world.water(x, 0, z, SOURCE);
            }
        }

        int corners = FluidSurface.corners(world, world.stateAt(0, 0, 0), 0, 0, 0);

        assertEquals(SOURCE / 16f, FluidSurface.northWest(corners), ROUNDING, "north-west");
        assertEquals(SOURCE / 16f, FluidSurface.southEast(corners), ROUNDING, "south-east");
    }

    /**
     * A solid neighbour has no opinion rather than a low one. Counting it as empty is the difference between water
     * that meets a wall flat and water that dips into every wall it touches.
     */
    @Test
    void aShoreDoesNotDragTheSurfaceDown() {
        // The north-west corner of the middle block, with water to the west and across the diagonal, so the block to
        // the north is the only thing touching it that is not water.
        Pond shore = pool().stone(0, 0, -1);
        Pond open = pool();

        float againstStone = FluidSurface.northWest(FluidSurface.corners(shore, shore.stateAt(0, 0, 0), 0, 0, 0));
        float againstAir = FluidSurface.northWest(FluidSurface.corners(open, open.stateAt(0, 0, 0), 0, 0, 0));

        assertEquals(SOURCE / 16f, againstStone, ROUNDING, "level against the shore");
        assertTrue(againstAir < againstStone, "where the same corner over open air is drawn down");
    }

    /** Water with nothing to run to is drawn with the still texture, which has no direction in it to get wrong. */
    @Test
    void aPoolThatIsNotGoingAnywhereStandsStill() {
        Pond world = new Pond();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                world.water(x, 0, z, SOURCE);
            }
        }

        assertTrue(Float.isNaN(FluidSurface.flow(world, world.stateAt(0, 0, 0), 0, 0, 0)), "no flow to point");
    }

    /**
     * The direction the texture is turned to, recovered from the angle the way the drawing turns it. Downhill is
     * the whole point: a stream drawn running back up the slope it came from is the thing that reads as wrong.
     */
    @Test
    void theSurfaceRunsTowardsWhateverIsLower() {
        Pond eastward = new Pond().water(0, 0, 0, SOURCE).water(1, 0, 0, 8);
        Pond southward = new Pond().water(0, 0, 0, SOURCE).water(0, 0, 1, 8);

        double east = FluidSurface.flow(eastward, eastward.stateAt(0, 0, 0), 0, 0, 0) + Math.PI / 2;
        double south = FluidSurface.flow(southward, southward.stateAt(0, 0, 0), 0, 0, 0) + Math.PI / 2;

        assertTrue(Math.cos(east) > 0.99, "running east, towards the shallower block beside it");
        assertTrue(Math.sin(south) > 0.99, "and south when the drop is south instead");
    }

    private static Pond pool() {
        return new Pond().water(0, 0, 0, SOURCE).water(-1, 0, 0, SOURCE).water(-1, 0, -1, SOURCE);
    }
}
