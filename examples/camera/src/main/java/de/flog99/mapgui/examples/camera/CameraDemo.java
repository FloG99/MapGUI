package de.flog99.mapgui.examples.camera;

import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.Session;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * A screenshot of the world onto a map - blocks with their real textures, and through glass and water.
 *
 * <p>The part worth copying is in {@link CameraScreen}: it asks whether the textures are installed and says so
 * itself, rather than taking a capture and discovering they are not.
 */
public final class CameraDemo implements Listener {

    private static final String NAME = "camera";

    public void register(JavaPlugin plugin) {
        MapGui.get().guis().registerOpenable(NAME, "A screenshot of what you are looking at", player -> new CameraScreen());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Sneak is a second shutter, and unlike a click it costs the player nothing to reach. */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;

        Session session = MapGui.get().session(event.getPlayer());
        if (session != null && session.screen() instanceof CameraScreen camera) {
            camera.sneaked();
        }
    }

    public void unregister() {
        MapGui.get().guis().unregister(NAME);
    }
}
