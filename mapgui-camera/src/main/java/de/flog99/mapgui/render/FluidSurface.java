package de.flog99.mapgui.render;

/**
 * How high a fluid stands at each corner of its block, which is what tilts a stream's surface downhill.
 *
 * <p>The client's own arithmetic, off {@code FluidRenderer}. A corner is the weighted average of the four blocks
 * that touch it, so two neighbouring blocks work out the same number for the corner they share and their surfaces
 * meet along the whole edge between them. That is what lets the client drop the entire face between two blocks of
 * the same fluid however different their depths are: there is no gap to see through, because the two tops are one
 * surface. Rendering the fluid as a flat box per block is what put that gap back.
 *
 * <p>A near-full corner counts ten times heavier than a shallow one, so a pool keeps its level where a stream runs
 * into it rather than being dragged down to meet it. A solid neighbour does not vote at all, which is what keeps
 * water flat against a shore instead of dipping into every wall it touches.
 */
final class FluidSurface {

    /** Corner heights are packed a byte each, so the surface of a block is one int and costs no allocation. */
    private static final int SCALE = 255;

    private FluidSurface() {
    }

    /**
     * The four corners of this block's fluid, packed north-west, north-east, south-east, south-west.
     *
     * <p>A fluid with the same fluid above it is full to the brim and flat, which is every block of a pool but its
     * top one - so the whole body of an ocean takes the early return and only its surface is ever averaged.
     */
    static int corners(VoxelSource world, BakedState state, int x, int y, int z) {
        float self = state.fluidTop() / 16f;
        if (self >= 1) return pack(1, 1, 1, 1);

        boolean lava = !state.water();
        float north = height(world, lava, x, y, z - 1);
        float south = height(world, lava, x, y, z + 1);
        float east = height(world, lava, x + 1, y, z);
        float west = height(world, lava, x - 1, y, z);

        return pack(
                corner(world, lava, self, north, west, x - 1, y, z - 1),
                corner(world, lava, self, north, east, x + 1, y, z - 1),
                corner(world, lava, self, south, east, x + 1, y, z + 1),
                corner(world, lava, self, south, west, x - 1, y, z + 1)
        );
    }

    static float northWest(int corners) {
        return (corners >>> 24) / (float) SCALE;
    }

    static float northEast(int corners) {
        return (corners >>> 16 & 0xFF) / (float) SCALE;
    }

    static float southEast(int corners) {
        return (corners >>> 8 & 0xFF) / (float) SCALE;
    }

    static float southWest(int corners) {
        return (corners & 0xFF) / (float) SCALE;
    }

    private static int pack(float northWest, float northEast, float southEast, float southWest) {
        return Math.round(northWest * SCALE) << 24 | Math.round(northEast * SCALE) << 16
                | Math.round(southEast * SCALE) << 8 | Math.round(southWest * SCALE);
    }

    /**
     * One corner, as the average of the block itself, the two neighbours beside that corner and the one diagonally
     * across it.
     *
     * <p>The diagonal is only asked about when one of the two beside it holds something, which is the client's own
     * short circuit and matters: a corner where the fluid does not reach cannot be pulled up by a block it does not
     * touch, and reading it anyway is a lookup per corner of every fluid block in the picture.
     */
    private static float corner(VoxelSource world, boolean lava, float self, float beside, float other,
                                int x, int y, int z) {

        if (beside >= 1 || other >= 1) return 1;

        float total = 0;
        float weight = 0;
        if (beside > 0 || other > 0) {
            float diagonal = height(world, lava, x, y, z);
            if (diagonal >= 1) return 1;

            total += weighted(diagonal);
            weight += weight(diagonal);
        }

        total += weighted(self) + weighted(beside) + weighted(other);
        weight += weight(self) + weight(beside) + weight(other);
        return total / weight;
    }

    /** Ten to one in favour of a nearly full block, and a solid one left out of the average entirely. */
    private static float weight(float height) {
        return height >= 0.8f ? 10 : height >= 0 ? 1 : 0;
    }

    private static float weighted(float height) {
        return height >= 0.8f ? height * 10 : height >= 0 ? height : 0;
    }

    /**
     * How high the same fluid stands in one block: its own depth, 0 for a block that holds none, and -1 for a solid
     * one, which is the client's way of saying that block has no opinion about the corner rather than a low one.
     *
     * <p>The depth already counts a block with more of the same fluid above it as full, since that is folded into
     * the state when it is baked.
     */
    private static float height(VoxelSource world, boolean lava, int x, int y, int z) {
        BakedState state = world.stateAt(x, y, z);
        if (holds(state, lava)) return state.fluidTop() / 16f;

        return world.solidAt(x, y, z) ? -1 : 0;
    }

    /** Whether a block holds the fluid being drawn, which a waterlogged stair does as much as water itself. */
    private static boolean holds(BakedState state, boolean lava) {
        return lava ? state.fluidTop() > 0 && !state.water() : state.water();
    }

    /** A surface that is not going anywhere, and is drawn with the still texture rather than a turned moving one. */
    static final float STILL = Float.NaN;

    private static final Direction[] SIDEWAYS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    /** What a source stands at, which is the drop the client credits a fluid with when it pours over an edge. */
    private static final float SOURCE = 8 / 9f;

    /**
     * Which way the fluid is running, as the angle its texture is turned by, or {@link #STILL} where it is not.
     *
     * <p>The client's {@code getFlow}, less the parts that only move entities. It is the downhill of the surface:
     * each side pulls by how much lower it stands, and a side that has dropped away entirely pulls by the fall to
     * whatever is under it. Only the direction survives, so normalising it is left out.
     */
    static float flow(VoxelSource world, BakedState state, int x, int y, int z) {
        boolean lava = !state.water();
        float self = state.fluidTop() / 16f;
        double towardsX = 0;
        double towardsZ = 0;

        for (Direction side : SIDEWAYS) {
            BakedState beside = world.stateAt(x + side.dx(), y, z + side.dz());
            // Only a different fluid has no say. Stone has none either but still counts, as the side the fluid
            // cannot run to - and a torch counts as a side it can, which testing for geometry rather than for
            // fluid got wrong in both directions.
            if (!holds(beside, lava) && beside.fluidTop() > 0) continue;

            float there = holds(beside, lava) ? beside.fluidTop() / 16f : 0;
            float drop = there > 0
                    ? self - there
                    : overEdge(world, lava, self, x + side.dx(), y, z + side.dz());

            towardsX += side.dx() * drop;
            towardsZ += side.dz() * drop;
        }

        if (towardsX == 0 && towardsZ == 0) return STILL;

        return (float) (Math.atan2(towardsZ, towardsX) - Math.PI / 2);
    }

    /**
     * The pull of a side the fluid has run off, which is a drop rather than nothing: what is under the gap counts,
     * lowered by a full source's depth so that pouring off a ledge pulls harder than running along the flat.
     */
    private static float overEdge(VoxelSource world, boolean lava, float self, int x, int y, int z) {
        if (world.solidAt(x, y, z)) return 0;

        BakedState under = world.stateAt(x, y - 1, z);
        if (!holds(under, lava)) return 0;

        float below = under.fluidTop() / 16f;
        return below > 0 ? self - (below - SOURCE) : 0;
    }
}
