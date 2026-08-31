package de.flog99.mapgui;

import org.jetbrains.annotations.ApiStatus;

/**
 * How the client-only item frames a wall hangs on are drawn.
 *
 * <p>None of these cost anything: the frames are packets rather than entities, so every one of them is a bit
 * or two in the metadata that already goes out when a viewer arrives.
 *
 * <p>{@link #DEFAULT} is what a wall has always had, and every field of it is a choice worth being able to
 * unmake - see {@link WallDisplay.Builder#glowing}, {@link WallDisplay.Builder#invisible} and
 * {@link WallDisplay.Builder#itemRotation}.
 *
 * @param glowing whether the picture is drawn at full brightness rather than lit by the block behind it
 * @param invisible whether the frame's own model is hidden, leaving only the picture
 * @param itemRotation how far the map is turned inside the frame, in eighths of a full turn
 */
@ApiStatus.Experimental
public record FrameStyle(boolean glowing, boolean invisible, int itemRotation) {

    /** How many positions an item in a frame has - it turns in eighths, and the client knows no others. */
    public static final int ROTATIONS = 8;

    /** Lit at night, no frame edge, and the picture the right way up. */
    public static final FrameStyle DEFAULT = new FrameStyle(true, true, 0);

    public FrameStyle {
        if (itemRotation < 0 || itemRotation >= ROTATIONS) {
            throw new IllegalArgumentException("An item frame turns in eighths, so 0 to " + (ROTATIONS - 1) + ", not " + itemRotation);
        }
    }
}
