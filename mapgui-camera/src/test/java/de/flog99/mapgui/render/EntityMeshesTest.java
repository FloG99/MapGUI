package de.flog99.mapgui.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The table, and what happens when the geometry it names is not there.
 *
 * <p>The second half is the point. Extraction runs the client's own code and can fail for reasons no server admin
 * can be expected to fix, so every one of those has to end in bounding boxes rather than in an exception on the
 * capture path.
 */
class EntityMeshesTest {

    @TempDir
    Path dir;

    /** Static, because the entry point a capture calls is - so a test that installs anything has to put it back. */
    @AfterEach
    void uninstall() {
        EntityMeshes.install(Map.of());
    }

    @Test
    void everyMeshTheTableNamesIsSpelledLikeAModelClass() {
        Set<String> names = new HashSet<>();
        for (EntityMeshes.Spec spec : EntityMeshes.specs()) {
            assertTrue(names.add(spec.mesh()), spec.mesh() + " is listed twice");
            assertFalse(spec.layers().isEmpty(), spec.mesh());

            for (EntityMeshes.Layer layer : spec.layers()) {
                assertTrue(layer.type().matches("([a-z][a-z0-9]*\\.)*[A-Z]\\w+"), layer.type() + " is not a class name");
                assertTrue(layer.factory().startsWith("create"), layer.factory());
                assertTrue(layer.textureWidth() >= 0 && layer.textureHeight() >= 0, layer.type());
                assertEquals(layer.textureWidth() == 0, layer.textureHeight() == 0, layer.type() + " states half a texture size");
            }
        }
        assertTrue(names.size() > 80, "a mesh per model, deduplicated across the types that share one: " + names.size());
    }

    /**
     * The mesh name is its own specification, so the parse of it has to come back out as what was written - a
     * misread factory name silently bakes a different model, and a misread texture size reads the right model off
     * the wrong part of its texture.
     */
    @Test
    void aMeshNameParsesIntoTheLayersItSpells() {
        Map<String, EntityMeshes.Spec> specs = new java.util.HashMap<>();
        EntityMeshes.specs().forEach(spec -> specs.put(spec.mesh(), spec));

        EntityMeshes.Layer cow = specs.get("animal.cow.CowModel").layers().getFirst();
        assertEquals("animal.cow.CowModel", cow.type());
        assertEquals("createBodyLayer", cow.factory(), "the default, since the name says no other");
        assertEquals(0, cow.textureWidth(), "a layer states its own texture size");
        assertEquals(0, cow.scale(), 1e-6);

        EntityMeshes.Layer cat = specs.get("animal.feline.AdultFelineModel#createBodyMesh@64x32*0.8").layers().getFirst();
        assertEquals("animal.feline.AdultFelineModel", cat.type());
        assertEquals("createBodyMesh", cat.factory());
        assertEquals(64, cat.textureWidth());
        assertEquals(32, cat.textureHeight());
        assertEquals(0.8f, cat.scale(), 1e-6);

        EntityMeshes.Layer mule = specs.get("animal.equine.DonkeyModel#createBodyLayer(0.92)").layers().getFirst();
        assertEquals(0.92f, mule.numbers()[0], 1e-6, "a mule is the equine mesh at 0.92, and zero collapses it");

        List<EntityMeshes.Layer> slime = specs.get("monster.slime.SlimeModel#createInnerBodyLayer+monster.slime.SlimeModel#createOuterBodyLayer").layers();
        assertEquals(2, slime.size(), "two layers over one texture are one mesh");
        assertEquals("createInnerBodyLayer", slime.getFirst().factory());
        assertEquals("createOuterBodyLayer", slime.getLast().factory());
    }

    /** With nothing installed there is no shape for anybody, which is the caller's cue to draw a box. */
    @Test
    void withNoGeometryEveryTypeSaysSoRatherThanGuessing() {
        EntityMeshes.install(Map.of());

        assertNull(EntityMeshes.of("cow", null, false));
        assertNull(EntityMeshes.of("cow", null, true));
        assertFalse(EntityMeshes.hasBaby("cow"));
        assertNull(EntitySnapshot.mob("cow", 0, 0, 0, 0, 0, 0, 1f), "so the capture falls back to the bounding box");
        assertEquals(List.of(), EntitySnapshot.over(EntitySnapshot.box(0, 0, 0, 0, 0, 1, 1, "hide"), "sheep"));
    }

