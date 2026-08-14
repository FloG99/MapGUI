package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static de.flog99.mapgui.render.Patches.assertNotPatch;
import static de.flog99.mapgui.render.Patches.assertPatch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A skin whose patches are all different colors, so which face of which box was hit is readable from the pixel.
 * Body yaw decides which way a model's front points, and getting it backwards turns everybody round.
 */
class EntityTracerTest {

    /** The head patch of a 64x64 skin: front at 8,8, back at 24,8, right at 0,8, left at 16,8, top at 8,0. */
    private static Texture skin() {
        return Texture.opaqueOf(64, 64, skinPixels());
    }

    private static int[] skinPixels() {
        int[] argb = new int[64 * 64];
        java.util.Arrays.fill(argb, 0xFF101010);
        // A real skin's second layer is transparent wherever it is unused, and so is this one - an overlay that
        // was opaque everywhere would hide the base layer from every test below.
        paint(argb, 32, 0, 32, 16, 0);
        paint(argb, 0, 32, 64, 16, 0);
        paint(argb, 0, 48, 16, 16, 0);
        paint(argb, 48, 48, 16, 16, 0);

        paint(argb, 8, 8, 8, 8, 0xFFFF0000);
        paint(argb, 24, 8, 8, 8, 0xFF00FF00);
        paint(argb, 0, 8, 8, 8, 0xFF0000FF);
        paint(argb, 16, 8, 8, 8, 0xFFFFFF00);
        paint(argb, 8, 0, 8, 8, 0xFF00FFFF);
        return argb;
    }

    private static void paint(int[] argb, int u, int v, int width, int height, int color) {
        for (int y = v; y < v + height; y++) {
            for (int x = u; x < u + width; x++) {
                argb[y * 64 + x] = color;
            }
        }
    }

    private TestWorld world() {
        return new TestWorld().texture("skin", skin());
    }

    /**
     * Pitch counts downward in Minecraft, and the sign of the head rotation is the one term here that does not
     * follow the yaw's lead. Getting it backwards agrees at pitch 0, so it survives every test that does not tilt
     * a head, and draws a mob staring at the sky while it looks at its feet.
     */
    @Test
    void aHeadTiltedDownShowsItsCrownAndNotItsChin() {
        TestWorld world = world();

        // Looking well down, seen from the front at its own height: the top of the head has swung toward the camera.
        EntitySnapshot down = EntitySnapshot.player(0.5, 0, 0.5, 0, 0, 60, false, SkinLayers.ALL, "skin");
        assertPatch(0xFF00FFFF, pixel(world, down, from(0.5, 4, 180)), "tilted down, the crown faces the camera");

        // And looking up, the underside does instead - which is the patch the old sign showed for looking down.
        EntitySnapshot up = EntitySnapshot.player(0.5, 0, 0.5, 0, 0, -60, false, SkinLayers.ALL, "skin");
        assertNotPatch(0xFF00FFFF, pixel(world, up, from(0.5, 4, 180)), "tilted up, the crown is turned away");
    }

    /** Head height on a standing player, so a level ray hits the head rather than the body. */
    private static final double EYE = 1.7;

    private int pixel(TestWorld world, EntitySnapshot entity, CameraView view) {
        int[] out = new int[1];
        new RayCaster(world).render(world, view, List.of(entity), 1, 1, out);
        return out[0];
    }

    private CameraView from(double x, double z, float yaw) {
        return new CameraView(x, EYE, z, yaw, 0, 70, 64);
    }

    private EntitySnapshot standing(float bodyYaw) {
        return EntitySnapshot.player(0.5, 0, 0.5, bodyYaw, bodyYaw, 0, false, SkinLayers.ALL, "skin");
    }

    /** Facing south at yaw 0, so somebody standing to the south sees the front of the head. */
    @Test
    void aPlayerFacesTheWayTheirBodyYawSays() {
        TestWorld world = world();

        assertPatch(0xFFFF0000, pixel(world, standing(0), from(0.5, 4, 180)), "seen from the south, front");
        assertPatch(0xFF00FF00, pixel(world, standing(0), from(0.5, -3, 0)), "seen from the north, back");
    }

    @Test
    void turningTheBodyTurnsTheModel() {
        TestWorld world = world();

        // Turned to face west, so the viewer to the south now sees a side rather than the face.
        assertNotPatch(0xFFFF0000, pixel(world, standing(90), from(0.5, 4, 180)), "no longer facing south");
        assertPatch(0xFFFF0000, pixel(world, standing(90), from(-3, 0.5, 270)), "and the face is now to the west");
    }

    /** The head turns within the body, so a mob can look at you without swivelling its whole torso. */
    @Test
    void theHeadTurnsIndependentlyOfTheBody() {
        TestWorld world = world();
        EntitySnapshot lookingWest = new EntitySnapshot(0.5, 0, 0.5, 0, 90, 0, 1f, EntityModel.player(false, SkinLayers.ALL, false), "skin");

        assertPatch(0xFFFF0000, pixel(world, lookingWest, from(-3, 0.5, 270)), "the face has turned west with the head");
        assertNotPatch(0xFFFF0000, pixel(world, lookingWest, from(0.5, 4, 180)), "and no longer looks south");
    }

