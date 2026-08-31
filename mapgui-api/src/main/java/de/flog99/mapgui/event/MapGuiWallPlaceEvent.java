package de.flog99.mapgui.event;

import de.flog99.mapgui.WallDisplay;
import de.flog99.mapgui.WallLayout;
import org.bukkit.World;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

/**
 * A wall about to go up, raised before it is registered, ticked, or sent to a single client.
 *
 * <p>This is the one a claim or region plugin wants. Cancelling it stops the wall existing:
 * {@link WallDisplay.Builder#open()} returns null and nothing was started, so protecting a region against
 * MapGUI walls takes one listener and no cooperation from whoever is placing them.
 *
 * <p>{@link #layout()} is the geometry, in blocks, and every block of the grid is reachable from it:
 *
 * <pre>{@code
 * WallLayout layout = event.layout();
 * for (int row = 0; row < layout.rows(); row++) {
 *     for (int col = 0; col < layout.cols(); col++) {
 *         // the block the map hangs on, with layout.facing() the side it sits against
 *         Block block = event.world().getBlockAt(layout.blockX(col, row), layout.blockY(col, row), layout.blockZ(col, row));
 *     }
 * }
 * }</pre>
 *
 * <p>There is no placing player, because a wall need not have one - most are opened by a plugin on startup,
 * putting back what it saved. A listener wanting to know who asked should gate the command or the menu that
 * asks rather than the wall itself.
 *
 * <p>Not raised for {@link WallDisplay.Builder#preview}, which puts nothing up: a preview is one frame sent to
 * one player, exists only in their client, and is gone by the time they let go of it.
 */
@ApiStatus.Experimental
public final class MapGuiWallPlaceEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final WallDisplay wall;

    private boolean cancelled;

    public MapGuiWallPlaceEvent(WallDisplay wall) {
        this.wall = wall;
    }

    /**
     * The wall, built but not yet up.
     *
     * <p>Nothing has been sent to any client and nothing is ticking it, so it is here to be read rather than
     * driven - and if this event is cancelled it is thrown away unused.
     */
    public WallDisplay wall() {
        return wall;
    }

    public World world() {
        return wall.world();
    }

    /** Where the grid is and how big it ended up, which is not always what was asked for - the content's own limits narrow it. */
    public WallLayout layout() {
        return wall.layout();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /** Raised by MapGUI, on the main thread. Returns whether the wall should go up. */
    @ApiStatus.Internal
    public static boolean allows(WallDisplay wall) {
        return Events.allows(new MapGuiWallPlaceEvent(wall));
    }
}
