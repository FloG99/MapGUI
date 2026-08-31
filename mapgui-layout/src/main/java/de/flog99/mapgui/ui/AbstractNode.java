package de.flog99.mapgui.ui;

import java.awt.Color;
import java.util.List;

/**
 * Styling and layout shared by every node.
 *
 * <p>The self type is what lets {@code Row().gap(2).padding(4)} keep returning {@code Panel}
 * instead of degrading to {@code Node} halfway down the chain.
 */
@SuppressWarnings("unchecked")
public abstract class AbstractNode<S extends AbstractNode<S>> implements Node {

    /** How much darker a fully pressed node is drawn. Enough to notice, not enough to look broken. */
    private static final double PRESS_DARKEN = 0.3;

    private Sizing width = Sizing.hug();
    private Sizing height = Sizing.hug();
    private Insets padding = Insets.NONE;
    private Color background;
    private Fill fill;
    private Border border = Border.none();
    private Corner corner = Corner.ROUND;
    private int borderRadius;
    private boolean hidden;
    private String key;
    private String cursorIcon;
    private java.util.function.Supplier<String> caption;
    private boolean tracksCursor;
    private int transitionMs;
    private Easing transitionEasing = Easing.EASE_OUT;

    /** Position in the tree, used to identify this node's animations when it has no key. */
    private String path = "";
    private Animator animator;

    private Color hoverBackground;
    private Color hoverBorderColor;
    private Runnable onClick;
    private ClickAt onClickAt;
    private Justify placeX = Justify.START;
    private Align placeY = Align.START;
    private double pressed;
    private Runnable onHoverStart;
    private Runnable onHoverStop;
    private boolean hovered;

    private Rect bounds = Rect.EMPTY;

    protected final S self() {
        return (S) this;
    }

    // ---- sizing ----

    // A mode change carries the bounds over, so .maxWidth(80).fill() and .fill().maxWidth(80) mean the
    // same thing. Order-dependent styling would be a trap in a chain this long.

    public S width(int pixels) {
        this.width = Sizing.fixed(pixels).bounded(width.min(), width.max());
        return self();
    }

    public S height(int pixels) {
        this.height = Sizing.fixed(pixels).bounded(height.min(), height.max());
        return self();
    }

    public S size(int widthPixels, int heightPixels) {
        return width(widthPixels).height(heightPixels);
    }

    /** Claim the leftover space on the container's main axis. */
    public S fill() {
        return fill(1);
    }

    public S fill(int weight) {
        this.width = Sizing.fill(weight).bounded(width.min(), width.max());
        this.height = Sizing.fill(weight).bounded(height.min(), height.max());
        return self();
    }

    public S fillWidth() {
        this.width = Sizing.fill(1).bounded(width.min(), width.max());
        return self();
    }

    public S fillHeight() {
        this.height = Sizing.fill(1).bounded(height.min(), height.max());
        return self();
    }

    /**
     * Smallest width this node will resolve to, whatever its sizing mode works out.
     *
     * <p>A minimum is allowed to overflow the space on offer. Something has to give when a node cannot
     * have what it asks for, and a control with a minimum is saying it would rather be clipped than
     * squeezed down to unusable.
     */
    public S minWidth(int pixels) {
        this.width = width.withMin(pixels);
        return self();
    }

    /**
     * Largest width this node will resolve to, whatever its sizing mode works out - including
     * {@link #width(int)}, which a maximum beats.
     *
     * <p>This is the pairing the three sizing modes cannot express on their own. {@code fill().maxWidth(80)}
     * is a content column that stops growing on a wide wall instead of stretching across it, and the space it
     * gives up goes back to its fill siblings rather than off the edge of the row.
     */
    public S maxWidth(int pixels) {
        this.width = width.withMax(pixels);
        return self();
    }

    public S minHeight(int pixels) {
        this.height = height.withMin(pixels);
        return self();
    }

