package de.flog99.mapgui.ui;

/** Handed to a {@link CustomPaint} node: the painter plus the rect the node was given. */
public record PaintContext(Painter painter, Rect bounds) {
}
