package de.flog99.mapgui.ui;

import java.awt.Color;
import java.util.List;
import java.util.function.Supplier;

/**
 * Clickable box with centered text.
 *
 * <p>A leaf rather than a panel wrapping a label, so hover styling reaches the text without
 * having to propagate down to a child.
 */
public final class Button extends AbstractNode<Button> {

    private final Supplier<String> text;
    private Color textColor = Color.WHITE;
    private Color hoverTextColor;

    public Button(Supplier<String> text) {
        this.text = text;
        padding(2, 4);
    }

    public Button textColor(Color value) {
        this.textColor = value;
        return this;
    }

    public Button hoverTextColor(Color value) {
        this.hoverTextColor = value;
        return this;
    }

    public String text() {
        String value = text.get();
        return value == null ? "" : value;
    }

    @Override
    public boolean interactive() {
        return true;
    }

    @Override
    protected Measured measureContent(LayoutContext context, int availableWidth, int availableHeight) {
        TextFont font = context.font();
        return new Measured(Math.min(font.widthOf(text()), availableWidth), font.lineHeight());
    }

    @Override
    protected void paintContent(Painter painter) {
        Rect box = contentBounds();
        String label = painter.ellipsize(text(), box.width());
        Color color = animated("text", hovered() && hoverTextColor != null ? hoverTextColor : textColor);
        painter.textBlock(box, List.of(label), color, TextAlign.CENTER, false);
    }
}
