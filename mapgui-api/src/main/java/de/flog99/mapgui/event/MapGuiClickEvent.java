package de.flog99.mapgui.event;

import de.flog99.mapgui.Click;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.WallDisplay;
import de.flog99.mapgui.ui.Node;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * A press on a MapGUI screen, raised before the screen itself hears about it.
 *
 * <p>Carries {@link #node()}, the path the layout gave whatever was pressed - {@code settings/volume} rather
 * than a pair of pixel coordinates. That is what makes this readable to a plugin which does not own the
 * screen: an analytics or audit listener can say <i>which control</i> was used without knowing anything about
 * the menu it belongs to, where a pixel position tells it nothing at all. The pixels are here too, for
 * something drawing rather than pressing.
 *
 * <p>Cancelling swallows the click: the screen is not told, no click sound plays, and nothing the press would
 * have run runs. The gesture is still taken off the player either way - the whole point of a menu is that a
 * right-click means "press this" and not "open the chest behind it" - so vetoing here silences the button, it
 * does not hand the click back to the world.
 *
 * <p>Raised once per press, which is cheap. There is deliberately no cursor-move event: a pointer moves every
 * tick for every viewer of every wall, and an event on that would cost more than everything it watched.
 */
@ApiStatus.Experimental
public final class MapGuiClickEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Screen screen;

    @Nullable
    private final WallDisplay wall;
    private final int x;
    private final int y;

    @Nullable
    private final String node;
    private final Click button;

    private boolean cancelled;

    public MapGuiClickEvent(Player player, Screen screen, @Nullable WallDisplay wall, int x, int y, @Nullable String node, Click button) {
        super(player);
        this.screen = screen;
        this.wall = wall;
        this.x = x;
        this.y = y;
        this.node = node;
        this.button = button;
    }

    /** The screen being pressed - the one on top of the player's stack, or the one this wall is showing them. */
    public Screen screen() {
        return screen;
    }

    /** The wall that was pressed, or null for a screen the player is holding. */
    @Nullable
    public WallDisplay wall() {
        return wall;
    }

    /**
     * Where on the screen, in its own pixels, or -1 for a screen with no cursor.
     *
     * <p>A cursorless screen is still clicked - that is what {@code clickedAnywhere} is for - and there is no
     * position to report for it.
     */
    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    /**
     * The layout path of whatever is under the click - {@code settings/volume} - or null if nothing is.
     *
     * <p>A node's key if it was given one, otherwise its position in the tree. A key is worth setting on
     * anything a listener might want to recognise, since a path shifts when rows move.
     */
    @Nullable
    public String node() {
        return node;
    }

    /** Which button, which is only ever anything but {@link Click#RIGHT} for a screen that widened what it accepts. */
    public Click button() {
        return button;
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

    /** Raised by MapGUI, on the main thread. Returns whether the screen should be told. */
    @ApiStatus.Internal
    public static boolean allows(Player player, Screen screen, @Nullable WallDisplay wall, int x, int y, Click button) {
        return Events.allows(new MapGuiClickEvent(player, screen, wall, x, y, nodeAt(screen, x, y), button));
    }

    /**
     * Hit-tests the laid-out tree for the node path, which the screen is about to hit-test again for the
     * click itself.
     *
     * <p>Twice rather than threaded through, because the two answers are asked at different levels and a click
     * is once per press - a tree walk there is beneath measuring.
     */
    @Nullable
    static String nodeAt(Screen screen, int x, int y) {
        if (x < 0 || y < 0) return null;

        Node root = screen.root();
        Node hit = root == null ? null : root.hitTest(x, y);
        return hit == null ? null : hit.identity();
    }
}