    /** Both angles nonzero, since a sign error in either is invisible while the other is 0 or 180. */
    @Test
    void bodyAndHeadTurnTogetherAtAwkwardAngles() {
        TestWorld world = world();

        // Body east, head turned back to face south.
        EntitySnapshot twisted = new EntitySnapshot(0.5, 0, 0.5, 270, 0, 0, 1f, EntityModel.player(false, SkinLayers.ALL, false), "skin");
        assertPatch(0xFFFF0000, pixel(world, twisted, from(0.5, 4, 180)), "the head faces south, so the face is");

        // Body north, head turned to the east.
        EntitySnapshot looking = new EntitySnapshot(0.5, 0, 0.5, 180, 270, 0, 1f, EntityModel.player(false, SkinLayers.ALL, false), "skin");
        assertPatch(0xFFFF0000, pixel(world, looking, from(4, 0.5, 90)), "and here it faces east");
    }

    @Test
    void lookingDownFromAboveHitsTheTopOfTheHead() {
        TestWorld world = world();

        assertPatch(0xFF00FFFF, pixel(world, standing(0), new CameraView(0.5, 6, 0.5, 0, 90, 70, 64)), "straight down onto the top of the head");
    }

    /** Blocks stop the ray, so an entity behind a wall must not show through it. */
    @Test
    void anEntityBehindAWallIsHidden() {
        TestWorld world = world().texture("stone", TestWorld.solid(0xFF888888));
        for (int x = -2; x <= 3; x++) {
            for (int y = 0; y <= 3; y++) {
                world.cube(x, y, 2, "stone", BakedState.Alpha.OPAQUE);
            }
        }

        // The player is at z 0.5, the wall at z 2, the camera to the south of both.
        int argb = pixel(world, standing(0), from(0.5, 5, 180));

        assertNotEquals(0xFFFF0000, argb, "the wall is in the way");
    }

    /** In front of the wall it does show, which is the other half of the same check. */
    @Test
    void anEntityInFrontOfAWallIsDrawn() {
        TestWorld world = world().texture("stone", TestWorld.solid(0xFF888888));
        for (int x = -2; x <= 3; x++) {
            for (int y = 0; y <= 3; y++) {
                world.cube(x, y, -3, "stone", BakedState.Alpha.OPAQUE);
            }
        }

        assertPatch(0xFFFF0000, pixel(world, standing(0), from(0.5, 4, 180)), "the face");
    }

    @Test
    void aMissReturnsTheSky() {
        TestWorld world = world();

        // Aimed well to the side of them.
        assertEquals(TestWorld.SKY, pixel(world, standing(0), from(30, 4, 180)));
    }

    /** A mob with no authored model is its own bounding box, correctly sized. */
    @Test
    void aPlainBoxEntityIsHitWithinItsBoundsAndMissedOutside() {
        TestWorld world = new TestWorld().texture("mob", TestWorld.solid(0xFFAA5500));
        EntitySnapshot cow = EntitySnapshot.box(0.5, 0, 0.5, 0, 0, 0.9, 1.4, "mob");

        assertPatch(0xFFAA5500, pixel(world, cow, new CameraView(0.5, 0.7, 5, 180, 0, 70, 64)), "the body patch");
        assertEquals(TestWorld.SKY, pixel(world, cow, new CameraView(0.5, 3.0, 5, 180, 0, 70, 64)), "above it");
    }

    /** A skin with a colour of its own on the chest, on the hat over the face, and on the jacket over the chest. */
    private static Texture wearing(int hat, int jacket) {
        int[] argb = skinPixels();
        paint(argb, 20, 20, 8, 12, 0xFF808080);
        paint(argb, 40, 8, 8, 8, hat);
        paint(argb, 20, 36, 8, 12, jacket);
        return Texture.opaqueOf(64, 64, argb);
    }

    private EntitySnapshot dressed(SkinLayers layers) {
        return EntitySnapshot.player(0.5, 0, 0.5, 0, 0, 0, false, layers, "skin");
    }

    /** Chest height rather than head height, for the parts that are not the hat. */
    private CameraView atChest() {
        return new CameraView(0.5, 1.1, 4, 180, 0, 70, 64);
    }

    /**
     * The overlay layers used to be coincident with the base ones, which meant the ray reached the base first and
     * the second layer never drew at all. They sit just outside it now.
     */
    @Test
    void theSecondSkinLayerIsDrawnOverTheFirst() {
        TestWorld world = new TestWorld().texture("skin", wearing(0xFF00FF00, 0xFF0000FF));

        assertPatch(0xFF00FF00, pixel(world, dressed(SkinLayers.ALL), from(0.5, 4, 180)), "the hat, not the face");
        assertPatch(0xFF0000FF, pixel(world, dressed(SkinLayers.ALL), atChest()), "the jacket, not the chest");
    }

