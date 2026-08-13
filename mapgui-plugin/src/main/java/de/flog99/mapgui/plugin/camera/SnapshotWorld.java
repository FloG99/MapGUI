package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.render.BakedState;
import de.flog99.mapgui.render.BiomeBlend;
import de.flog99.mapgui.render.BlockModels;
import de.flog99.mapgui.render.EmptySpace;
import de.flog99.mapgui.render.Sky;
import de.flog99.mapgui.render.VoxelSource;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A frozen square of world, safe to trace off the main thread.
 *
 * <p>{@code ChunkSnapshot} is the API that makes this possible at all: a read-only copy carrying block data,
 * sky and block light, and biomes, explicitly usable from another thread. The snapshots are taken in one tick
 * and then nothing here touches the server again.
 *
 * <p>Snapshots are held rather than copied into a dense array. A 129 block radius flattened out is over two
 * million entries, and the walk mostly stays near the camera - so an indexed grid of snapshots costs a couple
 * of array reads per step and none of that memory. Flattening is the optimization to reach for when render
 * distance becomes the problem, not before.
 */
final class SnapshotWorld implements VoxelSource {

    private final ChunkSnapshot[] chunks;
    private final int originChunkX;
    private final int originChunkZ;
    private final int chunksAcross;

    private final BlockModels models;
    private final int minY;
    private final int maxY;
    private final Sky sky;
    private final int highestBlock;

    /** Highest block a chunk could hold, from its topmost non-empty section - the bound the column scan starts at. */
    private final int[] ceilings;

    /**
     * Which space a ray can cross without asking about it, built once here rather than measured while tracing.
     *
     * <p>Eager and immutable, which is the whole reason it can be built at all cheaply: the emptiness is already
     * recorded per section, so this is a few thousand flag reads at copy time and then a byte read per query on
     * however many threads the trace uses.
     */
    private final EmptySpace empty;

    /**
     * True surface per column, measured on demand and kept, since many rays cross the same one.
     *
     * <p>Stored biased so that zero means "not measured yet". A fresh {@code int[]} is all zeroes and a thread that
     * sees the array before it sees what was written into it would otherwise read those zeroes as a real Y at the
     * bottom of the world. Biasing costs an add and makes the unwritten state unrepresentable, which is cheaper
     * than making every read of this synchronize. Two threads racing both measure the same column and agree.
     */
    private final int[][] columnTops;

    /**
     * Baked states by the exact {@code BlockData} the snapshot handed over.
     *
     * <p>Keyed by the object rather than by {@code getAsString()}, which allocates - and this is read once per
     * step of every ray. The server interns one instance per distinct state, so this map stays as small as the
     * number of states actually in view.
     */
    private final Map<BlockData, BakedState> states = new ConcurrentHashMap<>();

    /** The same states again for the blocks standing under more of their own fluid, which fills them to the top. */
    private final Map<BlockData, BakedState> flooded = new ConcurrentHashMap<>();

    private final BiomeTints tints;

    /** What water fogs this frame to, and 0 for a camera in open air. Decided at the eye, on the main thread. */
    private final int submerged;

    /** And how far it can see through it, which two biomes shorten. */
    private final double submergedSight;

