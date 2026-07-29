package de.flog99.mapgui;

import org.bukkit.block.BlockFace;

/**
 * One map hanging on one block face.
 *
 * <p>The block is the one being hung on, not the space in front of it, and {@code facing} is the face
 * of that block the map sits against - so it is exactly what a click on a block reports.
 */
public record FramedMap(int mapId, int blockX, int blockY, int blockZ, BlockFace facing) {
}
