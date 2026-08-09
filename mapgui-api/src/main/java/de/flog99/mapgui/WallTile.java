package de.flog99.mapgui;

import org.bukkit.block.BlockFace;

/**
 * One map of a wall as somebody is seeing it: where it hangs, and the palette indices on it.
 *
 * <p>What a camera needs to photograph a wall. A wall puts nothing in the world - the maps and the frames holding
 * them exist only in each viewer's client - so there is nothing for a capture to find by looking. It has to ask.
 *
 * <p>Per viewer because a per-player wall really is a different picture for each of them, and because a wall paints
 * only for the people watching it: somebody who has walked out of range is shown nothing, so a photograph of the wall
 * from over there has nothing to show either.
 *
 * @param pixels one map's worth, 128 by 128, indexed {@code x + y * 128} with x running right and y down as the
 *               viewer sees it - the same array a map update carries
 */
public record WallTile(int blockX, int blockY, int blockZ, BlockFace facing, byte[] pixels) {
}
