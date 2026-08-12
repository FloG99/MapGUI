package de.flog99.mapgui.ui;

/**
 * Runs of covered pixels along one row, as {@code start, end} pairs with the end excluded.
 *
 * <p>What {@link Shape#spansAt} speaks in, and the reason a filled shape costs a few sums a row rather than a test
 * per pixel. Kept as a flat {@code int[]} because there is one per row of every filled shape in a frame, and a pair
 * of objects each would be the allocation this exists to avoid.
 */
final class Spans {

    static final int[] NONE = new int[0];

    private Spans() {
    }

    /**
     * The runs between sorted crossings, paired off - first to second, third to fourth.
     *
     * <p>Rounded to the pixels whose middles actually fall inside, since that is what {@link Shape#contains} answers
     * for: a pixel is at {@code x + 0.5}, so a run from 2.3 to 5.1 covers 3 and 4.
     */
    static int[] between(double[] crossings, int found) {
        int[] spans = new int[found & ~1];
        int written = 0;

        for (int i = 0; i + 1 < found; i += 2) {
            int start = (int) Math.floor(crossings[i] - 0.5) + 1;
            int end = (int) Math.ceil(crossings[i + 1] - 0.5);
            if (end <= start) continue;

            spans[written++] = start;
            spans[written++] = end;
        }
        return written == spans.length ? spans : java.util.Arrays.copyOf(spans, written);
    }

    /** Where two rows of runs both cover. Both are in order and neither overlaps itself. */
    static int[] overlap(int[] first, int[] second) {
        int[] spans = new int[first.length + second.length];
        int written = 0;
        int mine = 0;
        int theirs = 0;

        while (mine < first.length && theirs < second.length) {
            int start = Math.max(first[mine], second[theirs]);
            int end = Math.min(first[mine + 1], second[theirs + 1]);
            if (end > start) {
                spans[written++] = start;
                spans[written++] = end;
            }

            // Whichever run ends first is done with; the other may still meet the next one along.
            if (first[mine + 1] < second[theirs + 1]) {
                mine += 2;
            } else {
                theirs += 2;
            }
        }
        return written == spans.length ? spans : java.util.Arrays.copyOf(spans, written);
    }

    /** The gaps: everything from {@code from} to {@code to} that the runs do not cover. */
    static int[] outside(int[] covered, int from, int to) {
        int[] spans = new int[covered.length + 2];
        int written = 0;
        int at = from;

        for (int i = 0; i + 1 < covered.length; i += 2) {
            int start = Math.max(from, covered[i]);
            if (start > at) {
                spans[written++] = at;
                spans[written++] = Math.min(start, to);
            }
            at = Math.max(at, Math.min(covered[i + 1], to));
        }
        if (at < to) {
            spans[written++] = at;
            spans[written++] = to;
        }
        return written == spans.length ? spans : java.util.Arrays.copyOf(spans, written);
    }
}
