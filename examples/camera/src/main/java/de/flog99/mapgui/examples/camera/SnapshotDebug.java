package de.flog99.mapgui.examples.camera;

import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.camera.CameraAssets;
import de.flog99.mapgui.camera.CameraStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * {@code /snapshot debug} - what this plugin's captures are costing, printed by this plugin.
 *
 * <p>The point of the example. MapGUI's own {@code /mapgui camera performance} prints the same numbers, and a server
 * running one plugin over MapGUI does not want two ways to ask the same question - so it can turn the whole
 * {@code /mapgui} tree off with {@code commands.enabled: false} and ship this instead.
 *
 * <p>Which is only honest if the API is as wide as the built-in command's own view, so it is:
 * {@link CameraStats} is exactly what {@code /mapgui camera performance} reads, with no private access on the side.
 * Everything below comes from two calls.
 *
 * <p>Two things here that MapGUI's report does not have, because they are per player and it is a server-wide report:
 * the rate <i>this</i> player's viewfinder is being allowed, and what is holding it there.
 */
final class SnapshotDebug {

    private SnapshotDebug() {
    }

    static void print(CommandSender sender) {
        CameraStats stats = MapGui.get().camera().stats();

        sender.sendMessage(Component.text("Camera", NamedTextColor.GOLD)
                .append(Component.text("  " + textures(), NamedTextColor.DARK_GRAY)));

        if (stats.idle()) {
            sender.sendMessage(Component.text("  nothing captured in the last few seconds", NamedTextColor.DARK_GRAY));
        } else {
            addCost(sender, stats);
        }

        addLive(sender, stats);
        addYours(sender);
        addFailure(sender, stats);
    }

    private static void addCost(CommandSender sender, CameraStats stats) {
        sender.sendMessage(line("captures", String.format(Locale.ROOT, "%.1f/s", stats.capturesPerSecond()), by(stats)));
        sender.sendMessage(line("main thread", String.format(Locale.ROOT, "%.2fms/t", stats.mainMillisPerTick()),
                String.format(Locale.ROOT, "%.1f%% of a tick, worst single %.1fms", stats.tickPercent(), stats.worstMainMillis())));
        sender.sendMessage(line("trace", String.format(Locale.ROOT, "%.1fms each", stats.traceMillisEach()),
                stats.queued() == 0 ? "nothing waiting" : stats.queued() + " waiting for a thread"));

        if (stats.dropped() > 0) {
            sender.sendMessage(line("turned away", stats.dropped() + " captures",
                    "the trace was too far behind to take them"));
        }
    }

    /** The caps, so a rate that looks low can be read against what was allowed rather than guessed at. */
    private static void addLive(CommandSender sender, CameraStats stats) {
        String cap = stats.liveFpsCeiling() <= 0 ? "no fps cap" : stats.liveFpsCeiling() + " fps cap";
        CameraStats.Live live = stats.live();

        if (live == null) {
            sender.sendMessage(line("live views", "none open", cap));
        } else {
            String budget = stats.liveMaxMillisPerTick() <= 0
                    ? String.format(Locale.ROOT, "%.2fms/t of no limit", live.usedMillisPerTick())
                    : String.format(Locale.ROOT, "%.2f of %.1fms/t", live.usedMillisPerTick(), stats.liveMaxMillisPerTick());

            sender.sendMessage(line("live views",
                    live.viewers() + (live.viewers() == 1 ? " viewer" : " viewers")
                            + String.format(Locale.ROOT, ", %.1f to %.1f fps", live.slowestFps(), live.fastestFps()),
                    budget + ", " + cap));
        }

        // The caps are set for live views, so captures taken without asking are not covered by them. Said here for
        // the same reason MapGUI says it: a budget being ignored looks exactly like a budget nothing needed.
        if (stats.unpacedPerSecond() > 0) {
            sender.sendMessage(line("unpaced", String.format(Locale.ROOT, "%.1f/s", stats.unpacedPerSecond()),
                    "taken without readyForFrame, so no budget applies"));
        }
    }

    /**
     * This player's own rate, which is the number somebody debugging a viewfinder actually wants - the server-wide
     * range says nothing about whether <i>theirs</i> is the slow one.
     */
    private static void addYours(CommandSender sender) {
        if (!(sender instanceof Player player)) return;

        double fps = MapGui.get().camera().frameRate(player);
        sender.sendMessage(line("your view", fps <= 0 ? "not open" : String.format(Locale.ROOT, "%.1f fps", fps),
                fps <= 0 ? "open one with /snapshot" : "shared with everyone else looking"));
    }

    private static void addFailure(CommandSender sender, CameraStats stats) {
        if (stats.failed() > 0) {
            sender.sendMessage(line("failed", stats.failed() + " of " + (stats.captures() + stats.failed()), ""));
        }
        if (stats.lastFailure() == null) return;

        sender.sendMessage(Component.text("  last failure  ", NamedTextColor.RED)
                .append(Component.text(stats.lastFailure().reason(), NamedTextColor.WHITE)));
    }

    private static String by(CameraStats stats) {
        StringBuilder callers = new StringBuilder();
        for (CameraStats.Caller caller : stats.callers()) {
            callers.append(callers.isEmpty() ? "" : ", ").append(caller.plugin());
        }
        return callers.isEmpty() ? "" : "asked for by " + callers;
    }

    private static String textures() {
        return switch (MapGui.get().camera().assets()) {
            case CameraAssets.Ready ready -> "textures ready, Minecraft " + ready.minecraftVersion();
            case CameraAssets.Loading ignored -> "textures still downloading";
            case CameraAssets.Unavailable unavailable -> "no textures - " + unavailable.fix();
        };
    }

    private static Component line(String name, String value, String note) {
        return Component.text("  " + name + "  ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE))
                .append(note.isEmpty() ? Component.empty() : Component.text("   " + note, NamedTextColor.DARK_GRAY));
    }
}
