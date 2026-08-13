package de.flog99.mapgui.plugin;

import de.flog99.mapgui.GuiCatalog;
import de.flog99.mapgui.HandOptions;
import de.flog99.mapgui.MapIds;
import de.flog99.mapgui.Screen;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.MapId;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Real map items, and the screens they open for whoever is holding them.
 *
 * <p>The item is a key rather than a screen, which is the whole idea: a phone left in a chest shows its finder
 * <i>their</i> phone, and a remote handed to a friend works for the friend. So what the item remembers is a name,
 * not an object - a name survives in NBT and a {@link Screen} does not - and every holder is given a screen built
 * fresh from the factory that name was registered with.
 *
 * <p>Three things ride on the stack. The map id goes in vanilla's own {@code map_id} component, because that is
 * what makes a client look up pixels for an id at all. The GUI name and the focus mode go in the persistent data
 * container, which Paper stores under {@code minecraft:custom_data}. Nothing else is worth keeping: an item's slot
 * and hand are wherever the player last put them, and an item is movable and offhandable by being an item.
 *
 * <p>Who is holding what is <b>swept</b> rather than listened for. There are a dozen ways an item reaches a hand -
 * scrolled to, swapped, dragged, picked up off the floor, handed over, respawned with - and a listener per route is
 * a listener per route to get wrong. A sweep asks the only question that matters, which hand holds one, and asks it
 * of every player every tick. It costs a material comparison per hand: the persistent data is only read once the
 * item is a filled map, which almost nobody is carrying.
 */
final class HandItems {

    private final MapGuiPlugin plugin;
    private final NamespacedKey guiKey;
    private final NamespacedKey focusKey;
    private final NamespacedKey ownKey;

    /**
     * Which GUI each player's session was opened for, so a swap to a different one is noticed.
     *
     * <p>By name, since a pinned map id is shared by every copy. Swapping between two of the same screen therefore
     * leaves it alone, which keeps its scroll position.
     */
    private final Map<UUID, String> showing = new HashMap<>();

    /**
     * Players whose screen was closed while they were still holding the item.
     *
     * <p>Without this the sweep would open it again on the next tick, and a screen that closes itself - a button
     * that says "done" - could never be closed at all. Cleared the moment the item leaves their hands, so putting
     * it away and taking it out again turns it back on.
     */
    private final Set<UUID> dismissed = new HashSet<>();

    HandItems(MapGuiPlugin plugin) {
        this.plugin = plugin;
        this.guiKey = new NamespacedKey(plugin, "gui");
        this.focusKey = new NamespacedKey(plugin, "focus");
        this.ownKey = new NamespacedKey(plugin, "own");
    }

    /**
     * Mints an item bound to a registered GUI.
     *
     * <p>Left unnamed, so it reads as "Map" until the caller says otherwise - a phone should be called a phone, and
     * only its author knows that. Rename the stack that comes back.
     *
     * @throws IllegalArgumentException if nothing openable is registered under that name, since an item nobody can
     *                                  build a screen from would be a filled map that never draws anything
     */
    ItemStack mint(String gui, HandOptions hand) {
        GuiCatalog.Entry entry = plugin.guis().get(gui);
        if (entry == null || !entry.openable()) {
            throw new IllegalArgumentException("No openable GUI is registered as \"" + gui + "\"");
        }

        ItemStack item = new ItemStack(Material.FILLED_MAP);
        // The id vanilla renders by. Deliberately not a MapView: nothing about this map exists on the server, so
        // there is no view to attach and no id for the world to keep.
        //
        // Stamped once and then carried by the stack for good, which is why a pinned id belongs here as much as on
        // the session: a pack keying on the id has to recognise the item sitting in a chest, not only the open one.
        int mapId = hand.mapId() == HandOptions.ANY_MAP_ID ? MapIds.next() : hand.mapId();
        item.setData(DataComponentTypes.MAP_ID, MapId.mapId(mapId));
        item.editMeta(MapMeta.class, meta -> {
            meta.getPersistentDataContainer().set(guiKey, PersistentDataType.STRING, gui);
            meta.getPersistentDataContainer().set(focusKey, PersistentDataType.STRING, hand.focus().name());
        });
        return item;
    }

    /** The GUI an item opens, or null for an item that is not one of ours. */
    @Nullable
    String guiOf(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP || !item.hasItemMeta()) return null;

