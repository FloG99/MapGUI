package de.flog99.mapgui.examples.gallery;

import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.media.GifFrames;
import de.flog99.mapgui.media.VideoPlayer;
import de.flog99.mapgui.ui.Dither;
import de.flog99.mapgui.ui.Quantizer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;

/**
 * Every widget, and the layout rules side by side.
 *
 * <p>The whole integration is one {@code register} call, which makes it {@code /mapgui hand open gallery}
 * without owning a command. The to-do demo shows the other route, where a plugin opens a GUI for its own users
 * itself.
 */
public final class GalleryDemo {

    private static final String NAME = "gallery";

    /** A second entry rather than a page of the first, because a screen picks one font for all of it. */
    private static final String TYPE = "type";

    /** Decoded at startup, since it takes about a second and every screen shares the one copy. */
    private VideoPlayer video;

    public void register(JavaPlugin plugin) {
        video = loadVideo(plugin);

        MapGui.get().guis().registerOpenable(NAME, "Every widget and layout rule", player -> new GalleryScreen(video));
        MapGui.get().guis().registerOpenable(TYPE, "A TrueType face and styled components", player -> new TypeScreen());
    }

    /**
     * Taken back out, which also closes anyone's open copy.
     *
     * <p>{@link MapGui#get()} is safe here: a plugin declaring MapGUI as a required dependency is always
     * disabled before it.
     */
    public void unregister() {
        MapGui.get().guis().unregister(NAME);
        MapGui.get().guis().unregister(TYPE);
    }

    /**
     * Straight out of the jar, so the demo needs no file installing and nothing downloading.
     *
     * <p>Dithered at decode rather than while painting, which is the only place an animation can be: by paint
     * time its pixels are palette indices. Floyd-Steinberg because the clip is photographic - see {@link Dither}.
     */
    private VideoPlayer loadVideo(JavaPlugin plugin) {
        try (InputStream source = plugin.getResource("bunny_sample_squared.gif")) {
            if (source == null) return null;
            return new VideoPlayer(GifFrames.read(source, Quantizer.of(MapColors.INSTANCE, Dither.FLOYD_STEINBERG)));
        } catch (IOException e) {
            plugin.getSLF4JLogger().warn("Could not read the sample video", e);
            return null;
        }
    }
}
