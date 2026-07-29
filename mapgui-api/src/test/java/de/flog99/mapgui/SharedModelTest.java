package de.flog99.mapgui;

import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.Ui;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedModelTest {

    /** Stands in for anything shared - a picture, a claim map, a queue. */
    private static final class Model extends SharedModel {
        void change() {
            changed();
        }
    }

    private static final class Blank extends Screen {
        @Override
        protected Node build() {
            return Ui.Spacer();
        }
    }

    private final Model model = new Model();

    /** A screen starts dirty, so laying it out first is the only way to see the change arrive. */
    private static Blank drawn() {
        Blank screen = new Blank();
        screen.layout(MapTextFont.INSTANCE, new Rect(0, 0, 128, 128));
        assertFalse(screen.isDirty(), "laying out should have settled it");
        return screen;
    }

    @Test
    void aChangeMarksEveryWatchingScreen() {
        Blank one = drawn();
        Blank two = drawn();
        one.watch(model);
        two.watch(model);

        model.change();

        assertTrue(one.isDirty());
        assertTrue(two.isDirty(), "the screen that was not clicked is the whole point");
    }

    @Test
    void aScreenThatIsNotWatchingIsLeftAlone() {
        Blank screen = drawn();

        model.change();

        assertFalse(screen.isDirty());
    }

    /** What replaces unregistering by hand, and what stops a model holding a closed screen forever. */
    @Test
    void closingStopsTheWatching() {
        Blank screen = drawn();
        screen.watch(model);
        screen.detach();

        model.change();

        assertFalse(screen.isDirty(), "still being told about a model after closing");
    }

    @Test
    void watchingTwiceIsHarmless() {
        Blank screen = drawn();
        screen.watch(model);
        screen.watch(model);
        screen.detach();

        model.change();

        assertFalse(screen.isDirty(), "the second watch outlived the screen");
    }
}
