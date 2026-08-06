package de.flog99.mapgui;

import de.flog99.mapgui.ui.AbstractNode;
import de.flog99.mapgui.ui.LayoutContext;
import de.flog99.mapgui.ui.Measured;
import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.TextAlign;
import net.kyori.adventure.text.Component;

import java.awt.Color;
import java.util.function.Supplier;

/**
 * A line of Adventure component, laid out like any other node.
 *
 * <p>The difference from a {@code Text} node is who owns the styling: a label is one string in one color the screen
 * chose, while this arrived already styled - a MiniMessage line from a config, an item's name, something out of chat
 * - and keeps the colors its author gave it. Measured with the same rule it is drawn by, so it sizes and aligns like
 * anything else:
 *
 * <pre>{@code
 * Column(
 *     RichText(() -> MiniMessage.miniMessage().deserialize(config.getString("title"))).shadow(),
 *     Text(() -> "and an ordinary label under it")
 * )
 * }</pre>
 *
 * <p><b>One line.</b> Wrapping styled text means cutting runs at the break, which is a different job from cutting a
 * string, so that stays with {@code Text}. Anything wider than its box is clipped rather than shortened.
 *
 * <p>Lives here rather than with the other nodes because the layout module deliberately has no Adventure on its
 * classpath: it is the part that unit tests with no server at all.
 */
public final class RichText extends AbstractNode<RichText> {

    private final Supplier<Component> text;
    private Color fallback = Color.WHITE;
    private TextAlign align = TextAlign.LEFT;
    private boolean shadow;

    public RichText(Supplier<Component> text) {
        this.text = text;
    }

    /** Read through a supplier, so a line that changes needs no rebuild of the tree. */
    public static RichText of(Supplier<Component> text) {
        return new RichText(text);
    }

    /** A component that does not change. */
    public static RichText of(Component text) {
        return new RichText(() -> text);
    }

    /**
     * What text with no color of its own is drawn in. White unless set.
     *
     * <p>Only reached where the component says nothing: anything the author colored keeps its own.
     */
    public RichText color(Color value) {
        this.fallback = value;
        return this;
    }

    public RichText align(TextAlign value) {
        this.align = value;
        return this;
    }

    public RichText shadow() {
        this.shadow = true;
        return this;
    }

    public Component text() {
        Component value = text.get();
        return value == null ? Component.empty() : value;
    }

    @Override
    protected Measured measureContent(LayoutContext context, int availableWidth, int availableHeight) {
        Component value = text();
        if (value.equals(Component.empty())) return Measured.ZERO;

        int width = ComponentText.widthOf(context.font(), value);
        return new Measured(Math.min(width, availableWidth), context.font().lineHeight());
    }

    @Override
    protected void paintContent(Painter painter) {
        Component value = text();
        if (value.equals(Component.empty())) return;

        ComponentText.draw(painter, contentBounds(), value, fallback, shadow, align);
    }
}
