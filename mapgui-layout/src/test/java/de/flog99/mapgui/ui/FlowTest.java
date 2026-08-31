package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Row;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The wrapping row, and the grid it becomes once it is told how many columns to use. */
class FlowTest {

    private static final LayoutContext CONTEXT = new LayoutContext(TestFont.INSTANCE);
    private static final Rect SCREEN = new Rect(0, 0, 128, 128);

    private static void layout(Node root) {
        root.measure(CONTEXT, SCREEN.width(), SCREEN.height());
        root.arrange(CONTEXT, SCREEN);
    }

    private static Panel box(int width, int height) {
        return Row().size(width, height);
    }

    @Test
    void aLineWrapsOnlyOnceTheNextChildNoLongerFits() {
        Panel first = box(62, 10);
        Panel second = box(62, 10);
        layout(Column(Ui.Flow(first, second).gap(4).fillWidth()));

        assertEquals(66, second.bounds().x());
        assertEquals(0, second.bounds().y(), "62 and 62 with a 4px gap is exactly the 128 available");

        Panel wide = box(63, 10);
        Panel next = box(63, 10);
        layout(Column(Ui.Flow(wide, next).gap(4).fillWidth()));

        assertEquals(0, next.bounds().x());
        assertEquals(14, next.bounds().y(), "one pixel over, so it goes to the next line");
    }

    @Test
    void columnsTurnItIntoAGrid() {
        Panel[] cells = {box(10, 8), box(10, 8), box(10, 8), box(10, 8), box(10, 8)};
        Flow grid = Ui.Flow(cells).columns(3).gap(2).fillWidth();

        layout(Column(grid));

        // 128 less two 2px gaps, split three ways: 41 per column.
        assertEquals(0, cells[0].bounds().x());
        assertEquals(43, cells[1].bounds().x());
        assertEquals(86, cells[2].bounds().x());
        assertEquals(0, cells[3].bounds().x(), "the fourth starts the second row, whatever fits beside it");
        assertEquals(10, cells[3].bounds().y());
        assertEquals(18, grid.bounds().height());
    }

    @Test
    void aFillChildTakesItsWholeColumn() {
        Panel stretchy = Row().fillWidth().height(8);
        Panel beside = box(10, 8);

        layout(Column(Ui.Flow(stretchy, beside).columns(2).gap(2).fillWidth()));

        assertEquals(63, stretchy.bounds().width());
        assertEquals(65, beside.bounds().x(), "and the next column starts where it ends");
    }

    @Test
    void linesCanHaveAGapOfTheirOwn() {
        Panel first = box(70, 10);
        Panel second = box(70, 12);
        Flow flow = Ui.Flow(first, second).gap(4, 10).fillWidth();

        layout(Column(flow));

        assertEquals(20, second.bounds().y(), "10 tall plus the 10px line gap");
        assertEquals(32, flow.bounds().height());
    }

    @Test
    void anEmptyFlowTakesNoSpace() {
        Flow empty = Ui.Flow();

        layout(Column(empty));

        assertEquals(0, empty.bounds().width());
        assertEquals(0, empty.bounds().height());
    }

    /** A line always takes one child, or something too wide would be pushed onto lines forever. */
    @Test
    void aChildWiderThanTheFlowGetsALineToItself() {
        Panel huge = box(200, 10);
        Panel after = box(20, 10);

        layout(Column(Ui.Flow(huge, after).gap(4).fillWidth()));

        assertEquals(128, huge.bounds().width(), "cut to the line rather than run off the edge");
        assertEquals(0, after.bounds().x());
        assertEquals(14, after.bounds().y());
    }

    /** The point of aligning per line: a short line does not inherit a tall line's height. */
    @Test
    void aChildAlignsWithinItsOwnLine() {
        Panel tall = box(70, 20);
        Panel small = box(70, 6);

        layout(Column(Ui.Flow(tall, small).gap(4).align(Align.CENTER).fillWidth()));

        assertEquals(0, tall.bounds().y());
        assertEquals(24, small.bounds().y());
        assertEquals(6, small.bounds().height(), "centered in a line only as tall as itself, so it has not moved");
    }

    @Test
    void justifyCentresTheShortLastLineOfAGrid() {
        Panel[] cells = {box(10, 8), box(10, 8), box(10, 8)};

        layout(Column(Ui.Flow(cells).columns(2).gap(2).justify(Justify.CENTER).fillWidth()));

        // One 63px column on the last line leaves 65 over, halved.
        assertEquals(32, cells[2].bounds().x());
    }
}
