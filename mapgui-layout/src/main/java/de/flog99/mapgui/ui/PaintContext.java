package de.flog99.mapgui.ui;

/**
 * Handed to a {@link CustomPaint} node: the painter, the rect the node was given, and whether the cursor is on it.
 *
 * <p>The hover flag is here because a custom-painted node has no background for {@code hoverBackground} to change,
 * so a mark drawn as pixels is the one kind of widget that has to answer for its own hover state. Without it the
 * only way to draw one differently under the cursor is to mirror the flag into a field of your own from
 * {@code onHover}, which is a second copy of something the node already knows.
 */
public record PaintContext(Painter painter, Rect bounds, boolean hovered) {
}
