package de.flog99.mapgui.examples.sketch;

import de.flog99.mapgui.MapGui;

/**
 * A held drawing board, and the one thing a map GUI could not do until now: read a button being <b>held</b>.
 *
 * <p>Everything else here is ordinary - one screen, one player, a byte per pixel. What makes it worth its own demo
 * is {@link de.flog99.mapgui.Screen#holdable()}, which turns a held right-click from a series of guesses into a
 * press, a tick for as long as it is down, and a release. Drawing is what that difference looks like: a line
 * rather than a dotted one.
 *
 * <p>The {@code register} call is the whole integration, like every other demo - see {@link SketchScreen} for the
 * three methods that make the pen.
 */
public final class SketchDemo {

    private static final String NAME = "sketch";

    public void register() {
        MapGui.get().guis().registerOpenable(NAME, "A drawing board - one line per held click", player -> new SketchScreen());
    }

    public void unregister() {
        MapGui.get().guis().unregister(NAME);
    }
}
