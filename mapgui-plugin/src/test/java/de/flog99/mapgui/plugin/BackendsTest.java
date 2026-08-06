package de.flog99.mapgui.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which backend a server version asks for.
 *
 * <p>Worth pinning because getting it wrong is not subtle - MapGUI refuses to enable - and because the rule
 * only starts to matter on the day a second version module exists, which is exactly when nobody will remember
 * what it was.
 */
class BackendsTest {

    @Test
    void aPatchReleaseIsItsMinorVersionsFamily() {
        assertEquals("26.2", Backends.family("26.2.1"));
        assertEquals("26.2", Backends.family("26.2.11"));
        assertEquals("26.2", Backends.family("26.2"));
    }

    @Test
    void adifferentMinorIsAdifferentFamily() {
        assertEquals("26.3", Backends.family("26.3"));
        assertEquals("27.1", Backends.family("27.1.4"));
    }

    /** Nothing says a version has to have two parts, and a snapshot name has none at all. */
    @Test
    void anythingShorterIsItsOwnFamily() {
        assertEquals("26", Backends.family("26"));
        assertEquals("", Backends.family(""));
        assertEquals("26w14a", Backends.family("26w14a"));
    }
}
