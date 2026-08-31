package de.flog99.mapgui.event;

import de.flog99.mapgui.WallDisplay;
import de.flog99.mapgui.WallLayout;
import org.bukkit.World;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

/**
 * A wall about to come down, raised before anything is taken off any client.
 *
 * <p>Cancelling leaves the wall up and ticking, and {@link WallDisplay#close()} does nothing. That makes it a
 * way to protect a wall from being taken down, and it needs using with care: the plugin that owns a wall calls
 * {@code close()} when it is finished with the wall, so refusing one means holding a display open on somebody
 * else's behalf. Gate the command or the menu that removes a wall in preference to this.
 *
 * <p>Not raised where a veto could not be honored: a server shutting down, a plugin disabling, a preview being
 * let go of, or {@code /mapgui wall remove}, which has already taken the wall out of {@code walls.yml} and
 * would otherwise leave one nothing owns and nothing can take down.
 */
@ApiStatus.Experimental
public final class MapGuiWallRemoveEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final WallDisplay wall;

    private boolean cancelled;

    public MapGuiWallRemoveEvent(WallDisplay wall) {
        this.wall = wall;
    }

    public WallDisplay wall() {
        return wall;
    }

    public World world() {
        return wall.world();
    }

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

    /** Raised by MapGUI, on the main thread. Returns whether the wall should come down. */
    @ApiStatus.Internal
    public static boolean allows(WallDisplay wall) {
        return Events.allows(new MapGuiWallRemoveEvent(wall));
    }
}
