package de.flog99.mapgui.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How an item sits in a hand, out of the item model's own {@code display} block.
 *
 * <p>Read rather than reasoned out, because the answers are all different: a plain item lies flat with its face to
 * the sky, a sword stands up across the body, a bow is upright and turned a further forty degrees. Drawn all the
 * same way, the two items recognizable at a glance are the two that come out wrong.
 *
 * <p>The chain is the client's own, in {@code ItemInHandLayer}: to the arm, a quarter turn back about X, a half turn
 * about Y, then the item's {@code display.thirdperson_righthand}. What comes out is that chain as one pose in this
 * module's space.
 *
 * <p>The two arms are separate answers rather than one mirrored, because vanilla does not mirror: it negates the
 * item's Y and Z rotations and its X translation, which turns the item round without turning it inside out.
 */
public final class ItemPoses {

    /** Where a held pose is stated. There are four of these and these are the two a capture can see. */
    private static final String IN_HAND = "thirdperson_righthand";

    private static final String IN_LEFT_HAND = "thirdperson_lefthand";

    /**
     * The client's own offset from the arm to the item, in blocks, before the item's own translation.
     *
     * <p>Stated in the frame the two turns leave behind, so it does not read as anything recognizable on its own. The
     * X term is the one that changes sign for the left hand.
     *
     * <p>Vanilla has a second, shorter set for a baby, whose arm is shorter and whose hand is higher. Not used here:
     * a baby is drawn at half scale, which brings this to within a pixel of that set on its own.
     */
    private static final float[] TO_HAND = {1 / 16f, 2 / 16f, -10 / 16f};

    /** The model every plain item inherits its pose from, and the fallback for an item with no model to read. */
    private static final String PLAIN_MODEL = "generated";

    /** Entity pixels per block, since a pose is stated in blocks and used in the space a mesh is measured in. */
    private static final float PIXELS = 16;

    /**
     * One item's pose, ready to hang off a hand.
     *
     * @param offset   from the arm's own pivot to the centre of the item, in entity pixels, in the arm's own frame
     * @param rotation {@code xRot, yRot, zRot} in radians, as a {@link MeshPart} states one
     * @param scale    what the client draws the item at, which is well under half a block for most of them
     */
    public record Pose(float[] offset, float[] rotation, float scale) {
    }

    private final AssetStack stack;
    private final ItemDefinitions definitions;

    private final Map<String, Pose> poses = new ConcurrentHashMap<>();

    public ItemPoses(AssetStack stack, ItemDefinitions definitions) {
        this.stack = stack;
        this.definitions = definitions;
    }

    /**
     * How this item sits in one of a mob's arms.
     *
     * <p>Keyed on the arm rather than on the hand, which is the client's own distinction and not a pedantic one: a
     * left-handed skeleton holds its bow in its left arm, and it is the arm that decides which of the two poses the
     * model states applies.
     *
     * <p>Never null. An item with no model to read - a datapack item, a name this version has never had - falls back
     * to the plain pose, which is what the great majority of items use anyway.
     */
    public Pose of(String item, boolean rightArm) {
        return poses.computeIfAbsent(rightArm ? item : item + " (left)", key -> read(item, rightArm));
    }

    /** Where a dropped pose is stated, which is the one other transform a capture can see. */
    private static final String ON_GROUND = "ground";

    /**
     * What the {@code ground} transform shrinks this item to, for one lying on the floor.
     *
     * <p>Read rather than assumed. The two obvious answers - half for an icon, a quarter for a block - are what
     * {@code item/generated} and {@code block/block} state, so they are right for nearly everything by inheritance
     * and wrong for whatever states its own. Heavy core is one: a block that says a half, which drawn at the quarter
     * its parent says lies on the floor at half the size the client draws it.
     *
     * @param fallback for an item whose chain states nothing at all, which no vanilla item does and a pack's own
     *                 model may. The caller knows which shape it is about to draw and so which default is nearer
     */
    public float groundScale(String item, float fallback) {
        float stated = grounds.computeIfAbsent(item, this::readGroundScale);
        return Float.isNaN(stated) ? fallback : stated;
    }

    /** NaN for a model whose chain states no ground transform, which is how {@link #groundScale} knows to fall back. */
    private float readGroundScale(String item) {
        JsonObject display = displayOf(definitions.of(item).model(), 0);
        if (display == null || !display.has(ON_GROUND)) return Float.NaN;

        return numbers(display.getAsJsonObject(ON_GROUND), "scale", Float.NaN)[0];
    }

    private final Map<String, Float> grounds = new ConcurrentHashMap<>();

