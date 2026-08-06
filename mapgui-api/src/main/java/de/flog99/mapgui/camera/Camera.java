package de.flog99.mapgui.camera;

import org.bukkit.entity.Player;

import java.util.function.Consumer;

/**
 * Captures what a player is looking at, onto a map.
 *
 * <p>Blocks with their real textures, transparency through glass, ice, water and leaves, and the entities and
 * players in view, rotated as they stand. Reached through {@code MapGui.get().camera()}.
 *
 * <p>Needs Minecraft's textures, which are not ours to ship - see {@code docs/camera.md}. Check
 * {@link #assets()} before offering a capture rather than after: a screen that greys out its own button reads
 * better than any error frame can. Player skins work either way, because they come from Mojang's profile
 * service rather than from any file on the server.
 */
public interface Camera {

    /** What the map is: 128 pixels square, and the only size that needs no scaling to fill one. */
    int MAP_SIZE = 128;

    /**
     * Whether a capture will draw, and if not, what to tell whoever can fix it.
     *
     * <p>Cheap enough to call every frame.
     */
    CameraAssets assets();

    /**
     * Captures the player's view and hands it back on the main thread.
     *
     * <p>The world is copied inside the calling tick and the trace runs off-thread, so this does not stall the
     * server - but it does mean the capture is of the instant it was asked for rather than of whenever the
     * callback runs.
     *
     * <p>{@code onShot} receives null when {@link #assets()} is not ready. Nothing is thrown: a camera on a
     * render path that throws produces a hundred thousand stack traces and buries the one line that mattered.
     *
     * @param size pixels square, {@link #MAP_SIZE} to fill a map exactly. Smaller renders faster and the
     *             palette hides much of the difference
     */
    void capture(Player player, int size, Consumer<CameraShot> onShot);

    /** The same, with the whole view spelled out. */
    void capture(Player player, CameraOptions options, Consumer<CameraShot> onShot);

    /**
     * Loads the textures now rather than on the first capture.
     *
     * @return false if a download is already running, or if {@code camera.assets.download} is off and there is
     *         nothing on disk to load
     */
    boolean prepare();
}
