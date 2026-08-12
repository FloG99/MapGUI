package de.flog99.mapgui.camera;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;
import java.util.function.Supplier;

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
     * @return the pack's SHA-1 in hex, or null if the resource could not be read. Handed back because you also have
     *         to <b>serve</b> this pack to clients, and a client is offered one by its hash - so using this one is
     *         what stops the copy players download and the copy captures are drawn with drifting apart
     */
    String useResourcePack(Plugin plugin, String resource);

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
     * @throws IllegalArgumentException if the size is outside {@link CameraOptions#MIN_SIZE} to
     *         {@link CameraOptions#MAX_SIZE}
     */
    void capture(Player player, int size, Consumer<CameraShot> onShot);

    /** The same, with the whole view spelled out. */
    void capture(Player player, CameraOptions options, Consumer<CameraShot> onShot);

    /**
     * A live view of this player, driven for you: the way to put moving pictures on a screen.
     *
     * <p>Every tick a frame could be taken it asks {@code options} what to capture, and hands the result to
     * {@code onFrame} on the main thread. What it saves you is the three things that are invisible when got wrong:
     * only one capture in flight, so a slow tick leaves the next frame late rather than two copies of the world in
     * memory; frames only as fast as {@link #readyForFrame} allows, so several viewers divide the server's time
     * rather than multiplying it; and none at all while the screen is put away, since those are frames nobody sees.
     *
     * <pre>{@code
     * protected void onOpen() {
     *     feed = MapGui.get().camera().feed(player(), this::framing, this::preview);
     * }
     *
     * protected void onClose() {
     *     feed.close();
     * }
     * }</pre>
     *
     * <p>Return <b>null</b> from {@code options} for a tick where no frame is wanted - mid-animation, mid-shutter,
     * still loading. That pauses the feed without closing it, and a paused one stops counting as a viewer, so it
     * gives back its share of the budget while it waits.
     *
     * <p>{@code onFrame} only ever sees real frames: a failed capture is dropped rather than passed on as a null.
     * Anything it throws closes the feed and is logged, rather than breaking the tick.
     *
     * @param options what to capture. Asked whenever a frame could be taken, so not while paused or while one is
     *                already in flight. Null means not now
     * @param onFrame given each finished frame, on the main thread
     */
    CameraFeed feed(Player player, Supplier<CameraOptions> options, Consumer<CameraShot> onFrame);

    /**
     * Whether a <b>live view</b> of this player should take a frame now.
     *
     * <p>{@link #feed} is this with the loop written for you, and is what a viewfinder should reach for first. This
     * stays for a plugin that drives its own timing - a view that only wants a frame when something in the world
     * changed, or one paced by something other than a tick.
     *
     * <p>For a viewfinder rather than a photograph. A still is one capture and whoever pressed the shutter is waiting
     * for it, so take it; a live view wants every frame it can get, forever, and how many that is depends on how many
     * other people are pointing one at the same server. Only MapGUI can see all of them, so it does the dividing:
     * ask every tick you would like a frame, and take one when this says yes.
     *
     * <pre>
     * if (MapGui.get().camera().readyForFrame(player)) {
     *     MapGui.get().camera().capture(player, options, shot -&gt; ...);
     * }
     * </pre>
     *
     * <p>The rate comes from two settings an admin owns - a budget in main-thread milliseconds per tick, and a
     * ceiling in frames a second - and from what your captures are measured to actually cost. Views get as many
     * frames as the budget affords and stop at the ceiling, so a lone viewer does not get twenty times the frames
     * for being alone, and a fifth one joining slows everybody a fifth rather than costing the server a fifth more.
     *
     * <p><b>Advisory.</b> Nothing stops a plugin capturing without asking - it is the admin's tick either way, and
     * `/mapgui camera performance` will name whoever is spending it. Asking is also what makes you a viewer: a screen
     * that asks once a second is one that wanted one frame a second and is divided by as one, and a screen that
     * stops asking stops being divided by at all, so there is nothing to open and nothing to close.
     *
     * <p>Keyed on the player being looked out of, not on the caller, so two plugins running a view for the same
     * person share one person's worth of frames rather than taking two.
     */
    boolean readyForFrame(Player player);

    /**
     * How many frames a second this player's live view is currently being allowed, or 0 if they have none open.
     *
     * <p>The companion to {@link #readyForFrame}, for a plugin that wants to say so on its own screen - or to work
     * out why a viewfinder that was smooth a minute ago is not. It moves as other people open and close theirs.
     */
    double frameRate(Player player);

    /**
     * What every capture on this server has cost over the last few seconds, whoever asked for it.
     *
     * <p>The same reading {@code /mapgui camera performance} prints, from the same method - so a debugging command
     * of your own can show whatever part of it you care about without MapGUI having to guess in advance which part
     * that is. Cheap enough to call from a command; it is a snapshot of counters that were being kept anyway.
     */
    CameraStats stats();

    /**
     * Loads the textures now rather than on the first capture.
     *
     * @return false if a download is already running, or if {@code camera.assets.download} is off and there is
     *         nothing on disk to load
     */
    boolean prepare();
}
