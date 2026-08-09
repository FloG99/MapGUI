package de.flog99.mapgui.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extractor against a real client jar, checking that the geometry which comes out is the geometry the client
 * draws.
 *
 * <p><b>Skipped unless there is a Minecraft installation on this machine</b> - it needs
 * {@code %APPDATA%/.minecraft/versions/26.2/26.2.jar} and the libraries that version declares beside it. CI has
 * neither, and nothing Mojang-derived may be committed here.
 */
class MeshExtractorTest {

    private static final String VERSION = "26.2";

    private static Path minecraft() {
        String appData = System.getenv("APPDATA");
        return appData == null ? null : Path.of(appData, ".minecraft");
    }

    /**
     * Exactly the libraries the version declares, rather than a sweep of the libraries directory: a real installation
     * has a dozen versions of authlib under there, and the wrong one fails every model deep inside a codec. It is
     * also what a Paper server's own classpath amounts to.
     */
    private static ClassLoader libraries(Path minecraft) throws IOException {
        JsonObject json = JsonParser.parseString(
                Files.readString(minecraft.resolve("versions").resolve(VERSION).resolve(VERSION + ".json"), StandardCharsets.UTF_8)
        ).getAsJsonObject();

        List<URL> classpath = new ArrayList<>();
        for (var library : json.getAsJsonArray("libraries")) {
            var downloads = library.getAsJsonObject().getAsJsonObject("downloads");
            if (downloads == null || !downloads.has("artifact")) {
                continue;
            }

            Path jar = minecraft.resolve("libraries").resolve(downloads.getAsJsonObject("artifact").get("path").getAsString());
            if (Files.isRegularFile(jar)) {
                classpath.add(jar.toUri().toURL());
            }
        }
        return new URLClassLoader(classpath.toArray(URL[]::new), MeshExtractorTest.class.getClassLoader());
    }

    private Map<String, List<MeshPart>> extract() throws Exception {
        return extract(EntityMeshes.specs());
    }

    /** The same, for meshes the table does not ask for. */
    private Map<String, List<MeshPart>> extract(EntityMeshes.Spec... extra) throws Exception {
        return extract(List.of(extra));
    }

    private Map<String, List<MeshPart>> extract(List<EntityMeshes.Spec> specs) throws Exception {
        Path minecraft = minecraft();
        Path version = minecraft == null ? null : minecraft.resolve("versions").resolve(VERSION);

        Assumptions.assumeTrue(version != null && Files.isRegularFile(version.resolve(VERSION + ".jar")),
                "no local Minecraft " + VERSION + " client jar to extract from");
        Assumptions.assumeTrue(Files.isRegularFile(version.resolve(VERSION + ".json")),
                "no version json, so there is no way to know which libraries to put on the path");

        return MeshExtractor.extract(version.resolve(VERSION + ".jar"), libraries(minecraft), specs);
    }

    /** The cow in detail: six parts, ten cubes, and a head that is a head, a muzzle and two horns. */
    @Test
    void theCowBakesIntoTheSixPartsAndTenCubesTheClientDraws() throws Exception {
        List<MeshPart> cow = extract().get("animal.cow.CowModel");
        assertNotNull(cow, "the cow did not bake");

        assertEquals(1, cow.size(), "one root");
        MeshPart root = cow.getFirst();
        assertEquals(6, root.children().size(), "head, body and four legs");
        assertEquals(10, count(root), "ten cubes over those six parts");

        MeshPart head = root.children().stream().filter(part -> part.name().equals("head")).findFirst().orElseThrow();
        assertEquals(4, head.cubes().size(), "the head itself, the muzzle, and a horn on each side");
        assertTrue(head.head(), "and it is the part the head rotation applies to");

        assertEquals(20.016f, root.y() + head.y(), 1e-3, "the neck height, in entity pixels off the ground");
        assertEquals(-8, head.z(), 1e-3, "and how far forward of the body's middle");

        MeshPart body = root.children().stream().filter(part -> part.name().equals("body")).findFirst().orElseThrow();
        assertEquals(2, body.cubes().size(), "the barrel and the udder");
        assertEquals(-Math.PI / 2, body.xRot(), 1e-3, "laid on its side, which is how every quadruped's barrel is built");
    }

