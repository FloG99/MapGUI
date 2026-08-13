package de.flog99.mapgui.plugin;

import de.flog99.mapgui.HandOptions;
import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.MapIds;
import de.flog99.mapgui.camera.Camera;
import de.flog99.mapgui.MapTransport;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.GuiCatalog;
import de.flog99.mapgui.HeldTrigger;
import de.flog99.mapgui.Session;
import de.flog99.mapgui.WallDisplay;
import de.flog99.mapgui.map.MapPrinter;
import de.flog99.mapgui.prompt.PromptRegistry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

final class SessionManager implements MapGui {

    private final MapGuiPlugin plugin;
    private final Map<UUID, PlayerSession> sessions = new HashMap<>();
    private final HeldMapDisplay display;
    private final WallRegistry walls;

    SessionManager(MapGuiPlugin plugin, WallRegistry walls) {
        this.plugin = plugin;
        this.walls = walls;
        this.display = new HeldMapDisplay(plugin.transport());
    }

    @Override
    public Session open(Player player, Screen screen) {
        return from(player, screen, null, null);
    }

    @Override
    public Session open(Player player, Screen screen, HandOptions hand) {
        return from(player, screen, hand, null);
    }

    /**
     * The same, remembering which catalog entry it came from.
     *
     * <p>Which is what lets {@code /mapgui hand list} name it, and what lets an unregistering plugin's menus be
     * closed while its classes are still loaded - the next repaint of one would otherwise fail to find them.
     */
    Session from(Player player, Screen screen, @Nullable HandOptions hand, @Nullable String entry) {
        HandOptions carried = carry(screen, hand);
        // A pinned id where the screen asked for one, so a resource pack has something to recognise it by, and one
        // nobody else is drawing to otherwise.
        int mapId = carried.mapId() == HandOptions.ANY_MAP_ID ? MapIds.next() : carried.mapId();
        return open(player, screen, carried, entry, mapId);
    }

    /**
     * A screen opened because somebody picked up the item that opens it, drawn under that item's own map id.
     *
     * <p>The id has to be the item's: it is stamped into the stack, and the client looks up pixels by it. Which is
     * also what lets the same item show two players their own screen - map data goes down one connection, so one id
     * can mean different pixels to different people.
     */
    Session openCarried(Player player, Screen screen, HandOptions hand, String entry, int mapId) {
        return open(player, screen, hand.sane(), entry, mapId);
    }

    private Session open(Player player, Screen screen, HandOptions hand, @Nullable String entry, int mapId) {
        close(player, true);

        PlayerSession session = new PlayerSession(plugin, player, display, screen, hand);
        session.openedFrom(entry);
        sessions.put(player.getUniqueId(), session);
        session.start(mapId);
        return session;
    }

    /**
     * How this screen is carried: what the caller said, else what the screen wants, else what the server's config
     * says.
     *
     * <p>The same order everything else here resolves in - the closest opinion to the screen wins, and the config
     * is the floor rather than the ceiling, because carry mode is not a cost to be capped.
     */
    private HandOptions carry(Screen screen, @Nullable HandOptions asked) {
        if (asked != null) return asked.sane();

        HandOptions wanted = screen.hand();
        return (wanted != null ? wanted : plugin.config().hand()).sane();
    }

    @Override
    public ItemStack item(String gui, HandOptions hand) {
        return plugin.handItems().mint(gui, hand);
    }

    @Override
    public HeldTrigger openWhileHolding(Predicate<ItemStack> item, HandOptions.Focus focus, Function<Player, Screen> factory) {
        return plugin.heldTriggers().add(item, HandOptions.offhand().focus(focus), factory);
    }

    @Override
    public ItemStack item(String gui) {
        return item(gui, HandOptions.item().focus(plugin.config().hand().focus()));
    }

    /** Closes every screen opened from a catalog entry that has just been unregistered. */
    void closeShowing(String entry) {
        for (PlayerSession session : List.copyOf(sessions.values())) {
            if (entry.equals(session.openedFrom())) {
                close(session.player(), true);
            }
        }
    }

    @Override
    public void close(Player player) {
        close(player, true);
    }

    void close(Player player, boolean restore) {
        PlayerSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.stop(restore);
        }
    }

    void closeAll() {
        for (PlayerSession session : new ArrayList<>(sessions.values())) {
            session.stop(true);
        }
        sessions.clear();
    }

    @Override
    @Nullable
    public PlayerSession session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    @Override
    public Collection<Session> sessions() {
        return List.copyOf(sessions.values());
    }

    /** The same, typed, for the commands that want more than the API exposes. */
    List<PlayerSession> open() {
        return List.copyOf(sessions.values());
    }

    HeldMapDisplay display() {
        return display;
    }

    @Override
    public WallDisplay.Builder wall() {
        return walls.builder();
    }

    @Override
    public GuiCatalog guis() {
        return plugin.guis();
    }

    @Override
    public MapTransport transport() {
        return plugin.transport();
    }

    @Override
    public Camera camera() {
        return plugin.camera();
    }

    @Override
    public MapPrinter printer() {
        return plugin.printer();
    }

    @Override
    public PromptRegistry prompts() {
        return plugin.prompts();
    }
}
