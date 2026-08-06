package de.flog99.mapgui.map;

import de.flog99.mapgui.camera.Camera;
import de.flog99.mapgui.camera.CameraShot;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Prints pixels onto real, placeable maps - a picture to hang in an item frame and leave there.
 *
 * <p>Deliberately not how the rest of MapGUI draws anything. A screen is virtual - no {@code MapView}, nothing
 * written into the world, pixels sent as packets - which is what makes it free to open. A picture nailed to a wall
 * has to outlive the session that took it, so it is a genuine map with a genuine id, drawn by a vanilla renderer
 * onto the vanilla canvas, with {@link de.flog99.mapgui.MapTransport} not involved at all.
 *
 * <p><b>Which costs map ids, permanently.</b> Every map printed here takes an id the world keeps forever, exactly as
 * a cartography table would, and one capture is several: 256 pixels square is four maps and 384 is nine. Nothing
 * reclaims them. Fine behind a command somebody types, wrong behind anything a player can hold a button down on.
 *
 * <p><b>These maps do not depend on MapGUI.</b> The picture goes into the pixels the world saves and the map is
 * locked, so it survives a restart and survives MapGUI being uninstalled.
 *
 * <p>On a server whose internals MapGUI cannot reach - a fork, a version it has not seen - printing falls back to
 * drawing the picture itself and says so in the console once. Such a map still shows, but only while MapGUI is
 * installed and enabled. Nothing is thrown either way.
 *
 * <p>Main thread only: making a map is world state. Reached through {@code MapGui.get().printer()}.
 */
public interface MapPrinter {

    /**
     * Prints one map's worth of palette indices onto a new map item.
     *
     * @param pixels {@link Camera#MAP_SIZE} squared indices, row by row, the way {@link CameraShot#pixels(int)}
     *               holds them. Palette indices rather than colours, so nothing is re-matched on the way in
     * @throws IllegalArgumentException if that is not exactly one map's worth
     */
    ItemStack print(World world, byte[] pixels);

    /**
     * Cuts a capture into map-sized squares and prints every one, in reading order - left to right, top to bottom.
     *
     * <p>One pixel per pixel with nothing resampled, which is why the size has to divide exactly: a map is 128 pixels
     * and nothing changes that, so the way to a picture with more in it is more maps. Ask the camera for
     * {@link #sizeFor(int)} and the cut is whole by construction.
     *
     * @throws IllegalArgumentException if the capture is not a square whole number of maps across
     */
    List<ItemStack> print(World world, CameraShot shot);

    /** The capture size that cuts into a square grid this many maps across. */
    static int sizeFor(int mapsAcross) {
        return Camera.MAP_SIZE * mapsAcross;
    }

    /** How many maps across a capture cuts into, or 0 if it is not a whole square number of them. */
    static int mapsAcross(CameraShot shot) {
        if (shot.width() != shot.height() || shot.width() % Camera.MAP_SIZE != 0) {
            return 0;
        }
        return shot.width() / Camera.MAP_SIZE;
    }

    /**
     * The cut on its own: each tile's pixels, in reading order, with no maps made.
     *
     * <p>For a wall of maps you already own - your own item frames, your own furniture - driven through
     * {@link de.flog99.mapgui.MapTransport}. That costs no ids and saves nothing, which is the trade the other way
     * around: free, and gone when the viewer's client forgets it.
     *
     * @throws IllegalArgumentException if the capture is not a square whole number of maps across
     */
    static List<byte[]> cut(CameraShot shot) {
        int across = mapsAcross(shot);
        if (across == 0) {
            throw new IllegalArgumentException("A capture cuts into maps only at a square whole multiple of "
                    + Camera.MAP_SIZE + " pixels, which " + shot.width() + " by " + shot.height() + " is not");
        }

        List<byte[]> tiles = new ArrayList<>(across * across);
        for (int row = 0; row < across; row++) {
            for (int column = 0; column < across; column++) {
                tiles.add(tile(shot, row, column));
            }
        }
        return tiles;
    }

    /** The tile's own square of the capture, copied out row by row - the two have different strides. */
    private static byte[] tile(CameraShot shot, int row, int column) {
        byte[] whole = shot.pixels(0);
        byte[] tile = new byte[Camera.MAP_SIZE * Camera.MAP_SIZE];

        int fromX = column * Camera.MAP_SIZE;
        int fromY = row * Camera.MAP_SIZE;
        for (int y = 0; y < Camera.MAP_SIZE; y++) {
            System.arraycopy(whole, (fromY + y) * shot.width() + fromX, tile, y * Camera.MAP_SIZE, Camera.MAP_SIZE);
        }
        return tile;
    }
}
