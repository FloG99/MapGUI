package de.flog99.mapgui.examples.walls;

import de.flog99.mapgui.SharedModel;
import de.flog99.mapgui.WallLayout;

import java.awt.Color;
import java.util.Arrays;

/**
 * The shared canvas everyone is drawing on.
 *
 * <p>The whole trick behind "shared drawing, private tools": the wall is opened with
 * {@code screenPerPlayer}, so each viewer has their own screen, color and brush, but every one of those
 * screens draws <i>this</i>.
 *
 * <p>A {@link SharedModel}, because a stroke by one player has to repaint everybody else's view and their
 * screens have no other way to hear about it.
 */
final class Drawing extends SharedModel {

    /**
     * The whole picture, and the reason the wall is pinned to 2x2.
     *
     * <p>One canvas is shared by every wall showing it, so it cannot be sized per wall. The alternative - a
     * canvas big enough for anything, with smaller walls looking onto part of it - means two players drawing
     * on the same board see different halves.
     */
    static final int SIZE = 2 * WallLayout.TILE;

    /** Nought means nothing drawn, so the wall shows through. Anything else is an index into PALETTE. */
    private final byte[] pixels = new byte[SIZE * SIZE];

    byte at(int x, int y) {
        return inside(x, y) ? pixels[y * SIZE + x] : 0;
    }

    // ---- drawing ----

    /** One round dab, because a single pixel at map scale is not something you can aim at. */
    void dot(int x, int y, int radius, byte color) {
        dab(x, y, radius, color);
        changed();
    }

    void line(int fromX, int fromY, int toX, int toY, int radius, byte color) {
        trace(fromX, fromY, toX, toY, radius, color);
        changed();
    }

    /**
     * A quadratic curve, which turns a series of clicks into something that looks drawn rather than folded.
     *
     * <p>Sampled at about one step per pixel of span and traced between the samples, since sampling alone
     * leaves gaps exactly where the curve is sharpest.
     */
    void curve(int fromX, int fromY, int viaX, int viaY, int toX, int toY, int radius, byte color) {
        int steps = Math.max(2, span(fromX, fromY, viaX, viaY) + span(viaX, viaY, toX, toY));
        int lastX = fromX;
        int lastY = fromY;

        for (int step = 1; step <= steps; step++) {
            double along = (double) step / steps;
            double rest = 1 - along;
            double weight = 2 * rest * along;

            int x = (int) Math.round(rest * rest * fromX + weight * viaX + along * along * toX);
            int y = (int) Math.round(rest * rest * fromY + weight * viaY + along * along * toY);
            trace(lastX, lastY, x, y, radius, color);
            lastX = x;
            lastY = y;
        }
        changed();
    }

    /** Replaces the connected run of whatever color was clicked. Four-way rather than eight, so a diagonal line holds the paint in. */
    void flood(int x, int y, byte color) {
        if (!inside(x, y)) return;

        int start = y * SIZE + x;
        byte target = pixels[start];
        if (target == color) return;

        IntStack pending = new IntStack();
        pixels[start] = color;
        pending.push(start);

        while (!pending.isEmpty()) {
            int at = pending.pop();
            int atX = at % SIZE;
            int atY = at / SIZE;

            spread(pending, atX - 1, atY, target, color);
            spread(pending, atX + 1, atY, target, color);
            spread(pending, atX, atY - 1, target, color);
            spread(pending, atX, atY + 1, target, color);
        }
        changed();
    }

    void clear() {
        Arrays.fill(pixels, (byte) 0);
        changed();
    }

    // ---- pixels ----

    /** Colored on the way in rather than on the way out, which is what stops it queuing a pixel twice. */
    private void spread(IntStack pending, int x, int y, byte target, byte color) {
        if (!inside(x, y)) return;

        int at = y * SIZE + x;
        if (pixels[at] != target) return;

        pixels[at] = color;
        pending.push(at);
    }

    /**
     * Silent, so a stroke made of hundreds of these repaints everyone once rather than hundreds of times.
     *
     * <p>Measured against a circle half a pixel wider than asked for, which is what
     * {@code radius * radius + radius} comes to for whole numbers. An exact circle reaches its full radius
     * only straight up, down and across, so its extreme rows are one pixel wide and every brush size grows a
     * spike - small, but at map scale you can count the pixels.
     */
    private void dab(int centerX, int centerY, int radius, byte color) {
        int reach = radius * radius + radius;

        for (int y = centerY - radius; y <= centerY + radius; y++) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                int dx = x - centerX;
                int dy = y - centerY;
                if (dx * dx + dy * dy > reach || !inside(x, y)) continue;

                pixels[y * SIZE + x] = color;
            }
        }
    }

    private void trace(int fromX, int fromY, int toX, int toY, int radius, byte color) {
        int steps = Math.max(1, Math.max(Math.abs(toX - fromX), Math.abs(toY - fromY)));
        for (int step = 0; step <= steps; step++) {
            dab(fromX + (toX - fromX) * step / steps, fromY + (toY - fromY) * step / steps, radius, color);
        }
    }

    private static int span(int fromX, int fromY, int toX, int toY) {
        return Math.abs(toX - fromX) + Math.abs(toY - fromY);
    }

    private static boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < SIZE && y < SIZE;
    }

    /** Ints without the boxing, because filling the whole canvas would be half a million of them. */
    private static final class IntStack {

        private int[] values = new int[256];
        private int size;

        void push(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        int pop() {
            return values[--size];
        }

        boolean isEmpty() {
            return size == 0;
        }
    }

    /** Nine colors. Nought is not one of them - that is "nothing here", which only clearing produces. */
    static final Color[] PALETTE = {
            new Color(230, 60, 60),
            new Color(235, 140, 40),
            new Color(235, 215, 70),
            new Color(90, 205, 100),
            new Color(60, 200, 205),
            new Color(70, 125, 235),
            new Color(160, 90, 230),
            new Color(240, 240, 240),
            new Color(30, 30, 35),
    };
}
