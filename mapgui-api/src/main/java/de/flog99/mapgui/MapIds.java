package de.flog99.mapgui;

/**
 * Hands out map ids the server never allocated.
 *
 * <p>Nothing registers these - the client makes a cache entry for whatever id it is sent pixels for. They
 * count down from the top so they cannot paint over a real map the player has looked at.
 *
 * <p>One counter for the whole plugin: held maps and walls draw from the same range, and two counters
 * starting at the top would hand out the same numbers twice.
 */
public final class MapIds {

    private static int next = Integer.MAX_VALUE;

    private MapIds() {
    }

    public static synchronized int next() {
        return next--;
    }
}
