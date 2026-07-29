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
     * Makes the client believe every hotbar slot holds this same map, and keeps it believing.
     *
     * <p>Not a one-off packet: a single send is undone by the next inventory resync, and canceling a
     * right-click on a block is exactly that. The slots are substituted on their way out instead, so no
     * resync can reveal what is really there. Call it again to change the item.
     *
     * <p>The whole hotbar rather than one slot, because the client lowers and raises the held item whenever
     * the selected slot changes - and the wheel is how MapGUI scrolls, so that would animate on every notch.
     * Nine copies of one item means the wheel changes nothing visible. The offhand is reported empty because
     * a map is only drawn large and two-handed while the other hand is free.
     */
    void showMapItem(Player player, ItemStack item, int mapId);

    /** Drops the pretence and lets the client see what is actually in the slot. */
    void hideMapItem(Player player);
}
