package de.flog99.mapgui.plugin;

import de.flog99.mapgui.GuiCatalog;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.ui.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static de.flog99.mapgui.ui.Ui.Column;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalog behind {@code /mapgui hand open} and {@code /mapgui wall place}.
 *
 * <p>No server involved: nothing here gets as far as opening a GUI, so a factory can be handed a null player
 * and asked only whether it was called.
 */
class GuiCatalogImplTest {

    private static final class Blank extends Screen {

        @Override
        protected Node build() {
            return Column();
        }
    }

    /** What the real one does with this is close GUIs, which is why removal has to be announced. */
    private final List<String> removed = new ArrayList<>();
    private final GuiCatalogImpl catalog = new GuiCatalogImpl(removed::add);

    private void openable(String name) {
        catalog.registerOpenable(name, "something", player -> new Blank());
    }

    private void placeable(String name) {
        catalog.registerPlaceable(name, "something", wall -> {});
    }

    // ---- one name, one or both surfaces ----

    /**
     * The point of one catalog rather than two: adding the other surface builds up the same entry, so
     * {@code unregister} can take a name out of everywhere in one call.
     */
    @Test
    void bothSurfacesShareOneEntry() {
        openable("jukebox");
        placeable("jukebox");

        GuiCatalog.Entry entry = catalog.get("jukebox");
        assertNotNull(entry);
        assertTrue(entry.openable());
        assertTrue(entry.placeable());
        assertEquals(1, catalog.openable().size());
        assertEquals(1, catalog.placeable().size());
    }

    @Test
    void oneSurfaceOnlyIsListedInOnePlace() {
        openable("todo");
        placeable("draw");

        assertEquals(List.of("todo"), catalog.openable().stream().map(GuiCatalog.Entry::name).toList());
        assertEquals(List.of("draw"), catalog.placeable().stream().map(GuiCatalog.Entry::name).toList());
    }

    /** Loudly, because the alternative is one plugin quietly replacing another's GUI. */
    @Test
    void theSameSurfaceTwiceIsRefused() {
        openable("jukebox");

        assertThrows(IllegalArgumentException.class, () -> openable("jukebox"));
    }

    /** Registering the other surface must not quietly drop the one already there. */
    @Test
    void addingASurfaceKeepsTheOther() {
        openable("jukebox");
        placeable("jukebox");

        assertThrows(IllegalArgumentException.class, () -> openable("jukebox"));
        assertNotNull(catalog.get("jukebox").open());
    }

    // ---- factories ----

    /**
     * A factory rather than an instance, because state lives on the screen - one shared instance would show two
     * players each other's scroll position.
     */
    @Test
    void everyOpenGetsItsOwnScreen() {
        openable("blank");

        GuiCatalog.Entry entry = catalog.get("blank");
        assertNotSame(entry.open().apply(null), entry.open().apply(null));
    }

    /** The player is handed to the factory, since a GUI is allowed to need it. */
    @Test
    void theFactoryIsToldWhoItIsFor() {
        List<Object> asked = new ArrayList<>();
        catalog.registerOpenable("blank", "something", player -> {
            asked.add(player);
            return new Blank();
        });

        catalog.get("blank").open().apply(null);
        assertEquals(1, asked.size());
    }

    // ---- names ----

    /**
     * A dot would make a name indistinguishable from one of the video files, which is the one thing
     * {@code /mapgui wall place} relies on to take either without being told which.
     */
    @Test
    void namesThatCouldBeFilesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> openable("bunny.gif"));
        assertThrows(IllegalArgumentException.class, () -> openable("Jukebox"));
        assertThrows(IllegalArgumentException.class, () -> openable("my jukebox"));
        assertThrows(IllegalArgumentException.class, () -> openable(""));
        assertThrows(IllegalArgumentException.class, () -> openable("-leading"));
    }

    @Test
    void dashesAndDigitsAreFine() {
        openable("tv-2");
        placeable("shop_front");

        assertNotNull(catalog.get("tv-2"));
        assertNotNull(catalog.get("shop_front"));
    }

    /** It is what the command lists, so an empty one leaves an admin with a name and no idea what it is. */
    @Test
    void aDescriptionIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> catalog.registerOpenable("x", " ", player -> new Blank()));
        assertThrows(IllegalArgumentException.class, () -> catalog.registerPlaceable("x", null, wall -> {}));
    }

    // ---- removal ----

    @Test
    void unregisteringTakesOutBothSurfacesAtOnce() {
        openable("jukebox");
        placeable("jukebox");

        assertTrue(catalog.unregister("jukebox"));
        assertNull(catalog.get("jukebox"));
        assertTrue(catalog.openable().isEmpty());
        assertTrue(catalog.placeable().isEmpty());
    }

    @Test
    void unregisteringReportsWhetherThereWasAnything() {
        openable("jukebox");

        assertTrue(catalog.unregister("jukebox"));
        assertFalse(catalog.unregister("jukebox"));
    }

    /** What closes an unregistering plugin's GUIs while its classes are still loaded. */
    @Test
    void removalIsAnnouncedOnce() {
        openable("jukebox");

        catalog.unregister("jukebox");
        catalog.unregister("jukebox");

        assertEquals(List.of("jukebox"), removed);
    }

    @Test
    void aNameIsFreeAgainAfterUnregistering() {
        openable("jukebox");
        catalog.unregister("jukebox");

        openable("jukebox");
        assertNotNull(catalog.get("jukebox"));
    }

    /** Listed in registration order, so the command's output does not shuffle between restarts. */
    @Test
    void entriesKeepTheirOrder() {
        openable("one");
        openable("two");
        openable("three");

        assertEquals(List.of("one", "two", "three"), catalog.openable().stream().map(GuiCatalog.Entry::name).toList());
    }
}
