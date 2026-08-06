package de.flog99.mapgui.render;

import java.util.ArrayList;
import java.util.List;

/**
 * One named, posed group of cubes, with the parts hung off it.
 *
 * <p>The shape vanilla's own {@code ModelPart} has, kept as plain data: a pose that places the group in its
 * parent's space, the cubes it draws, and its children. Nesting is what makes a head turn rather than orbit and a
 * two-segment leg bend at the knee, and it is why this is a tree instead of a flat list of boxes.
 *
 * <p>Coordinates are entity pixels - sixteenths of a block - with Y up, the feet at 0, local -Z the way the entity
 * faces and local +X the entity's own right. That is vanilla's model space turned by the half turn about Z that
 * {@code LivingEntityRenderer} applies before drawing anything, so X and Y both run the other way while the handedness
 * is kept - flipping Y alone would stand a model up and leave it mirrored, which is a pig whose tail curls the wrong
 * way. Rest rotations follow by conjugation: one about X or Y negates, one about Z does not.
 *
 * @param head whether the head rotation applies here, so a mob can look at the camera without swivelling its torso
 */
record MeshPart(
        String name,
        boolean head,
        float x, float y, float z,
        float xRot, float yRot, float zRot,
        float xScale, float yScale, float zScale,
        List<MeshCube> cubes,
        List<MeshPart> children) {

    /** The names vanilla gives the part that turns with the head. {@code head_parts} is the equines'. */
    static boolean isHead(String name) {
        return name.equals("head") || name.equals("head_parts");
    }

    /**
     * The same trees with the head rotation assigned to exactly the outermost part that answers to the name. The
     * equines nest a {@code head} inside a {@code head_parts} and both answer, so a rule looking at one name at a
     * time turns a donkey's head twice - only the tree knows which is outermost.
     *
     * <p>Here rather than at either end of the cache, because a flag derived in two places disagrees with itself:
     * fixing it in the extractor alone left the codec deriving it from the name, and the fix was invisible in game.
     */
    static List<MeshPart> withHeads(List<MeshPart> parts) {
        return marked(parts, false);
    }

    private static List<MeshPart> marked(List<MeshPart> parts, boolean underHead) {
        List<MeshPart> out = new ArrayList<>(parts.size());
        for (MeshPart part : parts) {
            boolean head = !underHead && isHead(part.name());
            out.add(part.asHead(head).withChildren(marked(part.children(), head || underHead)));
        }
        return List.copyOf(out);
    }

    /** A part with no rotation and no scale of its own, which is nearly all of them. */
    static MeshPart at(String name, float x, float y, float z, List<MeshCube> cubes, List<MeshPart> children) {
        return new MeshPart(name, isHead(name), x, y, z, 0, 0, 0, 1, 1, 1, cubes, children);
    }

    /** A leaf at the origin, for an authored model whose boxes already carry their own offsets. */
    static MeshPart of(String name, List<MeshCube> cubes) {
        return at(name, 0, 0, 0, cubes, List.of());
    }

    /**
     * The same part somewhere else, carrying everything below it along. The offset moves rather than the cubes, so a
     * subtree keeps its own rotations - what an item in a hand needs, since the whole shape shifts before it is turned.
     */
    MeshPart moved(float dx, float dy, float dz) {
        return new MeshPart(name, head, x + dx, y + dy, z + dz, xRot, yRot, zRot, xScale, yScale, zScale, cubes, children);
    }

    MeshPart withChildren(List<MeshPart> children) {
        return children.equals(this.children)
                ? this
                : new MeshPart(name, head, x, y, z, xRot, yRot, zRot, xScale, yScale, zScale, cubes, children);
    }

    /** Set rather than added, which is what the client's own animations do to a part it poses. */
    MeshPart withRotation(float xRot, float yRot, float zRot) {
        return xRot == this.xRot && yRot == this.yRot && zRot == this.zRot
                ? this
                : new MeshPart(name, head, x, y, z, xRot, yRot, zRot, xScale, yScale, zScale, cubes, children);
    }

    MeshPart asHead(boolean head) {
        return head == this.head
                ? this
                : new MeshPart(name, head, x, y, z, xRot, yRot, zRot, xScale, yScale, zScale, cubes, children);
    }
}
