package de.flog99.mapgui.plugin.camera;

import java.util.Locale;

/**
 * What one capture cost, split the way the work itself is split.
 *
 * <p>Reported per stage rather than as one number because the stages are not interchangeable: the copy happens on the
 * main thread and is the only part that can cost the server its tick, while the trace happens on a pool and cannot.
 * A capture that is slow because of the copy and one that is slow because of the trace want different answers.
 *
 * <p>Only work is counted. The two scheduler hops - out to a worker and back onto the main thread - used to be here
 * and are not any more: a task posted for the main thread waits for the next tick, so the trip back is 40 ms of
 * nothing whatever the camera does, and reporting it made every capture look like a 50 ms one. The total is the four
 * stages added up.
 *
 * @param number  which capture this is since the server started, because the first few are slower than the rest and
 *                the reason is the JIT rather than anything here
 * @param chunks  chunk columns copied out of the world, which is what the copy time is really a function of
 * @param filled  sections with blocks in them, out of {@code sections}
 */
record CaptureTimings(int size, int number, int chunks, int filled, int sections, int entities,
                      long copyNanos, long entityNanos, long traceNanos, long paletteNanos) {

    /** The four stages added up, which is what the capture actually spent. */
    long totalNanos() {
        return copyNanos + entityNanos + traceNanos + paletteNanos;
    }

    static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.1fms", nanos / 1_000_000.0);
    }
}
