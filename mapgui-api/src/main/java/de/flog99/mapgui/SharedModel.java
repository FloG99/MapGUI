package de.flog99.mapgui;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Something more than one screen draws, which redraws all of them when it changes.
 *
 * <p>Two screens are two objects even when they show the same thing: one per viewer on a per-player wall, one
 * per wall when two are put up, one per player holding a map. Pixels cannot be shared - a map tile is one
 * buffer - so the model underneath is. Without this, every screen but the one that was clicked keeps drawing
 * what it last read.
 *
 * <p>Extend it, call {@link #changed()} after anything a screen would draw differently, and have each screen
 * {@link Screen#watch} it. Watching ends when the screen closes, so there is nothing to unregister - which
 * matters, because a listener holding a screen keeps that screen, its state and its viewer alive.
 *
 * <p>Main thread only, like everything else a screen touches.
 */
public abstract class SharedModel {

    private final Set<Screen> watchers = new LinkedHashSet<>();

    /** Marks every screen watching this as needing to be drawn again. Cheap to call often. */
    protected final void changed() {
        // Copied, since a screen is free to stop watching - or close - while being told.
        for (Screen screen : List.copyOf(watchers)) screen.invalidate();
    }

    void watchedBy(Screen screen) {
        watchers.add(screen);
    }

    void forgottenBy(Screen screen) {
        watchers.remove(screen);
    }
}
