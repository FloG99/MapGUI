package de.flog99.mapgui.plugin;

import de.flog99.mapgui.HandOptions;
import de.flog99.mapgui.MapSlots;
import de.flog99.mapgui.MapSurface;
import de.flog99.mapgui.MapTransport;
import de.flog99.mapgui.Marker;
import de.flog99.mapgui.Session;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.UUID;

/**
 * One map a player carries: 128x128, one viewer, and - unless it is a real item - none of it real.
 *
 * <p>Owns <b>where</b> the map is, which is only a question for the carry modes that put it in a single slot. A
 * popup is in every hotbar slot and never moves; the others can be moved, and for a fake one that move is not a
 * move at all - the server's inventory never changes, only which slot is lied about. That is the whole benefit of
 * faking it, and it is why {@link #moveTo} is a field assignment rather than an inventory operation.
 *
 * <p>A real item is the exception in both directions: nothing is faked, because the item genuinely is in the slot,
 * and nothing has to be moved, because the player moving it is the real thing happening.
 */
final class HeldMapDisplay {

    static final int SIZE = 128;

    private final MapTransport transport;
    private final Map<UUID, Held> held = new HashMap<>();

    /**
     * @param previousSlot the slot the player was on before a popup took the hotbar over, since their scrolling
     *                     was menu input rather than a decision to change slots. Meaningless for the other modes,
     *                     which never took the wheel away
     * @param hand         which hand the map is in: the main one meaning {@code slot}, or the offhand
     * @param item         what the client is told the faked slots hold, and null for a real item, which needs
     *                     nothing told about it
     * @param mapId        what the client is sent pixels under, which is the item's own for a real one
     * @param mine         whether a stack is the one this session belongs to, which {@code mapId} cannot answer
     *                     once a screen pins one - see {@link PlayerSession#mine}
     */
    private record Held(HandOptions options, int previousSlot, int slot, EquipmentSlot hand, int mapId,
                        Predicate<ItemStack> mine, @Nullable ItemStack item, boolean minted) {

        Held at(int slot, EquipmentSlot hand) {
            return new Held(options, previousSlot, slot, hand, mapId, mine, item, minted);
        }

        Held showing(@Nullable ItemStack item) {
            return new Held(options, previousSlot, slot, hand, mapId, mine, item, minted);
        }

        boolean real() {
            return options.carry() == HandOptions.Carry.ITEM;
        }
    }

    HeldMapDisplay(MapTransport transport) {
        this.transport = transport;
    }

    /**
     * Starts carrying the map.
     *
     * @param mapId   the id to draw under. A real item's own, since the client looks up pixels by the id stamped
     *                into the item, and freshly invented for everything else
     * @param mine    which stack in the inventory this session's screen belongs to
     * @param carried the item to hand over if the player has none of their own, for a real carry and null otherwise
     */
    void open(Session session, HandOptions options, int mapId, Predicate<ItemStack> mine, @Nullable ItemStack carried) {
        Player player = session.player();
        int selected = player.getInventory().getHeldItemSlot();
        EquipmentSlot hand = options.reachesMainHand() ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        boolean real = options.carry() == HandOptions.Carry.ITEM;

        // A real item has to exist, and may already: the holder of a phone found in a chest is holding it before
        // any of this runs, and handing them a second one would be a duplicate of the thing they are carrying.
        boolean minted = real && !alreadyCarrying(player, mine);
        if (minted) {
            hand(player, carried);
        }

        held.put(player.getUniqueId(), new Held(options, selected, startingSlot(options, selected), hand, mapId, mine,
                real ? null : mapItem(session), minted));

        // Selected as well as placed, for a pinned map: the caller asked for the screen to be shown, and a map in
        // slot 3 while the player holds slot 0 is not being shown to anybody.
        if (options.carry() == HandOptions.Carry.PINNED && options.slot() != selected) {
            player.getInventory().setHeldItemSlot(options.slot());
        }
        reassert(player);
    }

    /**
     * Whether the player still has the map this session draws on - anywhere, not necessarily in a hand.
     *
     * <p>The lifetime question, as against the focus question {@link #holding} answers. They have to be different:
     * a screen that ended every time the player reached for their sword would lose its scroll position, its page and
     * everything else it had, and get it back as a blank one. So a real item's screen lives as long as it is carried
     * and has the mouse only while it is held.
     */
    boolean carries(Player player) {
        Held entry = held.get(player.getUniqueId());
        return entry != null && (!entry.real() || alreadyCarrying(player, entry.mine()));
    }

