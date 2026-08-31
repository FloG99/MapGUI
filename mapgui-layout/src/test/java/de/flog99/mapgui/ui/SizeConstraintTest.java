package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Row;
import static de.flog99.mapgui.ui.Ui.Text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Minimum and maximum sizes, which is the one thing {@code HUG | FIXED | FILL} cannot say on its own. */
class SizeConstraintTest {

    private static final LayoutContext CONTEXT = new LayoutContext(TestFont.INSTANCE);
    private static final Rect SCREEN = new Rect(0, 0, 128, 128);

    private static void layout(Node root) {
        root.measure(CONTEXT, SCREEN.width(), SCREEN.height());
        root.arrange(CONTEXT, SCREEN);
    }

    @Test
    void aMaximumCapsAShrinkWrappedNodeAndAMinimumFloorsIt() {
        Panel wide = Row(Row().width(100)).maxWidth(60);
        Panel narrow = Row(Row().width(10)).minWidth(40);

        layout(Column(wide, narrow));

        assertEquals(60, wide.bounds().width());
        assertEquals(40, narrow.bounds().width());
    }

    @Test
    void theBoundsWorkOnTheOtherAxisToo() {
        Panel tall = Column(Row().size(20, 200)).maxHeight(50);
        Panel squat = Column(Row().size(20, 4)).minHeight(30);

        layout(Column(tall, squat));

        assertEquals(50, tall.bounds().height());
        assertEquals(30, squat.bounds().height());
    }

    /** Otherwise one of the two would be silently dropped, and which one would be an implementation detail. */
    @Test
    void aMaximumBeatsAFixedSize() {
        Panel box = Row().width(200).maxWidth(120).height(30).maxHeight(20);

        layout(Column(box));

        assertEquals(120, box.bounds().width());
        assertEquals(20, box.bounds().height());
    }

    /** Order-dependent styling in a chain this long would be a trap, so a mode change keeps the bounds. */
    @Test
    void theBoundsSurviveAModeChangeInEitherOrder() {
        Panel boundedFirst = Row().maxWidth(40).fillWidth();
        Panel boundedLast = Row().fillWidth().maxWidth(40);

        layout(Column(boundedFirst, boundedLast));

        assertEquals(40, boundedFirst.bounds().width());
        assertEquals(40, boundedLast.bounds().width());
    }

    /** The content is measured against the maximum, or text would wrap to the parent and then be squeezed. */
    @Test
    void aMaximumIsWhatTextWrapsAgainst() {
        Label label = Text("aaa bbb ccc ddd").wrap().maxWidth(45);

        layout(Column(label));

        assertEquals(2 * 9 - 1, label.bounds().height(), "wrapped at the maximum, not at the screen width");
        assertTrue(label.bounds().width() <= 45, "and no wider than the maximum: " + label.bounds().width());
    }

    /** A centered content column on a wide wall, which is what this pairing is for. */
    @Test
    void aCappedFillChildLeavesSpaceForJustifyToCentre() {
        Panel content = Column(Row().height(20)).fillWidth().maxWidth(80);
        Panel root = Row(content).justify(Justify.CENTER);

        layout(root);

        assertEquals(80, content.bounds().width());
        assertEquals(24, content.bounds().x());
    }

    @Test
    void theSurplusFromACappedFillChildGoesToItsSiblings() {
        Panel open = Row().fillWidth();
        Panel capped = Row().fillWidth().maxWidth(40);

        layout(Row(open, capped));

        assertEquals(40, capped.bounds().width());
        assertEquals(88, open.bounds().width(), "the 24 pixels it gave up went here, not off the row");
        assertEquals(128, open.bounds().width() + capped.bounds().width());
    }

    /** The last fill child absorbs the integer remainder, so capping that one is the case that breaks. */
    @Test
    void aCappedFillChildGivesUpTheRemainderItWouldHaveAbsorbed() {
        Panel first = Row().fillWidth();
        Panel second = Row().fillWidth();
        Panel last = Row().fillWidth().maxWidth(10);

        layout(Row(first, second, last));

        assertEquals(10, last.bounds().width());
        assertEquals(59, first.bounds().width());
        assertEquals(59, second.bounds().width());
        assertEquals(128, first.bounds().width() + second.bounds().width() + last.bounds().width());
    }

    @Test
    void everyFillChildCappedStopsAtItsMaximum() {
        Panel first = Row().fillWidth().maxWidth(20);
        Panel second = Row().fillWidth().maxWidth(30);

        layout(Row(first, second));

        assertEquals(20, first.bounds().width());
        assertEquals(30, second.bounds().width());
    }

    @Test
    void aMinimumOnAFillChildTakesFromTheOthers() {
        Panel demanding = Row().fillWidth().minWidth(100);
        Panel rest = Row().fillWidth();

        layout(Row(demanding, rest));

        assertEquals(100, demanding.bounds().width());
        assertEquals(28, rest.bounds().width());
    }

    /** "Grow until it does not fit, then scroll", instead of a height decided before the rows are known. */
    @Test
    void aMaximumHeightOnAScrollIsWhereScrollingBegins() {
        Scroll roomy = Ui.Scroll(Row().fillWidth().height(30), Row().fillWidth().height(30))
                .gap(4).maxHeight(100).scrollDuration(0);

        layout(Column(roomy));

        assertEquals(64, roomy.bounds().height(), "under the maximum it is exactly as tall as its rows");
        assertEquals(0, roomy.maxOffset());
        assertFalse(roomy.scrollBy(10), "and there is nothing to scroll");

        Scroll tight = Ui.Scroll(Row().fillWidth().height(30), Row().fillWidth().height(30))
                .gap(4).maxHeight(50).scrollDuration(0);

        layout(Column(tight));

        assertEquals(50, tight.bounds().height());
        assertEquals(14, tight.maxOffset(), "past the maximum the rest becomes scroll travel");
        assertTrue(tight.scrollBy(10));
    }
}
