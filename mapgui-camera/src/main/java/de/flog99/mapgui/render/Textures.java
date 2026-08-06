package de.flog99.mapgui.render;

/**
 * Texture lookup by resolved name, as the tracer needs it.
 *
 * <p>An interface so the trace can be tested against a handful of hand-built textures rather than a zip of
 * assets - the geometry is the part worth testing, and it should not need a client jar to exercise.
 */
public interface Textures {

    /** Never null: a name no layer carries comes back as the missing-texture checkerboard. */
    Texture get(String name);

}
