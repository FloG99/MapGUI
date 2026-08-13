package de.flog99.mapgui.examples.gallery;

import de.flog99.mapgui.RichText;
import de.flog99.mapgui.HandOptions;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.ui.AwtFont;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.TextAlign;
import de.flog99.mapgui.ui.TextFont;
import de.flog99.mapgui.ui.Theme;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.awt.Color;
import java.awt.Font;

import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Divider;
import static de.flog99.mapgui.ui.Ui.Scroll;
import static de.flog99.mapgui.ui.Ui.Text;

/**
 * The same words in a font the game does not have, and text that brought its own colors.
 *
 * <p>Two things worth seeing next to each other rather than described. The vanilla map font is 8 pixels tall
 * and one weight; this screen is laid out and drawn in a TrueType face at whatever size it asks for. And a
 * {@link RichText} is an Adventure component in a layout - the sort of thing that arrives already styled from
 * a config or a chat line, keeping the colors its author wrote.
 *
 * <p>Uses a font the JVM ships rather than one bundled here, so the example needs no resources. A plugin with
 * its own face loads it the same way from {@code getResource(..)}.
 */
public final class TypeScreen extends Screen {

    private static final Theme THEME = Theme.DARK.withAccent(new Color(240, 170, 60));

    /**
     * Loaded once and shared, because a font caches a rasterized glyph per character - one built per screen
     * would rasterize the alphabet again for every player who opened this.
     */
    private static final TextFont FACE = AwtFont.named("SansSerif", Font.PLAIN, 11, true);

    @Override
    public Component title() {
        return Component.text("Type", NamedTextColor.GOLD);
    }

    @Override
    public Theme theme() {
        return THEME;
    }

    /** The one override that changes how everything on this screen is measured and drawn. */
    @Override
    public TextFont font() {
        return FACE;
    }

    /** Stated rather than left to config, so the demo is the same whatever a server sets for everything else. */
    @Override
    public HandOptions hand() {
        return HandOptions.popup();
    }

    @Override
    protected Node build() {
        return Column(
                Text("Anti-aliased TrueType").color(THEME.accent()).shadow(),
                Divider(THEME.accent()),
                Scroll().gap(4).key("body").fill().children(
                        Text("SansSerif at 11px, wrapped by its own metrics.").color(THEME.text()).wrap(),

                        Divider(THEME.muted()),

                        // One line each, sized to fit: a RichText is clipped rather than wrapped, since
                        // breaking styled text means cutting its runs and that is Text's job on plain text.
                        RichText.of(Component.text("keeps ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                                .append(Component.text("its ", NamedTextColor.AQUA))
                                .append(Component.text("own", NamedTextColor.WHITE))
                        ).shadow(),

                        // Siblings of an empty root, so each keeps its own decoration. Appended to the first
                        // instead, the second would inherit its underline - which is what the game does too.
                        RichText.of(Component.empty()
                                .append(Component.text("under ", NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.UNDERLINED))
                                .append(Component.text("struck", NamedTextColor.RED).decorate(TextDecoration.STRIKETHROUGH))
                        ),

                        RichText.of(Component.text("right", NamedTextColor.GRAY)).align(TextAlign.RIGHT),

                        Divider(THEME.muted()),

                        Text("Compare with the gallery, in the vanilla font.").color(THEME.muted()).wrap()
                )
        ).gap(4).padding(3).fill();
    }
}
