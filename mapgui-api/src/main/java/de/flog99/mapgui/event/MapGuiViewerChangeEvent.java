package de.flog99.mapgui.event;

import de.flog99.mapgui.WallDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.UUID;

/**
 * Who a wall started or stopped showing itself to, raised once for a tick in which either happened.
 *
 * <p>Batched rather than one per player, because a wall settles its whole audience in one pass - and a crowd
 * walking past a row of screens would otherwise be an event each way per screen per person.
 *
 * <p>Not cancellable. Being a viewer follows from standing in range and passing
 * {@link WallDisplay.Builder#visibleTo}, which is where to decide it: refusing the fact afterwards would leave
 * the wall and the client disagreeing about what the client has been sent.
 *
 * <p>Whether a viewer is being <i>sent frames</i> is a separate and much busier question - see
 * {@link WallDisplay.Builder#cullOffScreen} - and deliberately not an event. Somebody glancing about in front
 * of a video wall crosses that line several times a second.
 */
@ApiStatus.Experimental
public final class MapGuiViewerChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final WallDisplay wall;
    private final List<Player> arrived;
    private final List<UUID> left;

    public MapGuiViewerChangeEvent(WallDisplay wall, List<Player> arrived, List<UUID> left) {
        this.wall = wall;
        this.arrived = List.copyOf(arrived);
        this.left = List.copyOf(left);
    }

    public WallDisplay wall() {
        return wall;
    }

    /** Who has just been shown the wall. Never empty unless {@link #left()} is not. */
    public List<Player> arrived() {
        return arrived;
    }

    /**
     * Who has just stopped being a viewer, by id.
     *
     * <p>Ids rather than players, because leaving the world or the server is one of the ways to stop being a
     * viewer - so there is not always a player left to hand over.
     */
    public List<UUID> left() {
        return left;
    }

    /** How many are watching now that this tick's comings and goings are settled. */
    public int viewerCount() {
        return wall.viewerCount();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /** Raised by MapGUI, on the main thread, and only when something actually changed. */
    @ApiStatus.Internal
    public static void fire(WallDisplay wall, List<Player> arrived, List<UUID> left) {
        if (arrived.isEmpty() && left.isEmpty()) return;

        Events.fire(new MapGuiViewerChangeEvent(wall, arrived, left));
    }
}
