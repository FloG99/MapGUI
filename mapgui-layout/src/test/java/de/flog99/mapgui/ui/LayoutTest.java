package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Row;
import static de.flog99.mapgui.ui.Ui.Spacer;
import static de.flog99.mapgui.ui.Ui.Text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutTest {

    private static final LayoutContext CONTEXT = new LayoutContext(TestFont.INSTANCE);
    private static final Rect SCREEN = new Rect(0, 0, 128, 128);

    private static void layout(Node root) {
        root.measure(CONTEXT, SCREEN.width(), SCREEN.height());
        root.arrange(CONTEXT, SCREEN);
    }

    @Test
    void rowSplitsLeftoverSpaceBetweenFillChildren() {
        Panel a = Row().fillWidth();
        Panel b = Row().fillWidth();
        Panel root = Row(a, b).gap(4);

        layout(root);

        // 128 minus the 4px gap, halved.
        assertEquals(62, a.bounds().width());
        assertEquals(62, b.bounds().width());
        assertEquals(66, b.bounds().x());
    }

    @Test
    void lastFillChildAbsorbsTheOddPixel() {
        Panel a = Row().fillWidth();
        Panel b = Row().fillWidth();
        Panel c = Row().fillWidth();
        Panel root = Row(a, b, c);

        layout(root);

        assertEquals(128, a.bounds().width() + b.bounds().width() + c.bounds().width());
        assertEquals(42, a.bounds().width());
        assertEquals(44, c.bounds().width());
    }

    @Test
    void fixedChildrenKeepTheirSizeAndFillTakesTheRest() {
        Panel left = Row().width(20);
        Panel middle = Row().fillWidth();
        Panel right = Row().width(30);
        Panel root = Row(left, middle, right).gap(2);

        layout(root);

        assertEquals(20, left.bounds().width());
        assertEquals(74, middle.bounds().width());
        assertEquals(30, right.bounds().width());
        assertEquals(98, right.bounds().x());
    }

    @Test
    void spacerPushesTheNextChildToTheFarEdge() {
        Panel badge = Row().width(20);
        Panel root = Row(Row().width(10), Spacer(), badge);

        layout(root);

        assertEquals(108, badge.bounds().x());
    }

    @Test
    void paddingShrinksTheContentBox() {
        Panel child = Row().fillWidth().fillHeight();
        Panel root = Column(child).padding(6);

        layout(root);

        assertEquals(6, child.bounds().x());
        assertEquals(116, child.bounds().width());
        assertEquals(116, child.bounds().height());
    }

    @Test
    void columnStacksChildrenByMeasuredHeight() {
        Label first = Text("one");
        Label second = Text("two");
        Panel root = Column(first, second).gap(3);

        layout(root);

        assertEquals(0, first.bounds().y());
        assertEquals(8, first.bounds().height());
        assertEquals(11, second.bounds().y());
    }

    @Test
    void wrappedTextGrowsTallerAsWidthShrinks() {
        // Each word is 17px and a space costs 6, so "aaa bbb" needs 41.
        Label roomForTwo = Text("aaa bbb ccc ddd").wrap().width(45);
        Label roomForOne = Text("aaa bbb ccc ddd").wrap().width(40);

        layout(Column(roomForTwo));
        layout(Column(roomForOne));

        assertEquals(2 * 9 - 1, roomForTwo.bounds().height());
        assertEquals(4 * 9 - 1, roomForOne.bounds().height());
    }

    /**
     * Adding up word widths understates a joined line by two pixels per space, because each
     * measured word drops its trailing advance gap. That gap is what made right-aligned text sit
     * short of where wrapped text below it ended.
     */
    @Test
    void wrapAgreesWithMeasuredLineWidth() {
        for (String line : TestFont.INSTANCE.wrap("aaa bbb ccc ddd", 41)) {
            assertTrue(TestFont.INSTANCE.widthOf(line) <= 41, "line wider than the limit: " + line);
        }
        assertEquals(41, TestFont.INSTANCE.widthOf("aaa bbb"));
        assertEquals(2, TestFont.INSTANCE.wrap("aaa bbb ccc ddd", 41).size());
        assertEquals(4, TestFont.INSTANCE.wrap("aaa bbb ccc ddd", 40).size());
    }

    /** Overflow used to run off the edge and get clipped, which reads as a bug on 128 pixels. */
    @Test
    void overflowShrinksTheShrinkWrappedChildren() {
        Label wide = Text("aaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Panel fixed = Row().width(40);
        Panel root = Row(wide, fixed);

        layout(root);

        assertEquals(40, fixed.bounds().width(), "a fixed child keeps its size");
        assertEquals(88, wide.bounds().width(), "the label gives up the overflow");
        assertEquals(128, wide.bounds().width() + fixed.bounds().width());
    }

    /** Shares are floored, so the leftover pixels have to be taken off someone or it still overflows. */
    @Test
    void shrinkingRemovesTheWholeOverflowAcrossSeveralChildren() {
        Label first = Text("aaaaaaaaaaaaaaa");
        Label second = Text("bbbbbbbbbb");
        Panel fixed = Row().width(30);
        Panel root = Row(first, second, fixed).gap(3);

        layout(root);

        int total = first.bounds().width() + second.bounds().width() + fixed.bounds().width() + 6;
        assertEquals(128, total, "the row must add up to exactly the width available");
    }

    @Test
    void nothingShrinksWhenEverythingIsFixed() {
        Panel first = Row().width(100);
        Panel second = Row().width(100);
        Panel root = Row(first, second);

        layout(root);

        assertEquals(100, first.bounds().width());
        assertEquals(100, second.bounds().width());
    }

    @Test
    void hiddenChildrenTakeNoSpaceAndNoGap() {
        Panel visible = Row().width(10);
        Panel gone = Row().width(10).hidden(true);
        Panel after = Row().width(10);
        Panel root = Row(visible, gone, after).gap(5);

        layout(root);

        assertEquals(15, after.bounds().x());
    }

    /** A label that fits must not ask for frames, or every screen would repaint forever. */
    @Test
    void scrollingTextOnlyAnimatesWhenItOverflows() {
        Animator animator = new Animator();
        LayoutContext context = new LayoutContext(TestFont.INSTANCE, animator);
        Painter painter = TestPaint.painter();

        Label fits = Text("ab").scroll().width(60);
        Panel root = Column(fits);
        animator.clock(0);
        animator.beginLayout();
        root.measure(context, 128, 128);
        root.arrange(context, SCREEN);
        root.paint(painter);
        assertFalse(animator.animating(), "a label that fits should not request frames");

        Label overflows = Text("aaaaaaaaaaaaaaaaaaaa").scroll().width(30);
        Panel wide = Column(overflows);
        animator.beginLayout();
        wide.measure(context, 128, 128);
        wide.arrange(context, SCREEN);
        wide.paint(painter);
        assertTrue(animator.animating(), "an overflowing label should keep frames coming");
    }

    /** A caption that appeared on text which fits would just be noise under the cursor. */
    @Test
    void revealOnHoverOnlyCaptionsTextThatIsActuallyCutOff() {
        Painter painter = TestPaint.painter();

        Label fits = Text("ab").revealOnHover().width(60);
        Label cut = Text("far too long to fit in here").revealOnHover().width(30);
        Label sliding = Text("far too long to fit in here").scroll().revealOnHover().width(30);
        Panel root = Column(fits, cut, sliding);

        layout(root);
        root.paint(painter);

        assertNull(fits.caption(), "text that fits needs no caption");
        assertEquals("far too long to fit in here", cut.caption());
        assertNull(sliding.caption(), "sliding text can already be read, so it needs no caption");
    }

    /** An explicit caption is a plain tooltip and has nothing to do with truncation. */
    @Test
    void anExplicitCaptionAlwaysWins() {
        Painter painter = TestPaint.painter();

        Label label = Text("short").caption("explained").revealOnHover().width(60);
        Panel root = Column(label);

        layout(root);
        root.paint(painter);

        assertEquals("explained", label.caption());
        assertTrue(label.interactive(), "a node with a caption has to take part in hit testing");
    }

    /** A badge stuck in the top left is no use - escaping to a corner is the point of an overlay. */
    @Test
    void overlaidChildrenArePlacedIndependently() {
        Panel background = Row().fillWidth().height(30);
        Panel badge = Row().size(10, 6).place(Justify.END, Align.START);
        Panel centered = Row().size(10, 6).place(Justify.CENTER, Align.CENTER);
        // Nested, since the root itself is always arranged to the whole viewport.
        Stack overlay = Ui.Overlay(background, badge, centered).fillWidth();
        layout(Column(overlay));

        assertEquals(128, background.bounds().width(), "the backdrop still fills");
        assertEquals(118, badge.bounds().x(), "pinned to the right edge");
        assertEquals(0, badge.bounds().y());
        assertEquals(59, centered.bounds().x());
        assertEquals(12, centered.bounds().y());
    }

    /** Without fill() a hug column shrinks to its content, so its Spacer has nothing to push against. */
    @Test
    void anOverlaidColumnOnlyGetsTheWholeRectIfItFills() {
        Panel hugging = Column(Spacer(), Row().height(10));
        layout(Column(Ui.Overlay(Row().fill(), hugging).fill()));

        assertEquals(10, hugging.bounds().height());
        assertEquals(0, hugging.bounds().y(), "so it lands at the top, over anything underneath");

        Panel filling = Column(Spacer(), Row().height(10));
        Panel bottom = (Panel) filling.visibleChildren().get(1);
        layout(Column(Ui.Overlay(Row().fill(), filling.fill()).fill()));

        assertEquals(128, filling.bounds().height());
        assertEquals(118, bottom.bounds().y(), "and now the spacer reaches the bottom");
    }

    /** A canvas needs to know where in itself it was hit, not where on the screen. */
    @Test
    void aClickReportsWhereInsideTheNodeItLanded() {
        int[] seen = {-1, -1};
        Panel target = Row().size(20, 10).onClick((x, y) -> {
            seen[0] = x;
            seen[1] = y;
        });
        Panel root = Column(Row().size(20, 6), target);

        layout(root);
        Node hit = root.hitTest(5, 8);
        hit.click(5 - hit.bounds().x(), 8 - hit.bounds().y());

        assertSame(target, hit);
        assertEquals(5, seen[0]);
        assertEquals(2, seen[1], "two pixels down into a node that starts at six");
    }

    /** Reusable styling, so a look can be named once instead of repeated per node. */
    @Test
    void applyRunsStylingAndKeepsTheChainGoing() {
        Panel styled = Row().apply(row -> row.padding(4).width(30)).height(7);

        layout(Column(styled));

        assertEquals(30, styled.bounds().width());
        assertEquals(7, styled.bounds().height());
    }

    /**
     * The scrollbar takes width off the children, and narrower text wraps onto more lines - so measuring
     * the content at the full width leaves the extent short and the last child unreachable. This showed up
     * as the bottom line of a menu being permanently half cut off.
     *
     * <p>Twenty-one characters is 125px in the test font: one line inside 128, two inside 123.
     */
    @Test
    void scrollExtentAllowsForTheWidthTheScrollbarTakes() {
        Label tail = Text("aaaaaaaaaa bbbbbbbbbb").wrap().fillWidth();
        Scroll scroll = Ui.Scroll(Row().fillWidth().height(100), tail)
                .gap(4).height(64).scrollDuration(0);
        Panel root = Column(scroll);

        layout(root);
        assertEquals(17, tail.bounds().height(), "the tail wraps once the bar has taken its width");

        scroll.scrollBy(1000);
        layout(root);

        int viewportBottom = scroll.bounds().y() + scroll.bounds().height();
        assertTrue(tail.bounds().y() + tail.bounds().height() <= viewportBottom,
                "scrolled to the end, the last child has to be fully inside the viewport"
        );
    }

    @Test
    void hitTestFindsTheTopmostInteractiveNode() {
        Panel button = Row().size(20, 20).onClick(() -> {});
        Panel root = Column(Row().size(20, 20), button).gap(0);

        layout(root);

        assertSame(button, root.hitTest(5, 25));
        assertNull(root.hitTest(5, 5));
        assertNull(root.hitTest(100, 100));
    }

    @Test
    void alignCentresChildrenOnTheCrossAxis() {
        Panel child = Row().size(20, 10);
        Panel strip = Row(child).height(50).align(Align.CENTER);
        Panel root = Column(strip);

        layout(root);

        assertEquals(50, strip.bounds().height());
        assertEquals(20, child.bounds().y());
    }

    @Test
    void justifyEndPushesEverythingRight() {
        Panel child = Row().width(28);
        Panel root = Row(child).justify(Justify.END);

        layout(root);

        assertEquals(100, child.bounds().x());
    }

    /** A gap keeps its slot open where a hidden node takes the space with it. */
    @Test
    void aGapHoldsItsSpaceWhereAHiddenNodeGivesItUp() {
        Panel after = Row().width(10);
        layout(Row(Ui.Gap(14, 14), after));
        assertEquals(14, after.bounds().x());

        Panel moved = Row().width(10);
        layout(Row(Row().size(14, 14).hidden(true), moved));
        assertEquals(0, moved.bounds().x());
    }

    /** Placing through the interface, so a method handed plain nodes can still position them. */
    @Test
    void aNodeCanBePlacedWithoutKnowingItsConcreteType() {
        Node badge = Row().size(10, 10);
        layout(Ui.Overlay(placedBottomRight(badge)));

        assertEquals(118, badge.bounds().x());
        assertEquals(118, badge.bounds().y());
    }

    private static Node placedBottomRight(Node node) {
        return node.place(Justify.END, Align.END);
    }

    /** A missing image still takes its box, which is what lets the node's background stand in for the artwork. */
    @Test
    void anImagelessBitmapStillFills() {
        Bitmap missing = Ui.Image(null).fill();
        layout(Ui.Overlay(missing));

        assertEquals(128, missing.bounds().width());
        assertEquals(128, missing.bounds().height());
    }
}
