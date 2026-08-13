package de.flog99.mapgui;

/**
 * Hands out map ids the server never allocated.
 *
 * <p>Nothing registers these - the client makes a cache entry for whatever id it is sent pixels for. They count down
 * from below the band {@link HandOptions#mapId(int)} pins in, so they meet neither a pinned id nor a real map, which
 * the server allocates upwards from 0.
 *
 * <p>One counter for the whole plugin, and a client keeps one picture per id: every surface it can see at once needs
 * its own. Nothing is handed back, which two billion of them affords and an id stamped into an item would defeat.
 */
public final class MapIds {

    /** How many ids at the top are never handed out, so a plugin can pin one and keep it. */
    public static final int RESERVED = 1024;

    /** The lowest id this will never draw to, so the lowest one a plugin can pin and be sure of. */
    public static final int LOWEST_PINNABLE = Integer.MAX_VALUE - RESERVED + 1;

    private static int next = LOWEST_PINNABLE - 1;

    private MapIds() {
    }

    public static synchronized int next() {
        return next--;
    }
}
