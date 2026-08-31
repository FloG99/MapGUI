package de.flog99.mapgui.plugin.video;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the toolchain that can be held to anything without a network: which asset a machine is owed, and
 * the two pieces of text github answers with.
 *
 * <p>A wrong asset is not a bug that shows up here - it is a binary that will not start on somebody else's
 * server, and a musl system is the case nobody has to hand. So the mapping is tested rather than trusted.
 */
class ToolchainTest {

    @Test
    void namesEveryPlatformTheAssetsAreNamedFor() {
        assertEquals("windows-x86_64", Toolchain.platform("Windows 11", "amd64"));
        assertEquals("windows-x86", Toolchain.platform("Windows Server 2019", "x86"));
        assertEquals("linux-x86_64", Toolchain.platform("Linux", "x86_64"));
        assertEquals("linux-aarch64", Toolchain.platform("Linux", "aarch64"));
        assertEquals("linux-armv7", Toolchain.platform("Linux", "arm"));
        assertEquals("linux-riscv64", Toolchain.platform("Linux", "riscv64"));
        // Only macOS spells it arm64, and only because that is how its assets are named.
        assertEquals("darwin-arm64", Toolchain.platform("Mac OS X", "aarch64"));
        assertEquals("darwin-x86_64", Toolchain.platform("Mac OS X", "x86_64"));
    }

    @Test
    void aPlatformNothingIsPublishedForIsNamedRatherThanGuessedAt() {
        assertEquals("-", Toolchain.platform("AIX", "ppc64"));
        assertNull(Toolchain.ytdlpAsset(Toolchain.platform("AIX", "ppc64"), false),
                "an unknown platform has to come back as nothing to fetch, not as somebody else's binary");
    }

    @Test
    void picksTheMuslBuildOnAlpine() {
        assertEquals("yt-dlp_linux", Toolchain.ytdlpAsset("linux-x86_64", false));
        assertEquals("yt-dlp_musllinux", Toolchain.ytdlpAsset("linux-x86_64", true));
        assertEquals("yt-dlp_musllinux_aarch64", Toolchain.ytdlpAsset("linux-aarch64", true));
    }

    @Test
    void muslChangesNothingWhereThereIsNoMuslBuild() {
        // No musl asset exists for these, and a glibc build is what an Alpine armv7 or Windows would get anyway.
        assertEquals("yt-dlp_linux_armv7l.zip", Toolchain.ytdlpAsset("linux-armv7", true));
        assertEquals("yt-dlp.exe", Toolchain.ytdlpAsset("windows-x86_64", true));
    }

    @Test
    void macIsOneAssetForBothArchitectures() {
        assertEquals(Toolchain.ytdlpAsset("darwin-x86_64", false), Toolchain.ytdlpAsset("darwin-arm64", false));
    }

    @Test
    void readsTheReleaseTagOffTheRedirect() throws IOException {
        assertEquals("2026.08.20", Toolchain.tagFromLocation(
                "https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.20"));
    }

    @Test
    void refusesARedirectItCannotReadATagFrom() {
        assertThrows(IOException.class, () -> Toolchain.tagFromLocation(""));
        assertThrows(IOException.class, () -> Toolchain.tagFromLocation("nothing-like-a-url"));
        assertThrows(IOException.class, () -> Toolchain.tagFromLocation("https://github.com/releases/tag/"));
    }

    @Test
    void findsOneAssetInAChecksumFile() throws IOException {
        String sums = """
                a11f0e4f1a01d3fb2fa5c9d6f6f4a2b0c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3  yt-dlp
                b22f0e4f1a01d3fb2fa5c9d6f6f4a2b0c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3  yt-dlp.exe
                c33f0e4f1a01d3fb2fa5c9d6f6f4a2b0c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3  yt-dlp_musllinux
                """;

        assertTrue(Toolchain.checksumFor(sums, "yt-dlp.exe").startsWith("b22f"));
        assertTrue(Toolchain.checksumFor(sums, "yt-dlp_musllinux").startsWith("c33f"));
        // Not a prefix match: yt-dlp is a prefix of every other name in that file.
        assertTrue(Toolchain.checksumFor(sums, "yt-dlp").startsWith("a11f"));
    }

    @Test
    void refusesWhenNoChecksumWasPublishedForTheAssetItWants() {
        String sums = "a11f  yt-dlp\n";

        IOException missing = assertThrows(IOException.class, () -> Toolchain.checksumFor(sums, "yt-dlp_linux"));
        assertTrue(missing.getMessage().contains("yt-dlp_linux"));
    }

    @Test
    void handlesTheLineEndingsGithubActuallySends() throws IOException {
        assertEquals("a11f", Toolchain.checksumFor("a11f  yt-dlp\r\nb22f  yt-dlp.exe\r\n", "yt-dlp"));
    }
}
