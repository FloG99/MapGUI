package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No {@code .ttf} is committed anywhere in this repository on purpose - a font is somebody's licensed work - so
 * what is pinned here is the loading and the caching around it, which is what {@link Fonts} exists to own.
 */
class FontsTest {

    /** A face cached per path and size, or the field every caller writes today would still be doing the work. */
    @Test
    void aPathAndSizeIsLoadedOnce() {
        assertSame(Fonts.trueType("font/nothing.ttf", 12f), Fonts.trueType("font/nothing.ttf", 12f));
    }

    /** And per size, since the same file at two sizes is two sets of glyphs. */
    @Test
    void aSecondSizeIsASecondFace() {
        assertNotSame(Fonts.trueType("font/nothing.ttf", 12f), Fonts.trueType("font/nothing.ttf", 18f));
    }

    @Test
    void aLeadingSlashIsTheSamePath() {
        assertSame(Fonts.trueType("font/nothing.ttf", 14f), Fonts.trueType("/font/nothing.ttf", 14f));
    }

    /** A face that cannot be read still measures text, because a screen that threw while building draws nothing. */
    @Test
    void anUnreadableFaceFallsBackRatherThanThrowing() {
        TextFont font = Fonts.trueType("font/nothing.ttf", 16f);

        assertTrue(font.lineHeight() > 0);
        assertTrue(font.widthOf("Gallery") > 0);
    }

    /** A stream has no identity to key on, so what was in it is the key. */
    @Test
    void aStreamIsCachedByWhatWasInIt() {
        byte[] content = "not a font, but the same not-a-font twice".getBytes(StandardCharsets.UTF_8);

        TextFont first = Fonts.trueType(new ByteArrayInputStream(content), 11f);
        TextFont second = Fonts.trueType(new ByteArrayInputStream(content), 11f);

        assertSame(first, second);
        assertNotSame(first, Fonts.trueType(new ByteArrayInputStream("something else".getBytes(StandardCharsets.UTF_8)), 11f));
    }
}
