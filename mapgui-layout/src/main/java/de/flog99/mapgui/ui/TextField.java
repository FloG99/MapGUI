package de.flog99.mapgui.ui;

import java.awt.Color;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shows a value and asks for a new one when clicked.
 *
 * <p>There is no keyboard on a map, so editing always means handing off to something else - a
 * dialog, an anvil. This node only describes what to ask for; the screen supplies the
 * editor, which keeps the layout module free of any server dependency.
 */
public final class TextField extends AbstractNode<TextField> {

    private final Supplier<String> value;
    private String placeholder = "";
    private String title = "Enter a value";
    private int maxLength = 64;
    private String promptKey;
    private Consumer<String> onChange;
    private Color textColor = Color.WHITE;
    private Color placeholderColor = new Color(150, 158, 175);
    private TextAlign align = TextAlign.LEFT;

    private Consumer<TextField> editor;

    public TextField(Supplier<String> value) {
        this.value = value;
        padding(2, 3);
    }

    public TextField placeholder(String text) {
        this.placeholder = text;
        return this;
    }

    public TextField title(String text) {
        this.title = text;
        return this;
    }

    public TextField maxLength(int length) {
        this.maxLength = Math.max(1, length);
        return this;
    }

    /** Use a specific registered prompt provider instead of the server default. */
    public TextField prompt(String key) {
        this.promptKey = key;
        return this;
    }

    public TextField onChange(Consumer<String> action) {
        this.onChange = action;
        return this;
    }

    public TextField textColor(Color color) {
        this.textColor = color;
        return this;
    }

    public TextField placeholderColor(Color color) {
        this.placeholderColor = color;
        return this;
    }

    public TextField align(TextAlign value) {
        this.align = value;
        return this;
    }

    public String value() {
        String current = value.get();
        return current == null ? "" : current;
    }

    public String placeholder() {
        return placeholder;
    }

    public String title() {
        return title;
    }

    public int maxLength() {
        return maxLength;
    }

    public String promptKey() {
        return promptKey;
    }

    public void accept(String edited) {
        if (onChange != null) {
            onChange.accept(edited);
        }
    }

    /** Called by the screen once per rebuild. */
    public void attachEditor(Consumer<TextField> value) {
        this.editor = value;
    }

    @Override
    public boolean interactive() {
        return true;
    }

    @Override
    public void click(int x, int y) {
        super.click(x, y);
        if (editor != null) {
            editor.accept(this);
        }
    }

    @Override
    protected Measured measureContent(LayoutContext context, int availableWidth, int availableHeight) {
        TextFont font = context.font();
        String shown = value().isEmpty() ? placeholder : value();
        return new Measured(Math.min(font.widthOf(shown), availableWidth), font.lineHeight());
    }

    @Override
    protected void paintContent(Painter painter) {
        Rect box = contentBounds();
        String current = value();
        boolean empty = current.isEmpty();
        String shown = painter.ellipsize(empty ? placeholder : current, box.width());
        painter.textBlock(box, List.of(shown), empty ? placeholderColor : textColor, align, false);
    }
}
