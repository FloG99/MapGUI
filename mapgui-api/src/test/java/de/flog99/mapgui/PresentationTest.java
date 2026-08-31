package de.flog99.mapgui;

import de.flog99.mapgui.prompt.TextPrompt;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.Surface;
import de.flog99.mapgui.ui.TextField;
import de.flog99.mapgui.ui.TextFont;
import de.flog99.mapgui.ui.Theme;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static de.flog99.mapgui.ui.Ui.Column;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The resolution order behind {@link Screen#theme()} and its three siblings: what the caller asked for, then
 * what the screen prefers.
 *
 * <p>All four are final, so this is also the pin on the thing that made them final - a screen cannot write an
 * override that quietly beats whoever opened it.
 */
class PresentationTest {

    private static final Theme PURPLE = Theme.DARK.withAccent(new Color(120, 90, 240));

    /** A screen with no opinions of its own, which is most of them. */
    private static final class Plain extends Screen {

        @Override
        protected Node build() {
            return Column();
        }
    }

    /** A stand-in face, since what is being tested is which font is chosen rather than what it measures. */
    private static final class Face implements TextFont {

        @Override
        public int lineHeight() {
            return 8;
        }

        @Override
        public int widthOf(String text) {
            return text == null ? 0 : text.length() * 6;
        }

        @Override
        public int charWidth(char ch) {
            return 6;
        }

        @Override
        public String sanitize(String text) {
            return text == null ? "" : text;
        }

        @Override
        public void drawChar(Surface surface, int x, int y, char ch, byte color, Rect clip) {
        }
    }

    /** One that states a preference the way a screen is meant to. */
    private static final class Opinionated extends Screen {

        static final TextFont FACE = new Face();

        @Override
        public Theme defaultTheme() {
            return Theme.LIGHT;
        }

        @Override
        public TextFont defaultFont() {
            return FACE;
        }

        @Override
        public Color defaultBackground() {
            return Color.BLACK;
        }

        @Override
        public int defaultFps() {
            return 4;
        }

        @Override
        protected Node build() {
            return Column();
        }
    }

    private static Screen opened(Screen screen, OpenOptions options) {
        screen.attach(new Held(options));
        return screen;
    }

    @Test
    void withNoSessionAScreenIsItsOwnDefaults() {
        assertSame(Theme.DARK, new Plain().theme());
        assertSame(MapTextFont.INSTANCE, new Plain().font());
        assertSame(Theme.LIGHT, new Opinionated().theme());
        assertEquals(4, new Opinionated().fps());
    }

    @Test
    void anUnstatedFieldLeavesTheScreenToDecide() {
        Screen screen = opened(new Opinionated(), OpenOptions.of());

        assertSame(Theme.LIGHT, screen.theme());
        assertSame(Opinionated.FACE, screen.font());
        assertEquals(Color.BLACK, screen.background());
        assertEquals(4, screen.fps());
    }

    @Test
    void aStatedFieldBeatsTheScreensOwnPreference() {
        TextFont face = new Face();
        Screen screen = opened(new Opinionated(), OpenOptions.of()
                .theme(PURPLE)
                .font(face)
                .background(Color.WHITE)
                .fps(9)
        );

        assertSame(PURPLE, screen.theme());
        assertSame(face, screen.font());
        assertEquals(Color.WHITE, screen.background());
        assertEquals(9, screen.fps());
    }

    /** The whole point of the record being read on every build rather than captured at open. */
    @Test
    void aChangeWhileOpenIsSeenByTheNextBuild() {
        Held session = new Held(OpenOptions.of());
        Screen screen = new Plain();
        screen.attach(session);

        assertSame(Theme.DARK, screen.theme());

        session.presentation(shown -> shown.theme(Theme.LIGHT));
        assertSame(Theme.LIGHT, screen.theme(), "a screen resolves its theme every time it builds");
    }

    /** A background nobody stated is the theme's, so restyling a screen restyles its canvas with it. */
    @Test
    void anUnstatedBackgroundFollowsTheTheme() {
        Screen screen = opened(new Plain(), OpenOptions.of().theme(Theme.LIGHT));

        assertEquals(Theme.LIGHT.background(), screen.background());
    }

    /** Enough of a session to hold a presentation, which is all these need. */
    private static final class Held implements Session {

        private OpenOptions presentation;

        Held(OpenOptions presentation) {
            this.presentation = presentation;
        }

        @Override
        public OpenOptions presentation() {
            return presentation;
        }

        @Override
        public void presentation(UnaryOperator<OpenOptions> change) {
            presentation = change.apply(presentation);
        }

        @Override
        public Player player() {
            return null;
        }

        @Override
        public Screen screen() {
            return null;
        }

        @Override
        public int width() {
            return MapSurface.TILE;
        }

        @Override
        public int height() {
            return MapSurface.TILE;
        }

        @Override
        public void push(Screen screen) {
        }

        @Override
        public void pop() {
        }

        @Override
        public void close() {
        }

        @Override
        public int cursorX() {
            return -1;
        }

        @Override
        public int cursorY() {
            return -1;
        }

        @Override
        public void invalidate() {
        }

        @Override
        public void suspend() {
        }

        @Override
        public void resume() {
        }

        @Override
        public boolean suspended() {
            return false;
        }

        @Override
        public boolean focused() {
            return true;
        }

        @Override
        public void focus(boolean focused) {
        }

        @Override
        public HandOptions hand() {
            return null;
        }

        @Override
        public void promptText(TextPrompt prompt, String providerKey, Consumer<Optional<String>> callback) {
        }

        @Override
        public void edit(TextField field) {
        }
    }
}
