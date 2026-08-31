package de.flog99.mapgui.plugin;

import de.flog99.mapgui.ui.Dither;
import de.flog99.mapgui.HandOptions;
import de.flog99.mapgui.plugin.camera.CameraTuning;
import de.flog99.mapgui.plugin.camera.ReuseWindow;
import de.flog99.mapgui.plugin.camera.TrackingRanges;
import de.flog99.mapgui.render.CameraView;
import de.flog99.mapgui.render.Canopy;
import de.flog99.mapgui.render.RayCaster;
import de.flog99.mapgui.ui.Animator;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Arrays;
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
        boolean mediaFfmpeg,
        boolean resolvePageUrls,
        int downloadMaxFileMb,
        int downloadMaxTotalMb,
        int downloadMaxFrames,
        Dither mediaDither,
        Map<String, String> streams,
        boolean cameraDownload,
        List<String> cameraPacks,
        boolean cameraAllowVersionMismatch,
        boolean cameraFollowServerPacks,
        CameraTuning cameraTuning,
        boolean commandsEnabled,
        boolean commandsHideUnused) {

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
                config.getBoolean(preferred(config, "media.ffmpeg", "video.ffmpeg"), false),
                config.getBoolean("media.resolve-page-urls", false),
                Math.max(1, config.getInt("media.download.max-file-mb", 256)),
                Math.max(1, config.getInt("media.download.max-total-mb", 2048)),
                Math.max(1, config.getInt("media.download.max-frames", 1200)),
                dither(config),
                streams(config),
                config.getBoolean("camera.assets.download", true),
                List.copyOf(config.getStringList("camera.assets.packs")),
                config.getBoolean("camera.assets.allow-version-mismatch", false),
                config.getBoolean("camera.assets.follow-server-packs", true),
                camera(config),
                config.getBoolean("commands.enabled", true),
                config.getBoolean("commands.hide-unused", true)
        );
    }

    /** The numbers under {@code camera:}, together, since they are read together and passed on together. */
    private static CameraTuning camera(FileConfiguration config) {
        return new CameraTuning(
                (float) config.getDouble("camera.fov", CameraView.DEFAULT_FOV),
                Math.max(1, config.getInt("camera.max-distance", 96)),
                Math.max(1, config.getDouble("camera.max-entity-distance", TrackingRanges.DEFAULT_MAX)),
                // Both floor at zero, which each read as "no limit of this kind" rather than as "no frames".
                Math.max(0, config.getDouble("camera.live.max-ms-per-tick", 3.0)),
                Math.max(0, config.getInt("camera.live.max-fps", 10)),
                canopy(config),
                // Clamped rather than refused: it is a look, and the far end of it is only ever a flat grey picture.
                (float) Math.clamp(config.getDouble("camera.shadow-lift", RayCaster.SHADOW_LIFT), 0, 0.5),
                reuse(config),
                limits(config)
        );
    }

    /**
     * How far out leaves fill in. Both keys floor at zero and {@link Canopy} keeps the far one behind the near one,
     * so a pair written the wrong way round is a hard switch at one distance rather than a division by zero.
     */
    private static Canopy canopy(FileConfiguration config) {
        return new Canopy(
                config.getDouble("camera.leaves.near-blocks", Canopy.DEFAULT.near()),
                config.getDouble("camera.leaves.far-blocks", Canopy.DEFAULT.far())
        );
    }

    /**
     * How long each of the three caches may serve what it holds.
     *
     * <p>Every one of these is left out of config.yml by anybody who has not needed it, so each falls back to the
     * default the cache would have used anyway - which is why the defaults are read off {@link CameraTuning.Reuse}
     * rather than written here as a second set of numbers to keep in step.
     */
    private static CameraTuning.Reuse reuse(FileConfiguration config) {
        return new CameraTuning.Reuse(
                // Zero by default: reusing a copied chunk for a photograph is the only fast path the camera has
                // that is not exact, and a photograph is kept. The key it was under before is still read, so a
                // server that turned it on keeps what it asked for.
                Math.max(0, config.getInt("camera.reuse.chunks.stills-for-ms",
                        Math.max(0, config.getInt("camera.reuse-chunks-for-ms", 0)))),
                window(config, "camera.reuse.chunks", "chunks", CameraTuning.Reuse.CHUNKS),
                window(config, "camera.reuse.tile-entities", "blocks", CameraTuning.Reuse.BLOCK_ENTITIES),
                window(config, "camera.reuse.entities", "blocks", CameraTuning.Reuse.MOBS)
        );
    }

    /** @param unit what this window measures distance in, which is the word its two distance keys are named after */
    private static ReuseWindow window(FileConfiguration config, String path, String unit, ReuseWindow fallback) {
        return ReuseWindow.ofMillis(
                config.getInt(path + ".near-ms", fallback.nearMillis()),
                config.getInt(path + ".far-ms", fallback.farMillis()),
                config.getDouble(path + ".near-" + unit, fallback.near()),
                config.getDouble(path + ".far-" + unit, fallback.far())
        );
    }

    private static CameraTuning.Limits limits(FileConfiguration config) {
        CameraTuning.Limits defaults = CameraTuning.Limits.defaults();
        return new CameraTuning.Limits(
                Math.max(0, config.getInt("camera.limits.max-entities", defaults.mobs())),
                Math.max(0, config.getInt("camera.limits.max-tile-entities", defaults.blockEntities())),
                Math.max(0, config.getDouble("camera.limits.tile-entity-distance", defaults.blockEntityDistance()))
        );
    }

    /**
     * Named live streams, so placing one is the same gesture as placing a file.
     *
     * <p>A shortcut for {@code /mapgui wall place <name>} and nothing more. It is <b>not</b> an allowlist: a
     * plugin holding {@link de.flog99.mapgui.media.MediaService} may play any url it likes, because a plugin on
     * this server can already open a socket without asking us. What a name buys is not having to type a url into
     * a command, and one connection and one decode however many walls show it.
     */
    private static Map<String, String> streams(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection(preferred(config, "media.streams", "video.streams"));
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

    /**
     * The new name of a setting, or the old one while a file still only has that.
     *
     * <p>{@link ConfigMigration} rewrites the file, so this is the second line of defence rather than the
     * mechanism: a config.yml on a read-only mount cannot be migrated, and an admin's setting quietly ceasing
     * to take effect is exactly what the rename must not cause.
     *
     * <p>{@link org.bukkit.configuration.ConfigurationSection#isSet} rather than {@code contains}, deliberately:
     * the jar's own config.yml is registered as the defaults, so the new key is always <i>present</i> and only
     * {@code isSet} distinguishes what the admin's file actually holds.
     */
    private static String preferred(FileConfiguration config, String now, String before) {
        return config.isSet(now) || !config.isSet(before) ? now : before;
    }

    /**
     * The dither mode anything decoded gets unless whoever asked for it named one.
     *
     * <p>An unreadable name falls back to no dithering and says which names there are, rather than failing the
     * load: a typo in one setting should not stop a server, and undithered is what it did before anyway.
     */
    private static Dither dither(FileConfiguration config) {
        String name = config.getString("media.dither", "NONE");
        try {
            return Dither.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            Bukkit.getLogger().warning("media.dither is set to \"" + name + "\", which is not a mode. Using NONE."
                    + " The modes are " + Arrays.toString(Dither.values()) + ".");
            return Dither.NONE;
        }
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
                config.getBoolean("hand.offhand", false),
                // Not a setting: a pinned id is a plugin's arrangement with its own resource pack, and a server
                // owner setting one for every screen at once would have them all drawing to the same map.
                HandOptions.ANY_MAP_ID
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
