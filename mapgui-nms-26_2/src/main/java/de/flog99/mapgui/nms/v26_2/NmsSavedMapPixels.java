package de.flog99.mapgui.nms.v26_2;

import de.flog99.mapgui.map.SavedMapPixels;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;

/**
 * The picture straight into vanilla's own saved map data, so vanilla is what draws it from then on.
 *
 * <p>Three things make that stick. The pixels go into {@code colors}, which is the array the ordinary map renderer
 * paints and the array written to {@code map_N.dat}. The map is locked, which is what a cartography table does and
 * what stops the terrain scan from painting over the photograph the first time somebody holds it. And the saved data
 * is marked dirty, so the world writes it out and anybody already looking at the map is sent the new pixels.
 *
 * <p>The same array reads back out again, for drawing a map somebody has hung on a wall.
 */
public final class NmsSavedMapPixels implements SavedMapPixels {

    /** One map's edge, and the stride of {@code colors} - which is indexed x + y * 128, as a capture is. */
    private static final int MAP_SIZE = 128;

    @Override
    public byte[] read(int mapId) {
        MapItemSavedData data = dataOf(mapId);
        // A copy, since the caller reads it off the main thread and the world goes on painting this one.
        return data == null ? null : data.colors.clone();
    }

    @Override
    public boolean write(int mapId, byte[] pixels) {
        MapItemSavedData data = dataOf(mapId);
        if (data == null || data.colors.length != pixels.length) return false;

        System.arraycopy(pixels, 0, data.colors, 0, pixels.length);
        data.locked = true;

        // Both corners, since a holding player's dirty area is a rectangle grown a pixel at a time - marking one
        // would resend one pixel. The flag saves the file.
        data.setColorsDirty(0, 0, true);
        data.setColorsDirty(MAP_SIZE - 1, MAP_SIZE - 1, true);
        return true;
    }

    /** Kept by the overworld whichever world the map belongs to, which is where the server looks one up as well. */
    private static MapItemSavedData dataOf(int mapId) {
        ServerLevel overworld = ((CraftServer) Bukkit.getServer()).getServer().overworld();
        return overworld == null ? null : overworld.getMapData(new MapId(mapId));
    }
}