    SnapshotWorld(ChunkSnapshot[] chunks, int originChunkX, int originChunkZ, int chunksAcross,
                  BlockModels models, int minY, int maxY, Sky sky, BiomeTints tints, int submerged, double submergedSight) {
        this.tints = tints;
        this.submerged = submerged;
        this.submergedSight = submergedSight;
        this.chunks = chunks;
        this.originChunkX = originChunkX;
        this.originChunkZ = originChunkZ;
        this.chunksAcross = chunksAcross;
        this.models = models;
        this.minY = minY;
        this.maxY = maxY;
        this.sky = sky;
        this.columnTops = new int[chunks.length][];

        this.ceilings = new int[chunks.length];
        int highest = minY - 1;
        EmptySpace.Builder building = EmptySpace.over(originChunkX << 4, minY, originChunkZ << 4,
                ((originChunkX + chunksAcross) << 4) - 1, maxY, ((originChunkZ + chunksAcross) << 4) - 1);

        int sections = (maxY - minY) >> 4;
        for (int i = 0; i < chunks.length; i++) {
            ceilings[i] = ceilingOf(chunks[i]);
            highest = Math.max(highest, ceilings[i]);

            // A missing chunk marks nothing, which is right rather than convenient: an unloaded or culled chunk is
            // one the trace already draws sky through, so a ray has no more business stepping through it than
            // through open air.
            if (chunks[i] == null) continue;

            int blockX = (originChunkX + i % chunksAcross) << 4;
            int blockZ = (originChunkZ + i / chunksAcross) << 4;
            for (int section = 0; section <= sections; section++) {
                if (!chunks[i].isSectionEmpty(section)) {
                    // Both ends of the section, because a section lines up with a cell only while the world floor is
                    // a multiple of 16. It always is, and a cell half covered by a section nobody told it about would
                    // be a hole in the picture rather than a slow frame.
                    int bottom = minY + (section << 4);
                    building.occupied(blockX, bottom, blockZ).occupied(blockX, bottom + 15, blockZ);
                }
            }
        }

        this.highestBlock = highest;
        this.empty = building.build();
    }

    /** Top of a chunk's highest non-empty section: nothing above it exists, whatever the heightmaps say. */
    private int ceilingOf(ChunkSnapshot chunk) {
        if (chunk == null) return minY - 1;

        for (int section = (maxY - minY) >> 4; section >= 0; section--) {
            if (!chunk.isSectionEmpty(section)) return Math.min(maxY, minY + (section << 4) + 15);
        }
        return minY - 1;
    }

    @Override
    public BakedState stateAt(int x, int y, int z) {
        ChunkSnapshot chunk = chunkAt(x, z);
        if (chunk == null || y < minY || y > maxY) return BakedState.EMPTY;

        // A section that holds nothing is the cheapest possible answer, and most of a column is empty.
        if (chunk.isSectionEmpty((y - minY) >> 4)) return BakedState.EMPTY;

        BlockData data = chunk.getBlockData(x & 15, y, z & 15);
        if (!holdsFluid(data)) {
            return states.computeIfAbsent(data, key -> models.bake(key.getAsString()));
        }

        // Only the surface of a body of fluid is short, so this needs the block above and cannot be answered from
        // the state alone. Two caches rather than a compound key, since the answer is one bit and states repeat.
        boolean covered = holdsFluid(above(x, y, z));
        Map<BlockData, BakedState> cache = covered ? flooded : states;
        return cache.computeIfAbsent(data, key -> models.bake(key.getAsString(), covered));
    }

    /**
     * The block's own answer rather than its model's, which is what a fluid's corner heights are averaged against.
     *
     * <p>Cheaper than {@link #stateAt} as well as righter: no baking and no cache, since solidity is a property of
     * the material and the fluid arithmetic asks about eight neighbours per surface block.
     */
    @Override
    public boolean solidAt(int x, int y, int z) {
        ChunkSnapshot chunk = chunkAt(x, z);
        if (chunk == null || y < minY || y > maxY) return false;
        if (chunk.isSectionEmpty((y - minY) >> 4)) return false;

        return chunk.getBlockData(x & 15, y, z & 15).getMaterial().isSolid();
    }

    /** Null above the world or outside what was captured, which both read as nothing standing there. */
    private BlockData above(int x, int y, int z) {
        ChunkSnapshot chunk = chunkAt(x, z);
        if (chunk == null || y + 1 > maxY) return null;

        return chunk.getBlockData(x & 15, y + 1, z & 15);
    }

