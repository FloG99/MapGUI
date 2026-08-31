package de.flog99.mapgui.plugin;

import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.MapTransport;
import de.flog99.mapgui.PacketInput;
import de.flog99.mapgui.HandRaiser;
import de.flog99.mapgui.RotationController;
import de.flog99.mapgui.ServerBackend;
import de.flog99.mapgui.plugin.camera.CameraAssetStore;
import de.flog99.mapgui.plugin.camera.CameraFeeds;
import de.flog99.mapgui.plugin.camera.CameraService;
import de.flog99.mapgui.plugin.camera.ServerPacks;
import de.flog99.mapgui.plugin.map.MapPrinterService;
import de.flog99.mapgui.plugin.prompt.AnvilPrompt;
import de.flog99.mapgui.plugin.prompt.DialogPrompt;
import de.flog99.mapgui.plugin.video.MediaCache;
import de.flog99.mapgui.plugin.video.MediaSources;
import de.flog99.mapgui.plugin.video.StreamResolver;
import de.flog99.mapgui.plugin.video.Toolchain;
import de.flog99.mapgui.plugin.video.VideoNatives;
import de.flog99.mapgui.plugin.wall.WallListeners;
import de.flog99.mapgui.plugin.wall.WallManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

public final class MapGuiPlugin extends JavaPlugin {

    private MapGuiConfig config;
    private ServerBackend backend;
    private SessionManager sessions;
    private PromptRegistryImpl prompts;
    private RotationController rotation;
    private HandRaiser handRaiser;
    private MapTransport transport;
    private PacketInput input;
    private WallManager walls;
    private WallRegistry wallRegistry;
    private InputRouter router;
    private GuiCatalogImpl screens;
    private CameraAssetStore cameraAssets;
    private CameraService camera;
    private CameraFeeds feeds;
    private ServerPacks serverPacks;
    private MapPrinterService printer;
    private Toolchain tools;
    private MediaSources media;
    private HandItems handItems;
    private HeldTriggers heldTriggers;
    private CommandSurface surface;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        config = MapGuiConfig.from(getConfig());
        // Before warmUp, which is what fills the table - and the table is filled once. Choosing afterwards
        // would leave half the server on one formula, so MapColors refuses it rather than half-applying it.
        MapColors.matching(config.colorMatching());
        MapColors.warmUp();

        // Everything that reaches into the server, for the version this server happens to be.
        backend = Backends.forThisServer();
        rotation = backend.rotation();
        handRaiser = backend.handRaiser();
        transport = backend.transport();
        input = backend.input();
        router = new InputRouter(input);

        prompts = new PromptRegistryImpl();
        AnvilPrompt anvil = new AnvilPrompt();
        prompts.register("dialog", new DialogPrompt(this));
        prompts.register("anvil", anvil);
        prompts.setDefault(config.defaultPrompt());

        // Unregistering has to reach both surfaces, and the fields are read when it fires rather than now -
        // which is what lets the catalog exist before the two things it has to tell.
        screens = new GuiCatalogImpl(name -> {
            sessions.closeShowing(name);
            walls.hideContent(name);
        });

        // Resolves what is on disk but fetches nothing, so a server that only uses menus never pays for this.
        cameraAssets = new CameraAssetStore(this, config.cameraPacks(), config.cameraDownload(), config.cameraAllowVersionMismatch());

        // Before announce(), so the first load already layers whatever was kept on an earlier run.
        serverPacks = new ServerPacks(this, cameraAssets, getDataFolder().toPath().resolve("cache").resolve("camera"), config.cameraFollowServerPacks());
        cameraAssets.follow(serverPacks::followed);
        // Before the camera, which photographs whatever the walls are showing.
        wallRegistry = new WallRegistry(this, transport, prompts, router);
        // Held here rather than inside the camera so that a reload, which builds a new service, leaves everybody's
        // viewfinder running. Both suppliers are read at tick time, which is what lets this exist before either.
        feeds = new CameraFeeds(this, this::camera, this::sessions);
        camera = new CameraService(this, cameraAssets, serverPacks, backend, wallRegistry, feeds, config.cameraTuning());
        cameraAssets.announce();
        serverPacks.start();

        printer = new MapPrinterService(this, backend.savedMapPixels());
        announceMedia();
        media = mediaSources();

