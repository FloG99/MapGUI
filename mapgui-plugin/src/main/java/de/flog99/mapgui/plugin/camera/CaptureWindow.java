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
    private final int[] paced = new int[WINDOW_SECONDS];
    private final int[] dropped = new int[WINDOW_SECONDS];
    private final long[] mainNanos = new long[WINDOW_SECONDS];
    private final long[] copyNanos = new long[WINDOW_SECONDS];
    private final long[] columns = new long[WINDOW_SECONDS];
    private final long[] reused = new long[WINDOW_SECONDS];
    private final long[] filledSections = new long[WINDOW_SECONDS];
    private final long[] sections = new long[WINDOW_SECONDS];
    private final long[] entitiesBuilt = new long[WINDOW_SECONDS];
    private final long[] blockEntitiesBuilt = new long[WINDOW_SECONDS];
    private final long[] entitiesWanted = new long[WINDOW_SECONDS];
    private final long[] entitiesReused = new long[WINDOW_SECONDS];
    private final long[] entityNanos = new long[WINDOW_SECONDS];
    private final long[] blockEntityNanos = new long[WINDOW_SECONDS];
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
    synchronized void captured(long copy, long entities, long blockEntities, boolean asked) {
        int slot = slot();
        long nanos = copy + entities + blockEntities;
        captures[slot]++;
        mainNanos[slot] += nanos;
        copyNanos[slot] += copy;
        entityNanos[slot] += entities;
        blockEntityNanos[slot] += blockEntities;
        worstNanos[slot] = Math.max(worstNanos[slot], nanos);
        if (asked) {
            paced[slot]++;
        }
    }

    /**
     * A capture turned away because the trace was too far behind to take another.
     *
     * <p>Counted apart from a failure, since it is not one: nothing broke, the machine was asked for more than it
     * could draw. They are the same to whoever asked - a null shot - and completely different to whoever has to
     * decide what to do about it.
     */
    synchronized void turnedAway() {
        dropped[slot()]++;
    }

    /**
     * What the copy actually went through, for working out why it cost what it did.
     *
     * <p>Separate from {@link #captured} only to keep that one readable; both land in the same second, and the
     * averages divide by the same capture count.
     *
     * @param wanted     columns the frustum asked for, {@code fromCache} how many of them came back without being
     *                   copied, and {@code filled} how many of the sections in them held anything
     * @param askedEntities  entities the capture looked for a held shape for, and {@code reusedEntities} how many had one
     */
    synchronized void copied(int wanted, int fromCache, int filled, int total, int entities, int blockEntities,
                             int askedEntities, int reusedEntities) {
        int slot = slot();
        entitiesBuilt[slot] += entities;
        blockEntitiesBuilt[slot] += blockEntities;
        entitiesWanted[slot] += askedEntities;
        entitiesReused[slot] += reusedEntities;
        columns[slot] += wanted;
        reused[slot] += fromCache;
        filledSections[slot] += filled;
        sections[slot] += total;
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
            paced[slot] = 0;
            dropped[slot] = 0;
            mainNanos[slot] = 0;
            copyNanos[slot] = 0;
            columns[slot] = 0;
            reused[slot] = 0;
            filledSections[slot] = 0;
            sections[slot] = 0;
            entitiesBuilt[slot] = 0;
            blockEntitiesBuilt[slot] = 0;
            entitiesWanted[slot] = 0;
            entitiesReused[slot] = 0;
            entityNanos[slot] = 0;
            blockEntityNanos[slot] = 0;
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
        int asked = 0;
        int turnedAway = 0;
        int failures = 0;
        int traces = 0;
        long main = 0;
        long copy = 0;
        long entities = 0;
        long blockEntities = 0;
        long wanted = 0;
        long fromCache = 0;
        long filled = 0;
        long allSections = 0;
        long entitiesTotal = 0;
        long blockEntitiesTotal = 0;
        long entitiesAsked = 0;
        long entitiesFromCache = 0;
        long worst = 0;
        long trace = 0;

        for (int i = 0; i < WINDOW_SECONDS; i++) {
            long age = now - second[i];
            if (age <= 0 || age >= WINDOW_SECONDS) continue;

            total += captures[i];
            asked += paced[i];
            turnedAway += dropped[i];
            failures += failed[i];
            traces += traced[i];
            main += mainNanos[i];
            copy += copyNanos[i];
            entities += entityNanos[i];
            blockEntities += blockEntityNanos[i];
            wanted += columns[i];
            fromCache += reused[i];
            filled += filledSections[i];
            allSections += sections[i];
            entitiesTotal += entitiesBuilt[i];
            blockEntitiesTotal += blockEntitiesBuilt[i];
            entitiesAsked += entitiesWanted[i];
            entitiesFromCache += entitiesReused[i];
            trace += traceNanos[i];
            worst = Math.max(worst, worstNanos[i]);
        }

        // Divided by the whole window rather than by the seconds that had something in them: captures come in bursts,
        // and counting only the busy seconds would report one capture four seconds ago as one a second.
        return new Load(total, asked, total / (double) COUNTED_SECONDS,
                (total - asked) / (double) COUNTED_SECONDS, main / COUNTED_SECONDS, worst,
                total == 0 ? 0 : copy / total, total == 0 ? 0 : entities / total,
                total == 0 ? 0 : blockEntities / total,
                traces == 0 ? 0 : trace / traces, turnedAway, failures,
                total == 0 ? 0 : wanted / (double) total,
                wanted == 0 ? 0 : 100.0 * fromCache / wanted,
                total == 0 ? 0 : filled / (double) total,
                total == 0 ? 0 : allSections / (double) total,
                total == 0 ? 0 : entitiesTotal / (double) total,
                entitiesAsked == 0 ? 0 : 100.0 * entitiesFromCache / entitiesAsked,
                total == 0 ? 0 : blockEntitiesTotal / (double) total);
    }

    /**
     * @param perSecond           captures a second, averaged over the window
     * @param mainNanosPerSecond  what the tick spent on them in one second, which against the 1000 ms a second of tick
     *                            there is to spend is the share of the server they took
     * @param worstMainNanos      the most any one capture spent inside a tick, since a single long copy is a stutter
     *                            that an average hides
     * @param traceNanosEach      how long one capture takes off the main thread, which is what decides how many a
     *                            second this machine can keep up with
     * @param paced               how many of them asked {@code readyForFrame} first. The rest are outside the live
     *                            budget entirely, which is worth saying out loud: an admin who set one and is being
     *                            ignored cannot otherwise tell
     * @param dropped             captures turned away because the trace was too far behind
     * @param copyNanosEach       what copying the world's blocks cost one capture, {@code entityNanosEach} what
     *                            gathering what is standing in it cost, and {@code blockEntityNanosEach} what
     *                            gathering what is bolted to it cost. Three stages rather than two, because they are
     *                            three different fixes: fewer columns, fewer entities, fewer chests
     */
    record Load(int captures, int paced, double perSecond, double unpacedPerSecond, long mainNanosPerSecond,
                long worstMainNanos, long copyNanosEach, long entityNanosEach, long blockEntityNanosEach,
                long traceNanosEach, int dropped, int failed, double chunksEach, double reusedPercent,
                double filledSectionsEach, double sectionsEach, double entitiesEach, double entitiesReusedPercent,
                double blockEntitiesEach) {

        /** What one capture took out of a tick, which against the budget is what decides a live view's rate. */
        long mainNanosEach() {
            return copyNanosEach + entityNanosEach + blockEntityNanosEach;
        }

        boolean idle() {
            return captures == 0 && failed == 0 && dropped == 0;
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
