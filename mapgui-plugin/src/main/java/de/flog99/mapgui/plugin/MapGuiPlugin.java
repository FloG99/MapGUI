package de.flog99.mapgui.plugin;

import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.MapTransport;
import de.flog99.mapgui.PacketInput;
import de.flog99.mapgui.RotationController;
import de.flog99.mapgui.nms.NmsPacketInput;
import de.flog99.mapgui.nms.NmsMapTransport;
import de.flog99.mapgui.nms.NmsRotationController;
import de.flog99.mapgui.plugin.prompt.AnvilPrompt;
import de.flog99.mapgui.plugin.prompt.DialogPrompt;
import de.flog99.mapgui.plugin.wall.WallListeners;
import de.flog99.mapgui.plugin.wall.WallManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class MapGuiPlugin extends JavaPlugin {

    private MapGuiConfig config;
    private SessionManager sessions;
    private PromptRegistryImpl prompts;
    private RotationController rotation;
    private MapTransport transport;
    private PacketInput input;
    private WallManager walls;
    private WallRegistry wallRegistry;
    private InputRouter router;
    private GuiCatalogImpl screens;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = MapGuiConfig.from(getConfig());
        rotation = new NmsRotationController();
        transport = new NmsMapTransport();
        input = new NmsPacketInput();
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

        wallRegistry = new WallRegistry(this, transport, prompts, router);
        walls = new WallManager(this, wallRegistry::builder, router, screens, config.wallFps(), config.wallRange(), config.wallVideoSize());
        sessions = new SessionManager(this, wallRegistry);
        getServer().getServicesManager().register(MapGui.class, sessions, this, ServicePriority.Normal);

        getServer().getPluginManager().registerEvents(new InputListeners(this), this);
        getServer().getPluginManager().registerEvents(anvil, this);

        walls.load();
        PerformanceReport performance = new PerformanceReport(transport, sessions, walls);
        getServer().getPluginManager().registerEvents(new WallListeners(walls), this);
        getServer().getPluginManager().registerEvents(wallRegistry, this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(MapGuiCommand.build(this, sessions, walls, performance::lines), "Interactive map GUIs")
        );

        // Walls are not tied to a session, so they need a tick of their own. One per tick is the most a
        // map can be updated anyway, and a wall with nobody near it does almost nothing.
        getServer().getScheduler().runTaskTimer(this, () -> {
            walls.tick();
            wallRegistry.tick(System.currentTimeMillis());
        }, 1L, 1L);
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
}
