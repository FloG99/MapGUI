package de.flog99.mapgui.plugin.video;

import de.flog99.mapgui.media.Frames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of still decoding that needs nothing installed. FFmpeg is not on the test classpath - it is
 * downloaded at runtime and only when asked for - so what is held here is that the formats which never needed it
 * still do not, and that the ones which do say so.
 */
class StillImageTest {

    @TempDir
    Path folder;

    @Test
    void readsAPngWithoutFfmpeg() throws IOException {
        Path file = png(64, 32);

        Frames read = StillImage.read(file, 128);
        assertEquals(1, read.count(), "a still is one frame");
        assertEquals(64, read.width());
        assertEquals(32, read.height());
        assertTrue(read.durationMs() > 0, "a clock mapped onto it has to divide by something");
        assertEquals(0, read.indexAt(12345));
    }

    @Test
    void keepsTransparencyTransparent() throws IOException {
        Frames read = StillImage.read(png(64, 32), 128);
        byte[] pixels = read.pixels(0);

        // png() paints the left half opaque red and leaves the right half alone.
        assertNotEquals(Frames.TRANSPARENT, pixels[0], "an opaque pixel must draw");
        assertEquals(Frames.TRANSPARENT, pixels[read.width() - 1], "a see-through pixel must not");
    }

    @Test
    void shrinksToTheLongestEdgeAndKeepsTheShape() throws IOException {
        Frames read = StillImage.read(png(400, 200), 128);

        assertEquals(128, read.width());
        assertEquals(64, read.height());
    }

    @Test
    void neverEnlarges() throws IOException {
        Frames read = StillImage.read(png(16, 16), 256);

        assertEquals(16, read.width(), "upscaling here would only cost memory - the player scales when it draws");
    }

    @Test
    void namesTheSettingWhenOnlyFfmpegCouldHaveReadIt() throws IOException {
        Path file = folder.resolve("logo.webp");
        Files.write(file, new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0});

        IOException refused = assertThrows(IOException.class, () -> StillImage.read(file, 128));
        assertTrue(refused.getMessage().contains("media.ffmpeg"), "the fix has to be in the message: " + refused.getMessage());
    }

    @Test
    void animationTimingComesFromTheDecodersOwnTimestamps() {
        byte[] pixels = new byte[1];
        Frames frames = MemoryFrames.timed(1, 1, List.of(pixels, pixels, pixels), new int[] {0, 100, 250}, 100);

        assertEquals(0, frames.indexAt(0));
        assertEquals(0, frames.indexAt(99));
        assertEquals(1, frames.indexAt(100));
        assertEquals(2, frames.indexAt(250));
        // The last frame lasts as long as the ones before it averaged, since nothing follows it to say.
        assertEquals(375, frames.durationMs());
        assertEquals(0, frames.indexAt(375), "and then it wraps");
    }

    @Test
    void aDecoderThatRepeatsATimestampStillProducesUsableFrames() {
        byte[] pixels = new byte[1];
        Frames frames = MemoryFrames.timed(1, 1, List.of(pixels, pixels), new int[] {40, 40}, 100);

        assertTrue(frames.durationMs() > 0, "a duration of zero would divide by zero in indexAt");
        assertEquals(0, frames.indexAt(0));
    }

    /** A picture with an opaque left half and a see-through right half, so both paths are exercised. */
    private Path png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width / 2; x++) {
                image.setRGB(x, y, 0xFFCC2222);
            }
        }
        Path file = folder.resolve(width + "x" + height + ".png");
        ImageIO.write(image, "png", file.toFile());
        return file;
    }
}
