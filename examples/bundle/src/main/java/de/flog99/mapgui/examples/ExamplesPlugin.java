package de.flog99.mapgui.examples;

import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.examples.camera.CameraDemo;
import de.flog99.mapgui.examples.claims.ClaimDemo;
import de.flog99.mapgui.examples.gallery.GalleryDemo;
import de.flog99.mapgui.examples.minimap.MinimapDemo;
import de.flog99.mapgui.examples.todo.TodoDemo;
import de.flog99.mapgui.examples.walls.WallsDemo;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Every demo in one plugin, which is also the shape your own will have: one jar, one descriptor, and as many
 * GUIs registered from it as you like.
 *
 * <p>Each demo lives in its own package and registers itself, so lifting one into your plugin is a matter of
 * copying that package and calling it from here. The MapGUI dependency is declared once, in paper-plugin.yml,
 * and that block is the other half worth copying.
 */
public final class ExamplesPlugin extends JavaPlugin {

    private final GalleryDemo gallery = new GalleryDemo();
    private final TodoDemo todo = new TodoDemo();
    private final MinimapDemo minimap = new MinimapDemo();
    private final CameraDemo camera = new CameraDemo();
    private final ClaimDemo claims = new ClaimDemo();
    private final WallsDemo walls = new WallsDemo();

    @Override
    public void onEnable() {
        SampleVideo.install(this);

        gallery.register(this);
        todo.register();
        minimap.register();
        camera.register(this);
        claims.register();
        walls.register();
    }

    /**
     * Each demo takes its own entries back out, which closes anyone's open copy with them.
     *
     * <p>{@link MapGui#get()} is safe here: a plugin declaring MapGUI as a required dependency is always
     * disabled before it.
     */
    @Override
    public void onDisable() {
        gallery.unregister();
        todo.unregister();
        minimap.unregister();
        camera.unregister();
        claims.unregister();
        walls.unregister();
    }
}
