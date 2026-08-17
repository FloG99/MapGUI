package de.flog99.mapgui.ui;

import java.awt.Color;
import java.util.List;
import java.util.function.Supplier;

/**
 * A run of text.
 *
 * <p>Text is read through a supplier so a label can show live state without the screen having
 * to rebuild the tree for every change.
 */
public final class Label extends AbstractNode<Label> {

    private final Supplier<String> text;
    private Color color = Color.WHITE;
    private Color hoverColor;
    private TextAlign align = TextAlign.LEFT;
    private boolean wrap;
    private boolean shadow;
    private Overflow overflow = Overflow.ELLIPSIS;
    private int scrollMs;
    private boolean reveal;

    /** Set while painting, since whether the text was cut off is only known once it has a width. */
    private String cutOff;

    /**
     * The last set of wrapped lines, and what they were wrapped for.
     *
     * <p>One frame asks for them four times - the measure passes work out how tall the label is, then the paint
     * that follows draws them - and wrapping is the most expensive thing a font is asked to do, since every
     * candidate line is measured as it grows. Held on the node, so it lives exactly as long as the tree does and
     * there is nothing shared between frames, screens or threads to invalidate.
     *
     * <p>Keyed by the font as well as the text and the width, because a painter is pointed at the screen's font
     * per frame and a label measured with one must never be drawn from lines wrapped with another.
     */
    private List<String> wrappedLines;
    private String wrappedText;
    private TextFont wrappedFont;
    private int wrappedWidth;

    public Label(Supplier<String> text) {
        this.text = text;
    }

    public Label color(Color value) {
        this.color = value;
        return this;
    }

    public Label hoverColor(Color value) {
        this.hoverColor = value;
        return this;
    }

    public Label align(TextAlign value) {
        this.align = value;
        return this;
    }

    /** Wrap onto more lines instead of cutting the text off. */
    public Label wrap() {
        this.wrap = true;
        return this;
    }

    public Label shadow() {
        this.shadow = true;
        return this;
    }

    /** What to do when the text is wider than the space it got. */
    public enum Overflow {
        /** End it with ".." so it fits. */
        ELLIPSIS,
        /** Cut it off at the edge, mid-character if need be. */
        CLIP,
        /** Slide it back and forth so the hidden part can be read. */
        SCROLL
    }

    public Label overflow(Overflow value) {
        this.overflow = value;
        return this;
    }

    /** Cut off at the edge with no ".." added. */
    public Label clip() {
        return overflow(Overflow.CLIP);
    }

    /**
     * Show the whole text under the cursor while hovered, but only while it is actually cut off. Pairs with
     * {@link #clip()} and the default ellipsis, where there is otherwise no way to read the rest.
     */
    public Label revealOnHover() {
        this.reveal = true;
        return this;
    }

    /**
     * Slide overflowing text back and forth, the way Minecraft's own over-long button labels do. Only
     * animates while it actually overflows, so a label that fits costs nothing.
     *
     * <p>While it does overflow it repaints every tick and never settles - about 800 bytes a frame for a
     * 100x8 line, or 16 KB/s per player. Several of them add up, so use {@link #clip()} with
     * {@link #revealOnHover()} where that matters.
     */
    public Label scroll() {
        return overflow(Overflow.SCROLL);
    }

    /**
     * The same, with the round trip taking a set time rather than one based on how much is hidden.
     * A shorter period does not send more - the frame rate is the tick, not the period.
     */
    public Label scroll(int periodMillis) {
        this.scrollMs = periodMillis;
        return overflow(Overflow.SCROLL);
    }

    public String text() {
        String value = text.get();
        return value == null ? "" : value;
    }

    @Override
    public boolean interactive() {
        return super.interactive() || hoverColor != null || reveal;
    }

    @Override
    public String caption() {
        String explicit = super.caption();
        return explicit != null ? explicit : cutOff;
    }

    @Override
    protected Measured measureContent(LayoutContext context, int availableWidth, int availableHeight) {
        String value = text();
        if (value.isEmpty()) return Measured.ZERO;

        TextFont font = context.font();
        int naturalWidth = font.widthOf(value);
        int stride = font.lineHeight() + 1;

        if (!wrap) {
            return new Measured(Math.min(naturalWidth, availableWidth), font.lineHeight());
        }

        int lines = Math.max(1, wrapped(font, value, availableWidth).size());
        return new Measured(Math.min(naturalWidth, availableWidth), lines * stride - 1);
    }

    /** {@code text} wrapped to {@code maxWidth}, from {@link #wrappedLines} when that is what it already holds. */
    private List<String> wrapped(TextFont font, String value, int maxWidth) {
        if (wrappedLines != null && font == wrappedFont && maxWidth == wrappedWidth && value.equals(wrappedText)) {
            return wrappedLines;
        }

        wrappedLines = font.wrap(value, maxWidth);
        wrappedText = value;
        wrappedFont = font;
        wrappedWidth = maxWidth;
        return wrappedLines;
    }

    @Override
    protected void paintContent(Painter painter) {
        String value = text();
        if (value.isEmpty()) return;

        Rect box = contentBounds();
        Color painted = animated("text", hovered() && hoverColor != null ? hoverColor : color);

        if (wrap) {
            cutOff = null;
            painter.textBlock(box, wrapped(painter.font(), value, box.width()), painted, align, shadow);
            return;
        }

        String clean = painter.font().sanitize(value);
        int width = painter.font().widthOf(clean);
        int spare = box.width() - width;
        cutOff = reveal && spare < 0 && overflow != Overflow.SCROLL ? clean : null;

        if (spare >= 0 || overflow == Overflow.ELLIPSIS) {
            painter.textBlock(box, List.of(spare >= 0 ? clean : painter.ellipsize(value, box.width())), painted, align, shadow);
            return;
        }

        int offset = overflow == Overflow.SCROLL ? slideOffset(-spare) : 0;
        Rect previous = painter.pushClip(box);
        painter.textBlock(new Rect(box.x() - offset, box.y(), width, box.height()), List.of(clean), painted, TextAlign.LEFT, shadow);
        painter.popClip(previous);
    }

    /**
     * Eased ping-pong across the hidden part, matching Minecraft's own long labels: a sine of a cosine, which
     * dwells at each end instead of snapping round like a marquee. The longer the overflow, the longer the trip.
     */
    private int slideOffset(int hidden) {
        Animator animator = animator();
        if (animator == null) return 0;

        // Minecraft uses half a second per hidden pixel, which on a 128px canvas works out at half a
        // minute for a round trip. Faster here, since there is much less to travel.
        int period = scrollMs > 0 ? scrollMs : (int) Math.max(hidden * 150L, 2500);
        double t = animator.phase(period);
        double eased = Math.sin(Math.PI / 2 * Math.cos(Math.PI * 2 * t)) / 2 + 0.5;
        return (int) Math.round(eased * hidden);
    }
}
