package de.flog99.mapgui.examples.walls;

import de.flog99.mapgui.Click;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.ui.Align;
import de.flog99.mapgui.ui.Colors;
import de.flog99.mapgui.ui.Justify;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.PaintContext;
import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.State;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.awt.Color;
import java.util.List;
import java.util.Locale;

import static de.flog99.mapgui.ui.Ui.Box;
import static de.flog99.mapgui.ui.Ui.Button;
import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Draw;
import static de.flog99.mapgui.ui.Ui.Overlay;
import static de.flog99.mapgui.ui.Ui.Row;
import static de.flog99.mapgui.ui.Ui.Spacer;
import static de.flog99.mapgui.ui.Ui.Text;
import static de.flog99.mapgui.ui.Ui.each;

/**
 * A drawing board where the picture is shared and the tools are not.
 *
 * <p>One per viewer - the wall is opened with {@code screenPerPlayer} - and they all draw the same
 * {@link Drawing}. So a stroke shows up for everyone, while your color, brush and menu are yours alone.
 *
 * <p>It has to work this way round because a map tile is one pixel buffer: the instant any pixel differs
 * between two viewers they each need their own copy of that tile, so there is no arrangement where the
 * picture is sent once and the menu separately. Sharing the <i>model</i> is what gets you common ground.
 */
public final class DrawScreen extends Screen {

    private static final int MIN_BRUSH = 1;
    private static final int MAX_BRUSH = 10;

    /**
     * Longest gap between two clicks that still counts as one stroke.
     *
     * <p>Holding right-click repeats about every 200ms, so anything comfortably above that keeps a held line
     * continuous while letting go and pressing again starts a fresh one. Pausing is how you say "new line".
     */
    private static final long SAME_STROKE_MS = 300;
    private static final int SLIDER_WIDTH = 56;
    private static final int SLIDER_HEIGHT = 9;

    /** How a click draws. A tool rather than part of the picture, so it lives per player like the color. */
    private enum Mode {
        /** Joins clicks into one smooth line, until you pause. Hold the button and draw. */
        LINE,
        /** A dab where you clicked, and nothing between. */
        DOTS,
        /** Repaints the whole region you clicked in. */
        FILL
    }

    private final Drawing drawing;
    private final State<Integer> color = state(0);
    private final State<Mode> mode = state(Mode.LINE);
    private final State<Integer> brush = state(3);
    private final State<Boolean> menu = state(false);

    /** Where the pen last was, and how far the line has actually been drawn. See {@link #extend}. */
    private boolean penDown;
    private int penX;
    private int penY;
    private int drawnToX;
    private int drawnToY;
    private long penMovedAt;

    public DrawScreen(Drawing drawing) {
        this.drawing = drawing;
    }

    @Override
    public Component title() {
        return Component.text("Drawing board", NamedTextColor.AQUA);
    }

    /** Everyone else's strokes arrive this way. Nothing to undo on close - watching ends with the screen. */
    @Override
    protected void onOpen() {
        watch(drawing);
    }

    /** Left-click opens the menu, right-click draws - so the common action gets the steadier button. */
    @Override
    public Click activateOn() {
        return Click.BOTH;
    }

    @Override
    public Color background() {
        return new Color(28, 30, 38);
    }

    /**
     * Nothing but the picture until the menu is asked for.
     *
     * <p>The open menu sits inside a full-screen layer that takes clicks too, so it cannot be drawn through
     * and a click that misses it dismisses it rather than leaving a dab behind. Hit testing picks the
     * innermost node, so the swatch beats the menu and the menu beats the layer.
     */
    @Override
    protected Node build() {
        Node canvas = Draw(this::paintCanvas).onClick(this::stroke).fill();
        if (!menu.get()) return canvas;

        return Overlay(
                canvas,
                Column(Spacer(), menu())
                        .align(Align.STRETCH).padding(4).fill()
                        .onClick(this::dismiss)
        ).fill();
    }

    // ---- the menu ----

