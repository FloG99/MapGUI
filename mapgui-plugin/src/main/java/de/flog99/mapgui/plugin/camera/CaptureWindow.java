package de.flog99.mapgui.plugin.camera;

import java.util.function.LongSupplier;

/**
 * What captures have cost over the last few seconds, bucketed by the second.
 *
 * <p>A window rather than a total since startup, for the reason {@code Bandwidth} is one: somebody asking what the
 * camera costs is asking about the server in front of them, and a total is dominated by whatever happened hours ago.
 * Each bucket carries the second it belongs to, so anything older than the window reads as nothing without being swept.
 *
 * <p>The split that matters is main thread against everything else. Copying the world and gathering entities happen
 * inside the tick and are the only part that can cost the server its TPS; the trace and the palette run on a pool and
 * cannot. They are counted apart because the answer to a big one is different from the answer to the other.
 */
final class CaptureWindow {

    /** The five seconds bandwidth is averaged over, so both reports describe the same moment. */
    private static final int WINDOW_SECONDS = 5;

    /** The current second is still filling, so it is left out of every average and never divides one. */
    private static final int COUNTED_SECONDS = WINDOW_SECONDS - 1;

    /** Ticks in a second, for reporting a per-second figure in the unit a server is actually read in. */
    private static final int WINDOW_TICKS = 20;

    private final long[] second = new long[WINDOW_SECONDS];
    private final int[] captures = new int[WINDOW_SECONDS];
    private final long[] mainNanos = new long[WINDOW_SECONDS];
    private final long[] worstNanos = new long[WINDOW_SECONDS];
    private final int[] traced = new int[WINDOW_SECONDS];
    private final long[] traceNanos = new long[WINDOW_SECONDS];
    private final int[] failed = new int[WINDOW_SECONDS];

    private final LongSupplier clock;

    CaptureWindow() {
        this(() -> System.currentTimeMillis() / 1000);
    }

    /** Wound by hand in tests, since the whole thing is about which second a capture landed in. */
    CaptureWindow(LongSupplier seconds) {
        this.clock = seconds;
    }

    /**
     * The main-thread half, recorded in the tick it happened in rather than when the shot comes back - a capture
     * whose trace waits three seconds for a thread still cost this tick, not that one.
     */
    synchronized void captured(long nanos) {
        int slot = slot();
        captures[slot]++;
        mainNanos[slot] += nanos;
        worstNanos[slot] = Math.max(worstNanos[slot], nanos);
    }

    /** The trace and the palette, off the main thread and so never part of what the tick spent. */
    synchronized void traced(long nanos) {
        int slot = slot();
        traced[slot]++;
        traceNanos[slot] += nanos;
    }

    synchronized void failed() {
        failed[slot()]++;
    }

    private int slot() {
        long now = clock.getAsLong();
        int slot = Math.floorMod(now, WINDOW_SECONDS);

        if (second[slot] != now) {
            second[slot] = now;
            captures[slot] = 0;
            mainNanos[slot] = 0;
            worstNanos[slot] = 0;
            traced[slot] = 0;
            traceNanos[slot] = 0;
            failed[slot] = 0;
        }
        return slot;
    }

    /** Read in one go, so every number in a report is of the same moment. */
    synchronized Load read() {
        long now = clock.getAsLong();
        int total = 0;
        int failures = 0;
        int traces = 0;
        long main = 0;
        long worst = 0;
        long trace = 0;

        for (int i = 0; i < WINDOW_SECONDS; i++) {
            long age = now - second[i];
            if (age <= 0 || age >= WINDOW_SECONDS) continue;

            total += captures[i];
            failures += failed[i];
            traces += traced[i];
            main += mainNanos[i];
            trace += traceNanos[i];
            worst = Math.max(worst, worstNanos[i]);
        }

        // Divided by the whole window rather than by the seconds that had something in them: captures come in bursts,
        // and counting only the busy seconds would report one capture four seconds ago as one a second.
        return new Load(total, total / (double) COUNTED_SECONDS, main / COUNTED_SECONDS, worst,
                traces == 0 ? 0 : trace / traces, failures);
    }

    /**
     * @param perSecond           captures a second, averaged over the window
     * @param mainNanosPerSecond  what the tick spent on them in one second, which against the 1000 ms a second of tick
     *                            there is to spend is the share of the server they took
     * @param worstMainNanos      the most any one capture spent inside a tick, since a single long copy is a stutter
     *                            that an average hides
     * @param traceNanosEach      how long one capture takes off the main thread, which is what decides how many a
     *                            second this machine can keep up with
     */
    record Load(int captures, double perSecond, long mainNanosPerSecond, long worstMainNanos, long traceNanosEach,
                int failed) {

        boolean idle() {
            return captures == 0 && failed == 0;
        }

        /**
         * The same time per tick rather than per second, which is the unit a Minecraft server is read in - and the
         * unit {@code camera.live.max-ms-per-tick} is written in, so the two can be held against each other.
         */
        long mainNanosPerTick() {
            return mainNanosPerSecond / WINDOW_TICKS;
        }

        /** Share of the main thread, where a tick is 50 ms and there are 20 of them in a second. */
        double tickPercent() {
            return mainNanosPerSecond / 10_000_000.0;
        }
    }
}