    /** Where the overlay texel is transparent the base layer is what should show, which is most of a real skin. */
    @Test
    void aTransparentOverlayTexelLetsTheLayerUnderItThrough() {
        TestWorld world = new TestWorld().texture("skin", wearing(0, 0));

        assertPatch(0xFFFF0000, pixel(world, dressed(SkinLayers.ALL), from(0.5, 4, 180)), "the face");
        assertPatch(0xFF808080, pixel(world, dressed(SkinLayers.ALL), atChest()), "the chest");
    }

    /**
     * A texel that is neither solid nor clear has to be blended with what is behind it in the same mesh, which
     * means walking on rather than stopping at the nearest drawn one. A slime is the case that needs it - one mesh
     * holding a translucent outer shell around an opaque inner one - and an overlay skin layer is the same shape of
     * problem: stopping at the shell drew it over an inner model that was never looked for.
     */
    @Test
    void aTranslucentOverlayTexelBlendsWithTheLayerUnderIt() {
        TestWorld world = new TestWorld().texture("skin", wearing(0xB400FF00, 0));

        // Green at 180 of 255 over the red face, so a little under a third of the red is left.
        assertPatch(0xFF4BB400, pixel(world, dressed(SkinLayers.ALL), from(0.5, 4, 180)), "hat blended onto the face");
    }

    /** A layer the client has switched off is not drawn, however opaque the skin is where it would be. */
    @Test
    void aSkinPartTheClientHasTurnedOffIsNotDrawn() {
        TestWorld world = new TestWorld().texture("skin", wearing(0xFF00FF00, 0xFF0000FF));
        SkinLayers bareHeaded = new SkinLayers(false, true, true, true, true, true);
        SkinLayers jacketOff = new SkinLayers(true, false, true, true, true, true);

        assertPatch(0xFFFF0000, pixel(world, dressed(bareHeaded), from(0.5, 4, 180)), "hat off, so the face");
        assertPatch(0xFF0000FF, pixel(world, dressed(bareHeaded), atChest()), "and the jacket is still on");

        assertPatch(0xFF808080, pixel(world, dressed(jacketOff), atChest()), "jacket off, so the chest");
        assertPatch(0xFF00FF00, pixel(world, dressed(jacketOff), from(0.5, 4, 180)), "and the hat is still on");
    }

    /**
     * Sneaking is a pose and not a lower position: the torso tips over its own neck so the hips go back, the head
     * drops under it, and the legs slide back to stay beneath. Drawing a sneaking player upright is the thing that
     * gives away that a picture is not the game.
     */
    @Test
    void aSneakingPlayerTipsForwardRatherThanStandingLower() {
        EntityModel standing = EntityModel.player(false, SkinLayers.ALL, false);
        EntityModel sneaking = EntityModel.player(false, SkinLayers.ALL, true);

        assertTrue(sneaking.height() < standing.height(), "a sneaking player does not stand as tall");
        assertEquals(0f, part(standing, "body").xRot(), "an upright torso is not turned at all");

        // Negative is forward here. The client leans it by a positive half radian in a frame whose y runs the other
        // way, and taking its number across unchanged would sit the player back on their heels.
        assertTrue(part(sneaking, "body").xRot() < 0, "and a sneaking one is tipped forward over its neck");
        assertTrue(part(sneaking, "right_leg").z() > part(standing, "right_leg").z(), "with the legs back under it");
    }

    /**
     * The crouch shifts a part rather than placing it, which is what lets the one method pose both a player and the
     * armor worn over him: the player's parts are authored flat and armor's hang under a root, so a height means
     * different places in the two while a shift means the same. Placing them put a player's leg armor on his head.
     */
    @Test
    void crouchingShiftsAPartRatherThanPlacingIt() {
        MeshPart leg = MeshPart.at("right_leg", 2, 12, 0, List.of(), List.of());
        MeshPart root = new MeshPart("root", false, 0, 24, 0, 0, 0, 0, 1, 1, 1, List.of(), List.of(leg));

        MeshPart posed = EntityModel.of(List.of(root)).crouched().parts().getFirst().children().getFirst();

        assertEquals(12f, posed.y(), "a part under a root keeps the height its own parent measures it from");
        assertTrue(posed.z() > 0, "and is still moved back, which is what the crouch does to a leg");
    }

    private static MeshPart part(EntityModel model, String name) {
        return model.parts().stream().filter(part -> part.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no part called " + name));
    }

    @Test
    void everyPixelOfAFrameWithEntitiesIsWritten() {
        TestWorld world = world();
        List<EntitySnapshot> crowd = List.of(standing(0), EntitySnapshot.player(2.5, 0, 1.5, 45, 45, 0, true, SkinLayers.ALL, "skin"));

        int[] out = new int[32 * 32];
        new RayCaster(world).render(world, from(0.5, 6, 180), crowd, 32, 32, out);

        for (int i = 0; i < out.length; i++) {
            assertEquals(0xFF, out[i] >>> 24, "pixel " + i);
        }
        assertTrue(java.util.Arrays.stream(out).anyMatch(argb -> (argb >> 16 & 0xFF) > 0x80 && (argb & 0xFFFF) == 0),
                "somebody's face should be in frame");
    }
}
