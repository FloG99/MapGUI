package de.flog99.mapgui.plugin;

import de.flog99.mapgui.Bandwidth;
import de.flog99.mapgui.MapTransport;
import de.flog99.mapgui.Session;
import de.flog99.mapgui.WallDisplay;
import de.flog99.mapgui.WallLayout;
import de.flog99.mapgui.plugin.camera.CameraReport;
import de.flog99.mapgui.plugin.wall.WallManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * What MapGUI is costing right now, worst offender first.
 *
 * <p>Two views of the same bytes: per wall says which wall to turn down, per player says who is expensive.
 *
 * <p>And one line that is not bytes at all. Bandwidth is what MapGUI usually costs, but a camera costs main-thread
 * time instead - it sends nothing of its own, and the frames it ends up in are already counted above as whatever
 * wall or player received them. It goes here because this is where somebody looks when a server feels slow, and a
 * cost report that quietly leaves out the only part that can drop a tick is worse than no report.
 */
final class PerformanceReport {

    private final MapGuiPlugin plugin;
    private final MapTransport transport;
    private final SessionManager sessions;
    private final WallManager walls;

    PerformanceReport(MapGuiPlugin plugin, MapTransport transport, SessionManager sessions, WallManager walls) {
        this.plugin = plugin;
        this.transport = transport;
        this.sessions = sessions;
        this.walls = walls;
    }

    List<Component> lines() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("MapGUI - map payload, before packet compression", NamedTextColor.GOLD));
        lines.add(Component.text("Total  ", NamedTextColor.GRAY).append(rate(transport.bandwidth().perSecond())));

        addWalls(lines);
        addPlayers(lines);
        addCamera(lines);
        return lines;
    }

    /** The plugin rather than the service, since a reload replaces it and a captured one would count nothing. */
    private void addCamera(List<Component> lines) {
        Component cost = CameraReport.cost(plugin.camera());
        if (cost == null) return;

        lines.add(Component.text("Main thread", NamedTextColor.GRAY));
        lines.add(cost);
    }

    private void addWalls(List<Component> lines) {
        Map<String, WallDisplay> showing = walls.showing();
        if (showing.isEmpty()) {
            lines.add(Component.text("No walls up.", NamedTextColor.DARK_GRAY));
            return;
        }

        lines.add(Component.text("Walls", NamedTextColor.GRAY));
        showing.entrySet().stream()
                .sorted(Comparator.comparingLong(
                        (Map.Entry<String, WallDisplay> e) -> e.getValue().bandwidth().perSecond()).reversed())
                .forEach(entry -> {
                    WallDisplay wall = entry.getValue();
                    WallLayout layout = wall.layout();
                    lines.add(Component.text("  " + entry.getKey() + " ", NamedTextColor.WHITE)
                            .append(Component.text(layout.cols() + "x" + layout.rows()
                                    + " " + wall.viewerCount() + " viewer(s)  ", NamedTextColor.DARK_GRAY))
                            .append(rate(wall.bandwidth().perSecond()))
                            .append(Component.space())
                            .append(Coordinates.link(layout.centerX(), layout.centerY(), layout.centerZ()))
                    );
                });
    }

    private void addPlayers(List<Component> lines) {
        List<Player> receiving = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (transport.bandwidth(player).perSecond() > 0) {
                receiving.add(player);
            }
        }
        if (receiving.isEmpty()) return;

        lines.add(Component.text("Players", NamedTextColor.GRAY));
        receiving.sort(Comparator.comparingLong((Player p) -> transport.bandwidth(p).perSecond()).reversed());

        for (Player player : receiving) {
            Session session = sessions.session(player);
            Location at = player.getLocation();
            lines.add(Component.text("  " + player.getName() + "  ", NamedTextColor.WHITE)
                    .append(rate(transport.bandwidth(player).perSecond()))
                    .append(Component.text(session == null ? "" : "  menu open", NamedTextColor.DARK_GRAY))
                    .append(Component.space())
                    .append(Coordinates.link(at.getX(), at.getY(), at.getZ()))
            );
        }
    }

    /** Red past a megabit, which is where a busy server starts to notice. */
    private static Component rate(long bytesPerSecond) {
        NamedTextColor color = bytesPerSecond > 512 * 1024 ? NamedTextColor.RED
                : bytesPerSecond > 128 * 1024 ? NamedTextColor.YELLOW
                : NamedTextColor.GREEN;
        return Component.text(Bandwidth.describe(bytesPerSecond), color);
    }
}
