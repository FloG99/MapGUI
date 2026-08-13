package de.flog99.mapgui;

/**
 * Hands out map ids the server never allocated.
 *
 * <p>Nothing registers these - the client makes a cache entry for whatever id it is sent pixels for. They
 * count down from the top so they cannot paint over a real map the player has looked at, starting just under the
 * handful left for {@link HandOptions#mapId(int)} to pin.
 *
 * <p>One counter for the whole plugin: held maps and walls draw from the same range, and two counters
 * starting at the top would hand out the same numbers twice.
 *
 * <p><b>Where the top matters and where the counting does.</b> Starting at the top is what keeps these clear of the
 * server's own, which are allocated upwards from 0 - a single constant would do that just as well. The counting is
 * for something else: every surface one client can see at once needs its own id, since the client keys its cached
 * pixels on nothing else. That is a player's own screen, every tile of every wall in view, every prerendered frame
 * of a video wall, and every GUI item in anybody's hands. Two of those sharing an id is one drawn with the other's
 * pixels.
 *
 * <p>Nothing is ever handed back. A screen that closes leaks its id, which is affordable at two billion of them and
 * is the reason there is no bookkeeping here: an id stamped into an item can outlive the plugin's memory of it, so
 * "free" would have to mean "no such item exists anywhere", which nothing can answer.
 *
 * <p>A plugin that wants a fixed id - so a resource pack can recognise one screen - pins it instead, with
 * {@link HandOptions#mapId(int)}.
 */
public final class MapIds {

    /**
     * How many ids at the very top are never handed out, so a plugin can pin one and keep it.
     *
     * <p>A thousand of them, because the cost is nothing - a two billionth of the range - and because the top is
     * where anybody would reach first. Without the band, {@code Integer.MAX_VALUE - 1} is the second id this hands
     * out, which is the worst possible answer to "which one is safe to write into my resource pack".
     */
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
