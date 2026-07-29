package de.flog99.mapgui;

import java.util.function.LongSupplier;

/**
 * A rolling count of bytes, for answering "what is this costing right now".
 *
 * <p>Bucketed by the second rather than totalled, since a total since startup says nothing about a server
 * lagging now. Each bucket carries the second it belongs to, so anything older than the window reads as
 * nothing without being swept.
 *
 * <p>Counts <b>map payload before compression</b>. Minecraft deflates packets over its threshold, so the
 * wire figure is lower - much lower for flat menu colors, barely at all for dithered video. Treat it as a
 * ceiling.
 */
public final class Bandwidth {

    /** Long enough to smooth out a stalled tick, short enough to still read as "right now". */
    private static final int WINDOW_SECONDS = 5;

    private final long[] bytes = new long[WINDOW_SECONDS];
    private final long[] second = new long[WINDOW_SECONDS];
    private final LongSupplier clock;

    public Bandwidth() {
        this(() -> System.currentTimeMillis() / 1000);
    }

    /** Wound by hand in tests, since the whole thing is about which second a byte landed in. */
    Bandwidth(LongSupplier seconds) {
        this.clock = seconds;
    }

    public void add(long count) {
        long now = clock.getAsLong();
        int slot = Math.floorMod(now, WINDOW_SECONDS);

        if (second[slot] != now) {
            second[slot] = now;
            bytes[slot] = 0;
        }
        bytes[slot] += count;
    }

    /** Bytes a second, averaged over the window. The current second is left out, since it is still filling and would read low. */
    public long perSecond() {
        long now = clock.getAsLong();
        long total = 0;
        int counted = 0;

        for (int i = 0; i < WINDOW_SECONDS; i++) {
            long age = now - second[i];
            if (age <= 0 || age >= WINDOW_SECONDS) continue;

            total += bytes[i];
            counted++;
        }
        return counted == 0 ? 0 : total / counted;
    }

    /** "1.2 MB/s (9.8 Mbit/s)" - the pair of numbers anyone sizing a host wants. */
    public static String describe(long bytesPerSecond) {
        double megabits = bytesPerSecond * 8 / 1_000_000.0;
        String rate = bytesPerSecond >= 1024 * 1024
                ? String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024))
                : String.format("%d KB/s", bytesPerSecond / 1024);
        return rate + " (" + String.format("%.1f", megabits) + " Mbit/s)";
    }
}
