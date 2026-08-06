package de.flog99.mapgui.plugin.wall;

import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.PacketInput;
import de.flog99.mapgui.GuiCatalog;
import de.flog99.mapgui.WallDisplay;
import de.flog99.mapgui.WallLayout;
import de.flog99.mapgui.plugin.Coordinates;
import de.flog99.mapgui.plugin.InputRouter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The walls this plugin looks after itself, and the ones being put up right now.
 *
 * <p>Only the ones from {@code /mapgui wall place}. A plugin using MapGUI as a dependency opens its own through
 * {@link MapGui#wall()} and keeps track of them itself, since it already stores where its furniture is.
 */
public final class WallManager {

    /** How far {@code /mapgui wall remove} will look when no name is given. */
    public static final int NEAREST_RANGE = 32;

    private final Plugin plugin;
    private final Supplier<WallDisplay.Builder> walls;
    private final InputRouter router;
    private final WallStore store;
    private final WallContents contents;

    private int fps;
    private int range;

    private final Map<String, WallDisplay> live = new HashMap<>();
    private final Map<UUID, WallPlacement> placing = new HashMap<>();

    /** Kept so the same instance can be handed back to release the claim. */
    private final Map<UUID, PacketInput.Handler> placementInputs = new HashMap<>();

    /** Takes a way to start a wall rather than the whole of {@link MapGui}, which is all it ever used it for. */
    public WallManager(Plugin plugin, Supplier<WallDisplay.Builder> walls, InputRouter router,
                       GuiCatalog screens, int fps, int range, int videoSize, boolean prerender, Map<String, String> streams) {
        this.plugin = plugin;
        this.walls = walls;
        this.router = router;
        this.store = new WallStore(plugin);
        this.contents = new WallContents(screens, new VideoLibrary(plugin, videoSize, prerender, streams));
        this.fps = fps;
        this.range = range;
    }

    // ---- lifecycle ----

    public void load() {
        store.load();
    }

    public void close() {
        for (WallDisplay wall : List.copyOf(live.values())) wall.close();
        live.clear();
        contents.close();
        for (UUID id : List.copyOf(placing.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                cancelPlacing(player);
            }
        }
        placing.clear();
    }

    // ---- ticking ----

    /** Only opens walls and aims previews - the walls themselves are ticked by the plugin's registry. */
    public void tick() {
        long now = System.currentTimeMillis();

        for (Map.Entry<String, WallStore.Placed> entry : store.all().entrySet()) {
            if (live.containsKey(entry.getKey())) continue;

            Consumer<WallDisplay.Builder> content = contents.find(entry.getValue().content());
            if (content == null) continue;

            WallDisplay wall = build(entry.getValue(), content);
            if (wall != null) {
                live.put(entry.getKey(), wall);
            }
        }

        for (WallPlacement placement : placing.values()) placement.aim(now);
    }

    /**
     * Built on the first tick rather than at load, since a wall's world may not be loaded yet - and left
     * unbuilt if what it shows is missing, so a bad file costs a wall rather than a startup.
     *
     * <p>Retried every tick, which is what lets a plugin register its GUIs whenever it likes.
     */
    @Nullable
    private WallDisplay build(WallStore.Placed placed, Consumer<WallDisplay.Builder> content) {
        World world = Bukkit.getWorld(placed.world());
        if (world == null) return null;

        WallDisplay.Builder wall = walls.get()
                .at(world, placed.x(), placed.y(), placed.z(), placed.facing())
                .size(placed.cols(), placed.rows())
                .fps(fps)
                .range(range);

        // Last, so an entry that needs its own frame rate can say so and win.
        content.accept(wall);
        return wall.open();
    }

    // ---- placing ----

    /** Null on success, or why it could not start. */
    @Nullable
    public String startPlacing(Player player, String name) {
        contents.forget(name);

        Consumer<WallDisplay.Builder> content = contents.find(name);
        if (content == null) {
            // A file that is there but will not play is a different problem from a name nobody knows, and the
            // fix differs too - so say which it was rather than sending them to look in the folder either way.
            String problem = contents.problemWith(name);
            if (problem != null) {
                return "'" + name + "' will not play: " + problem;
            }

            return "Nothing called '" + name + "' - drop a .gif in plugins/MapGUI/videos, "
                    + "or pick one of the suggestions";
        }

        // The previous placement goes only now that the new one is known to be good, so a mistyped name does
        // not cost a placement in progress. Registered before any sweep, or the frames just decoded for it
        // would be the ones let go.
        stopPlacing(player);
        placing.put(player.getUniqueId(), new WallPlacement(player, walls, content, name, range));
        dropUnusedVideos();

        // Claimed ahead of any open menu, since it was asked for later - and no need to close that menu
        // any more, because the router lets both hold a claim at once.
        PacketInput.Handler gestures = new PlacementInput(player);
        placementInputs.put(player.getUniqueId(), gestures);
        router.claim(player, gestures);
        return null;
    }

    /**
     * Placement gestures, read off the connection rather than from events: the preview's own frames sit in
     * front of the wall, so the client reports an attack on an entity the server does not have and no
     * interact event is raised. Right-click comes through here too, which stops it placing a block.
     */
    private final class PlacementInput implements PacketInput.Handler {

        private final Player player;

        private PlacementInput(Player player) {
            this.player = player;
        }

        /** Placement takes everything too - while sizing a wall, a click must not also mine or place. */
        @Override
        public boolean leftClick() {
            onMainThread(() -> confirm(player));
            return true;
        }

        @Override
        public boolean rightClick() {
            onMainThread(() -> {
                cancelPlacing(player);
                player.sendRichMessage("<gray>Canceled.");
            });
            return true;
        }

        @Override
        public boolean drop() {
            onMainThread(() -> cancelPlacing(player));
            return true;
        }
    }

    private void onMainThread(Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    public boolean isPlacing(Player player) {
        return placing.containsKey(player.getUniqueId());
    }

    public void cancelPlacing(Player player) {
        stopPlacing(player);
        // Previewing a video decodes it, so abandoning the placement is the other way to end up holding frames
        // nothing shows. Confirming reaches here too, by which point the wall is saved and keeps its own.
        dropUnusedVideos();
    }

    /** The taking-down half, without the sweep - so starting a replacement can register it first. */
    private void stopPlacing(Player player) {
        WallPlacement placement = placing.remove(player.getUniqueId());
        if (placement == null) return;

        placement.cancel();
        PacketInput.Handler gestures = placementInputs.remove(player.getUniqueId());
        if (gestures != null) {
            router.release(player, gestures);
        }
    }

    /** A left click while placing: the first anchors, the second commits. Only reachable with no preview map in the way. */
    public void click(Player player, Block block, BlockFace face) {
        WallPlacement placement = placing.get(player.getUniqueId());
        if (placement == null) return;

        if (!placement.anchored()) {
            placement.anchor(block, face, System.currentTimeMillis());
        } else {
            confirm(player);
        }
    }

    private void confirm(Player player) {
        WallPlacement placement = placing.get(player.getUniqueId());
        if (placement == null || !placement.anchored()) return;

        WallLayout layout = placement.confirm();
        if (layout == null) return;

        String name = unusedName(placement.contentName());
        store.put(name, new WallStore.Placed(player.getWorld().getUID(),
                layout.anchorX(), layout.anchorY(), layout.anchorZ(), layout.facing(),
                layout.cols(), layout.rows(), placement.contentName())
        );

        // The preview's frames must go before the real ones arrive, or both sets sit in the same blocks.
        cancelPlacing(player);
        player.sendRichMessage("<green>Placed <white>" + name + "</white> - " + layout.cols() + "x" + layout.rows() + " maps.");
    }

    private String unusedName(String content) {
        String base = content.toLowerCase(Locale.ROOT).replaceAll("\\.[^.]+$", "").replaceAll("[^a-z0-9]+", "-");
        for (int i = 1; ; i++) {
            String name = base + "-" + i;
            if (!store.has(name)) return name;
        }
    }

    // ---- listing and removing ----

    public List<String> names() {
        return new ArrayList<>(store.names());
    }

    /** The ones actually up, for anything reporting on what they cost. */
    public Map<String, WallDisplay> showing() {
        return Map.copyOf(live);
    }

    /** One line per wall, positioned at its middle so clicking through puts you in front of the picture rather than at a corner. */
    public List<Component> describe() {
        List<Component> lines = new ArrayList<>();

        store.all().forEach((name, wall) -> {
            World world = Bukkit.getWorld(wall.world());
            WallLayout layout = layoutOf(wall);

            lines.add(Component.text("  " + name + "  ", NamedTextColor.WHITE)
                    .append(Component.text(wall.cols() + "x" + wall.rows() + "  " + wall.content()
                            + "  " + (world == null ? "unloaded world" : world.getName()) + "  ",
                            NamedTextColor.DARK_GRAY))
                    .append(live.containsKey(name)
                            ? Component.empty()
                            : Component.text("not showing  ", NamedTextColor.RED))
                    .append(Coordinates.link(layout.centerX(), layout.centerY(), layout.centerZ()))
            );
        });
        return lines;
    }

    /**
     * The nearest wall to a player, removed, or null when none is within {@link #NEAREST_RANGE}.
     *
     * <p>Bounded so standing nowhere near anything cannot quietly take down a wall across the world.
     */
    @Nullable
    public String removeNearest(Player player) {
        UUID world = player.getWorld().getUID();
        String closest = null;
        double best = (double) NEAREST_RANGE * NEAREST_RANGE;

        for (Map.Entry<String, WallStore.Placed> entry : store.all().entrySet()) {
            if (!entry.getValue().world().equals(world)) continue;

            WallLayout layout = layoutOf(entry.getValue());
            double dx = player.getX() - layout.centerX();
            double dy = player.getY() - layout.centerY();
            double dz = player.getZ() - layout.centerZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance > best) continue;

            best = distance;
            closest = entry.getKey();
        }

        if (closest != null) {
            remove(closest);
        }
        return closest;
    }

    /**
     * Takes down every wall showing something that has stopped being placeable, without forgetting where
     * they were.
     *
     * <p>Called the moment a plugin unregisters, normally while it is disabling, so its screens close while
     * its classes are still loaded. They stay in {@code walls.yml} and come back if the plugin does.
     */
    public void hideContent(String content) {
        store.all().forEach((name, wall) -> {
            if (!wall.content().equals(content)) return;

            WallDisplay showing = live.remove(name);
            if (showing != null) {
                showing.close();
            }
        });
    }

    public boolean remove(String name) {
        if (!store.remove(name)) return false;

        WallDisplay wall = live.remove(name);
        if (wall != null) {
            wall.close();
        }
        dropUnusedVideos();
        return true;
    }

    /**
     * Lets go of decoded frames nothing is asking for any more.
     *
     * <p>Wanted is what a wall could still need: every saved wall's content, and whatever is being placed right
     * now. Called when that set shrinks - a wall removed, a placement abandoned - rather than on a timer, since
     * those are the only two things that can shrink it and both are rare.
     */
    private void dropUnusedVideos() {
        Set<String> wanted = new HashSet<>();
        for (WallStore.Placed wall : store.all().values()) wanted.add(wall.content());
        for (WallPlacement placement : placing.values()) wanted.add(placement.contentName());

        int dropped = contents.retainOnly(wanted);
        if (dropped > 0) {
            plugin.getSLF4JLogger().info("Let go of {} decoded video(s) nothing is showing any more", dropped);
        }
    }

    private static WallLayout layoutOf(WallStore.Placed placed) {
        return WallLayout
                .anchoredAt(placed.x(), placed.y(), placed.z(), placed.facing())
                .resized(placed.cols(), placed.rows());
    }

    // ---- tuning ----

    /** Applied to walls already up as well, so an admin can throttle a server that is struggling. */
    public void retune(int fps, int range) {
        this.fps = fps;
        this.range = range;
        for (WallDisplay wall : live.values()) {
            wall.fps(fps);
            wall.range(range);
        }
    }

    /** Everything placeable right now, for tab completion. */
    public List<String> contentNames() {
        return contents.names();
    }

    /** The same, as lines to read, since a registered menu is not discoverable any other way. */
    public List<Component> describeContents() {
        return contents.describe();
    }
}
