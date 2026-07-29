package de.flog99.mapgui.plugin;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Turns ordinary player input into menu input.
 *
 * <p>Everything bails out for players without a menu open, and while a prompt is up: a suspended session
 * has to let clicks through so the prompt's own inventory stays usable.
 */
final class InputListeners implements Listener {

    private final MapGuiPlugin plugin;

    InputListeners(MapGuiPlugin plugin) {
        this.plugin = plugin;
    }

    private PlayerSession active(Player player) {
        PlayerSession session = plugin.sessions().session(player);
        return session == null || session.suspended() ? null : session;
    }

    /**
     * The scroll wheel reaches us only as a hotbar slot change, so it is read as one.
     *
     * <p>Deliberately not canceled. Every slot shows the same map, so which is selected changes nothing
     * visible, and letting it through keeps the server in step with the client - which is what makes the
     * notch count exact. Refusing it froze the server a slot behind, so a three-notch flick arrived as
     * 1 + 2 + 3. The slot the player started on is put back when the menu closes.
     */
    @EventHandler
    public void onHotbarChange(PlayerItemHeldEvent event) {
        PlayerSession session = active(event.getPlayer());
        if (session == null) return;

        session.scroll(Hotbar.notches(event.getPreviousSlot(), event.getNewSlot()));
    }

    /**
     * Left-click presses whatever the cursor is on. Right-click arrives as a packet instead, since the event
     * behind it only fires when the player has a real item in that slot.
     *
     * <p>Left-click plays the arm swing, so the map visibly drops on every press. The client starts that
     * before the server hears the click, so canceling cannot stop it.
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        PlayerSession session = active(event.getPlayer());
        if (session == null) return;

        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) return;

        event.setCancelled(true);
        session.leftClick();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (active(player) == null) return;

        event.setCancelled(true);
    }

    /**
     * Opening the inventory closes whatever menu the client had open, and in creative that leaves its view of
     * the faked slots stale - the map goes missing while the session is still running. Restated on the next
     * tick rather than here, since the close is still going through.
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        PlayerSession session = active(player);
        if (session != null) {
            session.reassertSoon();
        }
    }

    /** The offhand is reported empty to keep the map two-handed, so there is nothing to swap with. */
    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (active(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    // Cancelling the interact event stops the click, but not a held-down dig, so block damage is
    // refused too. Cheaper and far less invasive than switching the player's game mode.
    @EventHandler(priority = EventPriority.LOW)
    public void onBlockDamage(BlockDamageEvent event) {
        if (active(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockBreak(BlockBreakEvent event) {
        if (active(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * Right-click is the activate button, so reaching an entity with it is refused outright - otherwise
     * selecting a menu row could open a villager's trades or hang the player's real item in a frame.
     */
    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (active(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    /** Nothing of ours is dropped on death, since nothing of ours was ever in the inventory. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        plugin.sessions().close(event.getPlayer(), false);
    }

    /** The router owns the claim bookkeeping, so it clears the player outright rather than trusting every subsystem to tidy up. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.sessions().close(event.getPlayer(), true);
        plugin.router().releaseAll(event.getPlayer());
    }
}
