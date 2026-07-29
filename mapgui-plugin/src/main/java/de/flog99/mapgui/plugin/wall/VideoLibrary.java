package de.flog99.mapgui.plugin.wall;

import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.media.GifFrames;
import de.flog99.mapgui.media.VideoPlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The GIFs a server owner has dropped in {@code plugins/MapGUI/videos}.
 *
 * <p>Decoded once per file and shared by every wall showing it, at one size whatever the wall's. Decoding
 * per wall would mean about a second of work on every step of a resize, so the player scales it instead -
 * and upscaling loses nothing that was in the file.
 */
final class VideoLibrary {

    private static final String FOLDER = "videos";
    private static final String EXTENSION = ".gif";

    private final Plugin plugin;
    private final int size;

    private final Map<String, VideoPlayer> decoded = new HashMap<>();

    /** A wall retries its file every tick until it succeeds, so a broken one has to be remembered. */
    private final Set<String> unreadable = new HashSet<>();

    VideoLibrary(Plugin plugin, int size) {
        this.plugin = plugin;
        this.size = size;
        folder().mkdirs();
    }

    List<String> names() {
        String[] found = folder().list();
        if (found == null) return List.of();

        List<String> gifs = new ArrayList<>();
        for (String name : found) {
            if (name.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
                gifs.add(name);
            }
        }
        return gifs;
    }

    @Nullable
    VideoPlayer find(String name) {
        VideoPlayer cached = decoded.get(name);
        if (cached != null) return cached;
        if (unreadable.contains(name)) return null;

        File file = new File(folder(), name);
        if (!file.isFile()) {
            unreadable.add(name);
            return null;
        }

        try (InputStream source = Files.newInputStream(file.toPath())) {
            VideoPlayer video = new VideoPlayer(GifFrames.read(source, MapColors.INSTANCE, size));
            decoded.put(name, video);
            return video;
        } catch (IOException e) {
            unreadable.add(name);
            plugin.getSLF4JLogger().warn("Could not read {}: {}", name, e.getMessage());
            return null;
        }
    }

    /** Forgets that a file was unreadable, so dropping it in or fixing it and asking again works. */
    void forget(String name) {
        unreadable.remove(name);
    }

    /**
     * Drops the frames of every video not in {@code wanted}, and answers how many went.
     *
     * <p>A decoded GIF is the largest thing this plugin holds - a 20 second clip is roughly 13 MB - and
     * without this it stayed held for the life of the server, so an admin trying six videos and keeping one
     * paid for all six until a restart.
     *
     * <p>Safe while a wall is still showing one: that wall holds its own reference to the player, so dropping
     * the cache entry only means the file is read again the next time somebody places it.
     */
    int retainOnly(Set<String> wanted) {
        int before = decoded.size();
        decoded.keySet().retainAll(wanted);
        return before - decoded.size();
    }

    private File folder() {
        return new File(plugin.getDataFolder(), FOLDER);
    }
}
