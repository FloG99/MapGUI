package de.flog99.mapgui.plugin;

import de.flog99.mapgui.HandOptions;
import de.flog99.mapgui.render.CameraView;
import de.flog99.mapgui.ui.Animator;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record MapGuiConfig(
        String defaultPrompt,
        HandOptions hand,
        float minPitch,
        float maxPitch,
        boolean clampPitch,
        int terrainRefreshTicks,
        boolean animations,
        int fps,
        int loopFps,
        int wallFps,
        int wallRange,
        int wallVideoSize,
        boolean wallPrerender,
        boolean videoFfmpeg,
        Map<String, String> streams,
        boolean cameraDownload,
        List<String> cameraPacks,
        boolean cameraAllowVersionMismatch,
        float cameraFov,
        int cameraDistance,
        int cameraReuseChunksMillis) {

    public static MapGuiConfig from(FileConfiguration config) {
        int fps = clampFps(config.getInt("animations.fps", Animator.MAX_FPS));

        return new MapGuiConfig(
                config.getString("prompts.default", "dialog"),
                hand(config),
                (float) config.getDouble("cursor.min-pitch", 45.0),
                (float) config.getDouble("cursor.max-pitch", 90.0),
                config.getBoolean("cursor.clamp-pitch", true),
                Math.max(1, config.getInt("terrain.min-ticks-between-refresh", 4)),
                config.getBoolean("animations.enabled", true),
                fps,
                // A loop faster than the overall limit could never be reached anyway.
                Math.min(fps, clampFps(config.getInt("animations.loop-fps", Animator.DEFAULT_LOOP_FPS))),
                clampFps(config.getInt("walls.fps", 10)),
                Math.max(1, config.getInt("walls.view-distance", 48)),
                // One map's worth is the floor - below that a wall could only ever be upscaled.
                Math.max(128, config.getInt("walls.video-size", 256)),
                config.getBoolean("walls.prerender", true),
                config.getBoolean("video.ffmpeg", false),
                streams(config),
                config.getBoolean("camera.assets.download", true),
                List.copyOf(config.getStringList("camera.assets.packs")),
                config.getBoolean("camera.assets.allow-version-mismatch", false),
                (float) config.getDouble("camera.fov", CameraView.DEFAULT_FOV),
                Math.max(1, config.getInt("camera.max-distance", 96)),
                // Zero by default: reusing a copied chunk is the only fast path the camera has that is not exact.
                Math.max(0, config.getInt("camera.reuse-chunks-for-ms", 0))
        );
    }

    /**
     * Named live streams, so placing one is the same gesture as placing a file.
     *
     * <p>Configured rather than typed at the command, deliberately: a url an operator can hand to the server
     * is a url the server will connect to, and that is a decision for the person with access to config.yml.
     */
    private static Map<String, String> streams(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("video.streams");
        if (section == null) return Map.of();

        Map<String, String> streams = new LinkedHashMap<>();
        for (String name : section.getKeys(false)) {
            String url = section.getString(name);
            if (url != null && !url.isBlank()) {
                streams.put(name, url);
            }
        }
        return Map.copyOf(streams);
    }

    private static int clampFps(int value) {
        return Math.max(1, Math.min(Animator.MAX_FPS, value));
    }

    /**
     * How a screen is carried unless the screen or the caller says otherwise.
     *
     * <p>A typo falls back to the popup rather than failing to start, and says so in the log, because a server that
     * will not boot over one misspelled word in an optional section is worse than one that boots the old way.
     */
    private static HandOptions hand(FileConfiguration config) {
        HandOptions.Carry carry = named(HandOptions.Carry.class, config.getString("hand.carry"), HandOptions.Carry.POPUP);
        HandOptions.Focus focus = named(HandOptions.Focus.class, config.getString("hand.focus"), defaultFocus(carry));

        return new HandOptions(
                carry,
                focus,
                config.getInt("hand.slot", 8),
                config.getBoolean("hand.movable", false),
                config.getBoolean("hand.offhand", false)
        ).sane();
    }

    /** An offhand map is unreachable without a gesture, so it gets one by default and the others do not. */
    private static HandOptions.Focus defaultFocus(HandOptions.Carry carry) {
        return carry == HandOptions.Carry.OFFHAND ? HandOptions.Focus.SWAP_HANDS : HandOptions.Focus.MAIN_HAND;
    }

    /** Written with hyphens in yaml and underscores in Java, so {@code swap-hands} and {@code SWAP_HANDS} both read. */
    private static <E extends Enum<E>> E named(Class<E> type, String written, E fallback) {
        if (written == null || written.isBlank()) return fallback;

        try {
            return Enum.valueOf(type, written.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Bukkit.getLogger().warning("MapGUI: \"" + written + "\" is not a " + type.getSimpleName()
                    + ", using " + fallback.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            return fallback;
        }
    }
}