    /**
     * Largest height this node will resolve to.
     *
     * <p>On a {@link Scroll} this is what gives a list "grow until it does not fit, then scroll", rather
     * than a height that has to be decided before anyone knows how many rows there are.
     */
    public S maxHeight(int pixels) {
        this.height = height.withMax(pixels);
        return self();
    }

    public S widthBetween(int minimum, int maximum) {
        this.width = width.bounded(minimum, maximum);
        return self();
    }

    public S heightBetween(int minimum, int maximum) {
        this.height = height.bounded(minimum, maximum);
        return self();
    }

    // ---- appearance ----

    public S padding(int all) {
        this.padding = Insets.all(all);
        return self();
    }

    public S padding(int vertical, int horizontal) {
        this.padding = Insets.symmetric(vertical, horizontal);
        return self();
    }

    public S padding(Insets insets) {
        this.padding = insets;
        return self();
    }

    public S background(Color color) {
        this.background = color;
        this.fill = null;
        return self();
    }

    /**
     * Fills with a gradient instead of a flat color. Dithered when painted, because the map palette
     * has too few stops to ramp between arbitrary colors without visible stripes.
     */
    public S gradient(Color from, Color to, Fill.Direction direction) {
        this.fill = Fill.gradient(from, to, direction);
        return self();
    }

    public S gradient(Color from, Color to) {
        return gradient(from, to, Fill.Direction.VERTICAL);
    }

    /** Any fill of your own, for patterns the built-ins don't cover. */
    public S fill(Fill value) {
        this.fill = value;
        return self();
    }

    public S border(int pixels, Color color) {
        this.border = Border.solid(pixels, color);
        return self();
    }

    /** Bevel lit from the top left, shades worked out from the background. */
    public S raised(int pixels) {
        this.border = Border.raised(pixels);
        return self();
    }

    /** The same inverted, so the box reads as pressed in. */
    public S sunken(int pixels) {
        this.border = Border.sunken(pixels);
        return self();
    }

    /** Bevel with the shades spelled out. */
    public S bevel(int pixels, Color light, Color dark) {
        this.border = Border.bevel(pixels, light, dark);
        return self();
    }

    public S radius(int pixels) {
        this.borderRadius = pixels;
        return self();
    }

    /** Corner treatment. Ignored unless a radius is set. */
    public S corner(Corner value) {
        this.corner = value;
        return self();
    }

    public S corner(Corner value, int radius) {
        this.corner = value;
        this.borderRadius = radius;
        return self();
    }

    /**
     * Map cursor shown while this node is hovered, such as {@code "RED_MARKER"}. A name rather than the type
     * itself, so the layout engine stays free of any server dependency.
     */
    public S cursorIcon(String typeName) {
        this.cursorIcon = typeName;
        return self();
    }

    /** Text shown under the cursor while hovered. Room for a few words, so "what is this" rather than a paragraph. */
    public S caption(String text) {
        return caption(() -> text);
    }

    /** The same, read when it is needed, for a caption that depends on where the cursor is. */
    public S caption(java.util.function.Supplier<String> text) {
        this.caption = text;
        return self();
    }

    /**
     * Repaint whenever the cursor moves inside this node, not only when it arrives.
     *
     * <p>Costs a frame per pixel of movement, so it is off by default - but anything drawing at the cursor
     * needs it, or what it draws lags a hover behind.
     */
    public S tracksCursor(boolean value) {
        this.tracksCursor = value;
        return self();
    }

    @Override
    public boolean tracksCursor() {
        return tracksCursor;
    }

    public S hidden(boolean value) {
        this.hidden = value;
        return self();
    }

    /** Stable identity across rebuilds, so scroll offsets and animations survive a state change. */
    public S key(String value) {
        this.key = value;
        return self();
    }

    /** Ease this node's colors when they change, instead of snapping. */
    public S transition(int millis) {
        return transition(millis, Easing.EASE_OUT);
    }

