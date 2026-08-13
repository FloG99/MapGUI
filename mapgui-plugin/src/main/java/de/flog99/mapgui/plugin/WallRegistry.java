package de.flog99.mapgui.plugin;

import de.flog99.mapgui.Click;
import de.flog99.mapgui.MapTransport;
import de.flog99.mapgui.PacketInput;
import de.flog99.mapgui.WallDisplay;
import de.flog99.mapgui.WallTile;
import de.flog99.mapgui.camera.LiveWalls;
import de.flog99.mapgui.WallServices;
import de.flog99.mapgui.prompt.PromptRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every wall that is currently up, whoever opened it, and the clicks aimed at them.
 *
 * <p>A wall is not tied to a player or a session, so nothing else would tick it. Opening registers and
 * closing deregisters, so a plugin that forgets to close its walls still has them cleaned up on shutdown.
 *
 * <p>Input is claimed only for players standing in front of a wall with a menu on it, and the claim declines
 * any click they were not pointing at the wall for - so walking past one never costs you a door.
 */
final class WallRegistry implements Listener, LiveWalls {

    private final Plugin plugin;
    private final WallServices services;
    private final InputRouter router;

    /**
     * Every wall that is up, by identity - two walls in the same blocks are still two walls.
     *
     * <p>Concurrent because {@link WallGestures} walks it on the network thread while walls open and close on
     * the main one. An unsynchronized map does not merely miss a wall for a tick: a resize racing a read can
     * leave the reader on a stale table for good, and clicks stop arriving while the main thread looks fine.
     */
    private final Set<WallDisplay> open = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final Map<UUID, PacketInput.Handler> claims = new HashMap<>();

    /**
     * The set that {@link #tick} iterates over, built once and refreshed only when {@link #open} changes.
     *
     * <p>Kept separate from {@link #open} because a tick runs twenty times a second and a wall that sits
     * still must not pay for a {@code List.copyOf} each time. Invalidated from the same two places that
     * mutate {@code open} - the builder's registration and {@link #forget} - both on the main thread, and
     * the network thread never reads this.
     */
    private List<WallDisplay> snapshot = List.of();
    private boolean snapshotDirty = true;

    WallRegistry(Plugin plugin, MapTransport transport, PromptRegistry prompts, InputRouter router) {
        this.plugin = plugin;
        this.router = router;
        this.services = new WallServices(transport, prompts, task -> plugin.getServer().getScheduler().runTask(plugin, task));
    }

    /**
     * Every map of every wall this player is being shown, for the camera.
     *
     * <p>Walked rather than indexed by block: a server has a handful of walls up at once, and a lookup table would
     * have to be kept in step with walls opening, closing, moving and being resized.
     */
    @Override
    public List<WallTile> shownTo(Player viewer) {
        List<WallTile> shown = new ArrayList<>();
        for (WallDisplay wall : open) {
            if (wall.world().equals(viewer.getWorld())) {
                shown.addAll(wall.shownTo(viewer));
            }
        }
        return shown;
    }

    WallDisplay.Builder builder() {
        return new WallDisplay.Builder(services, this::registered, this::forget);
    }

    private void registered(WallDisplay wall) {
        open.add(wall);
        snapshotDirty = true;
    }

    private void forget(WallDisplay wall) {
        open.remove(wall);
        snapshotDirty = true;
    }

    /** Copied, since content is free to close its own wall while being painted. */
    void tick(long now) {
        if (snapshotDirty) {
            snapshot = List.copyOf(open);
            snapshotDirty = false;
        }
        List<WallDisplay> walls = snapshot;

        // Before painting, so the cursor a wall draws this frame is the one it just won or lost.
        for (Player player : plugin.getServer().getOnlinePlayers()) aimNearest(player, walls);
        for (WallDisplay wall : walls) wall.tick(now);
        updateClaims();
    }

    /**
     * Gives a player's aim to the nearest menu they are looking at, and takes it off every other.
     *
     * <p>A job for whoever knows about all of them: a wall can tell that a sight line crosses it and that no
     * block is in the way, but not that another menu is nearer - so two lined up would both answer and the
     * click would go to whichever came first out of a set.
     *
     * <p>Only menus take part. A video has no cursor and no clicks, so letting one stand in front of a menu
     * would leave the menu unusable rather than protected, and a placement preview is not a real wall yet.
     */
    private void aimNearest(Player player, List<WallDisplay> walls) {
        WallDisplay nearest = null;
        double closest = Double.MAX_VALUE;

        for (WallDisplay wall : walls) {
            if (!wall.interactive() || !wall.sees(player)) continue;

            double distance = wall.measureAim(player);
            if (distance < 0 || distance >= closest) continue;

            closest = distance;
            nearest = wall;
        }

        for (WallDisplay wall : walls) {
            if (wall.interactive() && wall.sees(player)) {
                wall.settleAim(player, wall == nearest);
            }
        }
    }

    /** Claims input for anyone in front of an interactive wall, per player rather than per wall since one listener answers for all of them. */
    private void updateClaims() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            boolean wanted = watchingAnyMenu(player);
            boolean held = claims.containsKey(player.getUniqueId());

            if (wanted && !held) {
                PacketInput.Handler gestures = new WallGestures(player);
                claims.put(player.getUniqueId(), gestures);
                router.claim(player, gestures);
            } else if (!wanted && held) {
                router.release(player, claims.remove(player.getUniqueId()));
            }
        }
    }

    private boolean watchingAnyMenu(Player player) {
        for (WallDisplay wall : open) {
            if (wall.interactive() && wall.sees(player)) return true;
        }
        return false;
    }

    /**
     * The wheel, for a player pointing at a wall - a scrollable list or a palette wants it.
     *
     * <p>Canceling alone is not enough: the client has already moved its own selection and would stay out of
     * step, so the slot is put back explicitly.
     */
    @EventHandler
    public void onHotbar(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        int notches = Hotbar.notches(event.getPreviousSlot(), event.getNewSlot());

        for (WallDisplay wall : open) {
            if (!wall.interactive() || !wall.isAiming(player)) continue;
            if (!wall.scroll(player, notches)) continue;

            event.setCancelled(true);
            player.getInventory().setHeldItemSlot(event.getPreviousSlot());
            return;
        }
    }

    void closeAll() {
        for (WallDisplay wall : List.copyOf(open)) wall.close();
        open.clear();
        claims.clear();
        snapshotDirty = true;
    }

    /**
     * Offers a click to whichever wall the player is pointing at.
     *
     * <p>Runs on the network thread, so it reads only whether the player was aiming as of the last tick.
     * The click itself is handed to the main thread.
     */
    private final class WallGestures implements PacketInput.Handler {

        private final Player player;

        private WallGestures(Player player) {
            this.player = player;
        }

        @Override
        public boolean rightClick() {
            return offer(Click.RIGHT);
        }

        @Override
        public boolean leftClick() {
            return offer(Click.LEFT);
        }

        /** Q belongs to whoever is holding a menu - a wall is furniture and does not close. */
        @Override
        public boolean drop() {
            return false;
        }

        private boolean offer(Click with) {
            for (WallDisplay wall : open) {
                if (!wall.interactive() || !wall.isAiming(player)) continue;

                plugin.getServer().getScheduler().runTask(plugin, () -> wall.click(player, with));
                return true;
            }
            return false;
        }
    }
}
