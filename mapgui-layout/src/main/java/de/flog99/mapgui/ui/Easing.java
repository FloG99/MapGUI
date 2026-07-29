package de.flog99.mapgui.ui;

/** Shapes a 0..1 progress value. */
public interface Easing {

    Easing LINEAR = t -> t;

    /** Starts fast and settles. The right default for anything the player triggered. */
    Easing EASE_OUT = t -> 1 - Math.pow(1 - t, 3);

    Easing EASE_IN = t -> t * t * t;

    Easing EASE_IN_OUT = t -> t < 0.5
            ? 4 * t * t * t
            : 1 - Math.pow(-2 * t + 2, 3) / 2;

    double apply(double progress);
}