    /**
     * Whether a block is fluid as far as its neighbour below is concerned - the fluid itself, or a block standing
     * in some. A waterlogged stair over water holds the pool up just as another water block would.
     */
    private static boolean holdsFluid(BlockData data) {
        if (data == null) return false;

        Material material = data.getMaterial();
        if (material == Material.WATER || material == Material.LAVA) return true;

        return data instanceof Waterlogged logged && logged.isWaterlogged();
    }

    /**
     * The light actually falling on a block, which is not quite the light the server stores.
     *
     * <p>Sky light is how much of the sky reaches here and nothing more - on open ground it is 15 at midnight as
     * surely as at noon, because the day cycle is applied when the world is drawn rather than when it is lit. So
     * the time of day is taken off the sky component here, and only that component: a torch is as bright at night
     * as it is by day, which is rather the point of a torch.
     */
    @Override
    public int lightAt(int x, int y, int z) {
        ChunkSnapshot chunk = chunkAt(x, z);
        if (chunk == null || y < minY || y > maxY) return Math.max(0, 15 - sky.skyDarken());

        int fromSky = Math.max(0, chunk.getBlockSkyLight(x & 15, y, z & 15) - sky.skyDarken());
        return Math.max(chunk.getBlockEmittedLight(x & 15, y, z & 15), fromSky);
    }

    /**
     * The tint at a block, blended across the biomes around it the way the client blends them.
     *
     * <p>{@link BiomeBlend} is where the arithmetic and the reason for it are. Here it is four biome reads, and
     * usually four of the same biome - a square five blocks wide is inside one biome nearly everywhere, and the
     * server hands back the same interned object for each, so the common answer is one comparison away and the
     * average is never computed.
     */
    @Override
    public int tintAt(int x, int y, int z, int index) {
        // The height is the block's own, and the biome read at it is the 3D one: a lush cave under a desert tints
        // its own vines green while the sand over it stays sand.
        int level = Math.clamp(y, minY, maxY);
        int west = BiomeBlend.low(x);
        int east = BiomeBlend.high(x);
        int north = BiomeBlend.low(z);
        int south = BiomeBlend.high(z);

        Biome northWest = biomeAt(west, level, north);
        Biome northEast = biomeAt(east, level, north);
        Biome southWest = biomeAt(west, level, south);
        Biome southEast = biomeAt(east, level, south);

        // A corner outside what was captured takes another corner's biome rather than dragging the average toward
        // some default. It is two blocks past the edge of the region, which is further out than any ray reaches, and
        // nothing at all is known about what is there.
        Biome captured = northWest != null ? northWest
                : northEast != null ? northEast
                : southWest != null ? southWest : southEast;
        if (captured == null) return 0xFFFFFFFF;

        if (northWest == null) northWest = captured;
        if (northEast == null) northEast = captured;
        if (southWest == null) southWest = captured;
        if (southEast == null) southEast = captured;

        if (northWest == northEast && northWest == southWest && northWest == southEast) {
            return tints.of(northWest, index);
        }

        return BiomeBlend.mix(tints.of(northWest, index), tints.of(northEast, index),
                tints.of(southWest, index), tints.of(southEast, index),
                BiomeBlend.weight(x), BiomeBlend.weight(z));
    }

    /** Null outside the captured region, which is what the caller substitutes a neighbouring corner for. */
    private Biome biomeAt(int x, int y, int z) {
        ChunkSnapshot chunk = chunkAt(x, z);
        return chunk == null ? null : chunk.getBiome(x & 15, y, z & 15);
    }

    @Override
    public Sky sky() {
        return sky;
    }

    @Override
    public int submergedIn() {
        return submerged;
    }

    @Override
    public double submergedSight() {
        return submergedSight;
    }