    /** And a partial extraction is useful rather than an error: the meshes that baked are drawn, the rest are not. */
    @Test
    void aMeshThatDidNotBakeLeavesOnlyItsOwnTypesOnBoxes() {
        EntityMeshes.install(Map.of("animal.cow.CowModel", List.of(MeshPart.of("body", List.of(MeshCube.plain(-6, 0, -8, 12, 20, 16))))));

        assertNotNull(EntityMeshes.of("cow", null, false));
        assertNotNull(EntityMeshes.of("mooshroom", null, false), "which shares the cow's mesh");
        assertEquals("entity/cow/mooshroom_red", EntityMeshes.of("mooshroom", null, false).texture());
        assertNull(EntityMeshes.of("pig", null, false), "whose mesh is not here");

        assertFalse(EntityMeshes.hasBaby("cow"), "the calf's mesh is not here either");
        assertEquals(0.5f, EntitySnapshot.mob("cow", 0, 0, 0, 0, 0, 0, 0.5f).scale(), 1e-6,
                "so a calf is drawn as a small cow rather than not at all");
    }

    /**
     * A calf is drawn from the calf's mesh at its own size, not from the cow's halved. Halving a mesh that is
     * already calf sized produces a kitten, and vanilla's young meshes are separately proportioned - which is the
     * reason for having them at all.
     */
    @Test
    void aBabyWithAMeshOfItsOwnIsDrawnAtThatMeshesSize() {
        EntityMeshes.install(Map.of(
                "animal.cow.CowModel", List.of(MeshPart.of("body", List.of(MeshCube.plain(-6, 0, -8, 12, 20, 16)))),
                "animal.cow.BabyCowModel", List.of(MeshPart.of("body", List.of(MeshCube.plain(-3, 0, -4, 6, 10, 8))))
        ));

        assertTrue(EntityMeshes.hasBaby("cow"));
        assertEquals(1f, EntitySnapshot.mob("cow", 0, 0, 0, 0, 0, 0, 0.5f).scale(), 1e-6);
        assertEquals(10, EntitySnapshot.mob("cow", 0, 0, 0, 0, 0, 0, 0.5f).model().height(), 1e-4);
        assertEquals(20, EntitySnapshot.mob("cow", 0, 0, 0, 0, 0, 0, 1f).model().height(), 1e-4);
    }

    /**
     * The variant meshes the extractor is asked for, named so that a line deleted from the table fails here rather
     * than in a capture.
     *
     * <p>These four are what vanilla 26.2 builds: a cold and a warm cow, a cold pig, a cold chicken. Anything else
     * that reaches {@code of} with a variant word is a recolor and takes the species mesh.
     */
    @Test
    void theTableAsksForEveryCoatVanillaBuildsAMeshFor() {
        Set<String> meshes = new HashSet<>();
        EntityMeshes.specs().forEach(spec -> meshes.add(spec.mesh()));

        assertTrue(meshes.contains("animal.cow.ColdCowModel"), "the cold cow, whose horns are two turned parts");
        assertTrue(meshes.contains("animal.cow.WarmCowModel"), "the warm cow, whose ears are four more cubes");
        assertTrue(meshes.contains("animal.pig.ColdPigModel"));
        assertTrue(meshes.contains("animal.chicken.ColdChickenModel"));
    }

    /** Distinct heights, so which mesh a lookup returned is readable off the model rather than by identity. */
    private static void installCows() {
        EntityMeshes.install(Map.of(
                "animal.cow.CowModel", body(20),
                "animal.cow.ColdCowModel", body(21),
                "animal.cow.WarmCowModel", body(22),
                "animal.cow.BabyCowModel", body(16)
        ));
    }

    private static List<MeshPart> body(float height) {
        return List.of(MeshPart.of("body", List.of(MeshCube.plain(-6, 0, -8, 12, height, 16))));
    }

    private static float height(String type, String variant, boolean baby) {
        return EntitySnapshot.mob(type, variant, 0, 0, 0, 0, 0, 0, baby ? 0.5f : 1f, baby).model().height();
    }

    /**
     * A coat that is a shape is drawn from that shape's mesh.
     *
     * <p>The bug this replaces was one mesh per type: vanilla's cold cow builds its horns as two turned parts off a
     * different patch of the texture, so a cold cow drawn from the temperate mesh reads its horns off texels that
     * are not horns on the cold skin - and comes out with none, which is what was reported.
     */
    @Test
    void aCoatWithAMeshOfItsOwnIsDrawnFromIt() {
        installCows();

        assertEquals(20, height("cow", null, false), 1e-4, "no variant asked about");
        assertEquals(20, height("cow", "temperate", false), 1e-4, "temperate is the species mesh, and needs no line");
        assertEquals(21, height("cow", "cold", false), 1e-4);
        assertEquals(22, height("cow", "warm", false), 1e-4);
    }

