package de.flog99.mapgui.plugin;

import de.flog99.mapgui.HandOptions;
import de.flog99.mapgui.HeldTrigger;
import de.flog99.mapgui.OpenOptions;
import de.flog99.mapgui.Screen;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Screens that open while a player holds somebody else's item.
 *
 * <p>The same idea as {@link HandItems} and the other half of it: there the map is the item, here the item is a
 * plugin's own and the map is drawn wherever its {@link HandOptions} say. A camera in the main hand with its
 * viewfinder in the offhand is the case this was written for.
 *
 * <p>Swept rather than listened for, for the reason {@link HandItems} gives: an item reaches a hand a dozen ways and
 * a listener per route is a listener per route to get wrong. Both sweeps ride the same tick.
 */
final class HeldTriggers {

    private record Trigger(Predicate<ItemStack> item, HandOptions hand, Function<Player, Screen> factory) {
    }

    private final MapGuiPlugin plugin;

    /** Copy-on-write because registering is allowed from any thread and the sweep reads it every tick. */
    private final List<Trigger> triggers = new CopyOnWriteArrayList<>();

    /** Which trigger opened what each player has up, so a swap to a different trigger item is noticed. */
    private final Map<UUID, Trigger> showing = new HashMap<>();

    /**
     * Players whose screen was closed while they were still holding the item. Without this the sweep would open it
     * again on the next tick, and a button that says "done" could never be done.
     */
    private final Set<UUID> dismissed = new HashSet<>();

    HeldTriggers(MapGuiPlugin plugin) {
        this.plugin = plugin;
    }

    HeldTrigger add(Predicate<ItemStack> item, HandOptions hand, Function<Player, Screen> factory) {
        Trigger trigger = new Trigger(item, hand.sane(), factory);
        triggers.add(trigger);
        return () -> remove(trigger);
    }

    /** Closes whatever this trigger has open before dropping it, since its factory is about to be unloaded. */
    private void remove(Trigger trigger) {
        triggers.remove(trigger);

        for (Map.Entry<UUID, Trigger> open : new ArrayList<>(showing.entrySet())) {
            Player player = plugin.getServer().getPlayer(open.getKey());
            if (open.getValue() == trigger && player != null) {
                forget(player);
                plugin.sessions().close(player, true);
            }
        }
    }

    void sweep() {
        if (triggers.isEmpty()) return;

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            reconcile(player);
        }
    }

    private void reconcile(Player player) {
        UUID id = player.getUniqueId();
        Trigger wanted = matching(player);
        Trigger open = showing.get(id);

        if (open != null) {
            // Ours and gone under us: closed by the screen itself, by a command, by another plugin. Left closed
            // until the item is put down, or "done" would be a button that does nothing.
            if (open == wanted && plugin.sessions().session(player) == null) {
                showing.remove(id);
                dismissed.add(id);
                return;
            }
            if (open == wanted) return;

            showing.remove(id);
            dismissed.remove(id);
            plugin.sessions().close(player, true);
        }

        if (wanted == null) {
            dismissed.remove(id);
            return;
        }
        if (dismissed.contains(id)) return;

        // Somebody else's screen is up - a popup a command opened, a menu another plugin put up. Taking it over
        // would mean nobody carrying a trigger item could be shown anything else. Picked up when that one closes.
        if (plugin.sessions().session(player) != null) return;

        open(player, wanted);
    }

    private void open(Player player, Trigger trigger) {
        Screen screen = trigger.factory().apply(player);
        if (screen == null) return;

        // Recorded after the open, since opening closes whatever was up first and that close is what would have
        // thrown this away again.
        plugin.sessions().open(player, screen, OpenOptions.of(trigger.hand()));
        showing.put(player.getUniqueId(), trigger);
    }

    /**
     * The first trigger that accepts the main hand, or null. Main hand only: a trigger found in the offhand would be
     * a screen drawn over the very item that opened it.
     */
    private Trigger matching(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main.isEmpty()) return null;

        for (Trigger trigger : triggers) {
            if (trigger.item().test(main)) return trigger;
        }
        return null;
    }

    /** So a session closing for its own reasons does not leave the sweep thinking one is still up. */
    void forget(Player player) {
        showing.remove(player.getUniqueId());
        dismissed.remove(player.getUniqueId());
    }
}