    private Node menu() {
        return Column(
                Row(
                        modeButton(Mode.LINE),
                        modeButton(Mode.DOTS),
                        modeButton(Mode.FILL),
                        Spacer(),
                        Button("clear").padding(2, 5).radius(3)
                                .background(new Color(60, 40, 46)).textColor(Color.WHITE)
                                .hoverBackground(new Color(150, 60, 70))
                                // Wipes it for everybody, because there is only one picture.
                                .onClick(drawing::clear)
                ).gap(3).align(Align.CENTER),
                Row(each(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), i -> "swatch" + i, this::swatch))
                        .gap(3).justify(Justify.CENTER),
                Row(
                        Text(() -> "size " + brush.get()).color(new Color(150, 158, 175)),
                        Draw(this::paintSlider).onClick(this::pickBrush).size(SLIDER_WIDTH, SLIDER_HEIGHT)
                ).gap(4).align(Align.CENTER).justify(Justify.CENTER)
        ).gap(3).padding(3).radius(4).background(Colors.alpha(Color.BLACK, 180))
                .onClick(this::clickedMenu);
    }

    /**
     * A click that hit the menu but none of its controls, swallowed rather than ignored - without a handler it
     * would fall through and paint a dab under the menu. Left-click still closes, since that is what opened it.
     */
    private void clickedMenu() {
        if (clickedWith() == Click.LEFT) {
            menu.set(false);
        }
    }

    /** A click that missed the menu. Dismisses it instead of drawing, so nothing is left where you clicked. */
    private void dismiss() {
        menu.set(false);
    }

    private Node modeButton(Mode value) {
        boolean current = mode.get() == value;
        return Button(label(value)).padding(2, 5).radius(3)
                .background(current ? new Color(60, 100, 150) : new Color(48, 50, 62))
                .textColor(Color.WHITE)
                .hoverBackground(new Color(90, 140, 190))
                .onClick(() -> {
                    mode.set(value);
                    liftPen();
                });
    }

    /** Stays open on a pick, so you can try a few colors, or set a color and a size, in one visit. */
    private Node swatch(int index) {
        return Box(Drawing.PALETTE[index]).size(14, 14).radius(3)
                .border(2, color.get() == index ? Color.WHITE : Colors.alpha(Color.BLACK, 140))
                .onClick(() -> pickColor(index));
    }

    /** A track with the picked size filled in, and the brush itself drawn as the knob. */
    private void paintSlider(PaintContext context) {
        Rect bounds = context.bounds();
        int middle = bounds.y() + bounds.height() / 2;
        int knob = bounds.x() + (brush.get() - MIN_BRUSH) * (bounds.width() - 1) / (MAX_BRUSH - MIN_BRUSH);

        context.painter().fill(new Rect(bounds.x(), middle, bounds.width(), 1), new Color(90, 95, 110));
        context.painter().fill(new Rect(bounds.x(), middle, knob - bounds.x() + 1, 1), Color.WHITE);
        context.painter().fill(new Rect(knob - 1, middle - 3, 3, 7), Drawing.PALETTE[color.get()]);
    }

    /** Anywhere along the track, so it reads as a slider rather than a row of buttons. */
    private void pickBrush(int x, int y) {
        int span = SLIDER_WIDTH - 1;
        int picked = MIN_BRUSH + (x * (MAX_BRUSH - MIN_BRUSH) + span / 2) / span;
        brush.set(Math.clamp(picked, MIN_BRUSH, MAX_BRUSH));
    }

    private static String label(Mode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    // ---- the picture ----

    /** The shared picture. Nought is left untouched, so the wall behind shows through unpainted parts. */
    private void paintCanvas(PaintContext context) {
        Rect bounds = context.bounds();

        for (int y = 0; y < bounds.height(); y++) {
            for (int x = 0; x < bounds.width(); x++) {
                byte value = drawing.at(x, y);
                if (value == 0) continue;

                context.painter().pixel(bounds.x() + x, bounds.y() + y, Drawing.PALETTE[value - 1]);
            }
        }
    }

    /** Right-click draws; left-click is the menu instead, since drawing needs the steady button. */
    private void stroke(int x, int y) {
        if (clickedWith() == Click.LEFT) {
            menu.set(!menu.get());
            liftPen();
            return;
        }

        byte paint = (byte) (color.get() + 1);
        switch (mode.get()) {
            case LINE -> extend(x, y, paint);
            case DOTS -> drawing.dot(x, y, brush.get(), paint);
            case FILL -> drawing.flood(x, y, paint);
        }
    }

    /**
     * Adds a point to the line being drawn, curving it through the point before.
     *
     * <p>Each click draws as far as the midpoint of the last two points, using the point between them as the
     * control - which turns a series of positions into a smooth line rather than a chain of corners, at the
     * cost of staying half a segment behind.
     *
     * <p>A gap of more than {@link #SAME_STROKE_MS} lifts the pen first.
     */
    private void extend(int x, int y, byte paint) {
        long now = System.currentTimeMillis();
        if (now - penMovedAt > SAME_STROKE_MS) {
            liftPen();
        }
        penMovedAt = now;

        if (!penDown) {
            drawing.dot(x, y, brush.get(), paint);
            penDown = true;
            penX = x;
            penY = y;
            drawnToX = x;
            drawnToY = y;
            return;
        }

        int midX = (penX + x) / 2;
        int midY = (penY + y) / 2;
        drawing.curve(drawnToX, drawnToY, penX, penY, midX, midY, brush.get(), paint);

        drawnToX = midX;
        drawnToY = midY;
        penX = x;
        penY = y;
    }

    /** Ends the line, so the next click starts a new one instead of joining where you left off. */
    private void liftPen() {
        penDown = false;
    }

    private void pickColor(int index) {
        color.set(index);
        // A stroke keeps one color, so changing it starts the next line rather than recoloring this one.
        liftPen();
    }

    /** The wheel steps through the palette, which is why {@code onScroll} exists at all. */
    @Override
    protected boolean onScroll(int notches) {
        pickColor(Math.floorMod(color.get() + notches, Drawing.PALETTE.length));
        return true;
    }
}
