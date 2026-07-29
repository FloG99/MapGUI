package de.flog99.mapgui.examples.gallery;

import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.media.GifFrames;
import de.flog99.mapgui.media.VideoPlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;

/**
 * Every widget, and the layout rules side by side.
 *
 * <p>The whole integration is one {@code register} call, which makes it {@code /mapgui hand open gallery}
 * without this plugin owning a command. {@code MapGUI-Todo} shows the other route, where a plugin opens a GUI
 * for its own users itself.
 */
public final class GalleryPlugin extends JavaPlugin {

    private static final String NAME = "gallery";

    /** Decoded at startup, since it takes about a second and every screen shares the one copy. */
    private VideoPlayer video;

    @Override
    public void onEnable() {
        video = loadVideo();

        MapGui.get().guis().registerOpenable(NAME, "Every widget and layout rule", player -> new GalleryScreen(video));
    }

    /**
     * Taken back out, which also closes anyone's open copy.
     *
     * <p>{@link MapGui#get()} is safe here: a plugin declaring MapGUI as a required dependency is always
     * disabled before it.
     */
    @Override
    public void onDisable() {
        MapGui.get().guis().unregister(NAME);
    }

    private VideoPlayer loadVideo() {
        try (InputStream source = getResource("bunny_sample_squared.gif")) {
            if (source == null) return null;
            return new VideoPlayer(GifFrames.read(source, MapColors.INSTANCE));
        } catch (IOException e) {
            getSLF4JLogger().warn("Could not read the sample video", e);
            return null;
        }
    }
}
