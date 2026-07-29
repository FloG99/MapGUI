package de.flog99.mapgui.ui;

import java.util.List;

/**
 * One element of the layout tree.
 *
 * <p>Layout runs in two passes: {@link #measure} reports the size the node wants given the
 * space on offer, then {@link #arrange} commits the final rect. Measure takes a width because
 * text height depends on wrapping, which depends on the width available - an intrinsic-size
 * pass alone could not express that.
 */
public interface Node {

    /** Stand-in for "as much as you like", used for the content of a scroll container. */
    int UNBOUNDED = 1 << 16;

    Measured measure(LayoutContext context, int availableWidth, int availableHeight);

    void arrange(LayoutContext context, Rect bounds);

    Rect bounds();

    void paint(Painter painter);

    List<Node> children();

    boolean hidden();

    String key();

    /** Stable identity across rebuilds, used to file away whatever the screen remembers per node. */
    String identity();

    /** Map cursor to show while hovered, named as a {@code MapCursor.Type} constant, or null. */
    String cursorIcon();

    /** Text to show under the cursor while hovered, or null. */
    String caption();

    Sizing widthSizing();

    Sizing heightSizing();

    /**
     * Whether this node wants a repaint every time the cursor moves inside it, not just when it becomes
     * the hovered one. For anything that draws something at the cursor, like a map highlighting a chunk.
     */
    default boolean tracksCursor() {
        return false;
    }

    /** How pressed this node looks, 1 right after a click and fading back to 0. */
    default double pressed() {
        return 0;
    }

    /** Where this node sits when it is overlaid on its siblings rather than laid out beside them. */
    default Justify placeX() {
        return Justify.START;
    }

    default Align placeY() {
        return Align.START;
    }

    /** Whether this node reacts to the cursor, and so takes part in hit testing. */
    boolean interactive();

    /** Coordinates are relative to this node's own top left. */
    void click(int x, int y);

    void hoverChanged(boolean hovered);

    /** Topmost interactive node containing the point, or {@code null}. */
    default Node hitTest(int x, int y) {
        if (hidden() || !bounds().contains(x, y)) return null;

        List<Node> children = children();
        for (int i = children.size() - 1; i >= 0; i--) {
            Node hit = children.get(i).hitTest(x, y);
            if (hit != null) return hit;
        }
        return interactive() ? this : null;
    }
}
