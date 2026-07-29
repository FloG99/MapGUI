package de.flog99.mapgui.ui;

/**
 * Click handler that is told where inside the node the click landed.
 *
 * <p>Coordinates are relative to the node's own top left, so a node does not have to know where it was
 * laid out. That is what a canvas needs: {@code Draw} covering a grid works out which cell was clicked
 * from these, rather than reaching back through the screen for the cursor.
 */
@FunctionalInterface
public interface ClickAt {

    void at(int x, int y);
}
