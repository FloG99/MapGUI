package de.flog99.mapgui.camera;

/**
 * A running live view, kept fed for you - see {@link Camera#feed}.
 *
 * <p>Nothing to tick and nothing to pace. Close it when the screen showing it closes; it also stops on its own if
 * the player leaves, so a forgotten one cannot outlive them.
 */
public interface CameraFeed {

    /** Stops it. Safe to call twice, and safe to call from inside a frame. */
    void close();

    /** False once closed, or once the player it was following went offline. */
    boolean running();
}
