package de.flog99.mapgui.plugin.camera;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
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
 * a command away under {@code timings follow}.
 *
 * <p>Bandwidth is missing for a different reason: a capture sends nothing. What reaches a client is the map frame a
 * screen paints it into, which {@code /mapgui performance} already counts - a second figure here would be the same
 * bytes twice.
 */
public final class CameraReport {

    /** Share of the main thread worth a colour. One percent is noticeable on a busy server, five is a problem. */
    private static final double TICK_NOTICEABLE = 1.0;
    private static final double TICK_BAD = 5.0;

    /** A single capture holding the tick this long is a stutter a player sees, whatever the average says. */
    private static final long SPIKE_NANOS = 10_000_000L;
    private static final long STALL_NANOS = 25_000_000L;

    /** One capture is traced at a time, so a couple waiting is a burst clearing and more than that is a backlog. */
    private static final int BACKLOG = 2;

    /** How long a failure stays worth mentioning outside the camera's own commands. */
    private static final long TROUBLE_MILLIS = 5 * 60 * 1000L;

    /** Enough of a stack trace's first line to recognise it by, since the whole of one is in the console anyway. */
    private static final int REASON_CHARS = 90;

    private CameraReport() {
    }

    /** The whole of it, for {@code /mapgui camera timings}. */
    public static List<Component> lines(CameraService camera) {
        CaptureLoad load = camera.load();
        CaptureWindow.Load now = load.read();
        List<Component> lines = new ArrayList<>();

        if (now.idle()) {
            lines.add(Component.text("Camera  ", NamedTextColor.GOLD)
                    .append(Component.text("nothing captured in the last few seconds", NamedTextColor.DARK_GRAY)));
            addLastFailure(lines, load, Long.MAX_VALUE);
            return lines;
        }

        lines.add(Component.text("Camera - what captures are costing, over the last few seconds", NamedTextColor.GOLD));

        // A window with nothing but failures in it skips the cost lines rather than printing four zeroes: what is
        // wrong there is that captures are not happening, and a row of noughts reads as "cheap" at a glance.
        if (now.captures() > 0) {
            lines.add(Component.text("Captures  ", NamedTextColor.GRAY)
                    .append(Component.text(rate(now.perSecond()), NamedTextColor.WHITE))
                    .append(Component.text("   " + by(load), NamedTextColor.DARK_GRAY)));

            lines.add(Component.text("Main thread  ", NamedTextColor.GRAY)
                    .append(Component.text(CaptureTimings.millis(now.mainNanosPerSecond()) + "/s", tickColor(now.tickPercent())))
                    .append(Component.text(String.format("  %.1f%% of a tick", now.tickPercent()), NamedTextColor.DARK_GRAY))
                    .append(Component.text("   worst single ", NamedTextColor.GRAY))
                    .append(Component.text(CaptureTimings.millis(now.worstMainNanos()), spikeColor(now.worstMainNanos()))));

            // Only when something is waiting. A trace time with nothing behind it is a renderer's number, and an empty
            // queue is not news - it is the normal state, and printing it every time trains an eye to skip the line.
            int waiting = camera.queued();
            if (waiting > 0) {
                lines.add(Component.text("Waiting  ", NamedTextColor.GRAY)
                        .append(Component.text(waiting + " queued", waiting > BACKLOG ? NamedTextColor.RED : NamedTextColor.YELLOW))
                        .append(Component.text("  " + CaptureTimings.millis(now.traceNanosEach())
                                + " to trace each, off the main thread", NamedTextColor.DARK_GRAY)));
            }
        }

        if (now.failed() > 0) {
            lines.add(Component.text("Failed  ", NamedTextColor.RED)
                    .append(Component.text(now.failed() + " of " + (now.captures() + now.failed()), NamedTextColor.WHITE)));
        }

        addLastFailure(lines, load, TROUBLE_MILLIS);
        return lines;
    }

    /**
     * One line for {@code /mapgui performance}, or null when the camera is doing nothing.
     *
     * <p>Only the tick share, since that is the one camera number in the same currency as the rest of that report -
     * what MapGUI is taking from the server right now.
     */
    public static Component cost(CameraService camera) {
        CaptureWindow.Load now = camera.load().read();
        if (now.captures() == 0) return null;

        return Component.text("  camera  ", NamedTextColor.WHITE)
                .append(Component.text(rate(now.perSecond()) + "  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(CaptureTimings.millis(now.mainNanosPerSecond()) + "/s", tickColor(now.tickPercent())))
                .append(Component.text(" on the main thread  - see /mapgui camera timings", NamedTextColor.DARK_GRAY));
    }

    /**
     * One line for {@code /mapgui status} when captures are failing, or null when they are not.
     *
     * <p>Nothing when it is merely busy: a working camera is not something happening to a server, and a status that
     * lists every healthy thing hides the one unhealthy one.
     */
    public static Component trouble(CameraService camera) {
        CaptureLoad.Failure failure = camera.load().lastFailure();
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
    private static void addLastFailure(List<Component> lines, CaptureLoad load, long within) {
        CaptureLoad.Failure failure = load.lastFailure();
        if (failure == null || System.currentTimeMillis() - failure.at() > within) return;

        lines.add(Component.text("Last failure  ", NamedTextColor.RED)
                .append(Component.text(ago(System.currentTimeMillis() - failure.at()) + " ago, for "
                        + failure.plugin(), NamedTextColor.WHITE)));
        lines.add(Component.text("  " + reason(failure.reason()), NamedTextColor.DARK_GRAY));
    }

    /** Who is asking, busiest first, which is the only actionable half of a rate. */
    private static String by(CaptureLoad load) {
        StringJoiner joiner = new StringJoiner(", ");
        for (CaptureLoad.Share share : load.shares()) {
            joiner.add(share.plugin() + " " + rate(share.perSecond()));
        }
        return joiner.toString();
    }

    private static String rate(double perSecond) {
        return perSecond >= 1 ? String.format("%.1f/s", perSecond) : String.format("%.2f/s", perSecond);
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

    private static NamedTextColor spikeColor(long nanos) {
        if (nanos >= STALL_NANOS) return NamedTextColor.RED;
        if (nanos >= SPIKE_NANOS) return NamedTextColor.YELLOW;

        return NamedTextColor.WHITE;
    }
}
