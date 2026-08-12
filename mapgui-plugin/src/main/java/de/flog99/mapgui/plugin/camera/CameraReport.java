package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.camera.CameraStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * What the camera is costing, for somebody keeping a server up rather than tuning a renderer.
 *
 * <p>Four questions and nothing else, because a report an admin has to interpret is one they stop reading. How much
 * is being asked for, and by whom, so there is something to turn down. What of it lands on the main thread, which is
 * the only part that can cost a tick. Whether the machine is keeping up. Whether it is failing.
 *
 * <p>The trace, the palette, the chunk and section counts are all missing on purpose. They are the numbers for
 * deciding whether the renderer could be faster, not for deciding whether a server is in trouble, and they are still
 * a command away under {@code performance follow}.
 *
 * <p>Bandwidth is missing for a different reason: a capture sends nothing. What reaches a client is the map frame a
 * screen paints it into, which {@code /mapgui performance} already counts - a second figure here would be the same
 * bytes twice.
 *
 * <p>Built from {@link CameraStats} and nothing else, which is the same thing any plugin can ask for. A built-in
 * command working from a wider view than the API offers is how an API ends up missing the field somebody needed.
 */
public final class CameraReport {

    /** Share of the main thread worth a colour. One percent is noticeable on a busy server, five is a problem. */
    private static final double TICK_NOTICEABLE = 1.0;
    private static final double TICK_BAD = 5.0;

    /** A single capture holding the tick this long is a stutter a player sees, whatever the average says. */
    private static final double SPIKE_MILLIS = 10.0;
    private static final double STALL_MILLIS = 25.0;

    /** One capture is traced at a time, so a couple waiting is a burst clearing and more than that is a backlog. */
    private static final int BACKLOG = 2;

    /** The window the counts are taken over, for turning one back into a rate. */
    private static final int WINDOW_SECONDS = 4;

    /** How long a failure stays worth mentioning outside the camera's own commands. */
    private static final long TROUBLE_MILLIS = 5 * 60 * 1000L;

    /** Enough of a stack trace's first line to recognise it by, since the whole of one is in the console anyway. */
    private static final int REASON_CHARS = 90;

    private CameraReport() {
    }

    /** The whole of it, for {@code /mapgui camera performance}. */
    public static List<Component> lines(CameraStats now) {
        List<Component> lines = new ArrayList<>();

        if (now.idle()) {
            lines.add(Component.text("Camera  ", NamedTextColor.GOLD)
                    .append(Component.text("nothing captured in the last few seconds", NamedTextColor.DARK_GRAY)));
            addLastFailure(lines, now, Long.MAX_VALUE);
            return lines;
        }

        lines.add(Component.text("Camera - what captures are costing, over the last few seconds", NamedTextColor.GOLD));

        // A window with nothing but failures in it skips the cost lines rather than printing four zeroes: what is
        // wrong there is that captures are not happening, and a row of noughts reads as "cheap" at a glance.
        if (now.captures() > 0) {
            lines.add(Component.text("Captures  ", NamedTextColor.GRAY)
                    .append(Component.text(rate(now.capturesPerSecond()), NamedTextColor.WHITE))
                    .append(Component.text("   " + by(now), NamedTextColor.DARK_GRAY)));

            lines.add(Component.text("Costs the server  ", NamedTextColor.GRAY)
                    .append(Component.text(perTick(now.mainMillisPerTick()), tickColor(now.tickPercent())))
                    .append(Component.text(String.format(Locale.ROOT, "  %.1f%% of every tick", now.tickPercent()), NamedTextColor.DARK_GRAY))
                    .append(Component.text("   slowest frame ", NamedTextColor.GRAY))
                    .append(Component.text(millis(now.worstMainMillis()), spikeColor(now.worstMainMillis()))));

            // What one capture costs, split three ways, each with what it went through beside what it cost - since a
            // slow stage is either a lot of things or expensive things, and those have opposite answers. The rate a
            // live view gets is the budget divided by this, so it is also the number that explains a slow viewfinder.
            CameraStats.Blocks blocks = now.blocks();
            lines.add(Component.text("Each capture  ", NamedTextColor.GRAY)
                    .append(Component.text(millis(now.mainMillisEach()), NamedTextColor.WHITE))
                    .append(Component.text(" on the tick", NamedTextColor.DARK_GRAY))
                    .append(Component.text(String.format(Locale.ROOT,
                            "   blocks %s (%.0f chunks, %.0f%% reused), entities %s (%.0f, %.0f%% reused), tile entities %s (%.0f)",
                            millis(now.blockMillisEach()), blocks.chunksEach(), blocks.reusedPercent(),
                            millis(now.entityMillisEach()), now.entitiesEach(), now.entitiesReusedPercent(),
                            millis(now.blockEntityMillisEach()), now.blockEntitiesEach()), NamedTextColor.DARK_GRAY)));

            addLive(lines, now);

            // Only when something is waiting. A trace time with nothing behind it is a renderer's number, and an empty
            // queue is not news - it is the normal state, and printing it every time trains an eye to skip the line.
            if (now.queued() > 0 || now.dropped() > 0) {
                lines.add(Component.text("Waiting  ", NamedTextColor.GRAY)
                        .append(Component.text(now.queued() + " queued", now.queued() > BACKLOG ? NamedTextColor.RED : NamedTextColor.YELLOW))
                        .append(Component.text("  " + millis(now.traceMillisEach())
                                + " to trace each, off the main thread", NamedTextColor.DARK_GRAY)));
            }

            // Turned away rather than failed, and said so in those words: nothing broke, the machine was asked for
            // more than it could draw, and the answer to that is to ask for less rather than to read a stack trace.
            if (now.dropped() > 0) {
                lines.add(Component.text("Turned away  ", NamedTextColor.RED)
                        .append(Component.text(rate(now.dropped() / (double) WINDOW_SECONDS), NamedTextColor.WHITE))
                        .append(Component.text("  the trace was too far behind to take them", NamedTextColor.DARK_GRAY)));
            }
        }

        if (now.failed() > 0) {
            lines.add(Component.text("Failed  ", NamedTextColor.RED)
                    .append(Component.text(now.failed() + " of " + (now.captures() + now.failed()), NamedTextColor.WHITE)));
        }

        addLastFailure(lines, now, TROUBLE_MILLIS);
        return lines;
    }

