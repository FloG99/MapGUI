package de.flog99.mapgui.event;

import de.flog99.mapgui.Screen;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * A screen about to be put in a player's hands, raised before anything is drawn or any slot is faked.
 *
 * <p>Cancelling means it does not open: {@code MapGui#open} returns null and nothing was started, so there is
 * nothing to put back. A screen pushed on top of another is vetoed the same way and leaves the one underneath
 * exactly as it was.
 *
 * <p>Only screens a player holds. A wall's screen is not opened per player in this sense - it belongs to the
 * wall - so {@link MapGuiWallPlaceEvent} is the one that covers those.
 */
@ApiStatus.Experimental
public final class MapGuiScreenOpenEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Screen screen;
    private final boolean pushed;

    private boolean cancelled;

    public MapGuiScreenOpenEvent(Player player, Screen screen, boolean pushed) {
        super(player);
        this.screen = screen;
        this.pushed = pushed;
    }

    public Screen screen() {
        return screen;
    }

    /**
     * True when this screen is going on top of one the player already has open, rather than being the first.
     *
     * <p>Worth telling apart: the first is somebody being handed a menu, a pushed one is a page inside a menu
     * they are already using. A listener gating <i>access</i> wants the first and should let the rest through.
     */
    public boolean pushed() {
        return pushed;
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

    /** Raised by MapGUI, on the main thread. Returns whether the screen should open. */
    @ApiStatus.Internal
    public static boolean allows(Player player, Screen screen, boolean pushed) {
        return Events.allows(new MapGuiScreenOpenEvent(player, screen, pushed));
    }
}
