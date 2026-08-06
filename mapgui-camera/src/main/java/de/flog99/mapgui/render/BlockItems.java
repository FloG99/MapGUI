package de.flog99.mapgui.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A block as an item, drawn from the block's own model.
 *
 * <p>Which is the difference between an oak log and a cube with bark on its end grain. 731 of 26.2's items are drawn
 * from a block model, and a great many are not one texture repeated. The geometry is already baked for the world, so
 * this is a change of frame rather than a second resolver: {@link BlockModels#shape} hands over the same boxes a ray
 * hits, and what happens here is turning them into entity cubes.
 *
 * <p><b>The frame.</b> A model is authored looking at its front from +Z while a mesh here faces -Z, so the block
 * turns a half circle about Y - which is why every coordinate comes through as {@code 16 - x}. It is the same half
 * turn the item sprite carries, and it lands a block's south face on the mesh's north one, where the picture lives.
 *
 * <p><b>The textures.</b> A snapshot samples one and a block model states up to seven, so this emits one snapshot
 * per texture, the same trick a fleece and a chestplate use.
 */
public final class BlockItems {

    /** How far a model box runs, in the sixteenths a block model and a mesh are both stated in. */
    private static final float BOX = 16;

    /**
     * How much wider each element is drawn than the one before it, in entity pixels.
     *
     * <p>For the models whose elements sit exactly on top of each other. A grass block is a full cube of dirt and
     * grass with a second full cube of green side overlay in the same place, and two surfaces at the same distance
     * are a coin toss - so the later one is grown a hair, which is the order the client draws them in. A thousandth
     * of a block, against textures that are sixteen pixels across.
     */
    private static final float LAYER_LIFT = 0.01f;

    /** One drawn layer: a texture and, for the handful of models that tint, the color to multiply it by. */
    private record Layer(String texture, int tint) {
    }

    private final BlockModels models;
    private final ItemDefinitions definitions;

    public BlockItems(BlockModels models, ItemDefinitions definitions) {
        this.models = models;
        this.definitions = definitions;
    }

    /**
     * The layers this item is drawn as, at the size its model states them, or empty when it is not drawn from a block
     * model at all.
     *
     * <p>Empty is the ordinary answer for most items - an apple is a sprite - and it is also the answer for a block
     * whose model resolved to nothing, so a caller falls back to its own cube rather than drawing a hole.
     *
     * <p>Full size and placed at the origin, because where they go is the caller's: the same layers are hung off a
     * hand and stood on the ground, at two different scales.
     */
    List<EntitySnapshot> layers(String item, TextureAtlas atlas) {
        ItemDefinitions.Definition definition = definitions.of(item);
        if (!definition.model().startsWith("block/")) return List.of();

        List<BakedElement> elements = models.shape(definition.model());
        if (elements.isEmpty()) return List.of();

        List<EntitySnapshot> drawn = new ArrayList<>();
        grouped(elements, definition.tint(), atlas).forEach((layer, parts) ->
                drawn.add(new EntitySnapshot(0, 0, 0, 0, 0, 0, 1f,
                        EntityModel.of(parts, true), layer.texture(), layer.tint())));
        return List.copyOf(drawn);
    }

    /**
     * The elements sorted into one shape per layer, in the order the model states them.
     *
     * <p>An element lands in as many layers as it has textures, carrying only the faces that belong to each - so a log
     * is a cube of bark with no top and a cube of rings with nothing but one, and neither draws the other's sides.
     */
    private static Map<Layer, List<MeshPart>> grouped(List<BakedElement> elements, int tint, TextureAtlas atlas) {
        Map<Layer, List<MeshPart>> layers = new LinkedHashMap<>();

        for (int index = 0; index < elements.size(); index++) {
            BakedElement element = elements.get(index);
            for (Layer layer : layersOf(element, tint, atlas)) {
                layers.computeIfAbsent(layer, key -> new ArrayList<>()).add(shapeOf(element, layer, tint, index));
            }
        }
        return layers;
    }

    /**
     * Which layers one element draws into.
     *
     * <p>Split on the tint as well as the texture, because a stated {@code tintindex} is per face: nothing says a
     * texture is multiplied everywhere it appears, though in practice a vanilla model that tints one face of a
     * texture tints them all.
     */
    private static List<Layer> layersOf(BakedElement element, int tint, TextureAtlas atlas) {
        List<Layer> found = new ArrayList<>(2);

        for (Direction side : Direction.values()) {
            BakedFace face = element.face(blockSide(side));
            if (face == null || !atlas.has(face.texture())) {
                continue;
            }

            Layer layer = layerOf(face, tint);
            if (!found.contains(layer)) {
                found.add(layer);
            }
        }
        return found;
    }

    /** Which layer a face belongs to, in one place so that the sorting and the drawing cannot disagree. */
    private static Layer layerOf(BakedFace face, int tint) {
        return new Layer(face.texture(), face.tint() == Tints.NONE ? 0 : tint);
    }

    /** One element as one part of one layer: the box, its share of the faces, and whatever turn it carries. */
    private static MeshPart shapeOf(BakedElement element, Layer layer, int tint, int index) {
        MeshCube box = boxOf(element, layer, tint, index * LAYER_LIFT);
        ElementRotation turn = element.rotation();
        String name = "element" + index;

        return turn == null ? MeshPart.of(name, List.of(box)) : turned(box, turn, name);
    }

    /**
     * The element's box, in this frame, wearing only the faces of one layer.
     *
     * <p>The face array is filled in after the box is built because the UVs are worked out by asking the box where its
     * own corners are - which is the whole point of doing it that way, since nothing then has to state a convention
     * twice.
     */
    private static MeshCube boxOf(BakedElement element, Layer layer, int tint, float lift) {
        float[][] faces = new float[6][];
        MeshCube box = new MeshCube(
                BOX - element.toX() - lift, element.fromY() - lift, BOX - element.toZ() - lift,
                BOX - element.fromX() + lift, element.toY() + lift, BOX - element.fromZ() + lift,
                faces);

        for (Direction side : Direction.values()) {
            BakedFace face = element.face(blockSide(side));
            if (face != null && layerOf(face, tint).equals(layer)) {
                faces[side.ordinal()] = corners(box, side, face);
            }
        }
        return box;
    }

    /**
     * A turned element as a part that carries the turn, since a cube cannot.
     *
     * <p>22.5 or 45 degrees about a point the author chose, which is what slopes a lectern's top and crosses an
     * azalea's planes. The part sits at that point and the box hangs off it, so both the turn and the widening happen
     * about the place the model says they do.
     */
    private static MeshPart turned(MeshCube box, ElementRotation turn, String name) {
        float originX = BOX - turn.originX();
        float originY = turn.originY();
        float originZ = BOX - turn.originZ();

        // A turn about X or Z runs the other way in this frame and one about Y does not, the half circle that brings a
        // model here being about Y itself.
        float angle = (float) Math.toRadians(turn.angle());
        float[] rotation = new float[3];
        rotation[turn.axis()] = turn.axis() == 1 ? angle : -angle;

        // Widened across the turn, which is how a turned cross still spans its block. A part widens before it turns
        // while the client turns before it widens, and the two agree only because this is the same factor on both axes
        // across the turn - a circle, which comes out the same whenever it is applied.
        float[] scale = {1, 1, 1};
        for (int axis = 0; axis < 3; axis++) {
            if (axis != turn.axis()) {
                scale[axis] = 1 / (float) turn.shrink();
            }
        }

        return new MeshPart(name, false, originX, originY, originZ,
                rotation[0], rotation[1], rotation[2], scale[0], scale[1], scale[2],
                List.of(box.moved(-originX, -originY, -originZ)), List.of());
    }

    /**
     * The four corners of one face, as the normalized UVs a mesh cube states.
     *
     * <p>Each corner is asked where it is, taken back into the block's own coordinates and put through the arithmetic
     * a ray goes through: the block face convention, then the rect the model states for that face, then the face's own
     * texture rotation - which has to be applied here, because a mesh cube carries no rotation to pass on. Nothing
     * about the mapping is assumed, which is why a face the model mirrored or laid on its side needs nothing said
     * about it.
     */
    private static float[] corners(MeshCube box, Direction side, BakedFace face) {
        Direction stated = blockSide(side);
        float[] corners = new float[8];

        for (int across = 0; across < 2; across++) {
            for (int down = 0; down < 2; down++) {
                float[] at = box.pointAt(side, across, down);
                double u = BakedFace.u(stated, BOX - at[0], at[1], BOX - at[2]);
                double v = BakedFace.v(stated, BOX - at[0], at[1], BOX - at[2]);

                float su = (float) (face.u1() + u / BOX * (face.u2() - face.u1()));
                float sv = (float) (face.v1() + v / BOX * (face.v2() - face.v1()));

                int slot = MeshCube.corner(across == 1, down == 1) * 2;
                corners[slot] = Texture.turnedU(su, sv, face.rotation()) / BOX;
                corners[slot + 1] = Texture.turnedV(su, sv, face.rotation()) / BOX;
            }
        }
        return corners;
    }

    /** Which side of the block a side of the mesh came from, the half turn about Y being what separates them. */
    private static Direction blockSide(Direction side) {
        return side == Direction.UP || side == Direction.DOWN ? side : side.opposite();
    }
}