    /**
     * And a young one is not, because vanilla builds one calf mesh and hangs every coat's baby layer off it -
     * {@code COLD_COW_BABY} and {@code WARM_COW_BABY} both resolve to {@code BabyCowModel}.
     */
    @Test
    void aCoatsYoungIsTheSpeciesYoungMesh() {
        installCows();

        assertEquals(16, height("cow", null, true), 1e-4);
        assertEquals(16, height("cow", "cold", true), 1e-4);
        assertEquals(16, height("cow", "warm", true), 1e-4);
    }

    /**
     * A recolor keeps the species shape whatever its variant is called, including a word another type answers to.
     *
     * <p>A mooshroom is a red or a brown cow and has no cold or warm form at all, so inheriting the cow's variant
     * list would be a trap rather than a shortcut.
     */
    @Test
    void aRecolorKeepsTheSpeciesShape() {
        installCows();

        assertEquals(20, height("mooshroom", "red", false), 1e-4);
        assertEquals(20, height("mooshroom", "brown", false), 1e-4);
        assertEquals(20, height("mooshroom", "cold", false), 1e-4, "even the word a cow would answer to");
    }

    /** A variant whose mesh did not bake falls back to the species one rather than to a bounding box. */
    @Test
    void aCoatWhoseMeshIsMissingFallsBackToTheSpecies() {
        EntityMeshes.install(Map.of("animal.cow.CowModel", body(20)));

        assertEquals(20, height("cow", "cold", false), 1e-4);
    }

    /** A sheep wears its fleece; nothing else wears anything, and a lamb is drawn without one. */
    @Test
    void onlyAMobThatWearsALayerHasOne() {
        EntityMeshes.install(Map.of(
                "animal.sheep.SheepModel", List.of(MeshPart.of("body", List.of(MeshCube.plain(-4, 0, -8, 8, 18, 16)))),
                "animal.sheep.BabySheepModel", List.of(MeshPart.of("body", List.of(MeshCube.plain(-2, 0, -4, 4, 9, 8)))),
                "animal.sheep.SheepFurModel#createFurLayer", List.of(MeshPart.of("body", List.of(MeshCube.plain(-5, 0, -9, 10, 20, 18)))),
                "animal.cow.CowModel", List.of(MeshPart.of("body", List.of(MeshCube.plain(-6, 0, -8, 12, 20, 16))))
        ));

        EntitySnapshot sheep = EntitySnapshot.mob("sheep", 0.5, 0, 0.5, 0, 0, 0, 1f);
        List<EntitySnapshot> fleece = EntitySnapshot.over(sheep, "sheep");
        assertEquals(1, fleece.size());
        assertEquals("entity/sheep/sheep_wool", fleece.getFirst().texture());

        assertEquals(List.of(), EntitySnapshot.over(EntitySnapshot.mob("cow", 0.5, 0, 0.5, 0, 0, 0, 1f), "cow"));
        assertEquals(List.of(), EntitySnapshot.over(EntitySnapshot.mob("sheep", 0.5, 0, 0.5, 0, 0, 0, 0.5f), "sheep"),
                "a lamb's fleece is not a sheep's shrunk, so it is left off rather than drawn the wrong size");
    }

    /** A pack that is not a client jar and carries no baked geometry: no meshes, no exception, no capture lost. */
    @Test
    void abaseWithNothingToExtractFromInstallsNothing() throws IOException {
        try (AssetPack base = AssetPack.open(Zips.write(dir.resolve("plain.zip"), Zips.cachedBase("26.2")))) {
            AssetStack.of(List.of(), base, "26.2").close();
        }

        assertNull(EntityMeshes.of("cow", null, false));
    }

    /** And one whose baked geometry is unreadable, which is the same outcome by a different route. */
    @Test
    void abaseWithCorruptGeometryInstallsNothing() throws IOException {
        Map<String, String> entries = new java.util.LinkedHashMap<>(Zips.cachedBase("26.2"));
        entries.put(AssetRepack.MESH_FILE, "{\"version\": 1, \"meshes\": {\"animal.cow.CowModel\": [ truncated");

        try (AssetPack base = AssetPack.open(Zips.write(dir.resolve("broken.zip"), entries))) {
            AssetStack.of(List.of(), base, "26.2").close();
        }

        assertNull(EntityMeshes.of("cow", null, false));
    }

    /** A repack of something that is not a client jar leaves the geometry out rather than failing the repack. */
    @Test
    void repackingApackWithNoModelClassesLeavesTheGeometryOut() throws IOException {
        Path jar = Zips.write(dir.resolve("pack.zip"), Zips.completeBase("26.2"));
        Path out = dir.resolve("subset.zip");
        AssetRepack.subset(jar, out);

        try (AssetPack packed = AssetPack.open(out)) {
            assertFalse(packed.has(AssetRepack.MESH_FILE));
            assertTrue(AssetRepack.isCurrent(out));
        }
    }
}
