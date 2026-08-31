package de.flog99.mapgui.examples.sketch;

import de.flog99.mapgui.Click;
import de.flog99.mapgui.HandOptions;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.PaintContext;
import de.flog99.mapgui.ui.Rect;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.awt.Color;
import java.util.Arrays;

import static de.flog99.mapgui.ui.Ui.Draw;

/**
 * A drawing board you hold in one hand, drawn on by holding the button rather than by clicking.
 *
 * <p><b>Why this is a demo of its own.</b> A held right-click is not something a server can normally see: the
 * client repeats it every four ticks and says nothing at all when it is let go, so a stroke has to be guessed at
 * from the gaps between clicks. That guess is what makes a drawing board on a wall dotted for a lagging player and
 * leaves the pen down after they stop.
 *
 * <p><b>The whole of the pen is three methods.</b> {@link #holdable()} asks for the button to be read as a hold,
 * {@link #onHold} arrives once a tick for as long as it is down with the cursor where it is now, and
 * {@link #onHoldEnd()} is the client saying it is up. No task, no scheduler and no plugin: MapGUI is already
 * ticking this screen, so the pen rides on that.
 *
 * <p>The picture is a byte per pixel, a palette index and nought for blank, and it belongs to this screen: one
 * player, one board. Sharing one between several would be a
 * {@link de.flog99.mapgui.SharedModel}, which is the drawing board on a wall.
 */
public final class SketchScreen extends Screen {

    /** A held map is 128 square, and this one is all canvas. */
    private static final int SIZE = 128;

    /** The far end of the hotbar, where a fake map is least likely to be covering something in use. */
    private static final int SLOT = 8;

    private static final Color[] PALETTE = {
            new Color(28, 30, 38),
            new Color(240, 240, 240),
            new Color(220, 60, 60),
            new Color(240, 150, 40),
            new Color(240, 220, 60),
            new Color(80, 190, 90),
            new Color(60, 140, 230),
            new Color(160, 90, 210)
    };

    private static final int MIN_BRUSH = 1;
    private static final int MAX_BRUSH = 8;

    /** The picture: a palette index plus one, so nought can mean nothing has been drawn there. */
    private final byte[] pixels = new byte[SIZE * SIZE];

    private int color = 1;
    private int brush = 2;

    /** Where the pen last was, or -1 for a pen that is up and a stroke that has not started. */
    private int penX = -1;
    private int penY = -1;

    @Override
    public Component title() {
        return Component.text("Sketch", NamedTextColor.AQUA);
    }

    /** One fake map in one hotbar slot, so the player keeps the other eight and their wheel. */
    @Override
    public HandOptions hand() {
        return HandOptions.pinned(SLOT);
    }

    /** The whole point of the demo: the right button reports when it is let go. */
    @Override
    public boolean holdable() {
        return true;
    }

    /** Right-click draws, left-click is the palette - so the steadier button gets the job that lasts. */
    @Override
    public Click activateOn() {
        return Click.BOTH;
    }

    @Override
    public Color defaultBackground() {
        return PALETTE[0];
    }

    @Override
    protected Node build() {
        return Draw(this::paint).onClick(this::press).fill();
    }

    /**
     * The left button, which is the palette rather than the pen. Sneak turns it into a clear, since wiping the
     * board is the only thing here worth asking for twice.
     *
     * <p>Right-clicks land here too and are left alone: drawing is {@link #onHold}, so that a press and everything
     * after it are the same code rather than two paths that have to agree.
     */
    private void press() {
        if (clickedWith() != Click.LEFT) return;

        if (sneaking()) {
            Arrays.fill(pixels, (byte) 0);
        } else {
            color = 1 + (color % (PALETTE.length - 1));
        }
        invalidate();
    }

    /**
     * The pen, once a tick for as long as the button is down - MapGUI's own tick, so there is nothing to schedule.
     *
     * <p>Each sample is joined to the last rather than dabbed on its own: a head turns further in a tick than a
     * brush is wide, so dabs alone would draw a row of spots.
     */
    @Override
    protected void onHold(int x, int y) {
        if (x < 0 || y < 0) return;

        if (penX < 0) {
            dot(x, y);
        } else if (x == penX && y == penY) {
            return;
        } else {
            line(penX, penY, x, y);
        }

        penX = x;
        penY = y;
        invalidate();
    }

    /** The button let go, or the map put away under a stroke - either way the next press starts a new line. */
    @Override
    protected void onHoldEnd() {
        penX = -1;
        penY = -1;
    }

    /** The wheel is the player's own on a pinned map, so the brush is on shift+scroll, which MapGUI hands over. */
    @Override
    protected boolean onScroll(int notches) {
        brush = Math.clamp(brush + notches, MIN_BRUSH, MAX_BRUSH);
        invalidate();
        return true;
    }

    // ---- the picture ----

    private void paint(PaintContext context) {
        Rect bounds = context.bounds();

        for (int y = 0; y < Math.min(bounds.height(), SIZE); y++) {
            for (int x = 0; x < Math.min(bounds.width(), SIZE); x++) {
                byte value = pixels[y * SIZE + x];
                if (value == 0) continue;

                context.painter().pixel(bounds.x() + x, bounds.y() + y, PALETTE[value]);
            }
        }

        nib(context);
    }

    /** The colour and the size in one corner, since both are changed without a menu to show them in. */
    private void nib(PaintContext context) {
        Rect bounds = context.bounds();
        int side = MAX_BRUSH * 2 + 4;
        int left = bounds.x() + 2;
        int top = bounds.y() + bounds.height() - side - 2;

        context.painter().fill(new Rect(left, top, side, side), PALETTE[0]);
        context.painter().circle(left + side / 2, top + side / 2, brush, PALETTE[color], PALETTE[color]);
    }

    /** A round nib, so a stroke has the same width whichever way it runs. */
    private void dot(int x, int y) {
        int radius = brush - 1;

        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dy * dy > radius * radius) continue;

                set(x + dx, y + dy);
            }
        }
    }

    private void set(int x, int y) {
        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return;

        pixels[y * SIZE + x] = (byte) color;
    }

    /** Bresenham, one dab per step, which is all a line between two samples has to be. */
    private void line(int fromX, int fromY, int toX, int toY) {
        int spanX = Math.abs(toX - fromX);
        int spanY = -Math.abs(toY - fromY);
        int stepX = fromX < toX ? 1 : -1;
        int stepY = fromY < toY ? 1 : -1;
        int error = spanX + spanY;

        while (true) {
            dot(fromX, fromY);
            if (fromX == toX && fromY == toY) return;

            int doubled = 2 * error;
            if (doubled >= spanY) {
                error += spanY;
                fromX += stepX;
            }
            if (doubled <= spanX) {
                error += spanX;
                fromY += stepY;
            }
        }
    }
}