    private Pose read(String item, boolean rightArm) {
        JsonObject display = displayOf(definitions.of(item).model(), 0);
        if (display == null) {
            display = displayOf(PLAIN_MODEL, 0);
        }
        // No item models at all, which means an asset subset from before they were kept. Held flat and unturned,
        // which is what the plain pose comes to anyway once its own small translation is dropped.
        if (display == null) return pose(new float[3], new float[3], 0.55f, rightArm);

        String key = !rightArm && display.has(IN_LEFT_HAND) ? IN_LEFT_HAND : IN_HAND;
        if (!display.has(key)) return pose(new float[3], new float[3], 0.55f, rightArm);

        JsonObject held = display.getAsJsonObject(key);
        return pose(numbers(held, "rotation", 0), numbers(held, "translation", 0), numbers(held, "scale", 1)[0], rightArm);
    }

    /**
     * The {@code display} block of an item model, or of the nearest parent that has one.
     *
     * <p>Followed rather than assumed, because the parents are what carry these: {@code item/generated} states the
     * flat pose that hundreds of items inherit and {@code item/handheld} the upright one, and only the awkward few -
     * a bow, a crossbow, a trident, a shield - state their own.
     */
    private JsonObject displayOf(String model, int depth) {
        // Deeper than any real chain, and a stop in case a pack points two models at each other.
        if (depth > 8) return null;

        JsonObject json = json(model);
        if (json == null) return null;
        if (json.has("display")) return json.getAsJsonObject("display");
        if (!json.has("parent")) return null;

        String parent = json.get("parent").getAsString();
        int colon = parent.indexOf(':');
        String path = colon < 0 ? parent : parent.substring(colon + 1);

        // Into the block models as well as the item ones, because a block item's chain leaves for them immediately:
        // {@code item/oak_planks} inherits from {@code block/oak_planks}, and the held pose is stated up at
        // {@code block/block} - a quarter turn, a half turn and three eighths scale. Stopping at the item models meant
        // every block in a hand fell back to the flat-item pose, which is half again too big and not turned at all.
        return displayOf(path, depth + 1);
    }

    /**
     * One model by its path, which says which of the two directories it is in.
     *
     * <p>A bare name means an item, since that is what a held thing starts as. Anything a chain reaches out to states
     * its own {@code item/} or {@code block/} - and the builtin parent a chain ends at is code rather than a file, so it
     * simply is not found, which is the answer.
     */
    private JsonObject json(String model) {
        String path = AssetStack.pathOf(model);
        if (!path.startsWith("block/") && !path.startsWith("item/")) {
            path = "item/" + path;
        }

        return read(AssetStack.asset(AssetStack.beside(model, path), "models", ".json"));
    }

    /** One json by its whole path, or null for anything missing or malformed - which costs that item its pose only. */
    private JsonObject read(String path) {
        try {
            byte[] raw = stack.read(path);
            if (raw == null) return null;

            return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static float[] numbers(JsonObject held, String key, float fallback) {
        float[] out = {fallback, fallback, fallback};
        if (!held.has(key)) return out;

        var array = held.getAsJsonArray(key);
        for (int i = 0; i < 3 && i < array.size(); i++) {
            out[i] = array.get(i).getAsFloat();
        }
        return out;
    }

    /**
     * The whole chain as one pose.
     *
     * <p>The left hand negates the stated Y and Z rotations and the stated X translation, and it does so whether or
     * not the model states a left-handed pose of its own - {@code ItemTransform#apply} takes the flag and not the
     * absence of an entry. Which is not a detail to guess at: a bow's own left-handed entry is twenty degrees from
     * the negation of its right-handed one, so the two rules disagree by exactly that much.
     */
    private static Pose pose(float[] rotation, float[] translation, float scale, boolean rightArm) {
        float yaw = rightArm ? rotation[1] : -rotation[1];
        float roll = rightArm ? rotation[2] : -rotation[2];
        float across = rightArm ? translation[0] : -translation[0];

        float[] hand = Turns.times(Turns.x(Math.toRadians(-90)), Turns.y(Math.toRadians(180)));
        float[] turn = Turns.times(hand, Turns.display(rotation[0], yaw, roll));

        // A half turn about X, because an item model and the quad this module draws it on do not agree on which way
        // the picture faces: vanilla's item is read from +Z with its texture running +X and +Y, while the sprite here
        // is read from -Z. Measured against the client rather than assumed - without it every item comes out with its
        // face and its top the right way round for a mirror.
        turn = Turns.times(turn, Turns.x(Math.PI));

        // Both translations are stated in the hand's frame and add there, before the item's own rotation.
        float[] reach = {
                (rightArm ? TO_HAND[0] : -TO_HAND[0]) + across / PIXELS,
                TO_HAND[1] + translation[1] / PIXELS,
                TO_HAND[2] + translation[2] / PIXELS
        };
        float[] offset = Turns.apply(hand, reach[0], reach[1], reach[2]);

        // Into this module's space, where X and Y run the other way round and a coordinate is a pixel not a block.
        return new Pose(
                new float[]{-offset[0] * PIXELS, -offset[1] * PIXELS, offset[2] * PIXELS},
                Turns.angles(Turns.mirrored(turn)),
                Math.max(0.01f, scale)
        );
    }
}
