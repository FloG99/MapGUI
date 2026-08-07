package de.flog99.mapgui.camera;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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
     * Draw captures with a resource pack of yours, out of your own jar.
     *
     * <p>For a plugin that adds items. A capture is a server-side render and knows nothing about what any client
     * has, so an item whose {@code item_model} points into your pack is drawn from its base material - a camera
     * built on a knowledge book photographs as a knowledge book - until MapGUI has the pack too.
     *
     * <p>Call it whenever; the first capture after it lands is drawn with the pack. The bytes are kept under
     * their own SHA-1, so calling this on every startup with an unchanged pack writes nothing and reloads
     * nothing, and shipping a new version of it replaces the old one on its own.
     *
     * <p>Layered under anything in {@code plugins/MapGUI/assets/}, which is the admin's and outranks yours.
     *
     * @param plugin   yours, whose jar the resource is read from
     * @param resource path inside that jar, such as {@code pack/my-pack.zip}
     */
    void useResourcePack(Plugin plugin, String resource);

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