    public S transition(int millis, Easing easing) {
        this.transitionMs = millis;
        this.transitionEasing = easing;
        return self();
    }

    public S transition() {
        return transition(Animator.DEFAULT_DURATION_MS);
    }

    /**
     * Where this node sits when it is overlaid rather than laid out in a row - a count in the top right,
     * a label across the middle. Ignored outside an {@link Ui#Overlay}.
     */
    @Override
    public S place(Justify horizontal, Align vertical) {
        this.placeX = horizontal;
        this.placeY = vertical;
        return self();
    }

    @Override
    public Justify placeX() {
        return placeX;
    }

    @Override
    public Align placeY() {
        return placeY;
    }

    // ---- interaction ----

    /**
     * Runs when this node is activated, whichever button the screen has bound to that.
     *
     * <p>Right-click by default because left-click plays the arm swing, which knocks the held map down on
     * every press - and the client starts that before the server hears the click, so nothing can suppress it.
     */
    public S onClick(Runnable action) {
        this.onClick = action;
        return self();
    }

    /** The same, told where inside this node the click landed. Both run if both are set. */
    public S onClick(ClickAt action) {
        this.onClickAt = action;
        return self();
    }

    /** Applies a bundle of styling, so a look can be named once and reused: {@code Button("Save").apply(PRIMARY)}. */
    public S apply(java.util.function.Consumer<S> styling) {
        styling.accept(self());
        return self();
    }

    public S onHover(Runnable start, Runnable stop) {
        this.onHoverStart = start;
        this.onHoverStop = stop;
        return self();
    }

    public S hoverBackground(Color color) {
        this.hoverBackground = color;
        return self();
    }

    public S hoverBorder(Color color) {
        this.hoverBorderColor = color;
        return self();
    }

    // ---- state accessors ----

    @Override
    public Rect bounds() {
        return bounds;
    }

    @Override
    public boolean hidden() {
        return hidden;
    }

    @Override
    public String key() {
        return key;
    }

    /**
     * What identifies this node across rebuilds: its key if it has one, otherwise its position in the tree.
     * Scroll offsets and animations are filed under this, so the fallback is what makes them survive without
     * a key - though a key is still worth setting inside a list, since paths shift when rows move.
     */
    @Override
    public String identity() {
        return key != null ? key : path;
    }

    @Override
    public Sizing widthSizing() {
        return width;
    }

    @Override
    public Sizing heightSizing() {
        return height;
    }

    @Override
    public List<Node> children() {
        return List.of();
    }

    public Insets padding() {
        return padding;
    }

    public boolean hovered() {
        return hovered;
    }

    /**
     * How pressed this node looks, 1 right after a click and fading to 0.
     *
     * <p>Minecraft reports the button going down but never the release, so there is no held state to style.
     */
    @Override
    public double pressed() {
        return pressed;
    }

    @org.jetbrains.annotations.ApiStatus.Internal
    public void pressed(double value) {
        this.pressed = value;
    }

    /** Darkened while pressed, which needs no color choices from the caller to read as a press. */
    private Color depressed(Color color) {
        if (color == null || pressed <= 0) return color;

        return Colors.scale(color, 1 - PRESS_DARKEN * pressed);
    }

    protected Color resolvedBackground() {
        return depressed(animated("bg", hovered && hoverBackground != null ? hoverBackground : background));
    }

    /** A hover background always wins over a gradient, so hover states stay possible either way. */
    protected Fill resolvedFill() {
        if (hovered && hoverBackground != null) return Fill.solid(depressed(animated("bg", hoverBackground)));
        if (fill != null) return fill;

        Color color = depressed(animated("bg", background));
        return color == null ? null : Fill.solid(color);
    }

    protected Border resolvedBorder() {
        Border resolved = hovered && hoverBorderColor != null ? border.recoloured(hoverBorderColor) : border;
        Color primary = animated("border", resolved.primary());
        return primary == resolved.primary() ? resolved : resolved.recoloured(primary);
    }

