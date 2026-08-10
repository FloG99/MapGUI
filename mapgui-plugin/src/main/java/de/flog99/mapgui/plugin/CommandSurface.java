package de.flog99.mapgui.plugin;

import de.flog99.mapgui.plugin.wall.WallManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Which branches of {@code /mapgui} this server has anything to administer.
 *
 * <p>MapGUI is a library, so what it can administer is whatever the plugins on top of it registered. A server that
 * installed it for one camera has no GUIs to open and no walls to place, and {@code /mapgui hand} and
 * {@code /mapgui wall} there are two branches of commands about features that will never run - which is worse than
 * clutter, since a tree full of things that answer "nothing to show" teaches an admin not to read it.
 *
 * <p>Worked out rather than configured, from what is actually registered. Nothing to keep in step, and it corrects
 * itself as plugins load: a branch is not gone, it is unlisted, and appears the moment it has something in it.
 *
 * <p>Asked per invocation rather than at registration, which is what lets it be dynamic at all - a Brigadier tree is
 * built once, and {@code requires} is what it runs each time. The client's copy of that tree is only resent when the
 * server says so, which is what {@link #refresh()} is for.
 */
final class CommandSurface {

    private final MapGuiPlugin plugin;
    private final GuiCatalogImpl guis;
    private final WallManager walls;

    /** What was showing when the client trees were last sent, as a bitmask, so a change is one comparison. */
    private int announced = -1;

    CommandSurface(MapGuiPlugin plugin, GuiCatalogImpl guis, WallManager walls) {
        this.plugin = plugin;
        this.guis = guis;
        this.walls = walls;
    }

    /** Everything, for a server that would rather see the whole tree than have MapGUI decide. */
    private boolean hidingOff() {
        return !plugin.config().commandsHideUnused();
    }

    /** Something to open. A GUI in a hand is always some plugin's, so with none registered there is nothing to hand out. */
    boolean hand() {
        return hidingOff() || !guis.openable().isEmpty();
    }

    /**
     * Something to place, or something already placed.
     *
     * <p>Saved walls count as well as registered content, since a wall whose plugin has been removed still has to be
     * removable - hiding the branch would leave it up with no way to take it down.
     */
    boolean wall() {
        return hidingOff() || !walls.contentNames().isEmpty() || !walls.names().isEmpty();
    }

    /**
     * A camera something has asked for, or textures somebody has set up.
     *
     * <p>The second half matters: an admin who put a client jar in {@code assets/} or listed one under
     * {@code camera.assets.packs} has said they intend to use the camera, and hiding the commands that manage what
     * they just installed would be MapGUI arguing with them.
     */
    boolean camera() {
        return hidingOff()
                || plugin.camera().everUsed()
                || plugin.cameraAssets().stack() != null
                || !plugin.config().cameraPacks().isEmpty();
    }

    /**
     * Resends the command trees if what is showing has changed since the last time.
     *
     * <p>A client is sent the tree when it joins and not again unless told, so a camera whose first use happens
     * while an admin is already online would otherwise not turn up in their tab-completion until they relogged. The
     * command still runs if typed - {@code requires} is live - but a command you cannot find is not much of one.
     *
     * <p>Compared rather than pushed from the places that change it: registering a GUI, placing a wall and touching
     * a camera are three unrelated paths, and one poll of three booleans is cheaper than keeping hooks in all of them
     * correct.
     */
    void refresh() {
        int showing = (hand() ? 1 : 0) | (wall() ? 2 : 0) | (camera() ? 4 : 0);
        if (showing == announced) return;

        announced = showing;
        // Per player, since the tree a client holds is the one it was sent - and it is filtered by that player's
        // permissions, so there is no one tree to push.
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.updateCommands();
        }
    }
}
