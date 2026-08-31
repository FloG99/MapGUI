package de.flog99.mapgui.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

/**
 * Puts one of MapGUI's events through the server.
 *
 * <p>Every call reaches here from the main thread and nothing here hops. That is the whole contract: a Bukkit
 * event raised off the main thread is a bug in every listener that reads the world, and MapGUI's input arrives
 * on the network thread - so each caller fires <i>after</i> its own hop rather than trusting this to do it.
 */
final class Events {

    private Events() {
    }

    /**
     * Nothing to ask when there is no server.
     *
     * <p>The wall and screen machinery runs headless: {@code mapgui-api}'s own tests open walls and tick them,
     * and {@code mapgui-preview} lays screens out with no plugin manager behind either. Both would otherwise
     * fail here rather than in anything they were testing.
     */
    static void fire(Event event) {
        if (Bukkit.getServer() == null) return;

        Bukkit.getPluginManager().callEvent(event);
    }

    /** The same, for an event whose answer is whether it may go ahead. */
    static <E extends Event & Cancellable> boolean allows(E event) {
        fire(event);
        return !event.isCancelled();
    }
}
