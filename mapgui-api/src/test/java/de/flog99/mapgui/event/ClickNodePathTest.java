package de.flog99.mapgui.event;

import de.flog99.mapgui.MapTextFont;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.ui.AbstractNode;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.Panel;
import de.flog99.mapgui.ui.Rect;
import org.junit.jupiter.api.Test;

import static de.flog99.mapgui.ui.Ui.Button;
import static de.flog99.mapgui.ui.Ui.Column;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The node path {@link MapGuiClickEvent} carries, which is the whole reason the event is worth having: a
 * listener that does not own the screen can tell which control was pressed, where a pixel position tells it
 * nothing.
 *
 * <p>Worth testing without a server because it is the one piece of the event with any logic in it, and because
 * a wrong answer is invisible - a path that names the wrong node reads exactly like one that names the right
 * one.
 */
class ClickNodePathTest {

    private static Screen laidOut(Node root) {
        Screen screen = new Screen() {
            @Override
            protected Node build() {
                return root;
            }
        };
        screen.layout(MapTextFont.INSTANCE, new Rect(0, 0, 128, 128));
        return screen;
    }

    @Test
    void aPressNamesTheNodeUnderIt() {
        Panel root = Column(Button("top"), Button("bottom"));
        Screen screen = laidOut(root);

        Node top = screen.root().children().get(0);
        String path = MapGuiClickEvent.nodeAt(screen, top.bounds().x() + 1, top.bounds().y() + 1);

        assertEquals(top.identity(), path);
    }

    @Test
    void aKeyIsWhatTheEventReportsWhenOneWasSet() {
        Panel root = Column(Button("top"));
        ((AbstractNode<?>) root.children().get(0)).key("settings/volume");

        Screen screen = laidOut(root);
        Node button = screen.root().children().get(0);

        assertEquals("settings/volume", MapGuiClickEvent.nodeAt(screen, button.bounds().x() + 1, button.bounds().y() + 1));
    }

    /** A cursorless screen is clicked at -1, which is not a position and cannot name anything. */
    @Test
    void aClickWithNoPositionNamesNothing() {
        Screen screen = laidOut(Column(Button("top")));

        assertNull(MapGuiClickEvent.nodeAt(screen, -1, -1));
    }

    @Test
    void aPressOnEmptySpaceNamesNothing() {
        Panel root = Column(Button("top"));
        Screen screen = laidOut(root);

        Node button = screen.root().children().get(0);
        assertNull(MapGuiClickEvent.nodeAt(screen, button.bounds().x() + 1, 127));
    }
}
