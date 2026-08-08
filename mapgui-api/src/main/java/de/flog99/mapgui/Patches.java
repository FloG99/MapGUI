package de.flog99.mapgui;

import de.flog99.mapgui.ui.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits what changed on one map into the cheapest set of rectangles to send it as.
 *
 * <p>A map update carries exactly one rectangle, so two things changing in opposite corners of the same map go
 * either as one box spanning both - almost all of it unchanged pixels - or as two packets for the same map id.
 * Which is cheaper is arithmetic, and this is where it is done.
 *
 * <p>Rows rather than a general 2D decomposition. A surface tracks the changed span of each row exactly, so a
 * run of rows wastes nothing horizontally unless the rows themselves differ, and runs already catch what a GUI
 * does: a header and a footer, a scrollbar beside a body, two widgets in different corners. What rows cannot
 * separate is two things side by side on the same lines, which costs the gap between them and nothing else.
 */
final class Patches {

    /**
     * What one more packet has to save, in payload bytes, to be worth sending.
     *
     * <p>A map update is a dozen bytes of header, but the header is not the price: the client uploads each
     * patch to its map texture separately, so a packet costs considerably more than it measures.
     *
     * <p>It is also the only thing bounding how many packets one map can turn into, which is why it is this
     * large rather than tuned to the header alone. A split into N rectangles has to save N times this, and a
     * whole map is 16384 bytes, so no map ever splits into more than sixteen however scattered the frame is.
     */
    private static final int SPLIT_COST = 1024;

    private Patches() {
    }

    /**
     * The rectangles to send for one map, cheapest first by total cost rather than by count.
     *
     * <p>Spans are read from {@code left} and {@code right} at {@code row * stride + column}, right exclusive,
     * which is how a surface holds them: one entry per row per map-wide column, so the tiles of a wall sit
     * beside each other in the same two arrays and a tile is a column of them.
     *
     * @return one rectangle when splitting cannot pay, several when it can, empty when nothing changed
     */
    static List<Rect> plan(int[] left, int[] right, int stride, int column, int firstRow, int lastRow) {
        int boxLeft = Integer.MAX_VALUE;
        int boxRight = Integer.MIN_VALUE;
        int boxTop = 0;
        int boxBottom = 0;
        long spanned = 0;
        int rows = 0;

        for (int row = firstRow; row <= lastRow; row++) {
            int span = row * stride + column;
            if (left[span] >= right[span]) continue;

            boxLeft = Math.min(boxLeft, left[span]);
            boxRight = Math.max(boxRight, right[span]);
            if (rows == 0) {
                boxTop = row;
            }
            boxBottom = row;
            spanned += right[span] - left[span];
            rows++;
        }
        if (rows == 0) return List.of();

        Rect box = new Rect(boxLeft, boxTop, boxRight - boxLeft, boxBottom - boxTop + 1);

        // Every split pays for at least the pixels that actually changed, plus a packet more than the box
        // does, so a box no bigger than that already wins and there is nothing to go looking for. This is
        // the answer for a full redraw and for anything that changed in one piece, which is most frames.
        long area = (long) box.width() * box.height();
        if (area <= spanned + SPLIT_COST) return List.of(box);

        return split(left, right, stride, column, boxTop, boxBottom, rows);
    }

    private static List<Rect> split(int[] left, int[] right, int stride, int column,
                                    int firstRow, int lastRow, int rows) {
        int[] y = new int[rows];
        int[] from = new int[rows];
        int[] to = new int[rows];

        int count = 0;
        for (int row = firstRow; row <= lastRow; row++) {
            int span = row * stride + column;
            if (left[span] >= right[span]) continue;

            y[count] = row;
            from[count] = left[span];
            to[count] = right[span];
            count++;
        }

        // best[n] is the cheapest way to cover the first n changed rows and begins[n] where its last
        // rectangle starts. Trying every start for every end finds the cheapest partition outright, which a
        // greedy pass does not: extending a rectangle by one row always looks cheap next to paying for a
        // packet, so greed widens the first rectangle and then never lets go of the width.
        long[] best = new long[count + 1];
        int[] begins = new int[count + 1];

        for (int end = 1; end <= count; end++) {
            best[end] = Long.MAX_VALUE;
            int groupLeft = Integer.MAX_VALUE;
            int groupRight = Integer.MIN_VALUE;

            for (int begin = end - 1; begin >= 0; begin--) {
                groupLeft = Math.min(groupLeft, from[begin]);
                groupRight = Math.max(groupRight, to[begin]);

                // Clean rows inside a run are paid for, since one rectangle is solid.
                long area = (long) (groupRight - groupLeft) * (y[end - 1] - y[begin] + 1);
                long cost = best[begin] + area + SPLIT_COST;

                // Not-worse rather than better, so a tie goes to the longer run and the frame leaves in
                // fewer packets. Reaching begin 0 tries the whole box, so this never comes out above it.
                if (cost <= best[end]) {
                    best[end] = cost;
                    begins[end] = begin;
                }
            }
        }
        return regions(y, from, to, begins, count);
    }

    /** Walks the chosen starts back from the last row, so the rectangles come out in order. */
    private static List<Rect> regions(int[] y, int[] from, int[] to, int[] begins, int count) {
        List<Rect> regions = new ArrayList<>();

        for (int end = count; end > 0; end = begins[end]) {
            int begin = begins[end];
            int groupLeft = Integer.MAX_VALUE;
            int groupRight = Integer.MIN_VALUE;

            for (int row = begin; row < end; row++) {
                groupLeft = Math.min(groupLeft, from[row]);
                groupRight = Math.max(groupRight, to[row]);
            }
            regions.add(new Rect(groupLeft, y[begin], groupRight - groupLeft, y[end - 1] - y[begin] + 1));
        }
        Collections.reverse(regions);
        return regions;
    }
}
