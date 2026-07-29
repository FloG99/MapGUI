package de.flog99.mapgui;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.awt.Color;

/**
 * Draws the world into a surface, centered on the player and following them - unlike a real map,
 * which is pinned to a fixed origin. Uses {@code BlockData#getMapColor} and the heightmap, so no
 * server internals.
 *
 * <p>Reads a block column per pixel, so it is not cheap: only run it when the player has moved,
 * and throttle it.
 *
 * <p>Only ever reads chunks that are already loaded, and leaves the rest blank. That is what keeps the
 * cost proportional to what the server is holding anyway rather than to how far the map is zoomed out -
 * without it, a wide {@code blocksPerPixel} turns one redraw into thousands of chunk generations.
 */
public final class TerrainRenderer {

    /** Vanilla's three map shades, as fractions of full brightness. */
    private static final double SHADE_LOW = 180 / 255.0;
    private static final double SHADE_NORMAL = 220 / 255.0;
    private static final double SHADE_HIGH = 1.0;

    private static final int MAX_WATER_DEPTH = 32;

    private TerrainRenderer() {
    }

    public static void render(MapSurface surface, Player player, int blocksPerPixel) {
        render(surface, player.getLocation(), blocksPerPixel);
    }

    /**
     * The same, centered on a place rather than a person.
     *
     * <p>What a wall wants: it is bolted to the world, so the ground it shows never moves and can be drawn
     * once and kept rather than redrawn as somebody walks about.
     */
    public static void render(MapSurface surface, Location center, int blocksPerPixel) {
        World world = center.getWorld();
        int scale = Math.max(1, blocksPerPixel);
        int centerX = center.getBlockX();
        int centerZ = center.getBlockZ();
        int halfWidth = surface.width() / 2;
        int halfHeight = surface.height() / 2;
        int minY = world.getMinHeight();

        for (int px = 0; px < surface.width(); px++) {
            int worldX = centerX + (px - halfWidth) * scale;
            double previousHeight = Double.NaN;

            for (int py = 0; py < surface.height(); py++) {
                int worldZ = centerZ + (py - halfHeight) * scale;

                // Reading a block in an unloaded chunk loads it, and generates it if it has never
                // existed. At one block per pixel the scan covers roughly what is loaded around the
                // player anyway, but every step of zoom quadruples the area: 1024 blocks across is 4096
                // chunks, and asking for those inside one tick stops a server dead. Ground nobody has
                // loaded is left blank instead, which is also what a vanilla map does with it.
                if (!world.isChunkLoaded(worldX >> 4, worldZ >> 4)) {
                    surface.set(px, py, (byte) 0);
                    previousHeight = Double.NaN;
                    continue;
                }

                Block block = visibleBlock(world, worldX, worldZ, minY);
                Color color = new Color(block.getBlockData().getMapColor().asRGB());
                double height = block.getY();
                if (Double.isNaN(previousHeight)) {
                    previousHeight = height;
                }

                double shade = block.isLiquid()
                        ? waterShade(block, minY, px, py)
                        : terrainShade(height, previousHeight, scale, px, py);

                surface.set(px, py, MapColors.INSTANCE.index(scaled(color, shade)));
                previousHeight = height;
            }
        }
    }

    /**
     * Top block that actually has a color on maps. The heightmap can land on something
     * transparent, in which case vanilla keeps walking down.
     */
    private static Block visibleBlock(World world, int x, int z, int minY) {
        Block block = world.getHighestBlockAt(x, z, HeightMap.WORLD_SURFACE);
        while (block.getY() > minY && block.getBlockData().getMapColor().asRGB() == 0) {
            block = block.getRelative(BlockFace.DOWN);
        }
        return block;
    }

    /** Vanilla shades land by how much it rises or falls going north, plus a dither. */
    private static double terrainShade(double height, double previousHeight, int scale, int px, int py) {
        double slope = (height - previousHeight) * 4.0 / (scale + 4) + ((px + py & 1) - 0.5) * 0.4;
        if (slope > 0.6) return SHADE_HIGH;
        if (slope < -0.6) return SHADE_LOW;
        return SHADE_NORMAL;
    }

    /** Deeper water is drawn darker. */
    private static double waterShade(Block surfaceBlock, int minY, int px, int py) {
        int depth = 0;
        Block below = surfaceBlock;
        while (depth < MAX_WATER_DEPTH && below.getY() > minY && below.isLiquid()) {
            below = below.getRelative(BlockFace.DOWN);
            depth++;
        }

        double murk = depth * 0.1 + (px + py & 1) * 0.2;
        if (murk < 0.5) return SHADE_HIGH;
        if (murk > 0.9) return SHADE_LOW;
        return SHADE_NORMAL;
    }

    private static Color scaled(Color color, double shade) {
        return new Color((int) (color.getRed() * shade), (int) (color.getGreen() * shade), (int) (color.getBlue() * shade));
    }
}
