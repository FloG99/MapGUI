package de.flog99.mapgui;

import de.flog99.mapgui.ui.AbstractNode;
import de.flog99.mapgui.ui.Animator;
import de.flog99.mapgui.ui.LayoutContext;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.Panel;
import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.TextFont;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static de.flog99.mapgui.ui.Ui.Button;
import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Row;
import static de.flog99.mapgui.ui.Ui.Text;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A/B equivalence for {@link Screen#assignPaths}: the path strings every node's {@code identity()} is
 * keyed by. The optimized (iterative) implementation must produce exactly the same strings as the
 * recursive one - a node's animations and scroll offsets survive a rebuild only if its name does not
 * change.
 *
 * <p>These pin the legacy behavior as the oracle; the rewrite keeps them green.
 */
class ScreenAssignPathsAbTest {

    /** Three levels, with hiding, so the walk has real work to do at every depth. */
    private static Node tree() {
        return Column(
                Row(
                        Button("a"),
                        Text("b").hidden(true),
                        Button("c")
                ),
                Column(
                        Text("d"),
                        Column(
                                Button("e"),
                                Text("f").hidden(true)
                        )
                ),
                Row(
                        Button("g")
                    )
        );
    }

    @Test
    void assignPathsMatchesRecursiveNaming() {
        Node root = tree();

        Screen.assignPaths(root, "0");

        assertEquals("0", root.identity());
        assertEquals("0.0", root.children().get(0).identity());
        assertSamePath("0.0.0", root.children().get(0).children().get(0));
        assertEquals("0.2", root.children().get(2).identity());
        assertEquals("0.1.1.0", root.children().get(1).children().get(1).children().get(0).identity());
    }

    private static void assertSamePath(String expected, Node node) {
        assertEquals(expected, node.identity(), () -> "node expected identity " + expected + " but got " + node.identity());
    }

    @Test
    void assignPathsRespectsExplicitKeys() {
        Panel root = Column(Button("kid"));
        ((AbstractNode<?>) root.children().get(0)).key("mine");

        Screen.assignPaths(root, "0");
        assertEquals("0", root.identity());
        assertEquals("mine", root.children().get(0).identity());
    }
}
