package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetResolverTest {

    @TempDir
    Path root;

    private Path assets() {
        return root.resolve("assets");
    }

    private Path cache() {
        return root.resolve("cache");
    }

    private AssetResolver.Request request(List<String> packs, boolean allowMismatch) {
        return request(packs, List.of(), allowMismatch);
    }

    private AssetResolver.Request request(List<String> packs, List<Path> followed, boolean allowMismatch) {
        return new AssetResolver.Request(assets(), cache(), packs, followed, "26.2", allowMismatch);
    }

    /**
     * A pack the server was seen handing its players is layered, and beneath the admin's own files.
     *
     * <p>Which way round matters: what an admin put in {@code assets/} is a decision, and what was followed off
     * the wire is a convenience, so the decision wins where the two disagree.
     */
    @Test
    void aFollowedPackLayersUnderTheAdminsOwn() throws IOException {
        Zips.write(cache().resolve("26.2.zip"), Zips.cachedBase("26.2"));
        Zips.write(assets().resolve("mine.zip"), Map.of("assets/minecraft/textures/block/stone.png", "the admin's"));
        Path followed = Zips.write(root.resolve("followed.zip"), Map.of(
                "assets/minecraft/textures/block/stone.png", "the server's",
                "assets/minecraft/textures/block/dirt.png", "only the server's"));

        AssetResolver.Resolution resolution = AssetResolver.resolve(request(List.of(), List.of(followed), false));

        AssetResolver.Resolution.Loaded loaded = assertInstanceOf(AssetResolver.Resolution.Loaded.class, resolution);
        try (AssetStack stack = loaded.stack()) {
            assertEquals("the admin's", new String(stack.read("assets/minecraft/textures/block/stone.png"), StandardCharsets.UTF_8));
            assertEquals("only the server's", new String(stack.read("assets/minecraft/textures/block/dirt.png"), StandardCharsets.UTF_8));
        }
    }

    /**
     * A followed pack is never the base, however complete it looks.
     *
     * <p>A total-conversion pack can carry stone, dirt and a couple of models, which is all {@code isComplete}
     * asks for. Promoting one to the base would put a pack with no {@code version.json} where vanilla belongs and
     * report the whole camera broken - over a file nobody chose to install.
     */
    @Test
    void aFollowedPackIsNeverPromotedToTheBase() throws IOException {
        Zips.write(cache().resolve("26.2.zip"), Zips.cachedBase("26.2"));
        Path followed = Zips.write(root.resolve("conversion.zip"), Zips.completeBase("26.2"));

        AssetResolver.Resolution resolution = AssetResolver.resolve(request(List.of(), List.of(followed), false));

        AssetResolver.Resolution.Loaded loaded = assertInstanceOf(AssetResolver.Resolution.Loaded.class, resolution);
        try (AssetStack stack = loaded.stack()) {
            assertEquals("26.2", stack.version(), "the cached download is still what says which version this is");
        }
    }

    @Test
    void nothingAnywhereIsMissing() {
        AssetResolver.Resolution resolution = AssetResolver.resolve(request(List.of(), false));

        AssetResolver.Resolution.Missing missing = assertInstanceOf(AssetResolver.Resolution.Missing.class, resolution);
        assertEquals("26.2", missing.wantedVersion());
    }

    @Test
    void cachedBaseForTheRightVersionLoads() throws IOException {
        Zips.write(cache().resolve("26.2.zip"), Zips.cachedBase("26.2"));

        AssetResolver.Resolution resolution = AssetResolver.resolve(request(List.of(), false));

        AssetResolver.Resolution.Loaded loaded = assertInstanceOf(AssetResolver.Resolution.Loaded.class, resolution);
        try (AssetStack stack = loaded.stack()) {
            assertEquals("26.2", stack.version());
            assertFalse(loaded.mismatchAllowed());
        }
    }

    /** A server upgrade: the cache still holds the old version, and that is an upgrade rather than a fresh install. */
    @Test
    void staleCachedBaseIsAMismatchNotMissing() throws IOException {
        Zips.write(cache().resolve("1.21.4.zip"), Zips.cachedBase("1.21.4"));

        AssetResolver.Resolution resolution = AssetResolver.resolve(request(List.of(), false));

        AssetResolver.Resolution.Mismatched mismatched = assertInstanceOf(AssetResolver.Resolution.Mismatched.class, resolution);
        assertEquals("1.21.4", mismatched.baseVersion());
        assertEquals("26.2", mismatched.wantedVersion());
        assertFalse(mismatched.adminSupplied(), "a cached zip is ours, so the fix is a download rather than a message");
    }

    /**
     * A cached copy packed before MapGUI started reading some subtree is worse than no copy: it loads, reports
     * itself ready, and then draws a checkerboard where whatever is now missing should be. So an unstamped or
     * outdated one counts as not installed, and the ordinary download path replaces it.
     */
    @Test
    void aCachedBasePackedByAnOlderVersionIsRefetched() throws IOException {
        Zips.write(cache().resolve("26.2.zip"), Zips.completeBase("26.2"));

        AssetResolver.Resolution resolution = AssetResolver.resolve(request(List.of(), false));

        AssetResolver.Resolution.Missing missing = assertInstanceOf(AssetResolver.Resolution.Missing.class, resolution,
                "the right version but the wrong subset, which a download fixes");
        assertEquals("26.2", missing.wantedVersion());
    }

    @Test
    void adminSuppliedBaseForTheWrongVersionIsFlaggedAsTheirs() throws IOException {
        Zips.write(assets().resolve("1.21.4.jar"), Zips.completeBase("1.21.4"));

        AssetResolver.Resolution resolution = AssetResolver.resolve(request(List.of("1.21.4.jar"), false));

        AssetResolver.Resolution.Mismatched mismatched = assertInstanceOf(AssetResolver.Resolution.Mismatched.class, resolution);
        assertEquals("1.21.4.jar", mismatched.baseName());
        assertTrue(mismatched.adminSupplied(), "their file is never replaced, so the message has to name it");
    }

    @Test
    void mismatchOverrideLoadsAnyway() throws IOException {
        Zips.write(assets().resolve("1.21.4.jar"), Zips.completeBase("1.21.4"));

        AssetResolver.Resolution resolution = AssetResolver.resolve(request(List.of("1.21.4.jar"), true));

        AssetResolver.Resolution.Loaded loaded = assertInstanceOf(AssetResolver.Resolution.Loaded.class, resolution);
        try (AssetStack stack = loaded.stack()) {
            assertEquals("1.21.4", stack.version());
            assertTrue(loaded.mismatchAllowed(), "the caller has to know to warn about it");
        }
    }

    /**
     * The arrangement from the docs: a resource pack listed above a client jar. The pack cannot be a base, so
     * it has to end up as an overlay over the jar without the admin saying which is which.
     */
    @Test
    void incompletePackBecomesAnOverlayOverTheJar() throws IOException {
        String texture = AssetStack.BLOCK_TEXTURES + "stone.png";
        Zips.write(assets().resolve("my-pack.zip"), Map.of(texture, "pack-stone"));
        Zips.write(assets().resolve("26.2.jar"), Zips.completeBase("26.2"));

        AssetResolver.Resolution resolution = AssetResolver.resolve(request(List.of("my-pack.zip", "26.2.jar"), false));

        AssetResolver.Resolution.Loaded loaded = assertInstanceOf(AssetResolver.Resolution.Loaded.class, resolution);
        try (AssetStack stack = loaded.stack()) {
            assertEquals("pack-stone", Zips.text(stack.read(texture)), "the pack has to win where it has a texture");
            assertEquals("dirt-png", Zips.text(stack.read(AssetStack.BLOCK_TEXTURES + "dirt.png")), "and vanilla fills in the rest");
            assertEquals(List.of("my-pack.zip", "26.2.jar"), stack.layerNames());
        }
    }

    /** Order in config.yml decides priority, so the same two files listed the other way round must swap. */
    @Test
    void packOrderDecidesPriority() throws IOException {
        String texture = AssetStack.BLOCK_TEXTURES + "stone.png";
        Zips.write(assets().resolve("a.zip"), Map.of(texture, "from-a"));
        Zips.write(assets().resolve("b.zip"), Map.of(texture, "from-b"));
        Zips.write(assets().resolve("26.2.jar"), Zips.completeBase("26.2"));

        AssetResolver.Resolution first = AssetResolver.resolve(request(List.of("a.zip", "b.zip", "26.2.jar"), false));
        try (AssetStack stack = assertInstanceOf(AssetResolver.Resolution.Loaded.class, first).stack()) {
            assertEquals("from-a", Zips.text(stack.read(texture)));
        }

        AssetResolver.Resolution second = AssetResolver.resolve(request(List.of("b.zip", "a.zip", "26.2.jar"), false));
        try (AssetStack stack = assertInstanceOf(AssetResolver.Resolution.Loaded.class, second).stack()) {
            assertEquals("from-b", Zips.text(stack.read(texture)));
        }
    }

    @Test
    void namedButAbsentPackIsBroken() {
        AssetResolver.Resolution resolution = AssetResolver.resolve(request(List.of("not-here.jar"), false));

        AssetResolver.Resolution.Broken broken = assertInstanceOf(AssetResolver.Resolution.Broken.class, resolution);
        assertTrue(broken.detail().contains("not-here.jar"), "the message has to name the file: " + broken.detail());
    }

    /** The common way an admin-supplied file is wrong: an unzipped folder, or a half-copied jar. */
    @Test
    void unreadableZipIsBroken() throws IOException {
        Files.createDirectories(assets());
        Files.write(assets().resolve("truncated.jar"), "this is not a zip".getBytes(StandardCharsets.UTF_8));

        AssetResolver.Resolution resolution = AssetResolver.resolve(request(List.of("truncated.jar"), false));

        AssetResolver.Resolution.Broken broken = assertInstanceOf(AssetResolver.Resolution.Broken.class, resolution);
        assertTrue(broken.detail().contains("truncated.jar"));
        assertTrue(broken.fix().contains("not unzipped"), "the fix should say what shape the file is meant to be");
    }

    /** A pack complete enough to be a base but with no version.json cannot be version-checked, so it is not one. */
    @Test
    void completePackWithoutAVersionIsBroken() throws IOException {
        Map<String, String> noVersion = Zips.completeBase("26.2");
        noVersion.remove("version.json");
        Zips.write(assets().resolve("mystery.zip"), noVersion);

        AssetResolver.Resolution resolution = AssetResolver.resolve(request(List.of("mystery.zip"), false));

        AssetResolver.Resolution.Broken broken = assertInstanceOf(AssetResolver.Resolution.Broken.class, resolution);
        assertTrue(broken.detail().contains("version.json"));
    }

    /** An admin jar beats the cache, so pinning a version by supplying it actually pins it. */
    @Test
    void adminBaseIsPreferredOverTheCache() throws IOException {
        Map<String, String> cached = Zips.cachedBase("26.2");
        cached.put(AssetStack.BLOCK_TEXTURES + "stone.png", "cached-stone");
        Zips.write(cache().resolve("26.2.zip"), cached);

        Map<String, String> supplied = Zips.completeBase("26.2");
        supplied.put(AssetStack.BLOCK_TEXTURES + "stone.png", "supplied-stone");
        Zips.write(assets().resolve("26.2.jar"), supplied);

        AssetResolver.Resolution resolution = AssetResolver.resolve(request(List.of("26.2.jar"), false));

        try (AssetStack stack = assertInstanceOf(AssetResolver.Resolution.Loaded.class, resolution).stack()) {
            assertEquals("supplied-stone", Zips.text(stack.read(AssetStack.BLOCK_TEXTURES + "stone.png")));
            assertEquals(List.of("26.2.jar"), stack.layerNames());
        }
    }
}