        walls = new WallManager(this, wallRegistry::builder, router, screens, media, config.wallFps(), config.wallRange(), config.wallVideoSize(), config.wallPrerender(), config.streams());
        sessions = new SessionManager(this, wallRegistry);
        handItems = new HandItems(this);
        heldTriggers = new HeldTriggers(this);
        getServer().getServicesManager().register(MapGui.class, sessions, this, ServicePriority.Normal);

        getServer().getPluginManager().registerEvents(new InputListeners(this), this);
        getServer().getPluginManager().registerEvents(anvil, this);

        walls.load();
        PerformanceReport performance = new PerformanceReport(this, transport, sessions, walls);
        getServer().getPluginManager().registerEvents(new WallListeners(walls), this);
        getServer().getPluginManager().registerEvents(wallRegistry, this);

        // Off means never registered rather than registered and refused, so nothing of MapGUI's appears in a tab
        // completion or a help listing at all. For a server whose plugin ships its own commands over the API and
        // does not want two ways to ask the same question. Restart to change it: a Brigadier tree is built once.
        surface = new CommandSurface(this, screens, walls);
        if (config.commandsEnabled()) {
            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                    event.registrar().register(MapGuiCommand.build(this, sessions, walls, surface, performance::lines), "Interactive map GUIs")
            );
        }

        // Walls are not tied to a session, so they need a tick of their own. One per tick is the most a
        // map can be updated anyway, and a wall with nobody near it does almost nothing.
        //
        // The two hand sweeps ride along for the same reason: an item can reach a hand a dozen ways, and asking who
        // is holding one is cheaper than a listener per route.
        getServer().getScheduler().runTaskTimer(this, () -> {
            // Finished captures first, before anything paints. A trace that lands mid-tick already has its pixels, and a
            // wall that has painted for this tick would sit on them until the next one - fifty milliseconds of a
            // reflection trailing behind whoever is looking at it, bought back by doing these in the right order.
            camera.deliverFinished();

            walls.tick();
            wallRegistry.tick(System.currentTimeMillis());
            handItems.sweep();
            heldTriggers.sweep();
            // Live camera views, which want a frame every tick and get one when the budget says so.
            feeds.tick();
        }, 1L, 1L);

        // Once a second, and only to notice that a branch of /mapgui has become worth listing - registering a GUI,
        // placing a wall and touching a camera are three unrelated paths, and one poll is cheaper than hooks in all
        // of them. Nothing is sent unless the answer changed.
        getServer().getScheduler().runTaskTimer(this, surface::refresh, 20L, 20L);
    }

    /**
     * Brings an older config.yml up to the current {@code config-version} before anything reads it.
     *
     * <p>Before {@link #getConfig()} is ever called, so nothing has to be re-read: {@link ConfigMigration}
     * rewrites the file on disk, and the first load then sees the new keys.
     */
    private void migrateConfig() {
        try {
            ConfigMigration.Result migrated = ConfigMigration.apply(getDataFolder().toPath().resolve("config.yml"));
            if (migrated == null) return;

            String changes = migrated.changes().isEmpty() ? "nothing had to move" : String.join("; ", migrated.changes());
            getLogger().info("config.yml was written for MapGUI config-version " + migrated.from() + " and is now "
                    + ConfigMigration.CURRENT + ": " + changes + ".");
        } catch (IOException | RuntimeException e) {
            // Not fatal, and deliberately so: both readers of the renamed keys still accept the old ones, so a
            // config.yml this cannot rewrite - read-only mount, no permission - keeps working exactly as it did.
            getLogger().warning("Could not update config.yml (" + e.getMessage() + "). Settings under their old"
                    + " names are still read, so nothing has stopped working.");
        }
    }

    /**
     * Everything that turns a url into pixels, built from what config.yml decided.
     *
     * <p>The toolchain is warmed on a worker rather than here: resolving it starts processes and may download,
     * both of which would be minutes of a server not starting. Warming it at all is what puts the line saying
     * what was obtained in the startup log instead of inside somebody's first play - the first question about a
     * 403 is which runtime, if any, the plugin actually got.
     */
    private MediaSources mediaSources() {
        tools = new Toolchain(getDataFolder().toPath(), getLogger());
        StreamResolver resolver = new StreamResolver(tools, getLogger(), config.resolvePageUrls());
        MediaCache cache = new MediaCache(getDataFolder().toPath().resolve("cache"),
                config.downloadMaxFileMb(), config.downloadMaxTotalMb());

        if (config.resolvePageUrls()) {
            getServer().getScheduler().runTaskAsynchronously(this, tools::warm);
        }
        return new MediaSources(this, resolver, cache, config.wallVideoSize(), config.wallFps(), config.downloadMaxFrames(),
                config.mediaDither(), config.mediaSteady());
    }

    /**
     * Says once whether media playback is actually there.
     *
     * <p>Worth a line because the answer is decided before this plugin exists - the loader fetches FFmpeg while
     * the server is still starting - so without it, an admin who turned the setting on has no way to tell
     * whether it worked until the first video refuses to play.
     */
    private void announceMedia() {
        if (!config.mediaFfmpeg()) return;

        if (VideoNatives.available()) {
            getLogger().info("FFmpeg is loaded, so video files, streams and formats ImageIO cannot read can be played. Built for " + VideoNatives.platform() + ".");
            return;
        }
        getLogger().warning("media.ffmpeg is on but FFmpeg did not load, so only GIFs and the image formats ImageIO reads will play. The download happens while the server starts - look for MavenLibraryResolver errors further up this log.");
    }

    @Override
    public void onDisable() {
        if (feeds != null) {
            feeds.closeAll();
        }
        if (sessions != null) {
            sessions.closeAll();
        }
        if (walls != null) {
            walls.close();
        }
        if (wallRegistry != null) {
            wallRegistry.closeAll();
        }
        if (cameraAssets != null) {
            cameraAssets.close();
        }
    }

    /**
     * Re-reads config.yml and pushes the parts that can change while things are running.
     *
     * <p>Most settings need nothing pushed, because they are read from {@link #config()} every time they are
     * used - the cursor range, the frame ceilings, the terrain interval. The prompt default is held by the
     * registry, so it is the one that has to be told.
     *
     * <p>{@code walls.video-size} is deliberately absent: videos already decoded are held at the old size, and
     * re-decoding every file on a reload would stall the server. It takes effect on restart.
     */
    void reload() {
        reloadConfig();
        config = MapGuiConfig.from(getConfig());
        walls.retune(config.wallFps(), config.wallRange());
        prompts.setDefault(config.defaultPrompt());

        // Re-reads the disk only if something that decides what to load actually moved. The baked models and
        // textures go either way, since the fov and distance they were built against may have changed.
        serverPacks.retune(config.cameraFollowServerPacks());
        cameraAssets.retune(config.cameraPacks(), config.cameraDownload(), config.cameraAllowVersionMismatch());
        // The feeds are not rebuilt with it: they look this method up each tick, so an open viewfinder carries on
        // through a reload against the new service rather than quietly freezing.
        camera = new CameraService(this, cameraAssets, serverPacks, backend, wallRegistry, feeds, config.cameraTuning());

        // A fresh toolchain with it, which is the only way a server that could not reach github.com when it
        // started ever tries again without a restart - resolution is settled once per instance, failures
        // included, so that a server with no network does not attempt a download on every play. Walls already
        // up keep the service they were opened with, and so does anything still playing.
        media = mediaSources();
    }

    MapGuiConfig config() {
        return config;
    }

    SessionManager sessions() {
        return sessions;
    }

    PromptRegistryImpl prompts() {
        return prompts;
    }

    GuiCatalogImpl guis() {
        return screens;
    }

    /** Which version's internals won, which is the first thing to ask when a report names a Minecraft version. */
    ServerBackend backend() {
        return backend;
    }

    RotationController rotation() {
        return rotation;
    }

    HandRaiser handRaiser() {
        return handRaiser;
    }

    MapTransport transport() {
        return transport;
    }

    InputRouter router() {
        return router;
    }

    public CameraService camera() {
        return camera;
    }

    public ServerPacks serverPacks() {
        return serverPacks;
    }

    public CameraAssetStore cameraAssets() {
        return cameraAssets;
    }

    /** The one piece of new public surface in phase 6, handed out through {@link MapGui#media()}. */
    public MediaSources media() {
        return media;
    }

    MapPrinterService printer() {
        return printer;
    }

    HandItems handItems() {
        return handItems;
    }

    HeldTriggers heldTriggers() {
        return heldTriggers;
    }

    /** Where the map a player carries actually is, which the listeners have to ask about to defend it. */
    HeldMapDisplay display() {
        return sessions.display();
    }
}
