package de.flog99.mapgui.plugin.wall;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The one placement gesture an event can still see, and the ways placement gets abandoned.
 *
 * <p>Left-clicking a block carries the block and the face it hit, which is what the anchor needs. Everything
 * else is read off the connection, because once the preview is up the client aims at its maps.
 */
public final class WallListeners implements Listener {

    private final WallManager walls;

    public WallListeners(WallManager walls) {
        this.walls = walls;
    }

    /** Canceling the interaction is what stops the block being mined, in creative as well as survival. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!walls.isPlacing(player)) return;

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            if (event.getClickedBlock() != null) {
                walls.click(player, event.getClickedBlock(), event.getBlockFace());
            }
        }
    }

    /** Nothing was in the world, so leaving mid-placement only has the preview to forget. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        walls.cancelPlacing(event.getPlayer());
    }

    /** The anchor is blocks in the world it was clicked in, and those coordinates mean nothing here. */
    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        walls.cancelPlacing(event.getPlayer());
    }
}
