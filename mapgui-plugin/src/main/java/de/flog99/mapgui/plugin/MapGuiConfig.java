package de.flog99.mapgui.plugin;

import de.flog99.mapgui.ui.Animator;
import org.bukkit.configuration.file.FileConfiguration;

public record MapGuiConfig(
        String defaultPrompt,
        float minPitch,
        float maxPitch,
        boolean clampPitch,
        int terrainRefreshTicks,
        boolean animations,
        int fps,
        int loopFps,
        int wallFps,
        int wallRange,
        int wallVideoSize) {

    public static MapGuiConfig from(FileConfiguration config) {
        int fps = clampFps(config.getInt("animations.fps", Animator.MAX_FPS));

        return new MapGuiConfig(
                config.getString("prompts.default", "dialog"),
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
                Math.max(128, config.getInt("walls.video-size", 256))
        );
    }

    private static int clampFps(int value) {
        return Math.max(1, Math.min(Animator.MAX_FPS, value));
    }
}
