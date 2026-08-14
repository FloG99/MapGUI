package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static de.flog99.mapgui.render.Patches.assertNotPatch;
import static de.flog99.mapgui.render.Patches.assertPatch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tracer against a mesh built by hand, so the part tree is exercised without a client jar.
 *
 * <p>What the extracted models bring that the authored ones did not is the tree itself: a part with a pose, a part
 * turned inside its parent, a part scaled inside its parent, and per-corner texture coordinates instead of a patch
 * offset with flip flags. Each of those is a way to draw a mob inside out, and none of them is visible in the code -
 * so each gets a texture whose patches are different colors and a ray aimed at one of them.
 */
class EntityMeshTest {

    private static final int HIDE = 0xFF202020;
    private static final int FACE = 0xFFFF0000;
    private static final int CHEST = 0xFF00FF00;
    private static final int CROWN = 0xFF0000FF;

    /** The head patch at 0,0 and the body patch at 16,16, laid out the way a skin is. */
    private static Texture skin() {
        int[] argb = new int[64 * 64];
        Arrays.fill(argb, HIDE);
        paint(argb, 8, 8, 8, 8, FACE);
        paint(argb, 8, 0, 8, 8, CROWN);
        paint(argb, 20, 20, 8, 12, CHEST);
        return Texture.opaqueOf(64, 64, argb);
    }

    private static void paint(int[] argb, int u, int v, int width, int height, int color) {
        for (int y = v; y < v + height; y++) {
            for (int x = u; x < u + width; x++) {
                argb[y * 64 + x] = color;
            }
        }
    }

    private static final MeshCube HEAD = MeshCube.box(-4, 0, -4, 8, 8, 8, 0, 0, 64, 64, 0);
    private static final MeshCube BODY = MeshCube.box(-4, 12, -2, 8, 12, 4, 16, 16, 64, 64, 0);

    /** A head on a body, the head hung off a part of its own at the neck - which is what makes it turn. */
    private static EntityModel headOnBody() {
        return EntityModel.of(List.of(
                MeshPart.of("body", List.of(BODY)),
                MeshPart.at("head", 0, 24, 0, List.of(HEAD), List.of())
        ));
    }

    private static EntitySnapshot standing(EntityModel model, float bodyYaw, float headYaw) {
        return new EntitySnapshot(0.5, 0, 0.5, bodyYaw, headYaw, 0, 1f, model, "hide");
    }

    private int pixel(EntityModel model, float bodyYaw, float headYaw, CameraView view) {
        TestWorld world = new TestWorld().texture("hide", skin());
        int[] out = new int[1];
        new RayCaster(world).render(world, view, List.of(standing(model, bodyYaw, headYaw)), 1, 1, out);
        return out[0];
    }

    /** Head height, chest height, and straight down from above, all from the south looking north. */
    private static CameraView atHead() {
        return new CameraView(0.5, 1.75, 4, 180, 0, 70, 64);
    }

    private static CameraView atChest() {
        return new CameraView(0.5, 1.1, 4, 180, 0, 70, 64);
    }

    private static CameraView fromAbove() {
        return new CameraView(0.5, 6, 0.5, 0, 90, 70, 64);
    }

    @Test
    void aPartsPoseMovesEverythingUnderIt() {
        assertPatch(FACE, pixel(headOnBody(), 0, 0, atHead()), "the head patch, up at the neck");
        assertPatch(CHEST, pixel(headOnBody(), 0, 0, atChest()), "the body patch, below it");
    }

    /**
     * The head turns inside the body and about its own pose, not about the model's origin. Pivoting it anywhere
     * else swings it round like a hammer, which is invisible at yaw 0 and absurd at anything else.
     */
    @Test
    void aHeadTurnsAboutItsOwnPartAndTheBodyDoesNot() {
        assertPatch(CROWN, pixel(headOnBody(), 0, 0, fromAbove()), "from above, the crown");
        assertPatch(FACE, pixel(headOnBody(), 0, 90, new CameraView(-3, 1.75, 0.5, 270, 0, 70, 64)), "the face has turned west with the head");
        assertNotPatch(FACE, pixel(headOnBody(), 0, 90, atHead()), "and no longer looks south");
        assertPatch(CHEST, pixel(headOnBody(), 0, 90, atChest()), "while the body has not moved");
    }

