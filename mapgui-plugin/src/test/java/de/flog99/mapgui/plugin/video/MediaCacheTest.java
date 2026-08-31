package de.flog99.mapgui.plugin.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The caps and the pruning, which are what stop a plugin's download button from being a way to fill a disk.
 *
 * <p>No network here - the transfer itself cannot be tested offline. What can be, and is what actually protects
 * the server, is the arithmetic around it: which file a url becomes, when a size is refused, and what gets
 * deleted to make room.
 */
class MediaCacheTest {

    private static final int KB = 1024;

    @TempDir
    Path folder;

    @Test
    void oneUrlIsAlwaysTheSameFile() {
        String url = "https://example.com/trailer.mp4";

        assertEquals(MediaCache.nameFor(url), MediaCache.nameFor(url), "the name is the cache key");
        assertNotEquals(MediaCache.nameFor(url), MediaCache.nameFor(url + "?v=2"));
    }

    @Test
    void keepsTheExtensionForSomebodyLookingInTheFolder() {
        assertTrue(MediaCache.nameFor("https://example.com/trailer.mp4").endsWith(".mp4"));
        assertTrue(MediaCache.nameFor("https://example.com/clip.webm?token=abc").endsWith(".webm"),
                "the query is not part of the path");
        assertTrue(MediaCache.nameFor("https://example.com/videoplayback").endsWith(".media"));
        assertTrue(MediaCache.nameFor("https://example.com/a.b.c/videoplayback").endsWith(".media"),
                "a dot in a directory name is not an extension");
    }

    @Test
    void refusesAFileOverTheCapAndSaysWhich() {
        MediaCache cache = new MediaCache(folder, 8, 64);

        assertNull(cache.refuse(4L * 1024 * 1024));
        assertNull(cache.refuse(-1), "a server that states no length is not a refusal on its own");

        String refusal = cache.refuse(9L * 1024 * 1024);
        assertTrue(refusal != null && refusal.contains("max-file-mb"), "a refusal has to name the cap: " + refusal);
    }

    @Test
    void prunesTheLeastRecentlyUsedFirst() throws IOException {
        // 2 MB of budget, 2100 KB in three clips. Dropping one leaves 1400, which is room for 600 more.
        MediaCache cache = new MediaCache(folder, 1, 2);
        Path oldest = clip("oldest.mp4", 700 * KB, 3);
        Path older = clip("older.mp4", 700 * KB, 2);
        Path newest = clip("newest.mp4", 700 * KB, 1);

        assertTrue(cache.makeRoom(600 * KB), "one of three has to be enough to fit a fourth");
        assertFalse(Files.exists(oldest), "the clip nobody has shown for longest is the one to go");
        assertTrue(Files.exists(older), "and only as many as it takes");
        assertTrue(Files.exists(newest));
    }

    @Test
    void deletesNothingWhenTheBudgetCouldNotHoldItEvenEmpty() throws IOException {
        MediaCache cache = new MediaCache(folder, 1, 2);
        Path kept = clip("kept.mp4", 700 * KB, 1);

        assertFalse(cache.makeRoom(3L * 1024 * 1024), "3 MB does not fit in a 2 MB cache, empty or not");
        assertTrue(Files.exists(kept), "refusing must not cost somebody the clip they had");
    }

    @Test
    void leavesADownloadInFlightAlone() throws IOException {
        MediaCache cache = new MediaCache(folder, 1, 2);
        Path inFlight = clip("download123.part", 900 * KB, 5);
        Path clip = clip("clip.mp4", 1300 * KB, 1);

        assertTrue(cache.makeRoom(900 * KB));
        assertTrue(Files.exists(inFlight), "a .part file belongs to a transfer that is still running");
        assertFalse(Files.exists(clip), "so the finished clip is what has to go instead");
    }

    @Test
    void anEmptyCacheNeedsNoRoomMade() throws IOException {
        assertTrue(new MediaCache(folder, 1, 2).makeRoom(500 * KB), "nothing to prune is not a failure");
    }

    /** A file of {@code bytes} in the cache directory, last used {@code daysAgo} days ago. */
    private Path clip(String name, int bytes, int daysAgo) throws IOException {
        Path directory = Files.createDirectories(folder.resolve("media"));
        Path file = directory.resolve(name);
        Files.write(file, new byte[bytes]);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(daysAgo, ChronoUnit.DAYS)));
        return file;
    }
}
