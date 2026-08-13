package de.flog99.mapgui;

import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.Ui;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A/B equivalence for the {@code cursorMoved} early-exit: hit-testing an aim that has not moved must be
 * skipped (the common animating-wall case), but any rebuild - a fresh {@code root} from {@code layout()} -
 * must still be re-hit-tested exactly as the old code did, and a moved aim must behave identically.
 */
class CursorMovedAbTest {

    private static final int[] HOVER = {0};

    private static final class Menu extends Screen {
        int builds;

        @Override
        protected Node build() {
            builds++;
            return Ui.Column(
                    Ui.Button("one").size(40, 20).onHover(
                            () -> HOVER[0]++,
                            () -> HOVER[0] = -1
                    ),
                    Ui.Spacer(),
                    Ui.Button("two").size(40, 20)
            );
        }
    }

    private static Menu drawn() {
        Menu screen = new Menu();
        screen.layout(MapTextFont.INSTANCE, new Rect(0, 0, 128, 128));
        return screen;
    }

    /** A point inside the first button, whose bounds are at the top-left of the canvas. */
    private static int[] inFirstButton(Screen screen) {
        Node first = screen.root().children().get(0);
        return new int[]{first.bounds().x() + 2, first.bounds().y() + 2};
    }

    @Test
    void unchangedCoordinatesDoNotWalkTheTree() {
        Menu screen = drawn();
        int[] p = inFirstButton(screen);

        HOVER[0] = 0;
        assertTrue(screen.cursorMoved(p[0], p[1]));
        assertEquals(1, HOVER[0], "first move lands on the button");

        // Same coords, same root: nothing to do - no walk, no extra hover, no repaint request.
        assertFalse(screen.cursorMoved(p[0], p[1]));
        assertFalse(screen.cursorMoved(p[0], p[1]));
        assertEquals(1, HOVER[0], "hover must not fire again for an unmoved aim");
    }

    @Test
    void rebuildForcesReHitTestEvenWhenAimDidNotMove() {
        Menu screen = drawn();
        int[] p = inFirstButton(screen);
        HOVER[0] = 0;
        assertTrue(screen.cursorMoved(p[0], p[1]));
        assertEquals(1, HOVER[0]);

        // A rebuild hands back a fresh tree; the hover must re-resolve against it.
        Node oldRoot = screen.root();
        screen.layout(MapTextFont.INSTANCE, new Rect(0, 0, 128, 128));
        assertNotSame(oldRoot, screen.root());

        // The new tree's button is a different object, but the same spot - hover re-fires on it.
        int before = HOVER[0];
        screen.cursorMoved(p[0], p[1]);
        assertEquals(before + 1, HOVER[0], "hover follows the rebuilt tree");
    }

    @Test
    void movedAimStillResolvesAndReports() {
        Menu screen = drawn();
        int[] p = inFirstButton(screen);
        HOVER[0] = 0;
        assertTrue(screen.cursorMoved(p[0], p[1]));
        assertEquals(1, HOVER[0]);

        // Move to empty space far below the buttons: hover leaves (sets HOVER to -1).
        assertTrue(screen.cursorMoved(p[0], 120));
        assertEquals(-1, HOVER[0], "leaving the button fires hover-stop, exactly as before");
    }
}