    private static boolean alreadyCarrying(Player player, Predicate<ItemStack> mine) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (mine.test(stack)) return true;
        }
        return false;
    }

    /**
     * Puts an item into the player's hand, which is what "here, hold this" has to mean.
     *
     * <p>Into the selected slot rather than the first free one, because a screen opened for a real item is only a
     * screen while it is being held - dropped into a spare slot it would be a session with nothing showing. Whatever
     * was in the slot goes back to the player, and onto the floor if they have no room, which is what vanilla does
     * with anything that will not fit.
     */
    private static void hand(Player player, ItemStack item) {
        int slot = player.getInventory().getHeldItemSlot();
        ItemStack displaced = player.getInventory().getItem(slot);
        player.getInventory().setItem(slot, item);

        if (displaced != null && !displaced.isEmpty()) {
            player.getInventory().addItem(displaced).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
    }

    /** Where a map that lives in one slot starts. Meaningless for a popup, which is in all of them. */
    private static int startingSlot(HandOptions options, int selected) {
        return options.slot() >= 0 && options.slot() < Hotbar.SLOTS ? options.slot() : selected;
    }

    void close(Session session) {
        Player player = session.player();
        Held entry = held.remove(player.getUniqueId());
        if (entry == null) return;

        transport.hideMapItem(player);
        // Only a popup took the wheel away, so only a popup owes the slot back. Restoring it after a pinned map
        // would undo the player's own last scroll.
        if (entry.options().fillsHotbar()) {
            player.getInventory().setHeldItemSlot(entry.previousSlot());
        }
        // Taken back only if it was handed over here. An item somebody found is theirs, and closing its screen
        // must not confiscate it - putting a phone away is not losing it.
        if (entry.minted()) {
            removeCarried(player, entry.mine());
        }
    }

    private static void removeCarried(Player player, Predicate<ItemStack> mine) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (mine.test(contents[slot])) {
                player.getInventory().setItem(slot, null);
                return;
            }
        }
    }

    void show(Session session, MapSurface surface, List<Marker> markers) {
        Player player = session.player();
        Held entry = held.get(player.getUniqueId());
        if (entry == null) return;

        // A frame is several packets when what changed is scattered, and a map that goes up in pieces tears
        // for the same reason a wall does - half the new screen over half the old one.
        transport.bundled(player, () -> transport.sendMap(player, entry.mapId(), surface, markers));
    }

    /** Re-issued when the top screen changes, so the item name follows the title. A real item keeps its own name. */
    void refresh(Session session) {
        Player player = session.player();
        Held entry = held.get(player.getUniqueId());
        if (entry == null || entry.real()) return;

        held.put(player.getUniqueId(), entry.showing(mapItem(session)));
        reassert(player);
    }

    /**
     * Moves the map to another slot, or between the hands, without moving anything on the server.
     *
     * <p>Only for a faked map. A real item is moved by the player and the server both, and there is nothing here
     * to keep in step with that beyond noticing where it went.
     *
     * @return false when the move is not allowed, which is the caller's cue to refuse the gesture that asked for it
     */
    boolean moveTo(Player player, int slot, EquipmentSlot hand) {
        Held entry = held.get(player.getUniqueId());
        if (entry == null || entry.real() || entry.options().fillsHotbar()) return false;

        HandOptions options = entry.options();
        if (hand == EquipmentSlot.OFF_HAND && !options.reachesOffhand()) return false;
        if (hand == EquipmentSlot.HAND && !options.reachesMainHand()) return false;
        if (hand == EquipmentSlot.HAND && slot != entry.slot() && !options.movable()) return false;
        if (hand == entry.hand() && slot == entry.slot()) return false;

        held.put(player.getUniqueId(), entry.at(slot, hand));
        reassert(player);
        return true;
    }

    /** Which hand the map is in, or null for a player carrying none. */
    @Nullable
    EquipmentSlot handOf(Player player) {
        Held entry = held.get(player.getUniqueId());
        return entry == null ? null : entry.hand();
    }

    /** Which hotbar slot the map occupies, or -1 for a player carrying none. Meaningless while it is in the offhand. */
    int slotOf(Player player) {
        Held entry = held.get(player.getUniqueId());
        return entry == null ? -1 : entry.slot();
    }

    /**
     * Which hand is actually holding the map at this moment, or null when neither is.
     *
     * <p>Not the same as {@link #handOf}: a map pinned to hotbar slot 3 is in the main hand only while slot 3 is
     * the selected one, and is in neither hand while the player holds their sword. That is the difference between
     * where the map lives and what the player has in their hands, and focus turns on the second.
     */
    @Nullable
    EquipmentSlot holding(Player player) {
        Held entry = held.get(player.getUniqueId());
        if (entry == null) return null;
        if (entry.options().fillsHotbar()) return EquipmentSlot.HAND;

        // A real item is wherever the player last put it, so the inventory is asked rather than remembered. Which
        // it has to be: it can be swapped, dragged, dropped and picked up again, and none of that goes through here.
        if (entry.real()) {
            if (entry.mine().test(player.getInventory().getItemInMainHand())) return EquipmentSlot.HAND;
            if (entry.mine().test(player.getInventory().getItemInOffHand())) return EquipmentSlot.OFF_HAND;

            return null;
        }

        if (entry.hand() == EquipmentSlot.OFF_HAND) return EquipmentSlot.OFF_HAND;

        return player.getInventory().getHeldItemSlot() == entry.slot() ? EquipmentSlot.HAND : null;
    }

    /**
     * Re-states the faked slots, which look after themselves while the wrapper is installed - so this is
     * only for what it cannot cover: a prompt with an inventory of its own, a change of title, and a move.
     */
    void reassert(Player player) {
        Held entry = held.get(player.getUniqueId());
        if (entry == null || entry.item() == null) return;

        transport.showMapItem(player, entry.item(), entry.mapId(), slots(entry));
    }

    private static MapSlots slots(Held entry) {
        if (entry.options().fillsHotbar()) return MapSlots.wholeHotbar();

        return entry.hand() == EquipmentSlot.OFF_HAND ? MapSlots.offhandOnly() : MapSlots.hotbar(entry.slot());
    }

    void forget(Player player) {
        held.remove(player.getUniqueId());
    }

    /** No map view to attach - the transport stamps the id on its way out. */
    private static ItemStack mapItem(Session session) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        item.editMeta(meta -> meta.displayName(session.screen().title()));
        return item;
    }
}
