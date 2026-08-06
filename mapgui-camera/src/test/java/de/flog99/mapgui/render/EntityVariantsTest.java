package de.flog99.mapgui.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which texture a coat wears, read from the game's own variant registries rather than guessed from a name. */
class EntityVariantsTest {

    @TempDir
    Path dir;

    private final Map<String, String> files = new LinkedHashMap<>();
    private AssetStack stack;

    /** Closed after every test: an open ZipFile locks its file on Windows, and @TempDir then cannot delete it. */
    @AfterEach
    void closeStack() {
        if (stack != null) {
            stack.close();
        }
    }

    private void entry(String registry, String variant, String json) {
        files.put(EntityVariants.REGISTRIES + registry + EntityVariants.REGISTRY + variant + ".json", json);
    }

    private EntityVariants variants() throws IOException {
        Map<String, String> all = new LinkedHashMap<>(Zips.completeBase("26.2"));
        all.putAll(files);

        stack = AssetStack.of(List.of(), AssetPack.open(Zips.write(dir.resolve("pack.zip"), all)), "26.2");
        return new EntityVariants(stack);
    }

    /** The whole point: the texture is stated, so it does not have to be spelled like the variant. */
    @Test
    void aVariantWearsTheTextureItsEntryNames() throws IOException {
        entry("cat", "british_shorthair", """
                {"asset_id": "minecraft:entity/cat/cat_british_shorthair"}
                """);

        assertEquals("entity/cat/cat_british_shorthair",
                variants().textureOf("cat", "british_shorthair", false, EntityVariants.Mood.WILD));
    }

    /** A young one wears what its entry states for a young one, rather than the adult's with a word on the end. */
    @Test
    void aBabyWearsTheTextureStatedForABaby() throws IOException {
        entry("cat", "calico", """
                {"asset_id": "minecraft:entity/cat/cat_calico", "baby_asset_id": "minecraft:entity/cat/cat_calico_baby"}
                """);

        EntityVariants variants = variants();

        assertEquals("entity/cat/cat_calico_baby", variants.textureOf("cat", "calico", true, EntityVariants.Mood.WILD));
        assertEquals("entity/cat/cat_calico", variants.textureOf("cat", "calico", false, EntityVariants.Mood.WILD));
    }

    /** And one whose entry states no young form wears the adult's, which is what a frog does. */
    @Test
    void aBabyWithNoTextureOfItsOwnWearsTheAdults() throws IOException {
        entry("frog", "cold", """
                {"asset_id": "minecraft:entity/frog/frog_cold"}
                """);

        assertEquals("entity/frog/frog_cold", variants().textureOf("frog", "cold", true, EntityVariants.Mood.WILD));
    }

    /** A wolf's coat is three textures and the mood picks one: the markings are the variant's, the face the mood's. */
    @Test
    void aWolfWearsTheCoatItsMoodStates() throws IOException {
        entry("wolf", "ashen", """
                {"assets": {"wild": "minecraft:entity/wolf/wolf_ashen",
                            "tame": "minecraft:entity/wolf/wolf_ashen_tame",
                            "angry": "minecraft:entity/wolf/wolf_ashen_angry"}}
                """);

        EntityVariants variants = variants();

        assertEquals("entity/wolf/wolf_ashen", variants.textureOf("wolf", "ashen", false, EntityVariants.Mood.WILD));
        assertEquals("entity/wolf/wolf_ashen_tame", variants.textureOf("wolf", "ashen", false, EntityVariants.Mood.TAME));
        assertEquals("entity/wolf/wolf_ashen_angry", variants.textureOf("wolf", "ashen", false, EntityVariants.Mood.ANGRY));
    }

    /** A mood the entry says nothing about falls back to the wild coat rather than to nothing. */
    @Test
    void aMoodThatIsNotStatedFallsBackToTheWildCoat() throws IOException {
        entry("wolf", "plain", """
                {"assets": {"wild": "minecraft:entity/wolf/wolf"}}
                """);

        assertEquals("entity/wolf/wolf", variants().textureOf("wolf", "plain", false, EntityVariants.Mood.ANGRY));
    }

    /**
     * A mob whose variants are not data answers with nothing, which is the caller's cue to fall back to its own
     * guess - a rabbit, a fox, a llama and a parrot are all still written into the client.
     */
    @Test
    void aMobWithNoRegistryAnswersWithNothing() throws IOException {
        assertNull(variants().textureOf("rabbit", "brown", false, EntityVariants.Mood.WILD));
    }

    /** An entry written in a shape this does not expect costs that coat its texture and nothing else. */
    @Test
    void anEntryThatNamesNoTextureIsNotReadAsOne() throws IOException {
        entry("cat", "odd", "{\"spawn_conditions\": [{\"priority\": 0}]}");
        entry("cat", "broken", "not json at all");

        EntityVariants variants = variants();

        assertNull(variants.textureOf("cat", "odd", false, EntityVariants.Mood.WILD));
        assertNull(variants.textureOf("cat", "broken", false, EntityVariants.Mood.WILD));
    }

    /** No variant is no lookup, since most mobs have none at all. */
    @Test
    void aMobWithNoVariantAsksNothing() throws IOException {
        assertNull(variants().textureOf("creeper", null, false, EntityVariants.Mood.WILD));
    }
}
