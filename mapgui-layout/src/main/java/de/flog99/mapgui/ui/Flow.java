package de.flog99.mapgui.ui;

import java.util.List;

/**
 * A row that wraps onto a new line when it runs out of width, and a grid when told how many columns to use.
 *
 * <p>Wrapping and gridding are one node rather than two because they are the same layout with one thing
 * decided differently - where a line ends. Left alone, a line takes as many children as fit;
 * with {@link #columns(int)} a line is exactly that many and the width is split into equal columns, so the
 * children line up down the grid as well as across it.
 *
 * <p>The cross-axis rule is per line: a child aligns within the height of its own line, not within the
 * tallest line in the flow. A wrapping row of mixed heights therefore grows no gaps that nothing asked for.
 */
public final class Flow extends AbstractContainer<Flow> {

    /** Stands for "no line gap set", so that {@link #gap(int)} can set both without overriding a real one. */
    private static final int UNSET = -1;

    private int gap;
    private int lineGap = UNSET;
    private int columns;
    private Align align = Align.START;
    private Justify justify = Justify.START;

    /** Space between children on a line, and between lines until {@link #lineGap(int)} says otherwise. */
    public Flow gap(int pixels) {
        this.gap = pixels;
        return this;
    }

    public Flow gap(int pixels, int betweenLines) {
        this.gap = pixels;
        this.lineGap = betweenLines;
        return this;
    }

    public Flow lineGap(int pixels) {
        this.lineGap = pixels;
        return this;
    }

    /**
     * How many children go on a line, which is what turns a wrapping row into a grid. Zero wraps on width.
     *
     * <p>A grid divides the width it is given, so give it a width or let it fill: unlike a wrapping row, it
     * has no narrower natural size of its own to shrink to.
     */
    public Flow columns(int count) {
        this.columns = Math.max(0, count);
        return this;
    }

    /** Cross-axis placement within a line. {@link Align#STRETCH} makes every child as tall as its own line. */
    public Flow align(Align value) {
        this.align = value;
        return this;
    }

    /** Main-axis distribution of a line's leftover width. Worth setting on a grid, whose last line is short. */
    public Flow justify(Justify value) {
        this.justify = value;
        return this;
    }

    private int lineGap() {
        return lineGap == UNSET ? gap : lineGap;
    }

    @Override
    protected Measured measureContent(LayoutContext context, int availableWidth, int availableHeight) {
        return layout(context, availableWidth, null);
    }

    @Override
    protected void arrangeContent(LayoutContext context, Rect content) {
        layout(context, content.width(), content);
    }

    /**
     * Both passes in one, so the wrap rule cannot drift between measuring and arranging - a line broken one
     * way for the size and another way for the placement would leave a hole nobody could see in the code.
     * With a null rect it only reports the size used.
     */
    private Measured layout(LayoutContext context, int availableWidth, Rect content) {
        List<Node> kids = visibleChildren();
        if (kids.isEmpty()) return Measured.ZERO;

        int count = kids.size();
        int column = columns > 0 ? Math.max(0, (availableWidth - gap * (columns - 1)) / columns) : 0;

        int[] widths = new int[count];
        int[] heights = new int[count];
        for (int i = 0; i < count; i++) {
            Node kid = kids.get(i);
            int room = columns > 0 ? column : availableWidth;
            // Measured unbounded on the cross axis, so a fill-height child reports its content instead of
            // claiming the whole flow. Its line's height is what it actually gets stretched to below.
            Measured measured = kid.measure(context, room, Node.UNBOUNDED);
            widths[i] = kid.widthSizing().isFill() && columns > 0 ? column : Math.min(measured.width(), room);
            heights[i] = measured.height();
        }

        int lineGap = lineGap();
        int widest = 0;
        int used = 0;
        int y = content == null ? 0 : content.y();

        int index = 0;
        while (index < count) {
            int end = index;
            int lineWidth = 0;
            if (columns > 0) {
                end = Math.min(count, index + columns);
                lineWidth = (end - index) * column + gap * (end - index - 1);
            } else {
                // A line always takes one child however wide it is, so something wider than the flow overflows
                // rather than being pushed onto a line it can never fit either.
                while (end < count) {
                    int grown = lineWidth + (end == index ? 0 : gap) + widths[end];
                    if (end > index && grown > availableWidth) break;

                    lineWidth = grown;
                    end++;
                }
            }

            int lineHeight = 0;
            for (int i = index; i < end; i++) {
                lineHeight = Math.max(lineHeight, heights[i]);
            }

            widest = Math.max(widest, lineWidth);
            if (content != null) {
                place(context, kids, widths, heights, index, end, column, lineWidth, lineHeight, y, content);
            }

            used += lineHeight + lineGap;
            y += lineHeight + lineGap;
            index = end;
        }

        return new Measured(widest, Math.max(0, used - lineGap));
    }

    private void place(LayoutContext context, List<Node> kids, int[] widths, int[] heights,
                       int from, int to, int column, int lineWidth, int lineHeight, int y, Rect content
    ) {
        int items = to - from;
        int free = Math.max(0, content.width() - lineWidth);
        int x = content.x() + justify.offset(free, items);
        int extraGap = justify.extraGap(free, items);

        for (int i = from; i < to; i++) {
            Node kid = kids.get(i);
            Sizing crossSizing = kid.heightSizing();
            int height = align == Align.STRETCH || crossSizing.isFill()
                    ? crossSizing.clamp(lineHeight)
                    : Math.min(heights[i], lineHeight);
            int crossOffset = switch (align) {
                case START, STRETCH -> 0;
                case CENTER -> (lineHeight - height) / 2;
                case END -> lineHeight - height;
            };

            kid.arrange(context, new Rect(x, y + crossOffset, widths[i], height));
            x += (columns > 0 ? column : widths[i]) + gap + extraGap;
        }
    }
}
