package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.camera.CameraOptions;
import de.flog99.mapgui.render.BlockModels;
import de.flog99.mapgui.render.CameraView;
import de.flog99.mapgui.render.ChunkFrustum;
import de.flog99.mapgui.render.Sky;
import de.flog99.mapgui.render.Textures;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;

import java.util.UUID;

/**
 * Copies the world a camera can see out of the server, in one tick.
 *
 * <p>Only chunks that are already loaded. Reading a block in an unloaded chunk loads it and generates it if it
 * has never existed, which for a wide capture means hundreds of chunk generations inside a single tick - the
 * same trap {@code TerrainRenderer} documents, and the same answer: what is not loaded is left out and the ray
 * sees sky through it. It also means a capture can never reach further than the server's view distance, however
 * fast the trace gets.
 *
 * <p>Two things keep the tick short: {@link ChunkFrustum} decides which columns a ray could reach at all, and
 * {@link SnapshotCache} hands back the ones a capture a moment ago already copied.
 */
final class WorldCapture {

    private WorldCapture() {
    }

    /**
     * @param cache columns a recent capture already copied, served back for as long as {@link SnapshotCache} will
     *              stand behind them
     */
    static SnapshotWorld take(Location eye, CameraView view, CameraOptions options, BlockModels models,
                              Textures textures, BiomeTints tints, SnapshotCache cache) {

        World world = eye.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        // Clamped because a snapshot only answers within the world's own range, and a player can stand above it.
        int sampleY = Math.clamp(eye.getBlockY(), minY, maxY);
        int radiusChunks = (view.maxDistance() >> 4) + 1;
        int originChunkX = (eye.getBlockX() >> 4) - radiusChunks;
        int originChunkZ = (eye.getBlockZ() >> 4) - radiusChunks;
        int across = radiusChunks * 2 + 1;

        ChunkFrustum frustum = new ChunkFrustum(view, minY, maxY);
        UUID worldId = world.getUID();
        long now = System.nanoTime();
        cache.expire(now);

        ChunkSnapshot[] chunks = new ChunkSnapshot[across * across];
        for (int cz = 0; cz < across; cz++) {
            for (int cx = 0; cx < across; cx++) {
                int chunkX = originChunkX + cx;
                int chunkZ = originChunkZ + cz;
                // Snapshotting a chunk copies its whole column of blocks and light, so the square around the
                // camera is far too much to take: at 96 blocks it is 225 of them on the main thread, and a ray
                // can only ever reach the ones the camera is pointed at.
                if (!frustum.mightSee(chunkX, chunkZ)) {
                    continue;
                }

                // A cached column is only worth anything while the chunk is still loaded, and asking is also what
                // drops the entry: what comes back under that name after an unload is not what was copied.
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    cache.forget(worldId, chunkX, chunkZ);
                    continue;
                }

                ChunkSnapshot snapshot = cache.get(worldId, chunkX, chunkZ, now);
                if (snapshot == null) {
                    // Biomes yes, temperature and rainfall no: what the snapshot would compute is per block, and
                    // the tint wants it per biome, which is cheaper to resolve once from the biome itself.
                    snapshot = world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(true, true, false);
                    cache.put(worldId, chunkX, chunkZ, snapshot, now);
                }
                chunks[cz * across + cx] = snapshot;

                // One biome per chunk, while the main thread still has the world to ask. Only a biome no asset
                // describes needs this, and a datapack one that covers less than a whole chunk centre will simply
                // be resolved without its climate later.
                tints.learn(world, snapshot.getBiome(8, sampleY, 8), (chunkX << 4) + 8, sampleY, (chunkZ << 4) + 8);
            }
        }

        // The air where the camera stands, for a dimension whose background is fog rather than sky. One biome rather
        // than a blend of the ones in view, which is what the client blends - but fog is what is between the lens and
        // everything, so the one at the lens is the one that counts.
        Sky.Dome dome = domeOf(world);
        int air = dome == Sky.Dome.NETHER ? tints.fogOf(eye.getBlock().getBiome()) : 0;
        Sky sky = new Sky(world.getTime(), moonPhase(world), world.hasStorm(), dome, options.clouds(), textures, air);

        return new SnapshotWorld(chunks, originChunkX, originChunkZ, across, models, minY, maxY, sky, tints,
                submergedIn(eye, tints), tints.waterSightOf(eye.getBlock().getBiome()));
    }

    /**
     * The color water fogs this frame to, and 0 for a camera that is not in any.
     *
     * <p>Read here because it is a main thread question about one block, and asked of the block at the eye rather
     * than the feet: a player standing in shallow water is looking over the surface, not through it.
     *
     * <p>Waterlogged blocks count. Swimming through a patch of kelp or past a coral fan is still swimming, and the
     * client fogs those the same way - which is the same reason {@code getBlockData() instanceof Waterlogged} is
     * asked rather than the block simply being water.
     */
    private static int submergedIn(Location eye, BiomeTints tints) {
        Block block = eye.getBlock();
        boolean water = block.getType() == Material.WATER
                || block.getBlockData() instanceof Waterlogged logged && logged.isWaterlogged();

        return water ? tints.waterFogOf(block.getBiome()) : 0;
    }

    /** A custom dimension is treated as an overworld, which is what one usually is. */
    private static Sky.Dome domeOf(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> Sky.Dome.NETHER;
            case THE_END -> Sky.Dome.END;
            default -> Sky.Dome.OVERWORLD;
        };
    }

    /** Where the camera is and what it is pointed at, as the tracer wants it. */
    static CameraView viewOf(Location eye, CameraOptions options, int maxDistance) {
        return new CameraView(eye.getX(), eye.getY(), eye.getZ(), eye.getYaw(), eye.getPitch(),
                options.fov(), maxDistance, options.fog());
    }

    /** Vanilla cycles the moon through eight phases, one per day. */
    private static int moonPhase(World world) {
        return (int) (world.getFullTime() / 24000L % 8L);
    }
}