    /**
     * A cold cow is built differently from a temperate one, which is the fact the variant table rests on: it hangs
     * its horns off its head as parts of their own where a temperate cow's are cubes of the head. Sharing one mesh
     * between them is what left a taiga cow with no horns at all.
     */
    @Test
    void aColdCowIsNotATemperateOneWithADifferentSkin() throws Exception {
        Map<String, List<MeshPart>> meshes = extract();

        MeshPart temperate = meshes.get("animal.cow.CowModel").getFirst();
        MeshPart cold = meshes.get("animal.cow.ColdCowModel").getFirst();
        assertNotNull(cold, "the cold cow did not bake, so the table has nothing of its own to draw it with");
        assertNotNull(meshes.get("animal.cow.WarmCowModel"), "nor did the warm one");

        assertFalse(head(cold).children().isEmpty(), "a cold cow's horns are parts hung off its head");
        assertTrue(head(temperate).children().isEmpty(), "a temperate cow's are cubes of the head itself");
    }

    private static MeshPart head(MeshPart root) {
        return root.children().stream().filter(part -> part.name().equals("head")).findFirst().orElseThrow();
    }

    /**
     * A mob's own right is on the east when it faces north, which is the fact that says the mesh is not mirrored.
     * Vanilla reaches its draw space with {@code PoseStack.scale(-1, -1, 1)}, a half turn about Z; flipping Y alone
     * stands the model up and leaves it <b>reflected</b>, which is invisible on symmetric geometry and texture.
     */
    @Test
    void aMobsRightSideIsOnItsRight() throws Exception {
        // Keyed by the whole spec string, since that is what names a mesh - see EntityMeshes.
        List<MeshPart> parts = extract(HUMANOID_MESH).get("HumanoidModel#createMesh@64x64");
        assertNotNull(parts, "the plain humanoid did not bake");

        MeshPart humanoid = parts.getFirst();

        assertTrue(limb(humanoid, "right_arm").x() > 0, "a right arm is on the east side of a model facing north");
        assertTrue(limb(humanoid, "left_arm").x() < 0, "and a left arm on the west");
        assertTrue(limb(humanoid, "right_leg").x() > 0, "the legs the same way");
        assertTrue(limb(humanoid, "left_leg").x() < 0);
    }

    /**
     * At most one part of a mesh takes the head rotation. The equines nest a {@code head} inside a {@code head_parts}
     * and both answer to the name, so the rotation was applied twice - double the yaw and double the pitch.
     */
    @Test
    void nothingTakesTheHeadRotationTwice() throws Exception {
        for (Map.Entry<String, List<MeshPart>> mesh : extract().entrySet()) {
            int heads = 0;
            for (MeshPart root : mesh.getValue()) {
                heads += headsIn(root);
            }
            assertTrue(heads <= 1, mesh.getKey() + " turns " + heads + " parts with the head rotation");
        }
    }

    /** Which parts of a mesh turn with the head, by path, so two sets of them can be compared and named. */
    private static List<String> turning(List<MeshPart> parts) {
        List<String> found = new ArrayList<>();
        for (MeshPart root : parts) {
            turning(root, "", found);
        }
        return found;
    }

    private static void turning(MeshPart part, String path, List<String> found) {
        String here = path + "/" + part.name();
        if (part.head()) {
            found.add(here);
        }
        for (MeshPart child : part.children()) {
            turning(child, here, found);
        }
    }

    private static int headsIn(MeshPart part) {
        int heads = part.head() ? 1 : 0;
        for (MeshPart child : part.children()) {
            heads += headsIn(child);
        }
        return heads;
    }

