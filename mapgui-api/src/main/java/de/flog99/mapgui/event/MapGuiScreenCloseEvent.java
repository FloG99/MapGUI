package de.flog99.mapgui.event;

import de.flog99.mapgui.Screen;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * A screen coming off a player, raised as it is detached.
 *
 * <p>Not cancellable, and deliberately: closing is how a player puts a menu down, how a plugin unloads one
 * while its classes are still loaded, and what a disconnect does - none of those can be refused, and a
 * listener that could refuse them would be a way to pin a screen on somebody.
 *
 * <p>One per screen rather than one per session, so a stack three pages deep raises three - deepest first,
 * the order they are taken off in.
 */
@ApiStatus.Experimental
public final class MapGuiScreenCloseEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Screen screen;

    public MapGuiScreenCloseEvent(Player player, Screen screen) {
        super(player);
        this.screen = screen;
    }

    public Screen screen() {
        return screen;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /** Raised by MapGUI, on the main thread. */
    @ApiStatus.Internal
    public static void fire(Player player, Screen screen) {
        Events.fire(new MapGuiScreenCloseEvent(player, screen));
    }
}
