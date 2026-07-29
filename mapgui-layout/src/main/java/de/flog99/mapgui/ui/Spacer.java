package de.flog99.mapgui.ui;

/** Draws nothing and eats the leftover space, which is how you push a sibling to the far edge. */
public final class Spacer extends AbstractNode<Spacer> {

    public Spacer() {
        fill();
    }

    @Override
    protected Measured measureContent(LayoutContext context, int availableWidth, int availableHeight) {
        return Measured.ZERO;
    }
}
