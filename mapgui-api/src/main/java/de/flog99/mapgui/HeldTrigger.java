package de.flog99.mapgui;

/**
 * A standing rule registered with {@link MapGui#openWhileHolding}, and the handle that takes it away again.
 *
 * <p>Cancel it in {@code onDisable}, for the same reason {@link GuiCatalog#unregister} exists: a trigger left
 * behind points at a factory whose classes are about to be unloaded.
 */
public interface HeldTrigger {

    /**
     * Stops watching, and closes any screen this trigger currently has open.
     *
     * <p><b>Main thread only</b>, since closing those screens touches the server.
     */
    void cancel();
}