    private static MeshPart limb(MeshPart root, String name) {
        return root.children().stream().filter(part -> part.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no part called " + name));
    }

    /**
     * The box this authors by hand is unwrapped the way the client unwraps one. The player is the one model not
     * extracted - there is no skin in the jar to bake it against - so its unwrap is written out in
     * {@link MeshCube#box} and can disagree with every mob, which it did. Pinned against a real vanilla cube rather
     * than a table of chosen numbers, so if the client ever lays a box out differently the player follows it.
     */
    @Test
    void theAuthoredUnwrapMatchesTheClientsOwn() throws Exception {
        MeshPart humanoid = extract(HUMANOID_MESH).get("HumanoidModel#createMesh@64x64").getFirst();
        MeshCube skull = limb(humanoid, "head").cubes().getFirst();

        // The same box, stated the way a hand-authored model has to state it: 8x8x8 at texOffs(0, 0) on a 64x64 skin.
        MeshCube authored = MeshCube.box(-4, 0, -4, 8, 8, 8, 0, 0, 64, 64, 0);

        for (Direction side : Direction.values()) {
            assertNotNull(skull.face(side), "the client draws every side of a head, so " + side + " should be there");
            assertArrayEquals(skull.face(side), authored.face(side), 1e-5f,
                    "the authored " + side + " face reads a different patch than the client's");
        }
    }


    /**
     * The authored player's arms and legs wear their own patches, on their own sides, overlay layers included. Not
     * the head's check over again: a head cube is centred, so it reads the same patch whichever way round left and
     * right are, and a limb does not.
     *
     * <p>Pinned against the client's own <b>player</b> mesh rather than its humanoid one, which has teeth: a humanoid
     * builds both arms from one patch and mirrors the left, so comparing against it says nothing about the left arm.
     */
    @Test
    void aPlayersLimbsWearTheirOwnPatchesOnTheirOwnSides() throws Exception {
        List<MeshPart> baked = extract(PLAYER_MESH).get("player.PlayerModel#createMesh@64x64");
        assertNotNull(baked, "the client's own player mesh did not bake");

        MeshPart clients = baked.getFirst();
        EntityModel ours = EntityModel.player(false, SkinLayers.ALL, false);

        for (String limb : List.of("right_arm", "left_arm", "right_leg", "left_leg")) {
            boolean right = limb.startsWith("right");
            boolean arm = limb.endsWith("arm");

            assertEquals(patches(limb(clients, limb)), oursOn(ours, right, arm),
                    "the authored " + limb + " reads different patches than the client's, or reads them on the other side");
        }
    }

    /**
     * The plain humanoid mesh, built here rather than taken from the table: the unposed one is a reference these
     * tests need and nothing in a capture does.
     */
    private static final EntityMeshes.Spec HUMANOID_MESH = new EntityMeshes.Spec(
            "HumanoidModel#createMesh@64x64",
            List.of(new EntityMeshes.Layer("HumanoidModel", "createMesh", 64, 64, new float[]{}, 0, null, null, EntityMeshes.Space.MOB)));

    /** The player mesh, which is not in the table - the player is authored, so nothing asks the client for its shape. */
    private static final EntityMeshes.Spec PLAYER_MESH = new EntityMeshes.Spec(
            "player.PlayerModel#createMesh@64x64",
            List.of(new EntityMeshes.Layer("player.PlayerModel", "createMesh", 64, 64, new float[]{}, 0, null, null, EntityMeshes.Space.MOB)));

    /**
     * Every patch a subtree reads, as text, so two sets of them can be compared and named. A subtree rather than a
     * part, since the client hangs a limb's overlay off the limb and the patch is what is being compared.
     */
    private static Set<String> patches(MeshPart part) {
        Set<String> found = new TreeSet<>();
        collectPatches(part, found);
        return found;
    }

    private static void collectPatches(MeshPart part, Set<String> into) {
        for (MeshCube cube : part.cubes()) {
            into.add(patch(cube));
        }
        for (MeshPart child : part.children()) {
            collectPatches(child, into);
        }
    }

    /**
     * The patches this module's own player reads on one side of the body, found by where the cube is rather than by
     * what part holds it - the client hangs every limb off its own part while this one keeps the legs among the
     * body's cubes. Sides by the sign of X, arms from legs by height; the torso is centred and so belongs to neither.
     */
    private static Set<String> oursOn(EntityModel player, boolean right, boolean arm) {
        Set<String> found = new TreeSet<>();
        for (MeshPart part : player.parts()) {
            for (MeshCube cube : part.cubes()) {
                float middleX = (cube.minX() + cube.maxX()) / 2 + part.x();
                float middleY = (cube.minY() + cube.maxY()) / 2 + part.y();
                if (middleX == 0 || middleX > 0 != right || middleY > 12 != arm) {
                    continue;
                }
                found.add(patch(cube));
            }
        }

        assertFalse(found.isEmpty(), "the authored player has nothing where " + (right ? "a right " : "a left ") + (arm ? "arm" : "leg") + " goes");
        return found;
    }

    /** One cube's patch, as the texels its front face spans - which is what names the patch without pinning geometry. */
    private static String patch(MeshCube cube) {
        float[] north = cube.face(Direction.NORTH);
        return String.format("u %.0f..%.0f v %.0f..%.0f",
                Math.min(north[0], north[2]) * 64, Math.max(north[0], north[2]) * 64,
                Math.min(north[1], north[5]) * 64, Math.max(north[1], north[5]) * 64);
    }

    /**
     * A mesh comes out standing the way its own model class stands it, not the way its geometry was authored.
     *
     * <p>The client's animation is what decides how a mob stands still, and each model class holds its own: the undead
     * reach forward, an archer does not, a vex holds its arms up. Asserted as the <i>difference</i> between two meshes
     * that are the same geometry - the plain humanoid body and a zombie's - because that is the claim being made and it
     * needs no angle chosen here. The zombie's arms have to be raised and the humanoid's have to be down; both numbers
     * come from Mojang.
     *
     * <p>This is what replaced a table of mob names with transcribed angles in the capture. Everything that table did
     * for a zombie, this does for every mob in the game.
     */
    @Test
    void aMeshStandsTheWayItsModelClassStandsIt() throws Exception {
        MeshPart humanoid = extract(HUMANOID_MESH).get("HumanoidModel#createMesh@64x64").getFirst();
        MeshPart zombie = extract().get("HumanoidModel#createMesh@64x64!monster.zombie.ZombieModel").getFirst();
        assertNotNull(zombie, "the zombie mesh did not bake");

        float atEase = Math.abs(limb(humanoid, "right_arm").xRot());
        float reaching = Math.abs(limb(zombie, "right_arm").xRot());

        assertTrue(atEase < 0.05, "a plain humanoid stands with its arms down, and this one is at " + atEase);
        assertTrue(reaching > 1, "a zombie holds its arms out in front of it, and this one is at " + reaching);
        assertEquals(limb(humanoid, "right_arm").x(), limb(zombie, "right_arm").x(), 1e-4,
                "posing a mesh should turn its parts, not move them");
    }

    /**
     * Every mesh draws something, every cube it draws has a side to draw, and no part is scaled to nothing.
     *
     * <p>Not "every part has a cube", which vanilla itself is not: a baby zombie's mesh carries an empty
     * {@code hat} part, there as a hook for the armor layers to hang off. An empty part is a placeholder, not a
     * cube that went missing on the way through here.
     */
    @Test
    void everyMeshDrawsSomethingAndNoPartIsScaledAway() throws Exception {
        Map<String, List<MeshPart>> meshes = extract();
        assertTrue(meshes.size() > 80, "meshes extracted: " + meshes.size());

        meshes.forEach((mesh, parts) -> {
            assertFalse(parts.isEmpty(), mesh);
            parts.forEach(part -> walk(mesh, part));
            assertTrue(count(parts.getFirst()) > 0, mesh + " baked no cubes at all");
        });
    }

    /**
     * Every face of every cube carries texture coordinates inside its own texture, and the four of them are the
     * four corners of a rectangle.
     *
     * <p>Which is what catches the one mistake this design can make: the corners are filed by measuring where each
     * of vanilla's vertices sits on the face, so a face whose corners were filed into the wrong slots would come
     * out as two of them holding the same coordinates - and that is a face drawn folded in half.
     *
     * <p>Inside its texture is a wider window than 0 to 1, deliberately. Vanilla runs a patch off the edge in a
     * good many models - a frog's body, a strider's bristles, a dragon's wings - and the client's texture wrapping
     * brings it back round, which is exactly what {@link Texture#sample} does too.
     */
    @Test
    void everyDrawnFaceHasFourDistinctCornersInsideItsTexture() throws Exception {
        int faces = 0;
        for (List<MeshPart> parts : extract().values()) {
            for (MeshPart part : parts) {
                faces += corners(part);
            }
        }
        assertTrue(faces > 5000, "faces checked: " + faces);
    }

    /** The whole set, as it is written into the texture cache and read back out of it. */
    @Test
    void theExtractedGeometrySurvivesBeingWrittenToTheCache() throws Exception {
        Map<String, List<MeshPart>> meshes = extract();
        Map<String, List<MeshPart>> back = MeshCodec.read(MeshCodec.write(meshes));

        assertEquals(meshes.keySet(), back.keySet());
        for (String mesh : meshes.keySet()) {
            assertEquals(count(meshes.get(mesh).getFirst()), count(back.get(mesh).getFirst()), mesh);
            assertEquals(turning(meshes.get(mesh)), turning(back.get(mesh)),
                    mesh + " turns different parts with the head once it has been through the cache");
        }

        EntityMeshes.install(back);
        try {
            assertNotNull(EntitySnapshot.mob("cow", 0, 0, 0, 0, 0, 0, 1f));
            assertEquals(29.016f, EntitySnapshot.mob("cow", 0, 0, 0, 0, 0, 0, 1f).model().height(), 1e-2, "a cow stands 29 pixels tall");
        } finally {
            EntityMeshes.install(Map.of());
        }
    }

    private static int count(MeshPart part) {
        int total = part.cubes().size();
        for (MeshPart child : part.children()) {
            total += count(child);
        }
        return total;
    }

    private static void walk(String mesh, MeshPart part) {
        assertTrue(part.xScale() > 0 && part.yScale() > 0 && part.zScale() > 0, mesh + " part '" + part.name() + "' has no size");
        for (MeshCube cube : part.cubes()) {
            boolean drawn = false;
            for (Direction side : Direction.values()) {
                drawn |= cube.face(side) != null;
            }
            assertTrue(drawn, mesh + " part '" + part.name() + "' has a cube with no sides");
        }
        part.children().forEach(child -> walk(mesh, child));
    }

    private static int corners(MeshPart part) {
        int found = 0;
        for (MeshCube cube : part.cubes()) {
            for (Direction side : Direction.values()) {
                float[] uv = cube.face(side);
                if (uv == null) {
                    continue;
                }
                found++;

                for (float value : uv) {
                    assertTrue(value > -1 && value < 2, "coordinate nowhere near the texture: " + value);
                }

                int topLeft = MeshCube.corner(false, false);
                int topRight = MeshCube.corner(true, false);
                int bottomLeft = MeshCube.corner(false, true);
                int bottomRight = MeshCube.corner(true, true);

                // Opposite corners of a rectangle sum to the same thing whichever pair you take, and this holds
                // however the face was mirrored or turned. A corner filed into the wrong slot breaks it.
                for (int axis = 0; axis < 2; axis++) {
                    assertEquals(uv[topLeft * 2 + axis] + uv[bottomRight * 2 + axis],
                            uv[topRight * 2 + axis] + uv[bottomLeft * 2 + axis], 1e-5, "not a rectangle");
                }

                // And where the patch has area at all, opposite corners are in different places on both axes.
                // Several patches have none: the four edges of a fin, and the sides of a cube an armor layer
                // inflated from something flat.
                if (span(uv, 0) > 1e-6 && span(uv, 1) > 1e-6) {
                    assertEquals(2, different(uv, topLeft, bottomRight), "corners folded together");
                    assertEquals(2, different(uv, topRight, bottomLeft), "corners folded together");
                }
            }
        }
        for (MeshPart child : part.children()) {
            found += corners(child);
        }
        return found;
    }

    private static float span(float[] uv, int axis) {
        float low = Float.MAX_VALUE;
        float high = -Float.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            low = Math.min(low, uv[corner * 2 + axis]);
            high = Math.max(high, uv[corner * 2 + axis]);
        }
        return high - low;
    }

    private static int different(float[] uv, int left, int right) {
        int axes = 0;
        for (int i = 0; i < 2; i++) {
            if (Math.abs(uv[left * 2 + i] - uv[right * 2 + i]) > 1e-6) {
                axes++;
            }
        }
        return axes;
    }
}