    /**
     * The highest block in one column, whatever kind of block it is.
     *
     * <p>Deliberately not {@code getHighestBlockYAt}. That is documented as the highest non-air block and in
     * practice is not one: the heightmap a snapshot carries skips blocks you can walk through, so grass, flowers,
     * torches, snow layers and rails are all missing from it. Trusting it made the renderer skip exactly those
     * blocks, and the giveaway was which ones survived - a plant under a tree still drew, because there the leaves
     * above it put the heightmap high enough to cover it.
     *
     * <p>So the surface is measured instead, scanning down from the top of the chunk's highest non-empty section.
     * The heightmap is still worth having as the floor of that scan, and only as that: it names a Y that is
     * certainly not air, which both ends the loop and bounds it by the height of whatever stands above solid
     * ground rather than by the height of the world. Being a lower bound is also why this stays correct if some
     * future version makes it mean what its javadoc says.
     */
    @Override
    public int columnTop(int x, int z) {
        int index = chunkIndex(x, z);
        if (index < 0 || chunks[index] == null) return minY - 1;

        int[] tops = columnTops[index];
        if (tops == null) {
            tops = new int[256];
            columnTops[index] = tops;
        }

        int local = (z & 15) << 4 | (x & 15);
        int stored = tops[local];
        if (stored != 0) return stored + minY - 2;

        int top = measure(chunks[index], ceilings[index], x & 15, z & 15);
        tops[local] = top - minY + 2;
        return top;
    }

    private int measure(ChunkSnapshot chunk, int ceiling, int localX, int localZ) {
        int floor = chunk.getHighestBlockYAt(localX, localZ);
        for (int y = ceiling; y > floor; y--) {
            if (!chunk.getBlockType(localX, y, localZ).isAir()) return y;
        }
        return floor;
    }

    /** How many chunks were actually copied, for a report on what the capture tick cost. */
    int chunks() {
        int copied = 0;
        for (ChunkSnapshot chunk : chunks) {
            if (chunk != null) {
                copied++;
            }
        }
        return copied;
    }

    /**
     * Sections with blocks in them, against every section of every copied chunk.
     *
     * <p>A section is the 16x16x16 cube Minecraft divides a chunk into - a subchunk, and what
     * {@code LevelChunkSection} is.
     *
     * <p>For deciding whether copying less is worth reaching past Bukkit for. A snapshot already costs nothing for a
     * section of pure air - the block container is a shared empty one, and a section of open sky has no light layer to
     * copy either - so the room left is only in the sections that do hold something and that no ray can reach anyway,
     * which is mostly the stone underneath you. This says how much that could ever be.
     */
    int[] sections() {
        int filled = 0;
        int total = 0;
        int perColumn = (maxY - minY + 1) / 16;

        for (ChunkSnapshot chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            total += perColumn;
            for (int section = 0; section < perColumn; section++) {
                if (!chunk.isSectionEmpty(section)) {
                    filled++;
                }
            }
        }
        return new int[]{filled, total};
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    @Override
    public int highestBlock() {
        return highestBlock;
    }

    /**
     * Where there is nothing to look at, at section granularity and coarser.
     *
     * <p>Exact by construction and not by inspection, which is the only way it could be trusted. A cell is empty only
     * when every section under it says it is empty, and that is the same flag {@link #stateAt} already answers
     * {@link BakedState#EMPTY} from - so anything the renderer can draw, down to a single sea pickle, keeps its cell.
     */
    @Override
    public EmptySpace emptySpace() {
        return empty;
    }

    /** Null outside what was captured, which is what makes a ray that leaves the region see sky. */
    private ChunkSnapshot chunkAt(int x, int z) {
        int index = chunkIndex(x, z);
        return index < 0 ? null : chunks[index];
    }

    /** Negative outside the captured square. Separate from {@link #chunkAt} for the caches keyed by it. */
    private int chunkIndex(int x, int z) {
        int cx = (x >> 4) - originChunkX;
        int cz = (z >> 4) - originChunkZ;
        if (cx < 0 || cz < 0 || cx >= chunksAcross || cz >= chunksAcross) return -1;

        return cz * chunksAcross + cx;
    }
}
