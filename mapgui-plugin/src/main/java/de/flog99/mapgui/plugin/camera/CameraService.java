package de.flog99.mapgui.plugin.camera;

import com.destroystokyo.paper.ClientOption;
import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.camera.Camera;
import de.flog99.mapgui.camera.CameraAssets;
import de.flog99.mapgui.camera.CameraOptions;
import de.flog99.mapgui.camera.CameraShot;
import de.flog99.mapgui.ServerBackend;
import de.flog99.mapgui.camera.EntityAngles;
import de.flog99.mapgui.camera.LiveWalls;
import de.flog99.mapgui.render.BiomeColors;
import de.flog99.mapgui.render.BlockItems;
import de.flog99.mapgui.render.BlockModels;
import de.flog99.mapgui.render.CameraView;
import de.flog99.mapgui.render.EntitySnapshot;
import de.flog99.mapgui.render.EntityVariants;
import de.flog99.mapgui.render.FrameTracer;
import de.flog99.mapgui.render.EquipmentAssets;
import de.flog99.mapgui.render.ItemDefinitions;
import de.flog99.mapgui.render.ItemModels;
import de.flog99.mapgui.render.ItemPoses;
import de.flog99.mapgui.render.TextureAtlas;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The camera as a plugin sees it: capture in a tick, trace off-thread, hand back pixels.
 *
 * <p>The split is the whole design: copying the world has to happen on the main thread and has to be quick, while
 * tracing 16384 rays does not and is not. One tick takes {@code ChunkSnapshot}s and reads the player's eye, an async
 * task does the arithmetic, and the result comes back on the main thread where a screen can use it.
 *
 * <p>Nothing here throws at a caller: a camera sits on a render path, and a render path that throws turns one broken
 * texture into a log nobody can read.
 */
public final class CameraService implements Camera {

    /** How far in front of the eyes a selfie is taken from, in blocks. Far enough that a face fits in frame. */
    private static final double SELFIE_REACH = 1.6;

    private final Plugin plugin;
    private final CameraAssetStore assets;
    private final ServerPacks packs;
    private final SkinCache skins = new SkinCache();

    /** The pixels of a map somebody has hung on a wall, which are the world's rather than the assets'. */
    private final FramedMaps framedMaps;

    /** The angles a capture reads straight off the server, which is the squid and nothing else. */
    private final EntityAngles angles;

    /** The walls MapGUI itself is showing, which are the one thing in front of a camera that is not in the world. */
    private final LiveWalls walls;

    /**
     * Not part of {@link Baked}: this holds world, not assets, and a reload that swaps the textures has not changed
     * a single block. It bounds itself by age and by count, so nothing here has to empty it.
     *
     * <p>Off unless configured, because it is the one shortcut here that can show a block as it was a moment ago.
     */
    private final SnapshotCache snapshots;

    /**
     * What every capture cost, whoever asked for it, for {@code /mapgui camera timings}.
     *
     * <p>Always on, since it is six numbers a second and the question it answers - "is this costing my server
     * anything" - is asked after the trouble rather than before it. Cleared by a reload, which builds a new service.
     */
    private final CaptureLoad load = new CaptureLoad();

    /**
     * Players following their own captures line by line, from {@code /mapgui camera timings follow}.
     *
     * <p>Per player rather than a config switch, because the question it answers is "why was that slow just now"
     * and the person asking is standing in the world.
     */
    private final Map<UUID, Follow> followed = new ConcurrentHashMap<>();

    /** One followed player's tail. Held so a live view capturing every tick cannot turn the report into a wall of chat. */
    private static final class Follow {

        /** Nanos between reports. Anything faster is a screen refreshing rather than somebody taking a picture. */
        private static final long EVERY_NANOS = TimeUnit.SECONDS.toNanos(1);

        private long lastAt;
        private int skipped;
    }

    /** Only for the report, where the point is that the first few captures are the JIT warming up rather than a cost. */
    private final AtomicInteger captureCount = new AtomicInteger();

