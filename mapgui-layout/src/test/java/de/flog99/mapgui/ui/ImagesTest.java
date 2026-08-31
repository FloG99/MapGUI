package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ImagesTest {

    private static final String QUAD = "de/flog99/mapgui/ui/quad.png";

    @Test
    void readsAnImageFromTheClasspath() {
        BufferedImage image = Images.of(QUAD);

        assertEquals(2, image.getWidth());
        assertEquals(2, image.getHeight());
        assertEquals(0xFFFF0000, image.getRGB(0, 0));
        assertEquals(0xFF0000FF, image.getRGB(0, 1));
    }

    /** Decoded once, so a screen rebuilt every frame is not decoding a PNG every frame. */
    @Test
    void decodesOnlyOnce() {
        assertSame(Images.of(QUAD), Images.of(QUAD));
    }

    /** A path written the way {@code Class#getResourceAsStream} wants it is the same path. */
    @Test
    void ignoresALeadingSlash() {
        assertSame(Images.of(QUAD), Images.of("/" + QUAD));
    }

    @Test
    void missingArtIsNullRatherThanAThrow() {
        assertNull(Images.of("de/flog99/mapgui/ui/nothing-is-here.png"));
    }

    @Test
    void generatedArtIsCachedUnderItsName() {
        AtomicInteger made = new AtomicInteger();
        BufferedImage first = Images.of("generated/dial", () -> {
            made.incrementAndGet();
            return new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB);
        });

        assertSame(first, Images.of("generated/dial", () -> {
            made.incrementAndGet();
            return null;
        }));
        assertEquals(1, made.get());
    }

    /** A miss is remembered too, or a typo would cost a failed lookup on every frame forever. */
    @Test
    void nothingIsRememberedAsNothing() {
        AtomicInteger made = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertNull(Images.of("generated/absent", () -> {
                made.incrementAndGet();
                return null;
            }));
        }
        assertEquals(1, made.get());
    }
}
