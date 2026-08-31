package de.flog99.mapgui.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Static factories for the layout DSL. Meant to be used as {@code import static
 * de.flog99.mapgui.ui.Ui.*;} so a screen reads like the tree it builds.
 *
 * <p>Only children go in the parentheses; everything else is a fluent modifier, because Java
 * has no named arguments and positional style would be unreadable the moment a node grew a
 * fourth property.
 */
public final class Ui {

    private Ui() {
    }

    public static Panel Row(Node... children) {
        return new Panel(Panel.Axis.ROW).children(children);
    }

    public static Panel Row(Collection<? extends Node> children) {
        return new Panel(Panel.Axis.ROW).children(children);
    }

    public static Panel Column(Node... children) {
        return new Panel(Panel.Axis.COLUMN).children(children);
    }

    public static Panel Column(Collection<? extends Node> children) {
        return new Panel(Panel.Axis.COLUMN).children(children);
    }

    public static Stack Overlay(Node... children) {
        return new Stack().children(children);
    }

    public static Stack Overlay(Collection<? extends Node> children) {
        return new Stack().children(children);
    }

    public static Scroll Scroll(Node... children) {
        return new Scroll().children(children);
    }

    public static Scroll Scroll(Collection<? extends Node> children) {
        return new Scroll().children(children);
    }

    /** A row that wraps when it runs out of width. With {@link Flow#columns(int)} it is a grid instead. */
    public static Flow Flow(Node... children) {
        return new Flow().children(children);
    }

    public static Flow Flow(Collection<? extends Node> children) {
        return new Flow().children(children);
    }

    public static Label Text(String text) {
        return new Label(() -> text);
    }

    /** Live text: re-read on every paint, so it tracks state without a rebuild. */
    public static Label Text(Supplier<String> text) {
        return new Label(text);
    }

    public static Button Button(String text) {
        return new Button(() -> text);
    }

    public static Button Button(Supplier<String> text) {
        return new Button(text);
    }

    public static Toggle Toggle(BooleanSupplier checked) {
        return new Toggle(checked);
    }

    public static TextField Field(Supplier<String> value) {
        return new TextField(value);
    }

    public static TextField Field(State<String> value) {
        return new TextField(value::get).onChange(value::set);
    }

    public static Spacer Spacer() {
        return new Spacer();
    }

    /**
     * Empty space of a fixed size, which a {@link #Spacer} is not - that one eats whatever is left over.
     *
     * <p>For holding a slot open when the thing that goes in it is not there: a control a server has turned off,
     * an icon that has not loaded. Hiding a node takes its space with it, so a row of three buttons becomes a row
     * of two and everything shifts.
     */
    public static Spacer Gap(int width, int height) {
        return new Spacer().size(width, height);
    }

    /** A picture from a file, drawn a pixel for a pixel. Null draws nothing, so a background shows through. */
    public static Bitmap Image(java.awt.image.BufferedImage image) {
        return new Bitmap(image);
    }

    /**
     * The same, read from your own plugin's resources and decoded once - see {@link Images}.
     *
     * <p>Which is what almost every picture on a screen is, so the path is the whole of it: no stream, no
     * {@code IOException}, and no cache of your own. A path that is not there draws nothing.
     */
    public static Bitmap Image(String path) {
        return new Bitmap(Images.of(path));
    }

    /** Art computed rather than shipped, kept in the same cache under a name of your own. */
    public static Bitmap Image(String name, Supplier<java.awt.image.BufferedImage> art) {
        return new Bitmap(Images.of(name, art));
    }

    /** A ring of dots that turns, for waiting on something that cannot say how far along it is. */
    public static Spinner Spinner() {
        return new Spinner();
    }

    public static Panel Divider(Color color) {
        return Row().fillWidth().height(1).background(color);
    }

    /** A box with nothing in it but a color - dots, bars, swatches. */
    public static Panel Box(Color color) {
        return Row().background(color);
    }

    public static CustomPaint Draw(Consumer<PaintContext> painter) {
        return new CustomPaint(painter);
    }

    /** Maps a list to children. The index is captured by the lambda, so no event strings. */
    public static <T> List<Node> each(List<T> items, BiFunction<T, Integer, Node> mapper) {
        List<Node> nodes = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Node node = mapper.apply(items.get(i), i);
            if (node != null) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    public static <T> List<Node> each(List<T> items, Function<T, Node> mapper) {
        return each(items, (item, index) -> mapper.apply(item));
    }

    /**
     * The same, keying each row by something stable about the item.
     *
     * <p>Worth reaching for whenever a list can reorder. Without a key a node is identified by its
     * position in the tree, so moving a row hands its scroll offset, its animations and its press flash
     * to whatever took its place - silently, since nothing can tell the difference.
     */
    public static <T> List<Node> each(List<T> items, Function<T, String> key, BiFunction<T, Integer, Node> mapper) {
        List<Node> nodes = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            Node node = mapper.apply(item, i);
            if (node == null) continue;

            if (node instanceof AbstractNode<?> concrete) {
                concrete.key(key.apply(item));
            }
            nodes.add(node);
        }
        return nodes;
    }

    public static <T> List<Node> each(List<T> items, Function<T, String> key, Function<T, Node> mapper) {
        return each(items, key, (item, index) -> mapper.apply(item));
    }
}
