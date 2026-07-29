package de.flog99.mapgui.ui;

public record Measured(int width, int height) {

    public static final Measured ZERO = new Measured(0, 0);
}
