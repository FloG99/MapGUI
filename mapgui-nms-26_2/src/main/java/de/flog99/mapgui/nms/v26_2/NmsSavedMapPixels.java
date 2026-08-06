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
 * <p>Map data is kept by the overworld whichever world the map belongs to, which is where the server looks a map id
 * up as well.
 */
public final class NmsSavedMapPixels implements SavedMapPixels {

    /** One map's edge, and the stride of {@code colors} - which is indexed x + y * 128, as a capture is. */
    private static final int MAP_SIZE = 128;

    @Override
    public boolean write(int mapId, byte[] pixels) {
        ServerLevel overworld = ((CraftServer) Bukkit.getServer()).getServer().overworld();
        if (overworld == null) return false;

        MapItemSavedData data = overworld.getMapData(new MapId(mapId));
        if (data == null || data.colors.length != pixels.length) return false;

        System.arraycopy(pixels, 0, data.colors, 0, pixels.length);
        data.locked = true;

        // Both corners, since a holding player's dirty area is a rectangle grown a pixel at a time - marking one
        // would resend one pixel. The flag saves the file.
        data.setColorsDirty(0, 0, true);
        data.setColorsDirty(MAP_SIZE - 1, MAP_SIZE - 1, true);
        return true;
    }
}
