package de.flog99.mapgui.camera;

import de.flog99.mapgui.WallTile;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * The MapGUI walls somebody can see, so a capture can photograph what is playing on them.
 *
 * <p>A wall is the one thing in front of a camera that is not in the world. Its maps and the frames holding them
 * exist only in each viewer's client, so a capture that looks at the blocks finds bare stone where the video is -
 * which is exactly the shot somebody standing in a cinema wants to take.
 */
@ApiStatus.Internal
public interface LiveWalls {

    /**
     * Every map of every open wall this player is being shown, in no particular order.
     *
     * <p>Theirs rather than everybody's: a per-player wall really is a different picture for each viewer, and a wall
     * nobody is watching from over here has been sent nothing to show.
     */
    List<WallTile> shownTo(Player viewer);
}