    /**
     * One line for {@code /mapgui performance}, or null when the camera is doing nothing.
     *
     * <p>Only the tick share, since that is the one camera number in the same currency as the rest of that report -
     * what MapGUI is taking from the server right now.
     */
    public static Component cost(CameraStats now) {
        if (now.captures() == 0) return null;

        return Component.text("  camera  ", NamedTextColor.WHITE)
                .append(Component.text(rate(now.capturesPerSecond()) + "  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(perTick(now.mainMillisPerTick()), tickColor(now.tickPercent())))
                .append(Component.text(" of every tick  - see /mapgui camera performance", NamedTextColor.DARK_GRAY));
    }

    /**
     * One line for {@code /mapgui status} when captures are failing, or null when they are not.
     *
     * <p>Nothing when it is merely busy: a working camera is not something happening to a server, and a status that
     * lists every healthy thing hides the one unhealthy one.
     */
    public static Component trouble(CameraStats now) {
        CameraStats.Failure failure = now.lastFailure();
        if (failure == null || System.currentTimeMillis() - failure.at() > TROUBLE_MILLIS) return null;

        return Component.text("Camera  ", NamedTextColor.GOLD)
                .append(Component.text("a capture for " + failure.plugin() + " failed "
                        + ago(System.currentTimeMillis() - failure.at()) + " ago", NamedTextColor.RED))
                .append(Component.text("  - see the console", NamedTextColor.DARK_GRAY));
    }

    /**
     * Kept out of the counted window, since the point of it is a failure that stopped happening because everything
     * stopped happening - a camera that fails every time and a camera nothing uses look the same from outside.
     */
    private static void addLastFailure(List<Component> lines, CameraStats now, long within) {
        CameraStats.Failure failure = now.lastFailure();
        if (failure == null || System.currentTimeMillis() - failure.at() > within) return;

        lines.add(Component.text("Last failure  ", NamedTextColor.RED)
                .append(Component.text(ago(System.currentTimeMillis() - failure.at()) + " ago, for "
                        + failure.plugin(), NamedTextColor.WHITE)));
        lines.add(Component.text("  " + reason(failure.reason()), NamedTextColor.DARK_GRAY));
    }

    /**
     * What the live views are getting, and next to it the two settings that decided it.
     *
     * <p>Both settings on the line rather than in the config file alone, because the number beside them is only
     * ever readable against them: 6.7 fps means one thing when the ceiling is 10 and the budget ran out, and quite
     * another when the ceiling is 6. The one that is binding is the one to change.
     */
    private static void addLive(List<Component> lines, CameraStats now) {
        CameraStats.Live live = now.live();
        if (live.viewers() > 0) {
            String fps = live.even()
                    ? String.format(Locale.ROOT, "%.1f fps", live.fastestFps())
                    : String.format(Locale.ROOT, "%.1f to %.1f fps", live.slowestFps(), live.fastestFps());

            // Green for a view sitting on the rate somebody chose, yellow for one the budget is holding down.
            boolean capped = now.bound() == CameraStats.Bound.FPS_CEILING;
            lines.add(Component.text("Live views  ", NamedTextColor.GRAY)
                    .append(Component.text(live.viewers() + (live.viewers() == 1 ? " viewer at " : " viewers at "), NamedTextColor.WHITE))
                    .append(Component.text(fps, capped ? NamedTextColor.GREEN : NamedTextColor.YELLOW))
                    .append(Component.text("   " + settings(now, live), NamedTextColor.DARK_GRAY)));
        }

        // The line that stops a budget being silently ignored. Pacing is something a plugin opts into by asking, so
        // a server can be capturing twenty a second with max-ms-per-tick set and none of it inside that number -
        // and without this, the report would show "no live views" beside a busy camera and look like agreement.
        if (now.unpacedPerSecond() <= 0 || !limited(now)) return;

        lines.add(Component.text("Unpaced  ", NamedTextColor.YELLOW)
                .append(Component.text(rate(now.unpacedPerSecond()), NamedTextColor.WHITE))
                .append(Component.text("  taken without asking readyForFrame, so no budget applies to them",
                        NamedTextColor.DARK_GRAY)));
    }

    /** Whether an admin set anything for the unpaced line to be a warning about. */
    private static boolean limited(CameraStats now) {
        return now.liveMaxMillisPerTick() > 0 || now.liveFpsCeiling() > 0;
    }

    /**
     * What the rates handed out add up to, against what they were allowed - the two numbers together are what say
     * which of the two settings is the binding one, and so which of them is worth changing.
     */
    private static String settings(CameraStats now, CameraStats.Live live) {
        String used = String.format(Locale.ROOT, "%.2f", live.usedMillisPerTick());
        String budget = now.liveMaxMillisPerTick() <= 0
                ? used + "ms/t, no limit"
                : used + " of " + String.format(Locale.ROOT, "%.1f", now.liveMaxMillisPerTick()) + "ms/t";
        String ceiling = now.liveFpsCeiling() <= 0 ? "no fps cap" : now.liveFpsCeiling() + " fps cap";

        return budget + ", " + ceiling;
    }

    /** Who is asking, busiest first, which is the only actionable half of a rate. */
    private static String by(CameraStats now) {
        StringJoiner joiner = new StringJoiner(", ");
        for (CameraStats.Caller caller : now.callers()) {
            joiner.add(caller.plugin() + " " + rate(caller.capturesPerSecond()));
        }
        return joiner.toString();
    }

    private static String rate(double perSecond) {
        return perSecond >= 1 ? String.format(Locale.ROOT, "%.1f/s", perSecond) : String.format(Locale.ROOT, "%.2f/s", perSecond);
    }

    /**
     * Per tick rather than per second, since that is the unit a Minecraft server is read in and the unit
     * {@code camera.live.max-ms-per-tick} is written in. Two decimals under a millisecond, where one would round a
     * real cost to nought.
     */
    private static String perTick(double value) {
        return value >= 1 ? String.format(Locale.ROOT, "%.1fms/t", value) : String.format(Locale.ROOT, "%.2fms/t", value);
    }

    private static String millis(double value) {
        return String.format(Locale.ROOT, "%.1fms", value);
    }

    private static String reason(String thrown) {
        return thrown.length() <= REASON_CHARS ? thrown : thrown.substring(0, REASON_CHARS) + "...";
    }

    private static String ago(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return seconds / 60 + "m";

        return seconds / 3600 + "h";
    }

    private static NamedTextColor tickColor(double percent) {
        if (percent >= TICK_BAD) return NamedTextColor.RED;
        if (percent >= TICK_NOTICEABLE) return NamedTextColor.YELLOW;

        return NamedTextColor.GREEN;
    }

    private static NamedTextColor spikeColor(double value) {
        if (value >= STALL_MILLIS) return NamedTextColor.RED;
        if (value >= SPIKE_MILLIS) return NamedTextColor.YELLOW;

        return NamedTextColor.WHITE;
    }
}
