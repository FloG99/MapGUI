package de.flog99.mapgui.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static de.flog99.mapgui.render.Patches.assertPatch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A sheep's fleece, which is the one thing a capture draws whose color is not in any texture.
 *
 * <p>There is exactly one wool texture in the assets and it is white. Vanilla colors it per animal, so a fleece
 * drawn from the texture alone comes out white on every sheep in the world - which is what was reported.
 */
class SheepWoolTest {

    /** Static, because the entry point a capture calls is - so a test that installs anything has to put it back. */
    @AfterEach
    void uninstall() {
        EntityMeshes.install(Map.of());
    }

    /**
     * The client's own table, not the dye's own color.
     *
     * <p>A dye carries three colors in vanilla - a map color, a firework color and a texture color - and the sheep
     * table takes the third and multiplies it by three quarters, flooring each channel. White is the exception: it
     * is a flat {@code E6E6E6} rather than a darkened {@code F9FFFE}, so a white sheep is very slightly grey and not
     * a hair off-white. Reading {@code DyeColor} straight gets every one of these wrong.
     */
    @Test
    void theWoolColorsAreTheClientsAndNotTheDyesOwn() {
        assertEquals(0xFFE6E6E6, Tints.wool("white"), "white is special-cased rather than scaled");
        assertEquals(0xFF84221C, Tints.wool("red"), "0xB02E26 at three quarters");
        assertEquals(0xFF2B86A3, Tints.wool("light_blue"), "and the two-word names resolve too");
        assertEquals(0xFF151518, Tints.wool("black"));
        assertEquals(0xFF757571, Tints.wool("light_gray"));

        assertEquals(0, Tints.wool("chartreuse"), "and a word that is not a dye is no tint at all");
        for (String dye : List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")) {
            assertEquals(0xFF000000, Tints.wool(dye) & 0xFF000000, dye + " should be a full-alpha color");
        }
    }

    /**
     * A white fleece over a hide of a color no wool is, so what arrives at the pixel is the tint and nothing else -
     * and a fleece that failed to be left off a shorn sheep cannot be mistaken for the hide.
     */
    private static TestWorld world() {
        int[] hide = new int[64 * 64];
        Arrays.fill(hide, 0xFF20FF20);
        int[] wool = new int[64 * 64];
        Arrays.fill(wool, 0xFFFFFFFF);

        return new TestWorld()
                .texture("entity/sheep/sheep", Texture.opaqueOf(64, 64, hide))
                .texture("entity/sheep/sheep_wool", Texture.opaqueOf(64, 64, wool));
    }

    private static void installSheep() {
        EntityMeshes.install(Map.of(
                "animal.sheep.SheepModel", List.of(MeshPart.of("body", List.of(MeshCube.plain(-4, 0, -8, 8, 18, 16)))),
                "animal.sheep.SheepFurModel#createFurLayer", List.of(MeshPart.of("body", List.of(MeshCube.plain(-5, 0, -9, 10, 20, 18))))
        ));
    }

    /** Chest height on the sheep, from the south, so the ray meets the fleece before the body under it. */
    private static CameraView atFleece() {
        return new CameraView(0.5, 0.6, 4, 180, 0, 70, 64);
    }

    private static int pixel(TestWorld world, List<EntitySnapshot> sheep) {
        int[] out = new int[1];
        new RayCaster(world).render(world, atFleece(), sheep, 1, 1, out);
        return out[0];
    }

    private static List<EntitySnapshot> sheep(boolean sheared, String dye) {
        EntitySnapshot body = EntitySnapshot.mob("sheep", null, 0.5, 0, 0.5, 0, 0, 0, 1f, false);
        List<EntitySnapshot> sheep = new ArrayList<>(List.of(body));
        sheep.addAll(EntitySnapshot.fleece(body, "sheep", null, sheared, dye));
        return List.copyOf(sheep);
    }

    @Test
    void aDyedFleeceArrivesAtThePixelInItsDyeColor() {
        TestWorld world = world();
        installSheep();

        assertPatch(0xFF84221C, pixel(world, sheep(false, "red")), "a red sheep");
        assertPatch(0xFF465D10, pixel(world, sheep(false, "green")), "a green one");
        assertPatch(0xFF2D337F, pixel(world, sheep(false, "blue")), "a blue one");
    }

    /**
     * White is a tint too rather than no tint, which is worth a test of its own: it is the only dye whose color the
     * client does not derive, and the default a sheep is born with - so treating it as "leave the texture alone"
     * would pass every other case here and still be wrong.
     */
    @Test
    void anUndyedFleeceIsTintedTheClientsWhite() {
        TestWorld world = world();
        installSheep();

        assertEquals(0xE6E6E6, EntitySnapshot.fleece(EntitySnapshot.mob("sheep", null, 0.5, 0, 0.5, 0, 0, 0, 1f, false),
                "sheep", null, false, "white").getFirst().tint() & 0xFFFFFF);
    }

    /** A shorn sheep wears no fleece at all, which is a layer left out rather than a color. */
    @Test
    void aShornSheepHasNoFleece() {
        TestWorld world = world();
        installSheep();

        assertEquals(List.of(), EntitySnapshot.fleece(EntitySnapshot.mob("sheep", null, 0.5, 0, 0.5, 0, 0, 0, 1f, false), "sheep", null, true, "red"));
        assertEquals(1, sheep(true, "red").size(), "just the body");
        assertPatch(0xFF20FF20, pixel(world, sheep(true, "red")), "so the hide under it is what shows");
    }

    /** The dye is on the fleece and not on the animal, or a red sheep would have red hooves and a red face. */
    @Test
    void theBodyUnderTheFleeceIsNotTinted() {
        installSheep();

        List<EntitySnapshot> sheep = sheep(false, "red");
        assertEquals(2, sheep.size());
        assertEquals(0, sheep.getFirst().tint(), "the body");
        assertEquals(0xFF84221C, sheep.getLast().tint(), "the fleece");
    }

    /** A sheep with no dye reported keeps the texture as it is rather than being multiplied by nothing. */
    @Test
    void aSheepWithNoDyeIsLeftUntinted() {
        installSheep();

        EntitySnapshot body = EntitySnapshot.mob("sheep", null, 0.5, 0, 0.5, 0, 0, 0, 1f, false);
        List<EntitySnapshot> fleece = EntitySnapshot.fleece(body, "sheep", null, false, null);
        assertEquals(1, fleece.size());
        assertEquals(0, fleece.getFirst().tint());
    }
}
