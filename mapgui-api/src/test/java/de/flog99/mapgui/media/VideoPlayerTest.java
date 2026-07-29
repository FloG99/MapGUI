package de.flog99.mapgui.media;

import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.Surface;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VideoPlayerTest {

    /** Frames whose every pixel is its own position, so a scale can be read straight off the surface. */
    private static Frames counting(int width, int height, int count) {
        return new Frames() {
            @Override
            public int width() {
                return width;
            }

            @Override
            public int height() {
                return height;
            }

            @Override
            public int count() {
                return count;
            }

            @Override
            public int durationMs() {
                return count * 100;
            }

            @Override
            public int indexAt(int millis) {
                return Math.floorMod(millis / 100, count);
            }

            @Override
            public byte[] pixels(int index) {
                byte[] pixels = new byte[width * height];
                // One-based, so nought means nothing was drawn there.
                for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) (index * 10 + i + 1);
                return pixels;
            }
        };
    }

    private static final class Recording implements Surface {
        final byte[] pixels;
        final int width;
        final int height;

        Recording(int width, int height) {
            this.width = width;
            this.height = height;
            this.pixels = new byte[width * height];
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public void set(int x, int y, byte color) {
            pixels[x + y * width] = color;
        }

        @Override
        public byte get(int x, int y) {
            return pixels[x + y * width];
        }

        byte[] row(int y) {
            byte[] row = new byte[width];
            System.arraycopy(pixels, y * width, row, 0, width);
            return row;
        }
    }

    /** A video draws palette indices straight through, so neither the palette nor the font is touched. */
    private static Painter painterOn(Recording surface) {
        return new Painter(surface, null, null);
    }

    @Test
    void stretchFillsTheBoxAndDoublesEveryPixel() {
        Recording surface = new Recording(4, 4);
        VideoPlayer video = new VideoPlayer(counting(2, 2, 1)).fit(VideoPlayer.Fit.STRETCH);

        video.paint(painterOn(surface), new Rect(0, 0, 4, 4), 0);

        assertArrayEquals(new byte[]{1, 1, 2, 2}, surface.row(0));
        assertArrayEquals(new byte[]{1, 1, 2, 2}, surface.row(1));
        assertArrayEquals(new byte[]{3, 3, 4, 4}, surface.row(2));
        assertArrayEquals(new byte[]{3, 3, 4, 4}, surface.row(3));
    }

    /** The letterbox has to be left alone rather than painted black, so a background shows through. */
    @Test
    void containCentersTheWholePictureAndLeavesTheGap() {
        Recording surface = new Recording(4, 4);
        VideoPlayer video = new VideoPlayer(counting(4, 2, 1));

        video.paint(painterOn(surface), new Rect(0, 0, 4, 4), 0);

        assertArrayEquals(new byte[]{0, 0, 0, 0}, surface.row(0), "untouched above");
        assertArrayEquals(new byte[]{1, 2, 3, 4}, surface.row(1));
        assertArrayEquals(new byte[]{5, 6, 7, 8}, surface.row(2));
        assertArrayEquals(new byte[]{0, 0, 0, 0}, surface.row(3), "untouched below");
    }

    /** Cover overhangs the box on purpose; the painter's clip is what stops it escaping the node. */
    @Test
    void coverFillsTheBoxAndCropsTheOverhang() {
        Recording surface = new Recording(4, 4);
        VideoPlayer video = new VideoPlayer(counting(2, 4, 1)).fit(VideoPlayer.Fit.COVER);
        Painter painter = painterOn(surface);
        painter.pushClip(new Rect(0, 0, 4, 4));

        video.paint(painter, new Rect(0, 0, 4, 4), 0);

        for (int y = 0; y < 4; y++) {
            for (byte pixel : surface.row(y)) {
                assertFalse(pixel == 0, "cover must leave no gap");
                assertFalse(pixel == 1 || pixel == 2, "the top of the picture is cropped away");
            }
        }
        assertArrayEquals(new byte[]{3, 3, 4, 4}, surface.row(0));
        assertArrayEquals(new byte[]{5, 5, 6, 6}, surface.row(3));
    }

    @Test
    void theFrameFollowsTheClockAndLoops() {
        VideoPlayer video = new VideoPlayer(counting(1, 1, 3)).fit(VideoPlayer.Fit.STRETCH);

        assertEquals(1, drawOne(video, 0));
        assertEquals(11, drawOne(video, 150));
        assertEquals(21, drawOne(video, 250));
        assertEquals(1, drawOne(video, 300), "wraps rather than running off the end");
    }

    private static byte drawOne(VideoPlayer video, int millis) {
        Recording surface = new Recording(1, 1);
        video.paint(painterOn(surface), new Rect(0, 0, 1, 1), millis);
        return surface.get(0, 0);
    }
}
