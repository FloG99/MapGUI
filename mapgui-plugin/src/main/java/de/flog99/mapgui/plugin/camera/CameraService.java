package de.flog99.mapgui.plugin.camera;

import com.destroystokyo.paper.ClientOption;
import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.camera.Camera;
import de.flog99.mapgui.camera.CameraAssets;
import de.flog99.mapgui.camera.CameraFeed;
import de.flog99.mapgui.camera.CameraOptions;
import de.flog99.mapgui.camera.CameraShot;
import de.flog99.mapgui.camera.CameraStats;
import de.flog99.mapgui.ServerBackend;
import de.flog99.mapgui.camera.EntityAngles;
import de.flog99.mapgui.camera.LiveWalls;
import de.flog99.mapgui.render.BiomeColors;
import de.flog99.mapgui.render.BlockItems;
import de.flog99.mapgui.render.BlockModels;
import de.flog99.mapgui.render.CameraView;
import de.flog99.mapgui.render.ChunkFrustum;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
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

    /**
     * The live views this server is driving, which outlive this service.
     *
     * <p>Owned by the plugin so that a reload rebuilding the camera does not silently stop everybody's viewfinder -
     * see {@link CameraFeeds}.
     */
    private final CameraFeeds feeds;

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

    /** The same idea for what is bolted to the world rather than standing on it. */
    private final BlockEntityCache blockEntities;

    /**
     * And for what a mob <i>looks like</i>, which is the expensive part of one and the part that does not change.
     *
     * <p>Never where it is standing - see {@link MobCache}. Built per service rather than per capture, since the
     * whole point is that it outlives one.
     */
    private final MobCache mobShapes;

    /**
     * What every capture cost, whoever asked for it, for {@code /mapgui camera performance}.
     *
     * <p>Always on, since it is six numbers a second and the question it answers - "is this costing my server
     * anything" - is asked after the trouble rather than before it. Cleared by a reload, which builds a new service.
     */
    private final CaptureLoad load = new CaptureLoad();

    /**
     * How often a live view is allowed a frame, which is the camera's own business rather than any one plugin's.
     *
     * <p>Only the server can see every viewfinder pointed at it at once, so only the server can divide the time
     * between them. Advisory: it paces whoever asks, and a plugin that never asks is never paced.
     */
    private final CaptureBudget budget;

    /**
     * Players following their own captures line by line, from {@code /mapgui camera performance follow}.
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

    /** Set by the first plugin to ask this camera for anything, and never cleared. */
    private volatile boolean used;

    /**
     * How many copied-but-untraced captures may be held at once.
     *
     * <p>The bound exists for memory rather than for latency. A queued capture holds the {@code ChunkSnapshot} of
     * every chunk column it copied - 167 of them at range 192 - so a backlog is not a queue of small jobs waiting,
     * it is that many copies of the world retained until they are drawn. Unbounded, a plugin capturing faster than
     * the machine traces runs the server out of heap rather than merely falling behind.
     *
     * <p>Small, because the other half of the argument points the same way: a capture that has waited for several
     * traces is a photograph of somewhere the player no longer is. Turning it away at once and saying so beats
     * drawing it a second late.
     */
    private static final int MAX_QUEUED = 3;

    /**
     * Where the off-thread half of a capture runs, instead of {@code runTaskAsynchronously}.
     *
     * <p>Measured rather than assumed: Bukkit's async scheduler normally starts the work in about 0.2 ms but
     * sometimes takes 40 to 50, which is a whole tick of latency for no work.
     *
     * <p>Not the tracer's own pool, which would deadlock - the job would hold one of its threads and then wait for a
     * band with no thread left to run on. One thread, and stated as one: this used to say {@code (0, 2, ...)} over an
     * unbounded queue, which never reaches two - a pool only grows past its core size when the queue refuses a task,
     * and an unbounded one never does. It is also the right number rather than an accident, since {@link FrameTracer}
     * already spreads one capture across every core; a second concurrent trace would contend with the first for the
     * same threads and hold a second copy of the world while it did. Core threads time out, so an idle camera holds
     * none and a service left behind by a reload cannot leak one.
     */
    private final ThreadPoolExecutor captures = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(MAX_QUEUED), runnable -> {
        Thread thread = new Thread(runnable, "MapGUI-capture");
        thread.setDaemon(true);
        return thread;
    });

    {
        captures.allowCoreThreadTimeOut(true);
    }

    private final CameraTuning tuning;

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

    /**
     * @param backend what this version of the server lets a capture read: the pixels behind a framed map, and the
     *                angles a squid is really swimming at. Null draws neither
     * @param walls   the MapGUI walls a photographer can see, or null to leave them out of the picture
     * @param feeds   where live views are kept, which is the plugin's rather than this service's
     * @param tuning  the numbers under {@code camera:} in config.yml
     */
    public CameraService(Plugin plugin, CameraAssetStore assets, ServerPacks packs, ServerBackend backend, LiveWalls walls, CameraFeeds feeds, CameraTuning tuning) {
        this.plugin = plugin;
        this.feeds = feeds;
        this.tuning = tuning;
        this.budget = new CaptureBudget(tuning.liveMaxMillisPerTick(), tuning.liveMaxFps());
        this.framedMaps = new FramedMaps(backend == null ? null : backend.savedMapPixels());
        this.angles = backend == null ? null : backend.entityAngles();
        this.walls = walls;
        this.assets = assets;
        this.packs = packs;
        CameraTuning.Reuse reuse = tuning.reuse();
        this.snapshots = new SnapshotCache(TimeUnit.MILLISECONDS.toNanos(reuse.stillChunksMillis()), reuse.chunks());
        this.blockEntities = new BlockEntityCache(this.snapshots, reuse.blockEntities());
        this.mobShapes = new MobCache(reuse.mobs());
    }

    @Override
    public CameraAssets assets() {
        used = true;
        return assets.state();
    }

    @Override
    public String useResourcePack(Plugin owner, String resource) {
        used = true;
        return packs.use(owner, resource);
    }

    @Override
    public CameraFeed feed(Player player, Supplier<CameraOptions> options, Consumer<CameraShot> onFrame) {
        used = true;
        return feeds.open(player, options, onFrame);
    }

    @Override
    public boolean prepare() {
        used = true;
        assets.ensure();
        return assets.state() instanceof CameraAssets.Ready || assets.state() instanceof CameraAssets.Loading;
    }

    @Override
    public void capture(Player player, int size, Consumer<CameraShot> onShot) {
        capture(player, CameraOptions.defaults().size(size).fov(tuning.fov()).maxDistance(tuning.maxDistance()), onShot);
    }

    @Override
    public void capture(Player player, CameraOptions options, Consumer<CameraShot> onShot) {
        used = true;
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

        // Before the copy rather than after it. The copy is the main-thread half, so a capture that was never going
        // to be traced in time should not cost the tick that would have paid for it.
        if (captures.getQueue().size() >= MAX_QUEUED) {
            load.turnedAway(owner);
            onShot.accept(null);
            return;
        }

        boolean paced = budget.claimPaced(player.getUniqueId());

        // On this thread, in this tick: everything the trace is allowed to touch.
        CameraView view = WorldCapture.viewOf(eye, options, distance);
        // Read either side of the copy rather than plumbed out of it. The cache counts every column a capture asks
        // for, and a capture is the only thing touching it on this thread, so the difference is exactly this one's.
        long wantedBefore = snapshots.lookups();
        long reusedBefore = snapshots.hits();
        SnapshotWorld world = WorldCapture.take(eye, view, options, ready.models(), ready.atlas(), ready.tints(), snapshots, paced);
        // Skins are published into the atlas before the trace, since it looks them up by name like any texture.
        skins.publishTo(ready.atlas());
        long copied = System.nanoTime();
        // A selfie is the one shot the holder belongs in. Every other one is taken from inside their own head, so
        // including them would fill the frame with the back of it.
        // The same frustum the copy culls columns with, so an entity the frame cannot reach is never built.
        ChunkFrustum framed = new ChunkFrustum(view, eye.getWorld().getMinHeight(), eye.getWorld().getMaxHeight() - 1);
        TrackingRanges ranges = TrackingRanges.of(eye.getWorld(), tuning.maxEntityDistance());

        // Read either side of the gather, the same way the columns are: the cache counts every mob a capture asked
        // it about, and a capture is the only thing touching it on this thread.
        long mobsBefore = mobShapes.lookups();
        long mobsReusedBefore = mobShapes.hits();
        List<EntitySnapshot> entities = new ArrayList<>();
        if (options.entities()) {
            entities.addAll(EntityCapture.take(player, eye, skins, ready.mobs(), framedMaps, angles, ranges, framed,
                    mobShapes, paced, tuning.limits().mobs(), options.selfie()));
        }
        // Split here: what is alive above, what is bolted to the world below. They are two different costs with
        // two different answers, and one timer over both says only that "entities" are slow.
        long mobbed = System.nanoTime();
        int mobs = entities.size();
        // Not under the entities option, whatever the trace calls these: a chest is part of the build, and turning
        // entities off asks for the world without the things standing in it rather than with holes in the walls.
        entities.addAll(BlockEntityCapture.take(eye, ready.mobs(), skins, framed, blockEntities, paced,
                tuning.limits().blockEntityDistance(), tuning.limits().blockEntities()));
        // Nor under it, for the same reason: a wall is part of the room, and a cinema with the screen left out is
        // not the shot anybody asked for.
        entities.addAll(WallCapture.take(player, eye, walls, ready.atlas()));
        long gathered = System.nanoTime();
        // Counted in the tick it happened in rather than when the shot comes back: a capture whose trace waits three
        // seconds for a thread still cost this tick, and a report that said otherwise would point at the wrong second.
        load.captured(owner, copied - started, mobbed - copied, gathered - mobbed, paced);
        int[] sections = world.sections();
        load.copied(owner, (int) (snapshots.lookups() - wantedBefore), (int) (snapshots.hits() - reusedBefore),
                sections[0], sections[1], mobs, entities.size() - mobs,
                (int) (mobShapes.lookups() - mobsBefore), (int) (mobShapes.hits() - mobsReusedBefore));
        // Only a paced one feeds the pacing. What a capture costs is a function of how wide and how far it was asked
        // to see, so a still at another size is a measurement of something the viewfinder never does.
        if (paced) {
            budget.spent(player.getUniqueId(), gathered - started);
        }

        try {
            trace(ready, owner, player, pixels, number, world, view, entities, onShot, started, copied, gathered);
        } catch (RejectedExecutionException e) {
            // Raced another capture through the check above, or the plugin is stopping. Either way the caller is
            // owed an answer rather than a shot that never arrives.
            load.turnedAway(owner);
            onShot.accept(null);
        }
    }

    /** The off-thread half, split out so the tick half above reads as the tick half. */
    private void trace(Baked ready, String owner, Player player, int pixels, int number,
                       SnapshotWorld world, CameraView view, List<EntitySnapshot> entities,
                       Consumer<CameraShot> onShot, long started, long copied, long gathered) {
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

    @Override
    public boolean readyForFrame(Player player) {
        used = true;
        return budget.readyForFrame(player.getUniqueId());
    }

    @Override
    public double frameRate(Player player) {
        return budget.frameRate(player.getUniqueId());
    }

    @Override
    public CameraStats stats() {
        CaptureWindow.Load now = load.read();
        CaptureLoad.Failure failure = load.lastFailure();
        CaptureBudget.Live live = budget.live();

        List<CameraStats.Caller> callers = new ArrayList<>();
        for (CaptureLoad.Share share : load.shares()) {
            callers.add(new CameraStats.Caller(share.plugin(), share.perSecond()));
        }

        return new CameraStats(
                now.captures(),
                now.perSecond(),
                now.unpacedPerSecond(),
                millis(now.mainNanosPerTick()),
                now.tickPercent(),
                millis(now.worstMainNanos()),
                millis(now.mainNanosEach()),
                millis(now.copyNanosEach()),
                millis(now.entityNanosEach()),
                millis(now.blockEntityNanosEach()),
                millis(now.traceNanosEach()),
                new CameraStats.Blocks(now.chunksEach(), now.reusedPercent(), now.filledSectionsEach(), now.sectionsEach()),
                now.entitiesEach(),
                now.entitiesReusedPercent(),
                now.blockEntitiesEach(),
                captures.getQueue().size(),
                now.dropped(),
                now.failed(),
                budget.maxMillisPerTick(),
                budget.fpsCeiling(),
                failure == null ? null : new CameraStats.Failure(failure.plugin(), failure.reason(), failure.at()),
                List.copyOf(callers),
                new CameraStats.Live(live.viewers(), live.slowestFps(), live.fastestFps(), live.usedMillisPerTick())
        );
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    /**
     * Whether anything has asked this camera for anything since the server started.
     *
     * <p>For the command tree, which hides the branch that administers a camera nobody is using - on a server that
     * installed MapGUI for menus, {@code /mapgui camera} is four commands about a feature that never runs.
     */
    public boolean everUsed() {
        return used;
    }

    /** What every capture has cost lately, for whoever asks rather than for whoever took them. */
    CaptureLoad load() {
        return load;
    }

    /** What the live views are getting out of the budget, for the report that has to explain it. */
    CaptureBudget.Live live() {
        return budget.live();
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
    public boolean toggleFollow(UUID player) {
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
                new FrameTracer(atlas, tuning.canopy()),
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