    /**
     * A quadruped's barrel of a body: a tall box authored standing up and laid on its side by a quarter turn about
     * its own X axis. What ends up facing forward is the patch that unwrapped the box's top, so getting the turn
     * wrong reads a flank onto the chest - which looks plausible on a cow and is wrong on every one of them.
     */
    @Test
    void aTurnedPartReadsThePatchTheTurnBringsToTheFront() {
        // Twelve pixels tall before the turn, so ten deep after it, with its top patch at 16,16.
        MeshCube barrel = MeshCube.box(-4, 0, -2, 8, 12, 4, 16, 16, 64, 64, 0);
        EntityModel laid = EntityModel.of(List.of(new MeshPart("body", false, 0, 12, 0,
                (float) -Math.PI / 2, 0, 0, 1, 1, 1, List.of(barrel), List.of())));

        // The box's own UP patch is at 20,16 by the skin unwrap, which is inside the crown-colored block.
        int[] argb = new int[64 * 64];
        Arrays.fill(argb, HIDE);
        paint(argb, 20, 16, 8, 4, CROWN);
        TestWorld world = new TestWorld().texture("hide", Texture.opaqueOf(64, 64, argb));

        int[] out = new int[1];
        // The turn puts the box between 10 and 14 pixels up, so the ray goes through the middle of that.
        new RayCaster(world).render(world, new CameraView(0.5, 0.75, 4, 180, 0, 70, 64),
                List.of(new EntitySnapshot(0.5, 0, 0.5, 0, 0, 0, 1f, laid, "hide")), 1, 1, out);

        assertPatch(CROWN, out[0], "the turn brings the box's top round to the front");
    }

    /** A part scaled inside its parent, which is how vanilla registers a husk, a cave spider and a giant. */
    @Test
    void aPartScaleShrinksWhatIsUnderIt() {
        EntityModel full = EntityModel.of(List.of(MeshPart.of("body", List.of(BODY))));
        EntityModel half = EntityModel.of(List.of(new MeshPart("body", false, 0, 0, 0, 0, 0, 0,
                0.5f, 0.5f, 0.5f, List.of(BODY), List.of())));

        assertEquals(24, full.height(), 1e-4);
        assertEquals(12, half.height(), 1e-4, "the bounds follow the scale, or the search stops looking too soon");

        // Chest height on the full-size body is above the top of the halved one.
        assertPatch(CHEST, pixel(full, 0, 0, atChest()), "the body");
        assertEquals(TestWorld.SKY, pixel(half, 0, 0, atChest()), "half as tall, so nothing there");
    }

    /**
     * The four corner coordinates are read as the corners they are, not as an offset plus a width.
     *
     * <p>Which is the whole reason for storing them: vanilla mirrors and rotates faces freely, and a face whose
     * corners run right to left has to be sampled right to left. Swapping the two coordinate pairs across the face
     * is the same thing a mirrored limb does, and a tracer that ignored them would read this identically to the
     * unswapped one.
     */
    @Test
    void aFaceWhoseCornersAreSwappedIsReadSwapped() {
        int[] argb = new int[64 * 64];
        Arrays.fill(argb, HIDE);
        paint(argb, 8, 8, 4, 8, FACE);
        paint(argb, 12, 8, 4, 8, CHEST);
        TestWorld world = new TestWorld().texture("hide", Texture.opaqueOf(64, 64, argb));

        // A ray a little to the entity's own +X side of centre, so it lands on one half of the north face rather
        // than the middle of it.
        CameraView offCentre = new CameraView(0.65, 1.75, 4, 180, 0, 70, 64);

        int straight = trace(world, headOnBody(), offCentre);
        int mirrored = trace(world, mirroredHead(), offCentre);

        // Which half is which is pinned by MeshExtractorTest against a real vanilla cube, which is the only thing
        // entitled to say. All that matters here is that the corners are read at all: a tracer taking a face's patch
        // as an offset and a width would read these two identically, and that is the mistake being guarded against.
        assertNotEquals(straight, mirrored, "a face whose corners are swapped has to be sampled swapped");
        assertPatch(CHEST, straight, "straight, one half of the patch");
        assertPatch(FACE, mirrored, "mirrored, the other half");
    }

    /** The head cube with its north face's corner coordinates swapped left for right. */
    private static EntityModel mirroredHead() {
        float[][] faces = new float[6][];
        for (Direction side : Direction.values()) {
            float[] corners = HEAD.face(side);
            faces[side.ordinal()] = corners == null ? null : corners.clone();
        }

        float[] north = faces[Direction.NORTH.ordinal()];
        swap(north, MeshCube.corner(false, false), MeshCube.corner(true, false));
        swap(north, MeshCube.corner(false, true), MeshCube.corner(true, true));

        MeshCube mirrored = new MeshCube(HEAD.minX(), HEAD.minY(), HEAD.minZ(), HEAD.maxX(), HEAD.maxY(), HEAD.maxZ(), faces);
        return EntityModel.of(List.of(MeshPart.at("head", 0, 24, 0, List.of(mirrored), List.of())));
    }

    private static void swap(float[] corners, int left, int right) {
        for (int i = 0; i < 2; i++) {
            float held = corners[left * 2 + i];
            corners[left * 2 + i] = corners[right * 2 + i];
            corners[right * 2 + i] = held;
        }
    }