    /** Eased toward {@code target} if this node has a transition, otherwise just {@code target}. */
    protected final Color animated(String property, Color target) {
        if (animator == null || transitionMs <= 0 || target == null) return target;

        return animator.color(animationKey(property), target, transitionMs, transitionEasing);
    }

    protected final double animated(String property, double target, int millis, Easing easing) {
        return animator == null ? target : animator.value(animationKey(property), target, millis, easing);
    }

    protected final String animationKey(String property) {
        return identity() + ':' + property;
    }

    protected final Animator animator() {
        return animator;
    }

    protected final int transitionMillis() {
        return transitionMs;
    }

    protected final Easing transitionEasing() {
        return transitionEasing;
    }

    /** Assigned by the screen before layout. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public void path(String value) {
        this.path = value;
    }

    @Override
    public String cursorIcon() {
        return cursorIcon;
    }

    @Override
    public String caption() {
        return caption == null ? null : caption.get();
    }

    @Override
    public boolean interactive() {
        return onClick != null || onClickAt != null || onHoverStart != null
                || hoverBackground != null || hoverBorderColor != null
                || cursorIcon != null || caption != null || tracksCursor;
    }

    @Override
    public void click(int x, int y) {
        if (onClick != null) {
            onClick.run();
        }
        if (onClickAt != null) {
            onClickAt.at(x, y);
        }
    }

    @Override
    public void hoverChanged(boolean value) {
        if (hovered == value) return;

        hovered = value;
        Runnable callback = value ? onHoverStart : onHoverStop;
        if (callback != null) {
            callback.run();
        }
    }

    // ---- layout ----

    @Override
    public final Measured measure(LayoutContext context, int availableWidth, int availableHeight) {
        // The content is measured against the size this node will really resolve to, not the parent's:
        // a fixed size has to reach the content, or text would wrap against the parent's width and then be
        // squeezed into a narrower box - and a maximum does the same thing in reverse.
        int limitWidth = limit(width, availableWidth);
        int limitHeight = limit(height, availableHeight);

        int contentWidth = Math.max(0, limitWidth - padding.horizontal());
        int contentHeight = Math.max(0, limitHeight - padding.vertical());
        Measured content = measureContent(context, contentWidth, contentHeight);

        return new Measured(
                resolve(width, content.width() + padding.horizontal(), availableWidth),
                resolve(height, content.height() + padding.vertical(), availableHeight)
        );
    }

    /** The size this node cannot get past, which is what its content is measured against. */
    private static int limit(Sizing sizing, int available) {
        return sizing.clamp(sizing.mode() == Sizing.Mode.FIXED ? sizing.value() : available);
    }

    private static int resolve(Sizing sizing, int natural, int available) {
        int resolved = switch (sizing.mode()) {
            case HUG, FILL -> Math.min(natural, available);
            case FIXED -> sizing.value();
        };
        return sizing.clamp(resolved);
    }

    @Override
    public final void arrange(LayoutContext context, Rect rect) {
        this.animator = context.animator();
        this.bounds = rect;
        arrangeContent(context, rect.shrink(padding));
    }

    @Override
    public void paint(Painter painter) {
        if (hidden) return;

        Fill painted = resolvedFill();
        Border outline = resolvedBorder();
        if (painted != null || outline.visible()) {
            painter.box(bounds, painted, outline, corner, borderRadius);
        }
        paintContent(painter);
    }

    /** Size of everything inside the padding box. */
    protected abstract Measured measureContent(LayoutContext context, int availableWidth, int availableHeight);

    /** Place children inside the padding box. Leaves do nothing. */
    protected void arrangeContent(LayoutContext context, Rect content) {
    }

    protected void paintContent(Painter painter) {
    }

    /** The padding box, i.e. where content is drawn. */
    protected Rect contentBounds() {
        return bounds.shrink(padding);
    }
}
