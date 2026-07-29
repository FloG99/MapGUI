package de.flog99.mapgui.plugin;

import de.flog99.mapgui.MapSurface;
import de.flog99.mapgui.MapTransport;
import de.flog99.mapgui.MapIds;
import de.flog99.mapgui.Marker;
import de.flog99.mapgui.Session;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A single map held in the hotbar: 128x128, one viewer, and none of it real.
 *
 * <p>The item is only ever sent to the client, so there is nothing to hand back or clean up. The one piece
 * of real server state involved is the selected slot, which is the one thing {@link #close} undoes.
 */
final class HeldMapDisplay {

    static final int SIZE = 128;

    private final MapTransport transport;
    private final Map<UUID, Held> held = new HashMap<>();

    /**
     * The item is kept so it can be re-sent without rebuilding it. The slot is remembered because the
     * player's scrolling was menu input rather than a decision to change slots.
     */
    private record Held(int previousSlot, int mapId, ItemStack item) {
    }

    HeldMapDisplay(MapTransport transport) {
        this.transport = transport;
    }

    void open(Session session) {
        Player player = session.player();
        held.put(player.getUniqueId(), new Held(player.getInventory().getHeldItemSlot(), MapIds.next(), mapItem(session)));

        reassert(player);
    }

    void close(Session session) {
        Player player = session.player();
        Held entry = held.remove(player.getUniqueId());
        if (entry == null) return;

        transport.hideMapItem(player);
        player.getInventory().setHeldItemSlot(entry.previousSlot());
    }

    void show(Session session, MapSurface surface, List<Marker> markers) {
        Held entry = held.get(session.player().getUniqueId());
        if (entry != null) {
            transport.sendMap(session.player(), entry.mapId(), surface, markers);
        }
    }

    /** Re-issued when the top screen changes, so the item name follows the title. */
    void refresh(Session session) {
        Player player = session.player();
        Held entry = held.get(player.getUniqueId());
        if (entry == null) return;

        held.put(player.getUniqueId(), new Held(entry.previousSlot(), entry.mapId(), mapItem(session)));
        reassert(player);
    }

    /**
     * Re-states the faked slots, which look after themselves while the wrapper is installed - so this is
     * only for what it cannot cover: a prompt with an inventory of its own, and a change of title.
     */
    void reassert(Player player) {
        Held entry = held.get(player.getUniqueId());
        if (entry == null) return;

        transport.showMapItem(player, entry.item(), entry.mapId());
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
