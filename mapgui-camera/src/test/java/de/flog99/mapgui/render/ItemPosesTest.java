package de.flog99.mapgui.render;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How an item is held, against a real client jar.
 *
 * <p><b>Skipped unless there is a Minecraft installation on this machine</b>, for the same reason
 * {@link MeshExtractorTest} is: the answers live in Mojang's own assets, and nothing Mojang-derived can be committed
 * here to stand in for them.
 *
 * <p>What is asserted is where each item ends up pointing, as a physical direction a mob would recognize - the item's
 * own axes turned into the space this module draws in. Those are the client's answers, worked out from its
 * {@code ItemInHandLayer} chain, and every step of the conversion is wrong in a way that moves at least one of them:
 * drop the half turn between the two texture conventions and everything is upside down, drop the space mirror and the
 * left and right of every item swap, decompose the angles in the wrong order and the bow goes flat.
 */
class ItemPosesTest {

    private static final String VERSION = "26.2";

    /** Our sprite reads its texture rightward against local X, upward with Y, and faces local -Z. */
    private static final float[] TEXTURE_RIGHT = {-1, 0, 0};

    private static final float[] TEXTURE_UP = {0, 1, 0};

    private static final float[] FRONT = {0, 0, -1};

    private static Path clientJar() {
        String appData = System.getenv("APPDATA");
        if (appData == null) return null;

        Path jar = Path.of(appData, ".minecraft", "versions", VERSION, VERSION + ".jar");
        return Files.isReadable(jar) ? jar : null;
    }

    /**
     * The three items whose held pose anybody would notice, and the plain one everything else inherits.
     *
     * <p>A bow is held upright with its face across the body, a sword leans forward with its face across the body the
     * other way up, and a plain item lies flat with its face to the sky. Those are three genuinely different poses out
     * of the assets, which is the point: drawn the same way, the two that are recognizable at a glance are the two
     * that read as wrong.
     */
    @Test
    void anItemIsHeldTheWayTheClientHoldsIt() throws Exception {
        Path jar = clientJar();
        Assumptions.assumeTrue(jar != null, "no Minecraft " + VERSION + " installation to read item models from");

        try (AssetPack pack = AssetPack.open(jar);
             AssetStack stack = AssetStack.of(List.of(), pack, VERSION)) {

            ItemPoses poses = new ItemPoses(stack, new ItemDefinitions(stack, new BiomeColors(stack, new TextureAtlas(stack))));

            // A plain item lies flat in the hand: face up, its top edge pointing where the mob is going.
            assertPointing(poses, "apple", TEXTURE_RIGHT, "right");
            assertPointing(poses, "apple", TEXTURE_UP, "forward");
            assertPointing(poses, "apple", FRONT, "up");

            // A sword stands up across the body. Its face is on the far side of the arm from the camera, which is
            // what makes a sword read as a sword rather than as a plank.
            assertPointing(poses, "iron_sword", FRONT, "left");

            // And a bow is upright: the tips point up and down rather than forward and back. This is the one the
            // arithmetic was wrong for, and the one where it shows.
            assertPointing(poses, "bow", TEXTURE_RIGHT, "up");
            assertPointing(poses, "bow", FRONT, "left");
        }
    }