        return item.getItemMeta().getPersistentDataContainer().get(guiKey, PersistentDataType.STRING);
    }

    /** How the item asked to be focused, falling back to the server's setting for anything unreadable. */
    private HandOptions.Focus focusOf(ItemStack item) {
        String stored = item.getItemMeta().getPersistentDataContainer().get(focusKey, PersistentDataType.STRING);
        if (stored == null) return plugin.config().hand().focus();

        try {
            return HandOptions.Focus.valueOf(stored.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // An item minted by a version that had a mode this one does not. Its screen still works.
            return plugin.config().hand().focus();
        }
    }

    /**
     * A map item belonging to one session and nothing else.
     *
     * <p>What {@code open(player, screen, HandOptions.item())} hands over: a real item for one player and one
     * screen, with no registered name behind it - so when it leaves them, the screen simply ends rather than
     * opening for whoever picked it up. The sweep ignores it for exactly that reason, since it has no GUI to name.
     *
     * <p>The token is how the session knows this stack from any other - not the map id, which a pinned screen
     * shares between every copy.
     */
    ItemStack blank(int mapId, UUID own) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        item.setData(DataComponentTypes.MAP_ID, MapId.mapId(mapId));
        item.editMeta(MapMeta.class, meta -> meta.getPersistentDataContainer().set(ownKey, PersistentDataType.STRING, own.toString()));
        return item;
    }

    /** The session a bare item was handed over for, or null for anything that is not one. */
    @Nullable
    UUID ownOf(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP || !item.hasItemMeta()) return null;

        String stored = item.getItemMeta().getPersistentDataContainer().get(ownKey, PersistentDataType.STRING);
        try {
            return stored == null ? null : UUID.fromString(stored);
        } catch (IllegalArgumentException e) {
            // Somebody's edited item. It is not one of ours, which is all this had to answer.
            return null;
        }
    }

    /** The map id stamped into an item, or -1 for anything that is not a map with one. */
    static int mapIdOf(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) return -1;

        MapId stamped = item.getData(DataComponentTypes.MAP_ID);
        return stamped == null ? -1 : stamped.id();
    }

    /** Reconciles every online player against what they are holding. */
    void sweep() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            reconcile(player);
        }
    }

    private void reconcile(Player player) {
        UUID id = player.getUniqueId();
        PlayerSession session = plugin.sessions().session(player);
        boolean mine = session != null && session.hand().carry() == HandOptions.Carry.ITEM;

        // A screen on a real item lives as long as the item is carried - not as long as it is held, which would end
        // it every time the player reached for their sword and hand back a blank one when they came back. Asked of
        // the display rather than of the item, because it knows the id its session draws on: that covers an item
        // minted for one player and bound to no GUI as well as it covers a phone that has changed hands.
        if (mine && !plugin.display().carries(player)) {
            showing.remove(id);
            dismissed.remove(id);
            plugin.sessions().close(player, true);
            return;
        }

        ItemStack held = ours(player);
        if (held == null) {
            dismissed.remove(id);
            showing.remove(id);
            return;
        }

        // Somebody else's screen is up - a popup a command opened, a menu another plugin put up. It has the whole
        // hotbar, so the item underneath is not being held in any sense that matters, and taking it over would mean
        // nobody carrying a phone could ever be shown anything else. Picked up again when that screen closes.
        if (session != null && !mine) {
            showing.remove(id);
            return;
        }

        int mapId = mapIdOf(held);
        String open = showing.get(id);
        if (open != null && open.equals(guiOf(held))) {
            // Ours, and gone under us: closed by the screen itself, by a command, by another plugin. Left closed
            // until the item is put down, or "done" would be a button that does nothing.
            if (session == null) {
                showing.remove(id);
                dismissed.add(id);
            }
            return;
        }
        if (dismissed.contains(id)) return;

        open(player, held, mapId);
    }

    private void open(Player player, ItemStack held, int mapId) {
        String gui = guiOf(held);
        GuiCatalog.Entry entry = gui == null ? null : plugin.guis().get(gui);
        if (entry == null || !entry.openable()) {
            // An item for a GUI whose plugin is not loaded. Nothing to draw and nothing to say every tick about it.
            return;
        }

        Screen screen = entry.open().apply(player);
        if (screen == null) return;

        // Recorded after the open, not before: opening closes whatever was up first, and that close is what would
        // have thrown this away again.
        plugin.sessions().openCarried(player, screen, HandOptions.item().focus(focusOf(held)), gui, mapId);
        showing.put(player.getUniqueId(), gui);
    }

    /** The item of ours in either hand, main hand first, or null for a player holding none. */
    @Nullable
    private ItemStack ours(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (guiOf(main) != null) return main;

        ItemStack off = player.getInventory().getItemInOffHand();
        return guiOf(off) != null ? off : null;
    }

    /** So a session closing for its own reasons does not leave the sweep thinking one is still up. */
    void forget(Player player) {
        showing.remove(player.getUniqueId());
        dismissed.remove(player.getUniqueId());
    }
}
