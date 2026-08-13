package de.flog99.mapgui.examples;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The sample GIF, put where {@code /mapgui wall place} looks for media.
 *
 * <p>It travels inside this jar, but that command lists real files, so one has to be written out. A GIF needs no
 * FFmpeg, so it plays on a server that has changed no settings. Written when missing, so deleting this jar is still
 * the whole off switch - reaching into MapGUI's own folder is worth it only because both are demos.
 */
final class SampleVideo {

    private static final String NAME = "polish-cow-transparent.gif";

    static void install(JavaPlugin plugin) {
        // A sibling of our own folder, so it follows a renamed plugins directory.
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
