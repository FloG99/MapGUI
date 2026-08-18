package de.flog99.mapgui.render;

import java.util.Arrays;

/**
 * A world that remembers the blocks it has already been asked about, for the frame it is asked about them in.
 *
 * <p>Because asking is expensive and the same positions are asked about again and again. A capture's world is a
 * {@code ChunkSnapshot}, and Bukkit's contract for reading a block state out of one is that you get an object you may
 * keep - so it hands back a <b>fresh copy every call</b>. Measured on a live server drawing a reflection over water,
 * with a profiler: {@code CraftBlockData.clone()} was <b>36.6%</b> of everything the trace did, and
 * {@code SnapshotWorld.stateAt} 53.5% of it including the cache lookup behind it.
 *
 * <p>And a ray asks far more often than it steps:
 *
 * <ul>
 *   <li>a fluid surface averages four blocks per corner, so one water block asks about nine positions sixteen times -
 *       and again for which way it flows
 *   <li>{@code above} asks for the block over a fluid to know whether it is full to the brim, which is a second read of
 *       a position the ray will step into next
 *   <li>{@code culled} asks about the neighbour behind every face it draws
 *   <li>and the pixel next door walks the same blocks all over again
 * </ul>
 *
 * <p>So this sits in front of the world for the duration of one band and answers from a direct-mapped table. Costs a
 * pack, a mask and an array read against a copy and a hash lookup; on a frame of water it takes the block reads that
 * reach the world from 18 a ray to under 2.
 *
 * <p>Sound because <b>a frame's world cannot change under it</b> - a capture is copied out of the server in one tick and
 * then only read, which is the same reason {@code RayCaster} may remember a fluid's corners and a block's tint. Emptied
 * per band rather than trusted across them, so a caster that traces the next frame cannot answer from this one.
 *
 * <p>Direct-mapped rather than a real map: a collision costs one re-read of the world, which is exactly what it cost
 * before, and there is nothing to allocate or rehash. One per tracing thread, so no synchronization either.
 */
final class SeenBlocks implements VoxelSource {

    /**
     * How many positions to remember. Sixteen thousand of them, which is a few frames' worth of the blocks a band
     * actually touches, at 128 KB of keys and 64 KB of references per tracing thread.
     */
    private static final int SLOTS = 1 << 14;

    /** No position packs to this, so it is a slot that nothing can match - including the origin, which packs to 0. */
    private static final long NOTHING = -1;

    private static final byte UNKNOWN = 0;
    private static final byte SOLID = 1;
    private static final byte NOT_SOLID = 2;

    private final long[] keys = new long[SLOTS];
    private final BakedState[] states = new BakedState[SLOTS];

    /** Kept beside the state because it is the server's answer and not the model's - see {@link VoxelSource#solidAt}. */
    private final byte[] solid = new byte[SLOTS];

    private VoxelSource world;

    SeenBlocks() {
        Arrays.fill(keys, NOTHING);
    }

    /** Points this at the world for one band, forgetting whatever it was told about the last one. */
    VoxelSource over(VoxelSource source) {
        this.world = source;
        Arrays.fill(keys, NOTHING);
        return this;
    }

    private static long key(int x, int y, int z) {
        return (long) (x & 0x3FFFFFF) << 38 | (long) (z & 0x3FFFFFF) << 12 | y + 2048 & 0xFFF;
    }

    private static int slotOf(long key) {
        return (int) (key ^ key >>> 29 ^ key >>> 47) & SLOTS - 1;
    }

    /** The slot this position owns, emptied first if it is holding another one. */
    private int slotFor(int x, int y, int z) {
        long key = key(x, y, z);
        int slot = slotOf(key);
        if (keys[slot] != key) {
            keys[slot] = key;
            states[slot] = null;
            solid[slot] = UNKNOWN;
        }
        return slot;
    }

    @Override
    public BakedState stateAt(int x, int y, int z) {
        int slot = slotFor(x, y, z);
        BakedState known = states[slot];
        if (known != null) return known;

        BakedState asked = world.stateAt(x, y, z);
        states[slot] = asked;
        return asked;
    }

    @Override
    public boolean solidAt(int x, int y, int z) {
        int slot = slotFor(x, y, z);
        byte known = solid[slot];
        if (known != UNKNOWN) return known == SOLID;

        boolean asked = world.solidAt(x, y, z);
        solid[slot] = asked ? SOLID : NOT_SOLID;
        return asked;
    }

    @Override
    public int lightAt(int x, int y, int z) {
        return world.lightAt(x, y, z);
    }

    @Override
    public int tintAt(int x, int y, int z, int index) {
        return world.tintAt(x, y, z, index);
    }

    @Override
    public Sky sky() {
        return world.sky();
    }

    @Override
    public int submergedIn() {
        return world.submergedIn();
    }

    @Override
    public double submergedSight() {
        return world.submergedSight();
    }

    @Override
    public int minY() {
        return world.minY();
    }

    @Override
    public int maxY() {
        return world.maxY();
    }

    @Override
    public int highestBlock() {
        return world.highestBlock();
    }

    @Override
    public int columnTop(int x, int z) {
        return world.columnTop(x, z);
    }

    @Override
    public int maxTopIn(int x, int z, int shift) {
        return world.maxTopIn(x, z, shift);
    }

    @Override
    public EmptySpace emptySpace() {
        return world.emptySpace();
    }
}
