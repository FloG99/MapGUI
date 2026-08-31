package de.flog99.mapgui.plugin.wall;

import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.WallContent;
import de.flog99.mapgui.WallDisplay;
import de.flog99.mapgui.media.GifFrames;
import de.flog99.mapgui.media.LiveSource;
import de.flog99.mapgui.media.VideoPlayer;
import de.flog99.mapgui.plugin.video.FfmpegSource;
import de.flog99.mapgui.plugin.video.VideoNatives;
import de.flog99.mapgui.ui.Quantizer;
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
import java.util.function.Consumer;

/**
 * The media a server owner has put in {@code plugins/MapGUI/videos}, plus the streams they have named in
 * config.yml.
 *
 * <p>Two kinds, and the difference is where the pixels live. A GIF is decoded once into memory and shared by
 * every wall showing it, at one size whatever the wall's - decoding per wall would mean about a second of work
 * on every step of a resize, so the player scales it instead. Everything else is handed to FFmpeg and arrives
 * a frame at a time, because a film or a stream is not something to hold all of.
 */
final class VideoLibrary {

    private static final String FOLDER = "videos";
    private static final String GIF = ".gif";

    /** What FFmpeg is asked to open. Not a whitelist of what it can do, just of what is worth listing. */
    private static final Set<String> PLAYABLE = Set.of(".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v", ".ts", ".flv");

    private final Plugin plugin;
    private final int size;
    private final boolean prerender;
    private final Map<String, String> streams;

    private final Map<String, VideoPlayer> decoded = new HashMap<>();
    private final Map<String, LiveSource> playing = new HashMap<>();

    /**
     * Why a name did not play, so it is not tried again every tick and so the reason can be told.
     *
     * <p>Three different problems that all end in nothing appearing: the file is not there, it is there and
     * will not decode, or it needs FFmpeg and FFmpeg is not loaded. Only the last is fixed in config.yml, and
     * an admin cannot tell which they have from a wall that stays blank.
     */
    private final Map<String, String> unplayable = new HashMap<>();

    VideoLibrary(Plugin plugin, int size, boolean prerender, Map<String, String> streams) {
        this.plugin = plugin;
        this.size = size;
        this.prerender = prerender;
        this.streams = streams;
        folder().mkdirs();
    }

    List<String> names() {
        List<String> names = new ArrayList<>(streams.keySet());

        String[] found = folder().list();
        if (found == null) return names;

        for (String name : found) {
            if (kindOf(name) != null) {
                names.add(name);
            }
        }
        return names;
    }

    /** Whether anything here needs FFmpeg, which is what makes it worth mentioning that FFmpeg is off. */
    boolean needsFfmpeg() {
        if (!streams.isEmpty()) return true;

        for (String name : names()) {
            if (kindOf(name) == Kind.PLAYED) return true;
        }
        return false;
    }

    private enum Kind { DECODED, PLAYED }

    @Nullable
    private Kind kindOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(GIF)) return Kind.DECODED;

        for (String extension : PLAYABLE) {
            if (lower.endsWith(extension)) return Kind.PLAYED;
        }
        return null;
    }

    /**
     * How to fill a wall with {@code name}, or null if there is nothing by that name that can be shown.
     *
     * <p>Returns the whole instruction rather than just the content, because whether a thing can be sent once
     * and replayed is a property of the thing: a short GIF can, a two hour film and a live stream cannot.
     */
    @Nullable
    Consumer<WallDisplay.Builder> place(String name) {
        WallContent media = content(name);
        if (media == null) return null;

        VideoPlayer looping = prerender ? decoded.get(name) : null;
        if (looping == null || looping.frames().count() > WallDisplay.MAX_PRERENDER_STEPS) {
            return wall -> wall.content(media);
        }

        // Short enough to hold: sent once as a copy per frame, then played by pointing the maps at them.
        return wall -> wall.content(media)
                .prerender(looping.frames().count(), Math.max(1, looping.frames().durationMs()));
    }

    /** Why {@code name} will not play, or null if there is nothing wrong with it. */
    @Nullable
    String problemWith(String name) {
        return unplayable.get(name);
    }

    @Nullable
    private WallContent content(String name) {
        if (unplayable.containsKey(name)) return null;

        String stream = streams.get(name);
        if (stream != null) return live(name, stream, false);

        Kind kind = kindOf(name);
        if (kind == null) return null;

        File file = new File(folder(), name);
        if (!file.toPath().normalize().startsWith(folder().toPath().normalize())) {
            unplayable.put(name, "that name points outside the videos folder");
            return null;
        }
        if (!file.isFile()) {
            unplayable.put(name, "there is no such file in plugins/MapGUI/videos");
            return null;
        }

        return kind == Kind.DECODED ? gif(name, file) : live(name, file.getAbsolutePath(), true);
    }

    @Nullable
    private WallContent gif(String name, File file) {
        VideoPlayer cached = decoded.get(name);
        if (cached != null) return WallContent.video(cached);

        try (InputStream source = Files.newInputStream(file.toPath())) {
            VideoPlayer video = new VideoPlayer(GifFrames.read(source, Quantizer.of(MapColors.INSTANCE), size));
            decoded.put(name, video);
            return WallContent.video(video);
        } catch (IOException e) {
            unplayable.put(name, "it could not be decoded: " + e.getMessage());
            plugin.getSLF4JLogger().warn("Could not read {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * One decoder per name, shared by every wall showing it.
     *
     * <p>Shared rather than one each because a stream is a connection to somewhere else: three walls showing
     * the same camera should be one connection and one decode, not three.
     */
    @Nullable
    private WallContent live(String name, String source, boolean loop) {
        LiveSource open = playing.get(name);
        if (open != null && open.running()) return WallContent.live(open);

        if (!VideoNatives.available()) {
            unplayable.put(name, "it needs FFmpeg - set video.ffmpeg: true in config.yml and restart");
            plugin.getSLF4JLogger().warn("{} needs FFmpeg, which is not loaded. Set video.ffmpeg: true in config.yml and restart - MapGUI will download it once, for this platform only.", name);
            return null;
        }

        // Square, because the wall it lands on is not known yet and the player letterboxes whatever it gets.
        LiveSource started = new FfmpegSource(source, size, size, loop);
        playing.put(name, started);
        return WallContent.live(started);
    }

    /** Forgets why something would not play, so dropping a file in or fixing config and asking again works. */
    void forget(String name) {
        unplayable.remove(name);
    }

    /**
     * Drops the frames of every video not in {@code wanted}, and answers how many went.
     *
     * <p>A decoded GIF is the largest thing this plugin holds - a 20 second clip is roughly 13 MB - and
     * without this it stayed held for the life of the server, so an admin trying six videos and keeping one
     * paid for all six until a restart. A stream costs a thread and a connection instead, and is closed here
     * for the same reason.
     *
     * <p>Safe while a wall is still showing a GIF: that wall holds its own reference to the player, so dropping
     * the cache entry only means the file is read again the next time somebody places it. A stream is not -
     * closing it stops the wall showing it, which is why {@code wanted} is what is up rather than what is
     * cached.
     */
    int retainOnly(Set<String> wanted) {
        int before = decoded.size() + playing.size();
        decoded.keySet().retainAll(wanted);

        playing.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey())) return false;

            entry.getValue().close();
            return true;
        });
        return before - decoded.size() - playing.size();
    }

    /** Stops every decoder. For shutdown, where a daemon thread left running would still hold a socket. */
    void close() {
        for (LiveSource source : playing.values()) source.close();
        playing.clear();
    }

    private File folder() {
        return new File(plugin.getDataFolder(), FOLDER);
    }
}
