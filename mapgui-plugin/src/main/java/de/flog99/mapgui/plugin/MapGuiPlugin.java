package de.flog99.mapgui.plugin;

import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.MapTransport;
import de.flog99.mapgui.PacketInput;
import de.flog99.mapgui.RotationController;
import de.flog99.mapgui.ServerBackend;
import de.flog99.mapgui.plugin.camera.CameraAssetStore;
import de.flog99.mapgui.plugin.camera.CameraService;
import de.flog99.mapgui.plugin.camera.ServerPacks;
import de.flog99.mapgui.plugin.map.MapPrinterService;
import de.flog99.mapgui.plugin.prompt.AnvilPrompt;
import de.flog99.mapgui.plugin.prompt.DialogPrompt;
import de.flog99.mapgui.plugin.video.VideoNatives;
import de.flog99.mapgui.plugin.wall.WallListeners;
import de.flog99.mapgui.plugin.wall.WallManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class MapGuiPlugin extends JavaPlugin {

    private MapGuiConfig config;
    private ServerBackend backend;
    private SessionManager sessions;
    private PromptRegistryImpl prompts;
    private RotationController rotation;
    private MapTransport transport;
    private PacketInput input;
    private WallManager walls;
    private WallRegistry wallRegistry;
    private InputRouter router;
    private GuiCatalogImpl screens;
    private CameraAssetStore cameraAssets;
    private CameraService camera;
    private ServerPacks serverPacks;
    private MapPrinterService printer;
    private HandItems handItems;
    private HeldTriggers heldTriggers;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = MapGuiConfig.from(getConfig());
        MapColors.warmUp();

        // Everything that reaches into the server, for the version this server happens to be.
        backend = Backends.forThisServer();
        rotation = backend.rotation();
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
        camera = new CameraService(this, cameraAssets, serverPacks, backend, wallRegistry, config.cameraFov(), config.cameraDistance(), config.cameraReuseChunksMillis(), config.cameraLiveMaxMillis(), config.cameraLiveMaxFps());
        cameraAssets.announce();
        serverPacks.start();

        printer = new MapPrinterService(this, backend.savedMapPixels());
        announceVideo();

        walls = new WallManager(this, wallRegistry::builder, router, screens, config.wallFps(), config.wallRange(), config.wallVideoSize(), config.wallPrerender(), config.streams());
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
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(MapGuiCommand.build(this, sessions, walls, performance::lines), "Interactive map GUIs")
        );

        // Walls are not tied to a session, so they need a tick of their own. One per tick is the most a
        // map can be updated anyway, and a wall with nobody near it does almost nothing.
        //
        // The two hand sweeps ride along for the same reason: an item can reach a hand a dozen ways, and asking who
        // is holding one is cheaper than a listener per route.
        getServer().getScheduler().runTaskTimer(this, () -> {
            walls.tick();
            wallRegistry.tick(System.currentTimeMillis());
            handItems.sweep();
            heldTriggers.sweep();
        }, 1L, 1L);
    }

    /**
     * Says once whether video playback is actually there.
     *
     * <p>Worth a line because the answer is decided before this plugin exists - the loader fetches FFmpeg while
     * the server is still starting - so without it, an admin who turned the setting on has no way to tell
     * whether it worked until the first video refuses to play.
     */
    private void announceVideo() {
        if (!config.videoFfmpeg()) return;

        if (VideoNatives.available()) {
            getLogger().info("FFmpeg is loaded, so video files and streams can be played. Built for " + VideoNatives.platform() + ".");
            return;
        }
        getLogger().warning("video.ffmpeg is on but FFmpeg did not load, so only GIFs will play. The download happens while the server starts - look for MavenLibraryResolver errors further up this log.");
    }

    @Override
    public void onDisable() {
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
        camera = new CameraService(this, cameraAssets, serverPacks, backend, wallRegistry, config.cameraFov(), config.cameraDistance(), config.cameraReuseChunksMillis(), config.cameraLiveMaxMillis(), config.cameraLiveMaxFps());
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

    RotationController rotation() {
        return rotation;
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
