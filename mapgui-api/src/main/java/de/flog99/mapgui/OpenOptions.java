package de.flog99.mapgui;

import de.flog99.mapgui.ui.TextFont;
import de.flog99.mapgui.ui.Theme;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;

/**
 * How a screen is presented: how it is carried, and what it is styled with.
 *
 * <p>Everything here is <b>the caller's opinion</b>, and every field may be left unstated - which is what null
 * means throughout, and what {@link #of()} is. A field nobody stated falls back to the screen's own preference,
 * and after that to the server's config, so the closest opinion to the screen wins.
 *
 * <pre>{@code
 * MapGui.get().open(player, screen, OpenOptions.of()
 *         .hand(HandOptions.popup())
 *         .theme(Theme.LIGHT));
 * }</pre>
 *
 * <p><b>It is not only read at open.</b> A screen's {@link Screen#theme()}, {@link Screen#font()},
 * {@link Screen#background()} and {@link Screen#fps()} resolve against this every time {@link Screen#build()}
 * runs, so {@link Session#presentation(java.util.function.UnaryOperator)} restyles a screen that is already up:
 *
 * <pre>{@code
 * session.presentation(shown -> shown.theme(Theme.DARK));
 * }</pre>
 *
 * <p>The exception is {@link #hand}, which is read when the session is opened and not afterwards - by then the
 * map is already where it is, in a slot the client has been told about. Everything else is a styling question
 * that a rebuild can answer.
 *
 * @param hand       how the screen is carried, or null for the screen's own {@link Screen#hand()} and then the
 *                   server's config. Read at open only
 * @param theme      the palette the screen builds against, or null for its own {@link Screen#defaultTheme()}
 * @param font       what its text is measured and drawn with, or null for its own {@link Screen#defaultFont()}.
 *                   A node can still take a font of its own for its subtree
 * @param background what fills the canvas where nothing was drawn, or null for its own
 *                   {@link Screen#defaultBackground()}, which is the theme's
 * @param fps        a ceiling on frames driven by animation, or {@link #SERVER_FPS} for the screen's own
 *                   {@link Screen#defaultFps()}. The server's setting caps this either way
 */
@ApiStatus.Experimental
public record OpenOptions(
        @Nullable HandOptions hand,
        @Nullable Theme theme,
        @Nullable TextFont font,
        @Nullable Color background,
        int fps) {

    /** No opinion about frame rate, which leaves it to the screen and then to the server. */
    public static final int SERVER_FPS = 0;

    /** Nothing stated: the screen decides everything, exactly as it did before anybody asked. */
    public static OpenOptions of() {
        return new OpenOptions(null, null, null, null, SERVER_FPS);
    }

    /** Carried the way you say, styled the way the screen wants - which is most of what a caller ever asks for. */
    public static OpenOptions of(HandOptions hand) {
        return new OpenOptions(hand, null, null, null, SERVER_FPS);
    }

    /**
     * How the screen is carried. Read {@link HandOptions} first: the choice decides whether the player can walk
     * about and click on the world while the screen is up, not only where the map appears.
     */
    public OpenOptions hand(HandOptions value) {
        return new OpenOptions(value, theme, font, background, fps);
    }

    public OpenOptions theme(Theme value) {
        return new OpenOptions(hand, value, font, background, fps);
    }

    public OpenOptions font(TextFont value) {
        return new OpenOptions(hand, theme, value, background, fps);
    }

    public OpenOptions background(Color value) {
        return new OpenOptions(hand, theme, font, value, fps);
    }

    public OpenOptions fps(int value) {
        return new OpenOptions(hand, theme, font, background, Math.max(SERVER_FPS, value));
    }
}