    /**
     * Where the off-thread half of a capture runs, instead of {@code runTaskAsynchronously}.
     *
     * <p>Measured rather than assumed: Bukkit's async scheduler normally starts the work in about 0.2 ms but
     * sometimes takes 40 to 50, which is a whole tick of latency for no work.
     *
     * <p>Not the tracer's own pool, which would deadlock - the job would hold one of its threads and then wait for a
     * band with no thread left to run on. Core size zero with a short keep-alive, so an idle camera holds no threads
     * and a service left behind by a reload cannot leak one.
     */
    private final ThreadPoolExecutor captures = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "MapGUI-capture");
        thread.setDaemon(true);
        return thread;
    });

    private final int defaultDistance;
    private final float defaultFov;

    /** Built once the assets are ready, and dropped when they are reloaded. */
    private volatile Baked baked;

    /**
     * The things that only make sense together, so that a reload swaps all of them at once rather than leaving a
     * tracer pointed at an atlas that has been closed.
     *
     * <p>The tracer is one of them rather than one per capture because it owns a thread pool, and a pool built and
     * shut down per photograph would cost more than the threads save.
     */
    private record Baked(BlockModels models, TextureAtlas atlas, MapColors palette, BiomeTints tints,
                         MobAssets mobs, FrameTracer tracer, String version) {
    }

    public CameraService(Plugin plugin, CameraAssetStore assets, ServerPacks packs, ServerBackend backend, LiveWalls walls, float defaultFov, int defaultDistance) {
        this(plugin, assets, packs, backend, walls, defaultFov, defaultDistance, 0);
    }

    /**
     * @param backend           what this version of the server lets a capture read: the pixels behind a framed map,
     *                          and the angles a squid is really swimming at. Null draws neither
     * @param walls             the MapGUI walls a photographer can see, or null to leave them out of the picture
     * @param reuseChunksMillis how long a copied chunk may be served to a later capture, or 0 to copy every time
     */
    public CameraService(Plugin plugin, CameraAssetStore assets, ServerPacks packs, ServerBackend backend, LiveWalls walls, float defaultFov, int defaultDistance, int reuseChunksMillis) {
        this.plugin = plugin;
        this.framedMaps = new FramedMaps(backend == null ? null : backend.savedMapPixels());
        this.angles = backend == null ? null : backend.entityAngles();
        this.walls = walls;
        this.assets = assets;
        this.packs = packs;
        this.defaultFov = defaultFov;
        this.defaultDistance = defaultDistance;
        this.snapshots = new SnapshotCache(TimeUnit.MILLISECONDS.toNanos(reuseChunksMillis));
    }

    @Override
    public CameraAssets assets() {
        return assets.state();
    }

    @Override
    public void useResourcePack(Plugin owner, String resource) {
        packs.use(owner, resource);
    }

    @Override
    public boolean prepare() {
        assets.ensure();
        return assets.state() instanceof CameraAssets.Ready || assets.state() instanceof CameraAssets.Loading;
    }

    @Override
    public void capture(Player player, int size, Consumer<CameraShot> onShot) {
        capture(player, CameraOptions.defaults().size(size).fov(defaultFov).maxDistance(defaultDistance), onShot);
    }

    @Override
    public void capture(Player player, CameraOptions options, Consumer<CameraShot> onShot) {
        assets.ensure();

        Baked ready = readyBaked();
        if (ready == null) {
            onShot.accept(null);
            return;
        }

        int pixels = options.size();
        Location eye = options.selfie() ? selfieFrom(player) : player.getEyeLocation();
        int reachable = viewDistanceBlocks(player);
        // Zero means "as far as this viewer can see", which is the sensible default: a capture that stops short
        // of the client's own horizon looks cropped.
        int distance = options.maxDistance() <= 0 ? reachable : Math.min(options.maxDistance(), reachable);

        long started = System.nanoTime();
        int number = captureCount.incrementAndGet();
        // Here rather than in the task, since this is the one moment the caller is still on the stack to be read.
        String owner = CallingPlugin.of(plugin);

        // On this thread, in this tick: everything the trace is allowed to touch.
        CameraView view = WorldCapture.viewOf(eye, options, distance);
        SnapshotWorld world = WorldCapture.take(eye, view, options, ready.models(), ready.atlas(), ready.tints(), snapshots);
        // Skins are published into the atlas before the trace, since it looks them up by name like any texture.
        skins.publishTo(ready.atlas());
        long copied = System.nanoTime();
        // A selfie is the one shot the holder belongs in. Every other one is taken from inside their own head, so
        // including them would fill the frame with the back of it.
        List<EntitySnapshot> entities = new ArrayList<>();
        if (options.entities()) {
            entities.addAll(EntityCapture.take(player, eye, skins, ready.mobs(), framedMaps, angles, options.selfie()));
        }
        // Not under the entities option, whatever the trace calls these: a chest is part of the build, and turning
        // entities off asks for the world without the things standing in it rather than with holes in the walls.
        entities.addAll(BlockEntityCapture.take(eye, ready.mobs(), skins));
        // Nor under it, for the same reason: a wall is part of the room, and a cinema with the screen left out is
        // not the shot anybody asked for.
        entities.addAll(WallCapture.take(player, eye, walls, ready.atlas()));
        long gathered = System.nanoTime();
        // Counted in the tick it happened in rather than when the shot comes back: a capture whose trace waits three
        // seconds for a thread still cost this tick, and a report that said otherwise would point at the wrong second.
        load.captured(owner, gathered - started);

        captures.execute(() -> {
            int[] argb = new int[pixels * pixels];
            byte[] indices = new byte[pixels * pixels];
            long traceStarted = System.nanoTime();
            long traced;
            try {
                ready.tracer().render(world, view, entities, pixels, pixels, argb);
                traced = System.nanoTime();
                ready.palette().quantize(argb, indices);
            } catch (RuntimeException e) {
                // With the stack, because without it this is unactionable. A capture failing is always a bug in here
                // rather than something an admin did, and the message alone once cost an afternoon.
                plugin.getLogger().log(Level.WARNING, "A camera capture failed", e);
                // And counted, since a console nobody is reading is where this used to end: a camera that fails every
                // time looks from the outside exactly like one nothing is using.
                load.failed(owner, e);
                onMainThread(() -> onShot.accept(null));
                return;
            }
            long quantized = System.nanoTime();
            load.traced(owner, quantized - traceStarted);

            // A capture that succeeded can still have been drawn from the wrong layers, and this is the only
            // moment anything knows: a pack that stopped being readable reads as a pack that never had the file.
            assets.reportDamage();

            CameraShot shot = new CameraShot(pixels, pixels, indices, ready.version());
            onMainThread(() -> {
                onShot.accept(shot);
                // After the shot is handed over, so a slow consumer is not timed as if the camera had been slow.
                Follow follow = followed.get(player.getUniqueId());
                if (follow != null && player.isOnline()) {
                    int[] sections = world.sections();
                    tail(player, follow, new CaptureTimings(pixels, number, world.chunks(), sections[0], sections[1],
                            entities.size(), copied - started, gathered - copied, traced - traceStarted, quantized - traced));
                }
            });
        });
    }

    /**
     * Hands a finished capture back to the main thread, where a caller is allowed to touch the server.
     *
     * <p>Wrapped because the trace no longer runs on a Bukkit task: nothing cancels it when the plugin stops, so it
     * can finish afterwards and find there is no scheduler left to post to. A capture nobody can be handed is not
     * worth a stack trace on the way down.
     */
    private void onMainThread(Runnable delivery) {
        try {
            Bukkit.getScheduler().runTask(plugin, delivery);
        } catch (IllegalStateException | IllegalArgumentException e) {
            plugin.getLogger().fine(() -> "A capture finished after the plugin stopped, so nobody was told: " + e);
        }
    }

    /** What every capture has cost lately, for whoever asks rather than for whoever took them. */
    CaptureLoad load() {
        return load;
    }

    /**
     * Captures copied out of the world and now waiting for a thread to trace them.
     *
     * <p>The one number here that says a server is over its capacity rather than just busy: the queue is unbounded, so
     * a plugin asking for captures faster than this machine can trace them shows up as a number that only goes up.
     */
    int queued() {
        return captures.getQueue().size();
    }

    /**
     * Whether this player is told what their captures cost, line by line.
     *
     * @return the state it is now in
     */
    public boolean toggleTimings(UUID player) {
        if (followed.remove(player) != null) return false;

        followed.put(player, new Follow());
        return true;
    }

    /**
     * One capture's four stages, for the player following along.
     *
     * <p>At most one a second. A camera driving a live view captures every tick, and thirty of these a second is not a
     * report - the ones left out are counted into the next line rather than dropped silently, since a reader who
     * cannot tell they are seeing one capture in twenty reads its cost as the whole cost.
     */
    private void tail(Player player, Follow follow, CaptureTimings timings) {
        long now = System.nanoTime();
        if (now - follow.lastAt < Follow.EVERY_NANOS) {
            follow.skipped++;
            return;
        }

        report(player, timings, follow.skipped);
        follow.lastAt = now;
        follow.skipped = 0;
    }

    private void report(Player player, CaptureTimings timings, int skipped) {
        player.sendMessage(Component.text("Capture ", NamedTextColor.GOLD)
                .append(Component.text("#" + timings.number() + "  " + timings.size() + "x" + timings.size() + "  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(CaptureTimings.millis(timings.totalNanos()), NamedTextColor.WHITE))
                .append(Component.text(" of work", NamedTextColor.DARK_GRAY))
                .append(skipped == 0 ? Component.empty()
                        : Component.text("  +" + skipped + " not shown", NamedTextColor.DARK_GRAY)));

        // Copy first and in its own color, because it is the only one of these that lands on the server's tick.
        player.sendMessage(Component.text("  copy ", NamedTextColor.YELLOW)
                .append(Component.text(CaptureTimings.millis(timings.copyNanos()), NamedTextColor.WHITE))
                .append(Component.text(" (" + timings.chunks() + " chunks, "
                        + timings.filled() + " of " + timings.sections() + " sections)", NamedTextColor.DARK_GRAY))
                .append(Component.text("  entities ", NamedTextColor.YELLOW))
                .append(Component.text(CaptureTimings.millis(timings.entityNanos()), NamedTextColor.WHITE))
                .append(Component.text(" (" + timings.entities() + ")", NamedTextColor.DARK_GRAY)));

        player.sendMessage(Component.text("  trace ", NamedTextColor.AQUA)
                .append(Component.text(CaptureTimings.millis(timings.traceNanos()), NamedTextColor.WHITE))
                .append(Component.text("  palette ", NamedTextColor.AQUA))
                .append(Component.text(CaptureTimings.millis(timings.paletteNanos()), NamedTextColor.WHITE)));
    }

    /**
     * Where a selfie is taken from: out at arm's length, turned back to face the holder.
     *
     * <p>The arm goes out along their gaze and the camera looks straight back down it, which puts their face dead
     * centre whatever they are looking at. Turned rather than mirrored: a phone shows you a mirror image, but this is
     * a picture of a place as much as of a person and flipping it would reverse the landscape behind them.
     *
     * <p>The arm shortens against anything solid, so a selfie with your back to a wall moves the camera closer rather
     * than photographing the inside of a block.
     */
    private static Location selfieFrom(Player player) {
        Location eye = player.getEyeLocation();
        Vector arm = eye.getDirection();

        double reach = SELFIE_REACH;
        while (reach > 0.25 && !player.getWorld().getBlockAt(eye.clone().add(arm.clone().multiply(reach))).isPassable()) {
            reach -= 0.3;
        }

        Location at = eye.add(arm.multiply(reach));
        at.setYaw(eye.getYaw() + 180);
        at.setPitch(-eye.getPitch());
        return at;
    }

    /**
     * Drops the baked models and textures, so the next capture builds them from whatever is loaded now.
     *
     * <p>The tracer's threads go with them. A capture that happens to be tracing at that moment loses its bands and
     * comes back as a failed shot, which is the same thing a reload already does to the assets under it - and a
     * reload is an explicit request from an admin, not something that happens while nobody is looking.
     */
    public synchronized void invalidate() {
        if (baked != null) {
            baked.tracer().close();
        }
        baked = null;
    }

    /**
     * The models and textures, built on first use.
     *
     * <p>Not at startup, and not eagerly when the assets land: a server that never takes a capture should never
     * pay for any of it. The palette is held here too, though it builds its own table the first time anything
     * anywhere asks it for a color.
     */
    private synchronized Baked readyBaked() {
        if (baked != null) return baked;
        if (!(assets.state() instanceof CameraAssets.Ready ready)) return null;

        TextureAtlas atlas = new TextureAtlas(assets.stack());
        BlockModels models = new BlockModels(assets.stack(), atlas);
        BiomeColors colors = new BiomeColors(assets.stack(), atlas);
        // One reader of the item definitions for both the pose and the geometry, so a held block cannot be posed by one
        // model's rules and shaped by another's.
        ItemDefinitions definitions = new ItemDefinitions(assets.stack(), colors);
        // Shared, since a dropped item wants the ground transform out of the same display block a held one reads.
        ItemPoses poses = new ItemPoses(assets.stack(), definitions);

        baked = new Baked(
                models,
                atlas,
                MapColors.INSTANCE,
                new BiomeTints(colors),
                new MobAssets(
                        atlas,
                        poses,
                        new ItemModels(atlas, new BlockItems(models, definitions), models, poses),
                        new EquipmentAssets(assets.stack()),
                        new EntityVariants(assets.stack())
                ),
                new FrameTracer(atlas),
                ready.minecraftVersion()
        );
        return baked;
    }

    /**
     * How far this viewer can actually see.
     *
     * <p>The client's own render distance where it is known, since it sends that in its settings packet, capped by
     * what the server keeps loaded. Field of view, brightness and graphics settings are never sent at all, which is
     * why those stay options rather than readings.
     */
    private int viewDistanceBlocks(Player player) {
        int server = Math.min(player.getWorld().getViewDistance(), Bukkit.getViewDistance());
        int chunks = server;

        Integer client = player.getClientOption(ClientOption.VIEW_DISTANCE);
        if (client != null && client > 0) {
            chunks = Math.min(server, client);
        }

        // One chunk more than the count. A render distance of n means n chunks of them beyond the one the player is
        // standing in, and they are somewhere inside that one rather than at its far edge - so n * 16 stops a chunk
        // short of where their horizon actually is.
        return Math.max(16, (chunks + 1) * 16);
    }
}
