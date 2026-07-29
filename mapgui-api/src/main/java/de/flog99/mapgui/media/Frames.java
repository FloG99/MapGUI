package de.flog99.mapgui.media;

/**
 * A decoded animation, ready to be drawn.
 *
 * <p>Deliberately dumb: frames as pixels, and how long each one lasts. Whatever produced them - a GIF,
 * a video file, a live stream - is not this interface's business, which is what lets a player draw any
 * of them without knowing the difference.
 *
 * <p>Frames are palette indices rather than colors, because a long animation lives in memory for as
 * long as it is loaded and that is a four times difference: a minute at 10 fps is 9 MB of map indices
 * against 39 MB of packed RGB. Matching to the palette is the expensive part of drawing, so doing it
 * once on the way in is a saving twice over.
 */
public interface Frames {

    int width();

    int height();

    int count();

    /** How long the whole thing runs for, so a clock can be mapped onto it. */
    int durationMs();

    /** Which frame is showing at a point in time, wrapping round for anything past the end. */
    int indexAt(int millis);

    /** The palette's transparent entry, which also means "do not draw this pixel". */
    byte TRANSPARENT = 0;

    /**
     * One frame as map palette indices, row by row, {@code width() * height()} long.
     *
     * <p>Indices mean whatever the palette these were decoded with says they mean, so decode with the
     * same palette the surface uses - in practice there is only one map palette.
     *
     * <p>{@link #TRANSPARENT} pixels are skipped rather than drawn, so a transparent GIF composites over
     * whatever is already there. Nothing opaque ever matches to that index, so it is free to mean this.
     *
     * <p>May decode on demand, so treat it as expensive and do not call it per pixel.
     */
    byte[] pixels(int index);
}
