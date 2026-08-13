package de.flog99.mapgui.examples.minimap;

import de.flog99.mapgui.HandOptions;
import de.flog99.mapgui.Marker;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.ui.Align;
import de.flog99.mapgui.ui.Colors;
import de.flog99.mapgui.ui.Justify;
import de.flog99.mapgui.ui.Node;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.map.MapCursor;

import java.awt.Color;
import java.util.List;

import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Row;
import static de.flog99.mapgui.ui.Ui.Spacer;
import static de.flog99.mapgui.ui.Ui.Text;

/**
 * Terrain drawn straight from the world, with a HUD on top.
 *
 * <p>Unlike a real map this follows the player rather than being pinned to a fixed center, which
 * is what {@code terrain()} buys you.
 *
 * <p>Also the example of a screen only meant to be looked at: no cursor at all, since there is nothing
 * on it to press and a pointer floating over the terrain would only be in the way. That leaves the
 * player's aim alone too.
 */
public final class MinimapScreen extends Screen {

    private static final int BLOCKS_PER_PIXEL = 1;

    private boolean showPlayer;

    /**
     * Worn in the offhand, and taking nothing from the player.
     *
     * <p>A minimap is the clearest case for not being a popup. A popup fills the hotbar and swallows every click,
     * which for something you look at while walking about is exactly wrong - you would be unable to mine, hit or
     * place anything while your map was up. In the offhand the whole hotbar stays the player's, and
     * {@link de.flog99.mapgui.HandOptions.Focus#NEVER} says the map never wants their mouse either.
     */
    @Override
    public HandOptions hand() {
        return HandOptions.offhand().focus(HandOptions.Focus.NEVER);
    }

    /**
     * Swapping hands puts it away.
     *
     * <p>A map with no cursor and no clicks has no key of its own, so without this the only way out is the command
     * that opened it. The swap-hands key is free here for the same reason the mouse is: nothing is being held to
     * swap, and {@link de.flog99.mapgui.HandOptions.Focus#NEVER} never claims it as a focus toggle.
     */
    @Override
    protected void onSwapHands() {
        session().close();
    }

    /**
     * Draw an icon for the player themselves. Off by default: the terrain is centered on them, so the
     * icon can only ever sit in the middle of the map and say nothing you did not already know.
     */
    public MinimapScreen showPlayer(boolean value) {
        this.showPlayer = value;
        return this;
    }

    @Override
    public Component title() {
        return Component.text("Minimap", NamedTextColor.GREEN);
    }

    @Override
    public boolean terrain() {
        return true;
    }

    @Override
    public int blocksPerPixel() {
        return BLOCKS_PER_PIXEL;
    }

    @Override
    public boolean cursor() {
        return false;
    }

    /** Terrain is centered on the player, so the player is always the middle pixel. */
    @Override
    public List<Marker> markers() {
        if (!showPlayer) return List.of();

        Location location = player().getLocation();
        return List.of(Marker.at(MapCursor.Type.RED_MARKER, 64, 64).rotation(Math.round(location.getYaw() / 22.5f)));
    }

    @Override
    protected Node build() {
        Color panel = Colors.alpha(Color.BLACK, 150);

        return Column(
                Row(Text(this::coordinates).color(Color.WHITE).shadow().padding(1, 3)
                        .background(panel).radius(2)).justify(Justify.CENTER).fillWidth(),
                Spacer(),
                Row(Text("swap hands to close").color(new Color(200, 200, 200)).padding(1, 3)
                        .background(panel).radius(2)).justify(Justify.CENTER).fillWidth()
        ).gap(2).padding(4).align(Align.STRETCH);
    }

    private String coordinates() {
        Location location = player().getLocation();
        return location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ();
    }
}
