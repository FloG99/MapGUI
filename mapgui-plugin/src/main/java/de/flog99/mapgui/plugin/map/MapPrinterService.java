package de.flog99.mapgui.plugin.map;

import de.flog99.mapgui.camera.Camera;
import de.flog99.mapgui.camera.CameraShot;
import de.flog99.mapgui.map.MapPrinter;
import de.flog99.mapgui.map.SavedMapPixels;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Printing as the plugin does it: make the map, then hand the picture to vanilla to keep.
 *
 * <p>Written into the map's own saved pixels through {@link SavedMapPixels}, which is what makes it an ordinary
 * vanilla map from then on - no renderer of ours, nothing of ours to store, and nothing lost if MapGUI is removed.
 *
 * <p>{@link Still} is the fallback for a server that will not let us at those pixels, and it is a worse deal: the
 * picture shows, but MapGUI is what draws it, so it lasts exactly as long as MapGUI stays installed and enabled.
 */
public final class MapPrinterService implements MapPrinter {

    private static final int PIXELS = Camera.MAP_SIZE * Camera.MAP_SIZE;

    private final Plugin plugin;
    private final SavedMapPixels saved;

    /** One line about the fallback per server run, rather than one per map of every capture. */
    private boolean warned;

    public MapPrinterService(Plugin plugin, SavedMapPixels saved) {
        this.plugin = plugin;
        this.saved = saved;
    }

    @Override
    public ItemStack print(World world, byte[] pixels) {
        if (pixels.length != PIXELS) {
            throw new IllegalArgumentException("A map is " + Camera.MAP_SIZE + " pixels square, so " + PIXELS + " of them, not " + pixels.length);
        }

        MapView view = Bukkit.createMap(world);
        // Locked is how a photograph stays one: the terrain scan skips a locked map, so nothing is ever painted
        // over it. No player arrow either, and no scale to speak of now that nothing maps the world.
        view.setLocked(true);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setScale(MapView.Scale.CLOSEST);

        if (!store(view.getId(), pixels)) {
            // A fresh map arrives drawing the terrain it was made in, which would sit underneath the picture.
            view.getRenderers().forEach(view::removeRenderer);
            view.addRenderer(new Still(pixels));
        }

        ItemStack item = new ItemStack(Material.FILLED_MAP);
        item.editMeta(MapMeta.class, meta -> meta.setMapView(view));
        return item;
    }

    @Override
    public List<ItemStack> print(World world, CameraShot shot) {
        return MapPrinter.cut(shot).stream()
                .map(tile -> print(world, tile))
                .toList();
    }

    /**
     * @return whether the picture is vanilla's now, rather than something MapGUI has to keep drawing
     */
    private boolean store(int mapId, byte[] pixels) {
        try {
            if (saved.write(mapId, pixels)) {
                return true;
            }
        } catch (RuntimeException | LinkageError e) {
            // A fork or a future version that has moved the saved data. Never thrown at the caller: printing sits
            // behind a camera, and a camera that throws buries the one line that mattered.
            fallback("MapGUI could not reach the saved pixels of map " + mapId + ": " + e);
            return false;
        }

        fallback("MapGUI could not reach the saved pixels of map " + mapId + " on this server");
        return false;
    }

    private void fallback(String detail) {
        if (warned) return;

        warned = true;
        plugin.getSLF4JLogger().warn("{}. Printed maps will be drawn by MapGUI instead, which means they will go blank if MapGUI is removed or disabled. Everything else is unaffected.", detail);
    }

    /**
     * One fixed image on a map, drawn by us because the server would not take it.
     *
     * <p>Non-contextual, so every player sees the same canvas rather than one being rendered per viewer, and drawn
     * once: {@code render} is called for as long as anybody is looking at the map, and the pixels never change.
     */
    private static final class Still extends MapRenderer {

        private final byte[] pixels;
        private boolean drawn;

        private Still(byte[] pixels) {
            super(false);
            this.pixels = pixels;
        }

        @Override
        @SuppressWarnings("deprecation")
        public void render(MapView view, MapCanvas canvas, Player player) {
            if (drawn) return;

            for (int y = 0; y < Camera.MAP_SIZE; y++) {
                for (int x = 0; x < Camera.MAP_SIZE; x++) {
                    // The byte overload rather than setPixelColor: what a capture holds is already palette indices,
                    // and going out through a Color and back in would re-match every one of them to a worse fit.
                    canvas.setPixel(x, y, pixels[y * Camera.MAP_SIZE + x]);
                }
            }
            drawn = true;
        }
    }
}
