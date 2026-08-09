package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.render.EntitySnapshot;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The blocks whose shape is an entity model rather than block json, copied out in the same tick as everything else.
 *
 * <p>A chest has no geometry in {@code models/block/chest.json} - the client draws it from {@code ChestModel} the way
 * it draws a mob, which is why one baked from the blockstate alone comes out invisible. So they are gathered here and
 * handed to the trace as entities, which they already are as far as it is concerned: a mesh, a texture and a yaw.
 *
 * <p>Read from {@link Chunk#getTileEntities()} rather than by walking the blocks in view. The capture has one tick,
 * and a 64 block box is a quarter of a million block reads to find the six chests in it; the chunk already keeps the
 * list.
 */
final class BlockEntityCapture {

    /** Past this a chest is a couple of pixels, and the same bound the entities use. */
    private static final double MAX_DISTANCE = 64;

    /** A cap for the same reason the entities have one: a storage room should not turn one capture into thousands. */
    private static final int MAX_BLOCK_ENTITIES = 64;

    private BlockEntityCapture() {
    }

    static List<EntitySnapshot> take(Location eye, MobAssets assets) {
        World world = eye.getWorld();
        int radius = (int) Math.ceil(MAX_DISTANCE / 16);
        int originX = eye.getBlockX() >> 4;
        int originZ = eye.getBlockZ() >> 4;

        List<EntitySnapshot> drawn = new ArrayList<>();
        double limit = MAX_DISTANCE * MAX_DISTANCE;

        for (int x = originX - radius; x <= originX + radius && drawn.size() < MAX_BLOCK_ENTITIES; x++) {
            for (int z = originZ - radius; z <= originZ + radius && drawn.size() < MAX_BLOCK_ENTITIES; z++) {
                // Never loaded here: a chunk the server does not have is one the trace already draws through.
                if (!world.isChunkLoaded(x, z)) continue;

                for (BlockState state : world.getChunkAt(x, z).getTileEntities()) {
                    if (drawn.size() >= MAX_BLOCK_ENTITIES) break;

                    Block block = state.getBlock();
                    if (block.getLocation().distanceSquared(eye) > limit) continue;

                    EntitySnapshot snapshot = snapshotOf(block, assets);
                    if (snapshot != null) {
                        drawn.add(snapshot);
                    }
                }
            }
        }
        return drawn;
    }

    /** Null for the great many block entities that are drawn from their own block model and need nothing here. */
    private static EntitySnapshot snapshotOf(Block block, MobAssets assets) {
        BlockData data = block.getBlockData();
        if (!(data instanceof Chest chest)) return null;

        String mesh = switch (chest.getType()) {
            case LEFT -> "chest_left";
            case RIGHT -> "chest_right";
            case SINGLE -> "chest";
        };

        // Placed at the middle of the block and at its floor, which is where the model is measured from.
        EntitySnapshot authored = EntitySnapshot.mob(mesh,
                block.getX() + 0.5, block.getY(), block.getZ() + 0.5,
                yawOf(chest.getFacing()), yawOf(chest.getFacing()), 0, 1f);
        if (authored == null) return null;

        String texture = textureOf(block, chest, assets);
        return texture == null ? null : authored.texture(texture);
    }

    /**
     * The chest this one is: its wood or its metal, and which half of a double it is.
     *
     * <p>Probed against the naming the assets follow rather than tabulated - {@code trapped_chest} wears
     * {@code entity/chest/trapped}, and its left half {@code trapped_left}. A chest whose texture is not there falls
     * back to the plain one, which is what an unknown modded chest gets.
     */
    private static String textureOf(Block block, Chest chest, MobAssets assets) {
        String half = switch (chest.getType()) {
            case LEFT -> "_left";
            case RIGHT -> "_right";
            case SINGLE -> "";
        };

        String stem = block.getType().getKey().value().replace("_chest", "").replace("chest", "normal");
        for (String candidate : List.of(stem, reversed(stem), "normal")) {
            String texture = "entity/chest/" + candidate + half;
            if (assets.atlas().has(texture)) return texture;
        }
        return null;
    }

    /**
     * The same words the other way round, because the two sides do not agree on the order: the block is a
     * {@code weathered_copper_chest} and the texture it wears is {@code copper_weathered}. A rule rather than a
     * table of the four copper states, so a fifth costs nothing.
     */
    private static String reversed(String stem) {
        String[] words = stem.split("_");
        StringBuilder out = new StringBuilder(stem.length());
        for (int i = words.length - 1; i >= 0; i--) {
            out.append(words[i]);
            if (i > 0) {
                out.append('_');
            }
        }
        return out.toString();
    }

    /**
     * The yaw that leaves a chest facing the way the block says.
     *
     * <p>Vanilla turns a chest by the negative of its facing's yaw and leaves a south-facing one alone. The trace
     * turns a model by {@code -180 - yaw}, since a mob's face is on its model's -Z side, so the two meet half a turn
     * apart - which is where the 180 comes from rather than from anything about chests.
     */
    private static float yawOf(BlockFace facing) {
        float yaw = switch (facing) {
            case SOUTH -> 0;
            case WEST -> 90;
            case NORTH -> 180;
            case EAST -> 270;
            default -> 0;
        };
        return yaw - 180;
    }
}
