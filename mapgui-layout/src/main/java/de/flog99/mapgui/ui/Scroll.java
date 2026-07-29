package de.flog99.mapgui.ui;

import java.awt.Color;
import java.util.List;

/**
 * Vertically scrolling container with an optional scrollbar.
 *
 * <p>The offset lives on the node, but {@code Screen} restores it by key after each rebuild so
 * scrolling survives a state change that recreates the tree.
 */
public final class Scroll extends AbstractContainer<Scroll> {

    private static final int BAR_WIDTH = 3;
    private static final int BAR_GAP = 2;

    private int gap;
    private int offset;
    private boolean showBar = true;
    private Color barColor = new Color(150, 158, 175);
    private Color trackColor = new Color(52, 56, 70);

    private int scrollMs = Animator.DEFAULT_DURATION_MS;
    private int contentHeight;
    private int viewportHeight;
    private boolean barVisible;
    private double shownOffset;

    public Scroll gap(int pixels) {
        this.gap = pixels;
        return this;
    }

    public Scroll scrollbar(boolean value) {
        this.showBar = value;
        return this;
    }

    public Scroll scrollbarColors(Color bar, Color track) {
        this.barColor = bar;
        this.trackColor = track;
        return this;
    }

    /** How long a scroll takes to settle. Zero jumps straight there. */
    public Scroll scrollDuration(int millis) {
        this.scrollMs = millis;
        return this;
    }

    public int offset() {
        return offset;
    }

    public Scroll offset(int value) {
        this.offset = Math.max(0, value);
        return this;
    }

    public int maxOffset() {
        return Math.max(0, contentHeight - viewportHeight);
    }

    /** Returns true if the offset actually moved, so callers can skip a pointless repaint. */
    public boolean scrollBy(int delta) {
        int next = Math.max(0, Math.min(offset + delta, maxOffset()));
        if (next == offset) return false;

        offset = next;
        return true;
    }

    @Override
    protected Measured measureContent(LayoutContext context, int availableWidth, int availableHeight) {
        List<Node> kids = visibleChildren();
        if (kids.isEmpty()) return Measured.ZERO;

        int total = gap * (kids.size() - 1);
        int widest = 0;
        for (Node kid : kids) {
            Measured measured = kid.measure(context, availableWidth, Node.UNBOUNDED);
            total += measured.height();
            widest = Math.max(widest, measured.width());
        }
        return new Measured(widest, total);
    }

    @Override
    protected void arrangeContent(LayoutContext context, Rect content) {
        List<Node> kids = visibleChildren();
        viewportHeight = content.height();

        // Measured at the full width first, only to decide whether a bar is needed. Taking width away can
        // only make content taller, so something that overflowed without a bar still overflows with one -
        // there is nothing here to oscillate.
        int loose = totalHeight(context, kids, content.width());
        barVisible = showBar && loose > viewportHeight;

        int width = barVisible ? Math.max(0, content.width() - BAR_WIDTH - BAR_GAP) : content.width();
        // Then measured again at the width children are actually given. Narrower text wraps onto more
        // lines, and a content height measured too small stops the scroll before the last child is
        // reachable - which reads as "the bottom of the menu is cut off".
        contentHeight = barVisible ? totalHeight(context, kids, width) : loose;

        offset = Math.min(offset, maxOffset());

        // The wheel sets a target; this is where the content has actually got to.
        shownOffset = animated("scroll", offset, scrollMs, Easing.EASE_OUT);
        int y = content.y() - (int) Math.round(shownOffset);
        for (Node kid : kids) {
            Measured measured = kid.measure(context, width, Node.UNBOUNDED);
            int kidWidth = kid.widthSizing().isFill() ? width : Math.min(measured.width(), width);
            kid.arrange(context, new Rect(content.x(), y, kidWidth, measured.height()));
            y += measured.height() + gap;
        }
    }

    private int totalHeight(LayoutContext context, List<Node> kids, int width) {
        int total = gap * Math.max(0, kids.size() - 1);
        for (Node kid : kids) {
            total += kid.measure(context, width, Node.UNBOUNDED).height();
        }
        return total;
    }

    @Override
    protected void paintContent(Painter painter) {
        Rect viewport = contentBounds();
        Rect previous = painter.pushClip(viewport);
        super.paintContent(painter);
        painter.popClip(previous);

        if (!barVisible) return;

        int x = viewport.right() - BAR_WIDTH;
        painter.rect(new Rect(x, viewport.y(), BAR_WIDTH, viewport.height()), trackColor, 0, null, 1);

        int thumbHeight = Math.max(6, viewport.height() * viewportHeight / contentHeight);
        int travel = viewport.height() - thumbHeight;
        int thumbY = viewport.y()
                + (maxOffset() == 0 ? 0 : (int) Math.round(travel * shownOffset / maxOffset()));
        painter.rect(new Rect(x, thumbY, BAR_WIDTH, thumbHeight), barColor, 0, null, 1);
    }

    /** Always takes part in hit testing so the scroll wheel can find it under the cursor. */
    @Override
    public boolean interactive() {
        return true;
    }
}
