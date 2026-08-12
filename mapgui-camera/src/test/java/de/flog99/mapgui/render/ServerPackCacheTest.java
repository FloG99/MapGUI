package de.flog99.mapgui.render;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Following a pack the server hands its players, over a real HTTP server on a real socket.
 *
 * <p>Worth the socket: what is being tested is a download, and a fake of one would test the wrong half. The
 * pieces that can be wrong here - a hash that does not match, the same pack asked for twice, a URL that answers
 * with 404 - are all things a live server does routinely and none of them may cost a capture.
 */
class ServerPackCacheTest {

    @TempDir
    Path dir;

    private HttpServer http;
    private byte[] served;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void serve() throws IOException {
        served = Files.readAllBytes(Zips.write(dir.resolve("source.zip"),
                Map.of("assets/yourpack/textures/item/thing.png", "the server's own look")));

        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/pack.zip", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, served.length);
            try (var body = exchange.getResponseBody()) {
                body.write(served);
            }
        });
        http.start();
    }

    @AfterEach
    void stop() {
        http.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/pack.zip";
    }

    private String sha1() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(served));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private ServerPackCache cache() {
        return new ServerPackCache(dir.resolve("cache"));
    }

    @Test
    void aPackIsFetchedAndKeptUnderItsOwnHash() throws IOException {
        ServerPackCache cache = cache();

        Path stored = cache.fetch(url(), sha1());

        assertEquals(sha1() + ".zip", stored.getFileName().toString(),
                "the name is the cache key, which is what makes the same pack free the second time");
        assertTrue(cache.has(sha1()));
        assertEquals(1, cache.stored().size());

        try (AssetPack pack = AssetPack.open(stored)) {
            assertTrue(pack.has("assets/yourpack/textures/item/thing.png"),
                    "and what landed is a readable zip, not a truncated one");
        }
    }

    /** Fifty players are sent the same pack, and a restart asks again. Neither should cost a download. */
    @Test
    void theSamePackIsNotDownloadedTwice() throws IOException {
        cache().fetch(url(), sha1());
        cache().fetch(url(), sha1());

        assertEquals(1, requests.get());
    }

    /**
     * Bytes that are not the ones the players got are worse than no pack at all.
     *
     * <p>The whole point of following the server's pack is to draw what its players are drawing, so a pack that
     * fails the check the client itself makes has to fail here too rather than be layered anyway.
     */
    @Test
    void aPackThatDoesNotMatchTheStatedHashIsRefused() {
        ServerPackCache cache = cache();
        String wrong = "0".repeat(40);

        IOException refused = assertThrows(IOException.class, () -> cache.fetch(url(), wrong));

        assertTrue(refused.getMessage().contains("expect"), refused.getMessage());
        assertFalse(cache.has(wrong));
        assertEquals(0, cache.stored().size(), "and nothing is left behind for the resolver to find");
    }

    /** A pack pushed without a hash is allowed, and named by what actually arrived. */
    @Test
    void aPackWithNoStatedHashIsNamedByItsContents() throws IOException {
        Path stored = cache().fetch(url(), "");

        assertEquals(sha1() + ".zip", stored.getFileName().toString());
    }

    /**
     * A pack handed over directly lands on the same shelf as a fetched one, and only writes when it changes.
     *
     * <p>The second call is what a plugin does on every restart for the rest of its life, so it has to be free
     * and it has to not trigger a reload of the whole stack.
     */
    @Test
    void aPackHandedOverIsKeptOnceAndRecognisedAfter() throws IOException {
        ServerPackCache cache = cache();

        ServerPackCache.Stored first = cache.keep(served);
        ServerPackCache.Stored again = cache.keep(served);

        assertTrue(first.fresh(), "the first time is a write");
        assertFalse(again.fresh(), "and every startup after it is not");
        assertEquals(first.zip(), again.zip());
        assertEquals(sha1() + ".zip", first.zip().getFileName().toString());
        assertEquals(1, cache.stored().size());
    }

    /** A plugin shipping a new version of its pack keeps it beside the old one, which ages out on its own. */
    @Test
    void aChangedPackIsKeptBesideTheOldOne() throws IOException {
        ServerPackCache cache = cache();
        cache.keep(served);

        assertTrue(cache.keep("a different pack entirely".getBytes()).fresh());
        assertEquals(2, cache.stored().size(), "pruned by age rather than deleted on sight");
    }

    /**
     * A plugin that has shipped its pack many times gets its newest one layered, and only a handful in total.
     *
     * <p>Both halves have been wrong. Layering went by file name, which is by hash, which is at random - so one of
     * the stale copies won and captures drew a texture the plugin had stopped shipping. And the cap was left to
     * pruning alone, which cannot delete a file the stack has open, so a session's worth of them piled up.
     */
    @Test
    void theNewestPacksAreLayeredNewestFirst() throws IOException {
        ServerPackCache cache = cache();
        Path newest = null;

        for (int version = 0; version < 9; version++) {
            Path kept = cache.keep(("pack version " + version).getBytes()).zip();
            // Stamped rather than slept for: the ordering is by age, and nine writes inside one millisecond is
            // exactly the tie that would make this pass by luck.
            Files.setLastModifiedTime(kept, java.nio.file.attribute.FileTime.fromMillis(1_000_000 + version * 1000L));
            newest = kept;
        }

        assertEquals(5, cache.stored().size(), "the newest handful, not everything ever kept");
        assertEquals(newest, cache.stored().getFirst(), "and the newest of those wins the layering");
    }

    @Test
    void aPackThatIsNotThereLeavesNothingBehind() {
        ServerPackCache cache = cache();
        String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/gone.zip";

        assertThrows(IOException.class, () -> cache.fetch(url, ""));
        assertEquals(0, cache.stored().size());
    }
}
