package de.flog99.mapgui.plugin;

import de.flog99.mapgui.HandOptions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Turns ordinary player input into menu input.
 *
 * <p>Two questions to keep apart, and nearly every handler here asks one of them.
 *
 * <p><b>Is there a session</b> decides whether MapGUI cares at all. <b>Does it have the mouse</b> decides whether
 * the input is the menu's rather than the world's - and for anything but a popup that changes while the session is
 * up, because putting the map away is the point of carrying it. So a handler that stops the player interacting with
 * the world asks {@link #focused}, and only the ones that defend the map itself ask {@link #active}.
 */
final class InputListeners implements Listener {

    private final MapGuiPlugin plugin;

    InputListeners(MapGuiPlugin plugin) {
        this.plugin = plugin;
    }

    /** A session that is up and not behind a prompt. May or may not have the player's mouse. */
    private PlayerSession active(Player player) {
        PlayerSession session = plugin.sessions().session(player);
        return session == null || session.suspended() ? null : session;
    }

    /** The same, only when the screen has the mouse - so its clicks are the menu's and not the world's. */
    private PlayerSession focused(Player player) {
        PlayerSession session = active(player);
        return session != null && session.focused() ? session : null;
    }

    /**
     * The scroll wheel, which means one of two things depending on what the player is carrying.
     *
     * <p>For a popup it is the menu's own scroll. Every slot shows the same map so which is selected changes
     * nothing visible, and the change is deliberately <b>not</b> canceled: letting it through keeps the server in
     * step with the client, which is what makes the notch count exact. Refusing it froze the server a slot behind,
     * so a three-notch flick arrived as 1 + 2 + 3.
     *
     * <p>For a map that is one item among the player's own, the wheel is theirs: scrolling off the map puts it away
     * and scrolling back picks it up. The menu's scroll moves to <b>shift+scroll</b>, which has to be canceled,
     * because shift does not stop the client changing slots - it would scroll the menu and drop the map out of the
     * player's hand in the same motion. Canceling puts the selection back, since CraftBukkit answers a refused
     * change by resending the slot the server still thinks is selected.
     *
     * <p>Which is also why that path counts direction rather than distance. The base never moves, so a fast flick
     * arrives as +1, +2, +3 against the same slot - three notches read as six. The sign of each is one notch each.
     */
    @EventHandler
    public void onHotbarChange(PlayerItemHeldEvent event) {
        PlayerSession session = active(event.getPlayer());
        if (session == null) return;

        if (session.hand().fillsHotbar()) {
            session.scroll(Hotbar.notches(event.getPreviousSlot(), event.getNewSlot()));
            return;
        }

        if (!event.getPlayer().isSneaking() || !session.focused()) return;

        event.setCancelled(true);
        session.scroll(Integer.signum(Hotbar.notches(event.getPreviousSlot(), event.getNewSlot())));
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
        PlayerSession session = focused(event.getPlayer());
        if (session == null) return;

        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) return;

        event.setCancelled(true);
        session.leftClick();
    }

    /**
     * Inventory clicks, which are about defending the map rather than about the mouse.
     *
     * <p>A popup refuses all of them: while it is up the player is in it. A faked map in one slot refuses clicks on
     * that slot only, so the rest of the inventory stays usable - and if it is movable, a number key over its slot
     * moves it there, which is the one drag gesture that needs nothing faked on the cursor. A real item is left
     * entirely alone, because being moved about is what makes it an item.
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        PlayerSession session = active(player);
        if (session == null) return;

        HandOptions hand = session.hand();
        if (hand.fillsHotbar()) {
            event.setCancelled(true);
            return;
        }
        if (!hand.faked() || !onTheMap(player, event)) return;

        event.setCancelled(true);
        if (hand.movable() && event.getClick() == ClickType.NUMBER_KEY) {
            plugin.display().moveTo(player, event.getHotbarButton(), EquipmentSlot.HAND);
        }
    }

    /**
     * Whether a click landed on the map's own slot.
     *
     * <p>Only the player's own inventory is asked about, since that is the only place a faked map ever is. Slot
     * numbers there are the inventory's own - 0 to 8 for the hotbar, 40 for the offhand.
     */
    private boolean onTheMap(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() != player.getInventory()) return false;

        EquipmentSlot hand = plugin.display().handOf(player);
        int slot = event.getSlot();
        return hand == EquipmentSlot.OFF_HAND ? slot == OFFHAND_SLOT : slot == plugin.display().slotOf(player);
    }

    /** Where the offhand sits in a player inventory's own numbering. */
    private static final int OFFHAND_SLOT = 40;

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

    /**
     * Swapping hands, which is asked to mean one of three things.
     *
     * <p>A popup has nothing to swap - the offhand is reported empty to keep the map two-handed - so it is refused.
     * For {@link HandOptions.Focus#SWAP_HANDS} the key is the focus toggle, which is why that mode exists: on an
     * offhand map the key that would have swapped the hands has nothing else to do. Otherwise it moves a faked map
     * between the hands, if the mode allows the offhand at all.
     *
     * <p>Where none of that applies the key is refused and handed to the screen through
     * {@link de.flog99.mapgui.Screen#onSwapHands()}, which is a press that costs the player no aim.
     *
     * <p>A real item is left alone. The server really does swap it, and the sweep notices which hand it landed in.
     */
    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = active(player);
        if (session == null || !session.hand().faked()) return;

        if (session.hand().fillsHotbar()) {
            refuse(event, session);
            return;
        }
        if (session.hand().focus() == HandOptions.Focus.SWAP_HANDS) {
            event.setCancelled(true);
            session.toggleFocus();
            return;
        }

        // A map that cannot reach the main hand has nothing to gain from the swap, and the player cannot see what
        // they would be swapping: the fake map is drawn over their real offhand item, so the key would silently
        // shuffle two items they are only holding one of.
        if (!session.hand().reachesMainHand()) {
            refuse(event, session);
            return;
        }

        EquipmentSlot where = plugin.display().handOf(player);
        EquipmentSlot other = where == EquipmentSlot.OFF_HAND ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        if (plugin.display().moveTo(player, plugin.display().slotOf(player), other)) {
            event.setCancelled(true);
        }
    }

    /** Nothing to swap, so the key is free for the screen to mean something by. */
    private static void refuse(PlayerSwapHandItemsEvent event, PlayerSession session) {
        event.setCancelled(true);
        session.screen().swapHands();
    }

    // Cancelling the interact event stops the click, but not a held-down dig, so block damage is
    // refused too. Cheaper and far less invasive than switching the player's game mode.
    @EventHandler(priority = EventPriority.LOW)
    public void onBlockDamage(BlockDamageEvent event) {
        if (focused(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockBreak(BlockBreakEvent event) {
        if (focused(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * Right-click is the activate button, so reaching an entity with it is refused outright - otherwise
     * selecting a menu row could open a villager's trades or hang the player's real item in a frame.
     */
    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (focused(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * Nothing of ours is dropped on death, since a faked map was never in the inventory.
     *
     * <p>A real item is: it drops like anything else, and the sweep closes the screen once it is out of the
     * player's hands. Closing here as well is what stops the corpse's screen ticking in the meantime.
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        plugin.sessions().close(event.getPlayer(), false);
    }

    /** The router owns the claim bookkeeping, so it clears the player outright rather than trusting every subsystem to tidy up. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.sessions().close(event.getPlayer(), true);
        plugin.handItems().forget(event.getPlayer());
        plugin.heldTriggers().forget(event.getPlayer());
        plugin.router().releaseAll(event.getPlayer());
        // Last, since closing the session is what still needs the transport to know about them.
        plugin.transport().forget(event.getPlayer());
    }
}
