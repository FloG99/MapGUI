package de.flog99.mapgui.ui;

import java.util.List;

/**
 * Stacks its children along one axis with a gap between them.
 *
 * <p>Children sized {@link Sizing#fill} split whatever main-axis space the fixed and
 * shrink-wrapped children leave over, by weight. The last of them absorbs the integer
 * remainder so a row always adds up to the exact pixel width.
 */
public final class Panel extends AbstractContainer<Panel> {

    public enum Axis { ROW, COLUMN }

    private final Axis axis;
    private int gap;
    private Align align = Align.START;
    private Justify justify = Justify.START;

    public Panel(Axis axis) {
        this.axis = axis;
    }

    public Panel gap(int pixels) {
        this.gap = pixels;
        return this;
    }

    public Panel align(Align value) {
        this.align = value;
        return this;
    }

    public Panel justify(Justify value) {
        this.justify = value;
        return this;
    }

    private boolean vertical() {
        return axis == Axis.COLUMN;
    }

    @Override
    protected Measured measureContent(LayoutContext context, int availableWidth, int availableHeight) {
        List<Node> kids = visibleChildren();
        if (kids.isEmpty()) return Measured.ZERO;

        boolean vertical = vertical();
        int totalMain = gap * (kids.size() - 1);
        int maxCross = 0;

        for (Node kid : kids) {
            Measured measured = kid.measure(context, availableWidth, vertical ? Node.UNBOUNDED : availableHeight);
            totalMain += vertical ? measured.height() : measured.width();
            maxCross = Math.max(maxCross, vertical ? measured.width() : measured.height());
        }

        return vertical ? new Measured(maxCross, totalMain) : new Measured(totalMain, maxCross);
    }

    /**
     * Takes space back off shrink-wrapped children when the row doesn't fit.
     *
     * <p>Without this the overflow just runs off the edge and gets clipped, which on 128 pixels
     * happens easily and looks like a bug. Only {@code HUG} children give anything up: a child asked
     * for an exact size keeps it, and a label that sized itself to its text can afford to truncate.
     */
    private static void shrinkToFit(List<Node> kids, int[] mains, boolean vertical, int contentMain, int gaps) {
        int used = gaps;
        for (int main : mains) used += main;

        int overflow = used - contentMain;
        if (overflow <= 0) return;

        int shrinkable = 0;
        for (int i = 0; i < kids.size(); i++) {
            if (isHug(kids.get(i), vertical)) {
                shrinkable += mains[i];
            }
        }
        if (shrinkable <= 0) return;

        int target = Math.min(overflow, shrinkable);
        int removed = 0;
        for (int i = 0; i < kids.size(); i++) {
            if (!isHug(kids.get(i), vertical)) continue;

            int cut = Math.min(mains[i], (int) ((long) target * mains[i] / shrinkable));
            mains[i] -= cut;
            removed += cut;
        }

        // Flooring each share leaves a pixel or two over; take those off whoever still has room.
        int leftover = target - removed;
        for (int i = 0; i < kids.size() && leftover > 0; i++) {
            if (!isHug(kids.get(i), vertical)) continue;

            int cut = Math.min(mains[i], leftover);
            mains[i] -= cut;
            leftover -= cut;
        }
    }

    private static boolean isHug(Node kid, boolean vertical) {
        Sizing sizing = vertical ? kid.heightSizing() : kid.widthSizing();
        return sizing.mode() == Sizing.Mode.HUG;
    }

    @Override
    protected void arrangeContent(LayoutContext context, Rect content) {
        List<Node> kids = visibleChildren();
        if (kids.isEmpty()) return;

        boolean vertical = vertical();
        int count = kids.size();
        int contentMain = vertical ? content.height() : content.width();
        int contentCross = vertical ? content.width() : content.height();
        int gaps = gap * (count - 1);

        int[] mains = new int[count];
        int[] crosses = new int[count];
        int usedMain = 0;
        int totalWeight = 0;
        int fillCount = 0;

        for (int i = 0; i < count; i++) {
            Node kid = kids.get(i);
            Sizing mainSizing = vertical ? kid.heightSizing() : kid.widthSizing();
            Measured measured = kid.measure(context, content.width(), vertical ? Node.UNBOUNDED : content.height());
            crosses[i] = vertical ? measured.width() : measured.height();

            if (mainSizing.isFill()) {
                mains[i] = -1;
                totalWeight += mainSizing.value();
                fillCount++;
            } else {
                mains[i] = vertical ? measured.height() : measured.width();
                usedMain += mains[i];
            }
        }

        if (totalWeight > 0) {
            int remaining = Math.max(0, contentMain - usedMain - gaps);
            int handedOut = 0;
            int seen = 0;
            for (int i = 0; i < count; i++) {
                if (mains[i] != -1) continue;

                Sizing sizing = vertical ? kids.get(i).heightSizing() : kids.get(i).widthSizing();
                seen++;
                mains[i] = seen == fillCount
                        ? remaining - handedOut
                        : remaining * sizing.value() / totalWeight;
                handedOut += mains[i];
            }

            // Now that a fill child's real width is known, ask it again - wrapped text only
            // knows how tall it is once it knows how wide it is.
            for (int i = 0; i < count; i++) {
                Node kid = kids.get(i);
                Sizing mainSizing = vertical ? kid.heightSizing() : kid.widthSizing();
                if (!mainSizing.isFill()) continue;

                Measured remeasured = vertical
                        ? kid.measure(context, content.width(), mains[i])
                        : kid.measure(context, mains[i], content.height());
                crosses[i] = vertical ? remeasured.width() : remeasured.height();
            }
        }

        shrinkToFit(kids, mains, vertical, contentMain, gaps);

        int used = gaps;
        for (int main : mains) used += main;
        int free = Math.max(0, contentMain - used);

        int offset = switch (justify) {
            case START, SPACE_BETWEEN -> 0;
            case CENTER -> free / 2;
            case END -> free;
            case SPACE_AROUND -> free / (count + 1);
        };
        int extraGap = switch (justify) {
            case SPACE_BETWEEN -> count > 1 ? free / (count - 1) : 0;
            case SPACE_AROUND -> free / (count + 1);
            default -> 0;
        };

        for (int i = 0; i < count; i++) {
            Node kid = kids.get(i);
            Sizing crossSizing = vertical ? kid.widthSizing() : kid.heightSizing();
            int cross = crossSizing.isFill() || align == Align.STRETCH
                    ? contentCross
                    : Math.min(crosses[i], contentCross);
            int crossOffset = switch (align) {
                case START, STRETCH -> 0;
                case CENTER -> (contentCross - cross) / 2;
                case END -> contentCross - cross;
            };

            kid.arrange(context, vertical
                    ? new Rect(content.x() + crossOffset, content.y() + offset, cross, mains[i])
                    : new Rect(content.x() + offset, content.y() + crossOffset, mains[i], cross)
            );

            offset += mains[i] + gap + extraGap;
        }
    }
}