    private int trace(TestWorld world, EntityModel model, CameraView view) {
        int[] out = new int[1];
        new RayCaster(world).render(world, view, List.of(standing(model, 0, 0)), 1, 1, out);
        return out[0];
    }

    /**
     * A 4x8x4 cube whose patch is transparent everywhere but the side named, laid out the way a skin unwraps a box.
     *
     * <p>Which is a chicken's leg in miniature: vanilla textures that box on its back face and its underside and
     * leaves the other four clear, so the leg is drawn by texels that face away from anybody standing in front of
     * the bird.
     */
    private static TestWorld oneSidedBox(Direction drawn, int color) {
        int[] argb = new int[64 * 64];
        int[] patch = switch (drawn) {
            case WEST -> new int[]{0, 4, 4, 8};
            case NORTH -> new int[]{4, 4, 4, 8};
            case EAST -> new int[]{8, 4, 4, 8};
            case SOUTH -> new int[]{12, 4, 4, 8};
            case UP -> new int[]{4, 0, 4, 4};
            case DOWN -> new int[]{8, 0, 4, 4};
        };
        paint(argb, patch[0], patch[1], patch[2], patch[3], color);
        return new TestWorld().texture("hide", Texture.opaqueOf(64, 64, argb));
    }

    /** That box at chest height, its own front toward a camera to the south. */
    private static EntityModel oneSidedModel(boolean culled) {
        return EntityModel.of(List.of(MeshPart.of("body", List.of(MeshCube.box(-2, 12, -2, 4, 8, 4, 0, 0, 64, 64, 0)))), culled);
    }

    private static CameraView atBox() {
        return new CameraView(0.5, 1.0, 4, 180, 0, 70, 64);
    }

    /**
     * The far side of a cube draws where the near side is a clear texel.
     *
     * <p>Vanilla draws mob models with culling off, so both sides of every quad are visible, and a chicken's leg
     * depends on it entirely: its front, its two flanks and its top are clear, and what makes the leg is one column
     * on the back face. Sampling only the side the ray enters through drew nothing at all from the front, which is
     * the reported bug - chickens with no legs.
     */
    @Test
    void theFarSideOfACubeDrawsThroughAClearNearSide() {
        assertPatch(CHEST, trace(oneSidedBox(Direction.SOUTH, CHEST), oneSidedModel(false), atBox()),
                "the back face, seen through the clear front one");
    }

    /** And the near side still wins when it draws, or every mob would be lit from the inside out. */
    @Test
    void theNearSideWinsWhenBothSidesDraw() {
        int[] argb = new int[64 * 64];
        paint(argb, 4, 4, 4, 8, FACE);
        paint(argb, 12, 4, 4, 8, CHEST);
        TestWorld world = new TestWorld().texture("hide", Texture.opaqueOf(64, 64, argb));

        assertPatch(FACE, trace(world, oneSidedModel(false), atBox()), "the front face, not the back one");
    }

    /**
     * A model whose vanilla render type culls draws only the side facing the ray.
     *
     * <p>Which is the other half of the rule rather than a special case: item entities are drawn with a culling
     * render type, and their sprite is a flat quad carrying the same picture mirrored on its back - so letting the
     * back through the front would draw every dropped item twice, once the wrong way round.
     */
    @Test
    void aCulledModelDrawsOnlyTheSideFacingTheRay() {
        assertEquals(TestWorld.SKY, trace(oneSidedBox(Direction.SOUTH, CHEST), oneSidedModel(true), atBox()),
                "the back face is culled away and the clear front lets the sky through");
    }

    /** A side the model does not draw is not hit, so a ray through it finds whatever is behind instead. */
    @Test
    void anUndrawnSideIsNotHit() {
        MeshCube open = new MeshCube(-4, 0, -4, 4, 8, 4, new float[6][]);
        EntityModel nothing = EntityModel.of(List.of(MeshPart.of("body", List.of(open))));

        TestWorld world = new TestWorld().texture("hide", skin());
        int[] out = new int[1];
        new RayCaster(world).render(world, new CameraView(0.5, 0.25, 4, 180, 0, 70, 64),
                List.of(standing(nothing, 0, 0)), 1, 1, out);

        assertEquals(TestWorld.SKY, out[0], "a cube with no faces draws nothing");
    }

    /** Whatever a mesh is made of, the frame has to come out complete - no unwritten pixels. */
    @Test
    void everyPixelOfAFrameWithAMeshIsWritten() {
        TestWorld world = new TestWorld().texture("hide", skin());
        int[] out = new int[16 * 16];
        new RayCaster(world).render(world, atHead(), List.of(standing(headOnBody(), 30, 60)), 16, 16, out);

        for (int i = 0; i < out.length; i++) {
            assertEquals(0xFF, out[i] >>> 24, "pixel " + i);
        }
        assertTrue(Arrays.stream(out).anyMatch(argb -> argb != TestWorld.SKY), "and the mesh should be in it");
    }
}
