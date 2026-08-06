package de.flog99.mapgui.render;

/**
 * One box of a block's model, in sixteenths of a block.
 *
 * <p>Faces are indexed by {@link Direction#ordinal()} and a null means the model does not draw that side, so a
 * ray that hits one passes through - which is what makes a stair look like a stair from every angle.
 *
 * @param shade whether face direction darkens this box. Cross models say no, and they are flat planes whose
 *              reported face is arbitrary, so shading them picks a multiplier at random
 * @param rotX     the blockstate rotation this box was baked with, kept so a hit can be taken back into model
 *                 space. The geometry is already turned, but the face UVs were authored before it was - sample
 *                 them with a world-space convention and a sideways log has its bark running the wrong way
 * @param rotation the box's own turn within the model, or null for the great majority that have none
 * @param emission the light this box makes for itself, 1 to 15, or 0 to be lit by the block it sits in. The model
 *                 format's own {@code light_emission}, and how a firefly bush glows: its bush and its fireflies are
 *                 the same four planes twice over, the second set stating 15 - so the leaves take the light of the
 *                 night around them and the fireflies do not
 */
public record BakedElement(
        float fromX, float fromY, float fromZ,
        float toX, float toY, float toZ,
        BakedFace[] faces,
        boolean shade,
        int rotX,
        int rotY,
        ElementRotation rotation,
        int emission) {

    /** An unturned box, which is what all but a few hundred vanilla elements are. */
    public BakedElement(float fromX, float fromY, float fromZ, float toX, float toY, float toZ,
                        BakedFace[] faces, boolean shade, int rotX, int rotY) {
        this(fromX, fromY, fromZ, toX, toY, toZ, faces, shade, rotX, rotY, null, 0);
    }

    /** A box that makes no light of its own, which is all but a handful. */
    public BakedElement(float fromX, float fromY, float fromZ, float toX, float toY, float toZ,
                        BakedFace[] faces, boolean shade, int rotX, int rotY, ElementRotation rotation) {
        this(fromX, fromY, fromZ, toX, toY, toZ, faces, shade, rotX, rotY, rotation, 0);
    }

    /**
     * Whether this box fills its whole block, which is what lets the renderer take the DDA's face directly.
     *
     * <p>Never true of a turned box: its corners have left the block even when its bounds had not, so the face the
     * grid walk reported is not the face the ray actually met.
     */
    public boolean isFullBlock() {
        return rotation == null && fromX <= 0 && fromY <= 0 && fromZ <= 0 && toX >= 16 && toY >= 16 && toZ >= 16;
    }

    public BakedFace face(Direction direction) {
        return faces[direction.ordinal()];
    }

    boolean rotated() {
        return rotX != 0 || rotY != 0;
    }
}
