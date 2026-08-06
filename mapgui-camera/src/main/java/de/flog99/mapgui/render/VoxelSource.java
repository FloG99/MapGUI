package de.flog99.mapgui.render;

/**
 * The world as a ray sees it: baked blocks, light and tint, at a position.
 *
 * <p>An interface rather than the server's own types because the trace has to run off the main thread, and
 * because it is the seam that keeps this module free of Bukkit. The plugin's implementation is a dense array
 * copied out of {@code ChunkSnapshot}s in one tick; a test's implementation is a handful of blocks in a
 * {@code HashMap}.
 *
 * <p>Every method is called per step of every ray, so implementations should be a lookup and nothing more. No
 * allocation, no synchronization, no reaching back into the server.
 */
public interface VoxelSource {

    /**
     * The block at a position, or {@link BakedState#EMPTY} for air and for anything outside what was captured.
     *
     * <p>Never null. A ray that leaves the captured region has to end up looking at sky rather than at a
     * {@code NullPointerException} on the last pixel of a frame.
     */
    BakedState stateAt(int x, int y, int z);

    /**
     * Combined block and sky light, 0 to 15.
     *
     * <p>Sampled at the air the ray came through rather than at the block it hit, because the light inside a
     * solid block is zero and lighting a wall by it makes every wall black.
     */
    int lightAt(int x, int y, int z);

    /**
     * The color a face with this tint index is multiplied by, as packed ARGB.
     *
     * <p>Not optional decoration: {@code grass_block_top} averages a flat grey on disk, and so do leaves and
     * water. Untinted foliage renders as concrete.
     *
     * @param index the model's {@code tintindex}, plus 1 for water, which is how {@link BlockModels} marks it
     */
    int tintAt(int x, int y, int z, int index);

    /** What a ray that hits nothing looks at: gradient, sun, moon, stars, clouds. */
    Sky sky();

    /**
     * The color water fades everything into when the camera is inside some, and 0 when it is not.
     *
     * <p>A property of where the camera is rather than of what a ray hits, which is why it is one number for the whole
     * frame: standing in water puts water between the eye and every single thing in the picture, so nothing in the
     * frame is seen through anything else. That is also why it cannot be worked out while tracing - the ray never
     * crosses the surface it is already under.
     *
     * <p>The color is the biome's own {@code water_fog_color}, which is a far darker blue than the water itself:
     * a swamp fogs to a murky green and an ocean to near-black navy, and both are stated per biome. Opaque, which is
     * what lets zero mean "not in water" without a second method - a real answer always carries full alpha.
     */
    default int submergedIn() {
        return 0;
    }

    /**
     * How far the camera can see through that water, in blocks.
     *
     * <p>Only meaningful when {@link #submergedIn} says there is any. The client's own default is 96 and a couple of
     * biomes shorten it - which is the difference between water you can see across and water that fades to near-black
     * in front of you.
     */
    default double submergedSight() {
        return 96;
    }

    /** Inclusive world height bounds, so a ray heading up can stop rather than marching to the distance cap. */
    int minY();

    int maxY();

    /**
     * The highest block anywhere in what was captured.
     *
     * <p>The cheapest optimization here by a distance. A ray heading upward from above this can only ever see
     * sky, so it stops immediately instead of stepping to the far plane - and looking at the horizon, which is
     * most of a frame, is exactly the case that would otherwise march the full distance for nothing. Terrain
     * tops out a couple of hundred blocks below the world ceiling, so the bound is far tighter than
     * {@link #maxY()}.
     */
    default int highestBlock() {
        return maxY();
    }

    /**
     * The highest block in one column, or below {@link #minY()} for a column with nothing in it.
     *
     * <p>What makes long range affordable. Stepping the grid is a few adds; {@link #stateAt} is a chunk lookup, a
     * block-data read and a map lookup, and it is the whole cost of a ray. A ray travelling above the terrain -
     * which is most of a frame looking at the horizon - can skip every one of those calls by comparing against
     * this instead, so implementations should make it a heightmap read and nothing more.
     */
    default int columnTop(int x, int z) {
        return maxY();
    }

    /**
     * Where there is provably nothing to draw, so that a ray can cross it without asking about a single block.
     *
     * <p>The same argument as {@link #columnTop} taken a step further: that one saves the block read and still pays a
     * heightmap read per column, and a ray over open ground crosses a lot of columns. This saves both, for the sky
     * above terrain and for the inside of a cave alike.
     *
     * <p>Answering {@link EmptySpace#NONE} is always correct and only slower.
     */
    default EmptySpace emptySpace() {
        return EmptySpace.NONE;
    }
}
