package de.flog99.mapgui.plugin.video;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which urls are pages, and when a resolved one has to be replaced.
 *
 * <p>The expiry arithmetic is the part of phase 6 that cannot be caught by trying it: a signed url lasts hours,
 * so a test that plays something for five minutes proves nothing at all about the wall that goes black at three
 * in the morning. So it is arithmetic here rather than a hope.
 */
class StreamResolverTest {

    @Test
    void aPageUrlIsAnythingHttpThatIsNotAlreadyMedia() {
        assertTrue(StreamResolver.isPageUrl("https://www.youtube.com/watch?v=aqz-KE-bpKQ"));
        assertTrue(StreamResolver.isPageUrl("https://www.twitch.tv/somebody"));
        assertTrue(StreamResolver.isPageUrl("https://vimeo.com/12345"));
    }

    @Test
    void mediaUrlsAreLeftAlone() {
        assertFalse(StreamResolver.isPageUrl("https://example.com/clip.mp4"));
        assertFalse(StreamResolver.isPageUrl("https://example.com/live/index.m3u8"));
        assertFalse(StreamResolver.isPageUrl("https://example.com/CLIP.MP4"), "extensions come in both cases");
        // The query is not the path: a signed url ends in whatever the CDN felt like.
        assertFalse(StreamResolver.isPageUrl("https://example.com/clip.mp4?expire=123&sig=abc"));
    }

    @Test
    void nothingButHttpIsEverAPage() {
        assertFalse(StreamResolver.isPageUrl("rtsp://10.0.0.5:554/stream1"));
        assertFalse(StreamResolver.isPageUrl("rtmp://example.com/live/key"));
        assertFalse(StreamResolver.isPageUrl("udp://239.0.0.1:1234"));
        assertFalse(StreamResolver.isPageUrl("/srv/minecraft/plugins/MapGUI/videos/trailer.mp4"));
        assertFalse(StreamResolver.isPageUrl("C:\\videos\\trailer.mp4"));
    }

    @Test
    void readsYouTubesOwnDeadlineOffTheUrl() {
        Instant now = Instant.parse("2026-08-31T12:00:00Z");
        long expires = now.plus(Duration.ofHours(6)).getEpochSecond();

        Instant read = StreamResolver.expiryOf(
                "https://rr3---sn-x.googlevideo.com/videoplayback?expire=" + expires + "&itag=18", now);
        assertEquals(Instant.ofEpochSecond(expires), read);
    }

    @Test
    void readsACdnsDeadlineTheSameWay() {
        Instant now = Instant.parse("2026-08-31T12:00:00Z");
        long expires = now.plus(Duration.ofHours(2)).getEpochSecond();

        assertEquals(Instant.ofEpochSecond(expires),
                StreamResolver.expiryOf("https://cdn.example.com/x.m3u8?Expires=" + expires + "&Signature=y", now));
    }

    @Test
    void aUrlThatSaysNothingGetsAShortLease() {
        Instant now = Instant.parse("2026-08-31T12:00:00Z");

        Instant assumed = StreamResolver.expiryOf("https://example.com/live/index.m3u8", now);
        assertTrue(assumed.isAfter(now), "a lease in the past would refresh in a loop");
        assertTrue(assumed.isBefore(now.plus(Duration.ofHours(2))),
                "assuming a signed url is good for hours is how a wall goes black overnight");
    }

    @Test
    void ignoresANumberThatIsPlainlyNotAnExpiry() {
        Instant now = Instant.parse("2026-08-31T12:00:00Z");
        Instant lease = StreamResolver.expiryOf("https://example.com/v?expire=1", now);

        assertTrue(lease.isAfter(now), "an expiry in 1970 is not an expiry");
        assertEquals(StreamResolver.expiryOf("https://example.com/v", now), lease);
    }

    @Test
    void reconnectsBeforeTheUrlLapsesRatherThanAfter() {
        Instant expires = Instant.now().plus(Duration.ofHours(4));

        Instant refresh = ResolvingSource.refreshAt(expires);
        assertTrue(refresh.isBefore(expires), "reconnecting after expiry is reconnecting after the wall went black");
        // Two minutes of margin, which is a resolve and a connection on a slow link.
        assertTrue(Duration.between(refresh, expires).toSeconds() >= 110);
        assertTrue(Duration.between(refresh, expires).toMinutes() <= 3);
    }

    @Test
    void aShortLeaseGetsAProportionalMarginRatherThanTheWholeThing() {
        Instant expires = Instant.now().plus(Duration.ofMinutes(10));

        Instant refresh = ResolvingSource.refreshAt(expires);
        assertTrue(refresh.isAfter(Instant.now()), "a two minute margin on a ten minute lease is fine, but on a"
                + " one minute lease it would refresh immediately and forever");
        assertTrue(Duration.between(Instant.now(), refresh).toSeconds() >= 8 * 60);
    }

    /**
     * "In the future" is not enough, which is what the earlier version of this test asked for: three seconds from
     * now is in the future, and it is also a yt-dlp process and a fresh connection every three seconds, per wall,
     * for as long as the wall is up.
     */
    @Test
    void aVeryShortLeaseIsFlooredRatherThanChased() {
        Instant refresh = ResolvingSource.refreshAt(Instant.now().plus(Duration.ofSeconds(30)));

        assertTrue(Duration.between(Instant.now(), refresh).toSeconds() >= 55,
                "a lease this machine's clock thinks is nearly spent is a clock to distrust, not a deadline");
    }

    @Test
    void anExpiryAlreadyPastIsAlsoFloored() {
        // A clock running ahead is how this arrives: the url's own deadline looks spent the moment it resolves.
        Instant refresh = ResolvingSource.refreshAt(Instant.now().minus(Duration.ofHours(1)));

        assertTrue(Duration.between(Instant.now(), refresh).toSeconds() >= 55,
                "refreshing immediately on a past expiry is an unbounded resolve loop");
    }

    @Test
    void somethingWithNoExpiryIsNeverReconnectedForNoReason() {
        // A file, an rtsp camera, an HLS playlist: no deadline, so a refresh would drop frames on purpose.
        assertEquals(Instant.MAX, ResolvingSource.refreshAt(null));
    }
}
