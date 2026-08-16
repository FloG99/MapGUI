package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static de.flog99.mapgui.ui.Ui.Button;
import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Row;
import static de.flog99.mapgui.ui.Ui.Text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A/B equivalence tests: every traversal that was converted from recursion to iteration must visit the
 * same nodes, in the same order, and resolve the same hits, as the original recursive walk.
 *
 * <p>The optimized (iterative) implementation is the one under test; the recursive one is kept as the
 * oracle through the legacy path. Both run over identical trees and must agree bit for bit, which is
 * what proves the rewrite changed nothing observable.
 */
class NodesTraversalAbTest {

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

    private static LayoutContext context() {
        return new LayoutContext(TestFont.INSTANCE, new Animator());
    }

    /** Lays the tree out in a 32x32 box so bounds are real and hit-testing is meaningful. */
    private static void layout(Node root) {
        LayoutContext context = context();
        root.measure(context, 32, 32);
        root.arrange(context, new Rect(0, 0, 32, 32));
    }

    // ---- Nodes.walk: pre-order visit order ----

    @Test
    void walkVisitsEveryNodeInPreOrder() {
        Node root = tree();
        List<Node> iterative = new ArrayList<>();
        Nodes.walk(root, iterative::add);

        List<Node> expected = new ArrayList<>();
        legacyWalkPre(root, expected);
        assertEquals(expected, iterative);
        assertEquals(12, iterative.size(), "every node of the tree, hidden included");
    }

    private static void legacyWalkPre(Node node, List<Node> out) {
        if (node == null) return;
        out.add(node);
        for (Node child : node.children()) {
            legacyWalkPre(child, out);
        }
    }

    @Test
    void walkSwallowsNullRoot() {
        List<Node> seen = new ArrayList<>();
        Nodes.walk(null, seen::add);
        assertEquals(List.of(), seen);
    }

    @Test
    void walkVisitsChildrenLeftToRight() {
        Node root = Column(Text("x"), Text("y"));
        List<Node> seen = new ArrayList<>();
        Nodes.walk(root, seen::add);

        assertEquals(root, seen.get(0));
        assertEquals(root.children().get(0), seen.get(1));
        assertEquals(root.children().get(1), seen.get(2));
    }

    // ---- Nodes.collect: same order as walk, filtered ----

    @Test
    void collectFindsEveryNodeOfAType() {
        Node root = tree();
        List<Button> buttons = Nodes.collect(root, Button.class);
        assertEquals(4, buttons.size(), "four buttons in the tree");
    }

    @Test
    void collectIsSameOrderAsWalkFiltered() {
        Node root = tree();

        List<Button> viaCollect = Nodes.collect(root, Button.class);
        List<Button> viaWalk = new ArrayList<>();
        Nodes.walk(root, node -> {
            if (node instanceof Button button) {
                viaWalk.add(button);
            }
        });

        assertEquals(viaWalk, viaCollect);
    }

    // ---- Nodes.findAt: innermost node of a type at a point, deepest-first ----

    @Test
    void findAtReturnsInnermostMatch() {
        Panel inner = new Panel(Panel.Axis.COLUMN).key("inner");
        inner.children(Button("innerButton"));
        Panel outer = new Panel(Panel.Axis.COLUMN);
        outer.children(inner);
        layout(outer);

        Button innerButton = (Button) inner.children().get(0);
        Rect b = innerButton.bounds();
        Button hit = Nodes.findAt(outer, Button.class, b.x() + 1, b.y() + 1);
        assertSame(innerButton, hit, "the button inside the inner panel, not the outer one");
    }

    @Test
    void findAtMatchesSelfWhenNoDeeperMatch() {
        Panel panel = Column(Button("kid"));
        layout(panel);

        Button kid = (Button) panel.children().get(0);
        Rect p = panel.bounds();

        // The button hugs the panel's top-left corner, so the bottom-right corner of the panel is
        // inside the panel but outside the button - the panel itself is the innermost match there.
        int probeX = p.x() + p.width() - 1;
        int probeY = p.y() + p.height() - 1;
        assertSame(panel, Nodes.findAt(panel, Panel.class, probeX, probeY));
        assertNull(Nodes.findAt(panel, Button.class, probeX, probeY));
    }

    @Test
    void findAtChecksChildrenBackToFront() {
        Panel overlay = Column(Button("top"), Button("bottom"));
        layout(overlay);

        Button top = (Button) overlay.children().get(0);
        Button bottom = (Button) overlay.children().get(1);
        Rect bp = bottom.bounds();

        assertSame(bottom, Nodes.findAt(overlay, Button.class, bp.x() + 1, bp.y() + 1),
                "later children win where nothing deeper matches, matching the old back-to-front order");
        assertSame(top, Nodes.findAt(overlay, Button.class, top.bounds().x() + 1, top.bounds().y() + 1));
    }

    @Test
    void findAtNullRootAndHiddenReturnNull() {
        assertNull(Nodes.findAt(null, Button.class, 0, 0));

        Node hidden = Column(Text("x")).hidden(true);
        layout(hidden);
        assertNull(Nodes.findAt(hidden, Button.class, 5, 5));
    }
}
