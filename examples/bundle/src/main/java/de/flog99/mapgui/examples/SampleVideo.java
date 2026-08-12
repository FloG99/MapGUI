package de.flog99.mapgui.examples;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The sample GIF, put where {@code /mapgui wall place} looks for media.
 *
 * <p>It travels inside this jar, so there is nothing to download and nothing to unpack. Writing it out is still
 * necessary: that command lists real files in {@code plugins/MapGUI/videos}, and a demo of it has to leave one
 * there. GIFs need no FFmpeg, so it plays on a server that has changed no settings at all.
 *
 * <p>Reaching into another plugin's folder is deliberate and worth doing only because both are demos - written
 * when missing, so deleting this jar is still the whole off switch.
 */
final class SampleVideo {

    private static final String NAME = "polish-cow-transparent.gif";

    static void install(JavaPlugin plugin) {
        // Sibling of our own folder rather than a path from the server root, so it follows a renamed plugins
        // directory.
        Path videos = plugin.getDataFolder().toPath().resolveSibling("MapGUI").resolve("videos");
        Path target = videos.resolve(NAME);
        if (Files.exists(target)) return;

        try (InputStream source = plugin.getResource(NAME)) {
            if (source == null) return;

            Files.createDirectories(videos);
            Files.copy(source, target);
            plugin.getSLF4JLogger().info("Put {} in plugins/MapGUI/videos - try /mapgui wall place", NAME);
        } catch (IOException e) {
            plugin.getSLF4JLogger().warn("Could not install the sample video", e);
        }
    }

    private SampleVideo() {
    }
}