    /**
     * The left arm, by the client's own rule rather than by mirroring.
     *
     * <p>The rule has a corner in it, and the corner is why this is asserted rather than assumed.
     * {@code ItemTransform#apply} negates the stated Y and Z rotations for the left hand <b>whether or not</b> the
     * model states a left-handed pose - and every vanilla model that states one states it already mirrored. The two
     * cancel: an item keeps the orientation it has in the main hand and only its reach changes sides. Skip the
     * negation and every left-handed item is turned the wrong way round; skip the stated pose and a bow is twenty
     * degrees out.
     */
    @Test
    void theLeftArmFollowsTheClientsRuleRatherThanBeingMirrored() throws Exception {
        Path jar = clientJar();
        Assumptions.assumeTrue(jar != null, "no Minecraft " + VERSION + " installation to read item models from");

        try (AssetPack pack = AssetPack.open(jar);
             AssetStack stack = AssetStack.of(List.of(), pack, VERSION)) {

            ItemPoses poses = new ItemPoses(stack, new ItemDefinitions(stack, new BiomeColors(stack, new TextureAtlas(stack))));

            for (String item : List.of("apple", "iron_sword", "bow")) {
                assertEquals(nearest(turned(poses.of(item, true), FRONT)), nearest(turned(poses.of(item, false), FRONT)),
                        "a " + item + " faces a different way in the left arm");
            }

            // The reach does swap sides, which is the translation rather than the rotation.
            assertTrue(poses.of("iron_sword", true).offset()[0] > 0, "the right arm reaches out to the right");
            assertTrue(poses.of("iron_sword", false).offset()[0] < 0, "and the left arm to the left");

            // A sword's left-handed pose negates back to exactly its right-handed one, so the two hands agree to the
            // last decimal - which is what says the negation is really being applied.
            assertArrayEquals(poses.of("iron_sword", true).rotation(), poses.of("iron_sword", false).rotation(), 1e-6f,
                    "a sword's two hands should come out identically turned");

            // A bow's does not: its own left-handed pose is twenty degrees from the negation of its right-handed one,
            // so ignoring the stated pose would leave these identical instead.
            assertFalse(Arrays.equals(poses.of("bow", true).rotation(), poses.of("bow", false).rotation()),
                    "the bow's own left-handed pose was not read");
        }
    }

    /**
     * An item in a frame faces out of it, which is the whole of what its {@code fixed} transform is for.
     *
     * <p>{@code item/generated} states a half turn about Y there and nothing else, and it is not decoration: an icon
     * is a picture on one side of a one-pixel quad, so without that turn every item in every frame on the server
     * shows you its back.
     *
     * <p>Asserted as a direction rather than as an angle because the pose comes back in the frame a block model
     * arrives in - a half circle about Y from the client's own - and an angle read against the wrong one of those two
     * looks right up until something is drawn.
     */
    @Test
    void anItemInAFrameFacesOutOfIt() throws Exception {
        Path jar = clientJar();
        Assumptions.assumeTrue(jar != null, "no Minecraft " + VERSION + " installation to read item models from");

        try (AssetPack pack = AssetPack.open(jar);
             AssetStack stack = AssetStack.of(List.of(), pack, VERSION)) {

            ItemPoses poses = new ItemPoses(stack, new ItemDefinitions(stack, new BiomeColors(stack, new TextureAtlas(stack))));

            // Out of a frame is +Z here, not -Z: these are placed against a block model, which arrives a half circle
            // about Y from where its json states it, so the way out is the opposite of a mob's face. The model's own
            // half turn about Y is what leaves the picture pointing that way rather than into the wall.
            ItemPoses.Pose framed = poses.stated("apple", ItemPoses.IN_FRAME);
            assertEquals("back", nearest(turned(framed, FRONT)), "an apple in a frame shows its face out of it");
            assertEquals(1f, framed.scale(), 1e-6f, "and at the size the model states, which for an icon is all of it");

            // A block shrinks to half, and that is stated rather than assumed anywhere here.
            assertEquals(0.5f, poses.stated("stone", ItemPoses.IN_FRAME).scale(), 1e-6f,
                    "a block in a frame is half size");
        }
    }

    private static void assertPointing(ItemPoses poses, String item, float[] axis, String expected) {
        String found = nearest(turned(poses.of(item, true), axis));

        assertEquals(expected, found, "a held " + item + " points its "
                + (axis == FRONT ? "face" : axis == TEXTURE_UP ? "top" : "right edge") + " " + found);
    }

    private static float[] turned(ItemPoses.Pose pose, float[] axis) {
        float[] turn = Turns.part(pose.rotation()[0], pose.rotation()[1], pose.rotation()[2]);
        return Turns.apply(turn, axis[0], axis[1], axis[2]);
    }

    /** The nearest named direction in this module's space, where X is the mob's right, Y is up and Z is behind it. */
    private static String nearest(float[] v) {
        String[] names = {"right", "left", "up", "down", "back", "forward"};
        float[][] axes = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

        int best = 0;
        double bestDot = -2;
        for (int i = 0; i < axes.length; i++) {
            double dot = v[0] * axes[i][0] + v[1] * axes[i][1] + v[2] * axes[i][2];
            if (dot > bestDot) {
                bestDot = dot;
                best = i;
            }
        }
        return names[best];
    }
}
