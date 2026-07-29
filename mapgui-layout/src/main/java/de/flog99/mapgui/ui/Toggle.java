package de.flog99.mapgui.ui;

import java.awt.Color;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Checkbox. The tick is drawn from two lines so it stays crisp at any size. */
public final class Toggle extends AbstractNode<Toggle> {

    private final BooleanSupplier value;
    private Consumer<Boolean> onChange;
    private int box = 11;
    private Color onColor = new Color(56, 193, 96);
    private Color offColor = new Color(110, 118, 138);
    private Color tickColor = Color.WHITE;

    public Toggle(BooleanSupplier value) {
        this.value = value;
    }

    public Toggle onChange(Consumer<Boolean> action) {
        this.onChange = action;
        return this;
    }

    public Toggle boxSize(int pixels) {
        this.box = Math.max(5, pixels);
        return this;
    }

    public Toggle colors(Color checked, Color unchecked, Color tick) {
        this.onColor = checked;
        this.offColor = unchecked;
        this.tickColor = tick;
        return this;
    }

    public boolean checked() {
        return value.getAsBoolean();
    }

    @Override
    public boolean interactive() {
        return true;
    }

    @Override
    public void click(int x, int y) {
        super.click(x, y);
        if (onChange != null) {
            onChange.accept(!checked());
        }
    }

    @Override
    protected Measured measureContent(LayoutContext context, int availableWidth, int availableHeight) {
        return new Measured(box, box);
    }

    @Override
    protected void paintContent(Painter painter) {
        Rect content = contentBounds();
        int size = Math.min(box, Math.min(content.width(), content.height()));
        int x = content.x() + (content.width() - size) / 2;
        int y = content.y() + (content.height() - size) / 2;
        Rect area = new Rect(x, y, size, size);
        boolean checked = checked();

        int radius = Math.max(1, size / 5);
        if (checked) {
            painter.rect(area, hovered() ? onColor.brighter() : onColor, 0, null, radius);
            int left = x + size / 5;
            int middle = y + size * 3 / 5;
            int bottom = y + size * 3 / 4;
            painter.line(left, middle, x + size * 2 / 5, bottom, tickColor);
            painter.line(x + size * 2 / 5, bottom, x + size * 4 / 5, y + size / 4, tickColor);
        } else {
            Color outline = hovered() ? onColor : offColor;
            painter.rect(area, null, 1, outline, radius);
        }
    }
}
