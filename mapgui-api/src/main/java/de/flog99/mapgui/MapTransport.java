package de.flog99.mapgui;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Gets pixels, and the map item itself, to one client.
 *
 * <p>None of it exists on the server. There is no {@link org.bukkit.map.MapView} and the item is never in
 * anyone's inventory - the client is told a slot holds a map, and told what it looks like. An item the
 * server does not hold cannot be dropped, stolen, framed or left on the ground on death, so none of that
 * needs defending against.
 */
public interface MapTransport {

    /**
     * Pushes the changed rectangle of a surface under a map id only this player sees.
     *
     * <p>Markers go whole every time, since the client replaces its set from each update rather than merging.
     */
    void sendMap(Player player, int mapId, MapSurface surface, List<Marker> markers);

    /**
     * Pushes one rectangle of already-extracted pixels, in the map's own 0..127 coordinates.
     *
     * <p>For a wall, where one surface spans several maps and each is told only about its own tile.
     */
    void sendMap(Player player, int mapId, int x, int y, int width, int height, byte[] pixels);

    /**
     * Runs the action with everything it sends this player held back and delivered as one lot.
     *
     * <p>A wall is several maps, so a frame is several packets, and the client draws whichever have arrived
     * when it next renders. Halfway through a frame the top of the picture is the new one and the bottom is
     * still the old, which reads as tearing on anything that moves. Bundled, the client applies all of them
     * in the same tick or none of them.
     *
     * <p>Nesting is allowed and does nothing: the outermost call is the one that bundles.
     *
     * <p>Optional. A transport that cannot do it just sends as it goes, which is what the default does.
     */
    default void bundled(Player player, Runnable sends) {
        sends.run();
    }

    /** Client-only item frames for a grid of maps, invisible so the grid reads as one picture. Nothing is sent until shown. */
    MapMount framedMaps(World world, List<FramedMap> maps);

    /**
     * Markers only, for a map whose picture has not changed. Coordinates are 0..127 within that one map.
     *
     * <p>What a cursor costs on a shared wall, and the only reason every viewer can have their own: the
     * pixels go to everyone identically and a pointer moving is a few bytes rather than a frame.
     */
    void sendMarkers(Player player, int mapId, List<Marker> markers);

    /** Everything this transport has pushed, measured here because it is the only place bytes actually leave. */
    Bandwidth bandwidth();

    Bandwidth bandwidth(Player player);

    /**
     * Makes the client believe the given slots hold this map, and keeps it believing.
     *
     * <p>Not a one-off packet: a single send is undone by the next inventory resync, and canceling a
     * right-click on a block is exactly that. The slots are substituted on their way out instead, so no
     * resync can reveal what is really there. Call it again to change the item or move it.
     *
     * <p>{@link MapSlots#wholeHotbar()} is what a popup uses, and the reason it takes all nine is the wheel:
     * the client lowers and raises the held item whenever the selected slot changes, so with one slot faked a
     * scroll animates. Nine copies of one item means the wheel changes nothing visible, which is what frees it
     * to be the menu's own scroll. A map meant to read as an item wants {@link MapSlots#hotbar(int)} or
     * {@link MapSlots#offhandOnly()} instead, and gives the wheel back to the player.
     */
    void showMapItem(Player player, ItemStack item, int mapId, MapSlots slots);

    /** The whole hotbar, which is what this used to be the only way to ask for. */
    default void showMapItem(Player player, ItemStack item, int mapId) {
        showMapItem(player, item, mapId, MapSlots.wholeHotbar());
    }

    /** Drops the pretence and lets the client see what is actually in the slot. */
    void hideMapItem(Player player);

    /**
     * Drops whatever is being remembered about a player who has left.
     *
     * <p>What a transport keeps per player is small - a byte count, a note that their slots are faked - but it
     * is keyed by a UUID and nothing else ever removes it, so without this a server accumulates an entry for
     * every player who has ever opened a map and holds them until it restarts.
     *
     * <p>Optional, since a transport with nothing per player has nothing to forget.
     */
    default void forget(Player player) {
    }
}
