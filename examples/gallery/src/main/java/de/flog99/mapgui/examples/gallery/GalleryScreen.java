package de.flog99.mapgui.examples.gallery;

import de.flog99.mapgui.HandOptions;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.media.VideoPlayer;
import de.flog99.mapgui.ui.Align;
import de.flog99.mapgui.ui.Button;
import de.flog99.mapgui.ui.Corner;
import de.flog99.mapgui.ui.Easing;
import de.flog99.mapgui.ui.Fill;
import de.flog99.mapgui.ui.Justify;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.PaintContext;
import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.State;
import de.flog99.mapgui.ui.TextAlign;
import de.flog99.mapgui.ui.Theme;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.awt.Color;
import java.util.List;
import java.util.function.Consumer;

import static de.flog99.mapgui.ui.Ui.Box;
import static de.flog99.mapgui.ui.Ui.Button;
import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Divider;
import static de.flog99.mapgui.ui.Ui.Draw;
import static de.flog99.mapgui.ui.Ui.Field;
import static de.flog99.mapgui.ui.Ui.Overlay;
import static de.flog99.mapgui.ui.Ui.Row;
import static de.flog99.mapgui.ui.Ui.Scroll;
import static de.flog99.mapgui.ui.Ui.Spacer;
import static de.flog99.mapgui.ui.Ui.Text;
import static de.flog99.mapgui.ui.Ui.Toggle;
import static de.flog99.mapgui.ui.Ui.each;

/** Every widget in one place. Doubles as the visual check after touching the layout engine. */
public final class GalleryScreen extends Screen {

    private static final Theme THEME = Theme.DARK.withAccent(new Color(120, 90, 240));

    private static final Color BG = THEME.background();
    private static final Color PANEL = THEME.surface();
    private static final Color ACCENT = THEME.accent();
    private static final Color GREEN = THEME.success();
    private static final Color AMBER = THEME.warning();
    private static final Color TEXT = THEME.text();
    private static final Color MUTED = THEME.muted();

    private static final Color[] SWATCHES = {THEME.accent(), THEME.success(), THEME.warning(), THEME.danger()};

    /**
     * A look named once and reused, rather than the same six calls on every button.
     *
     * <p>{@code apply} takes any styling as a lambda, so this is all a reusable style needs to be - no
     * style objects, no stylesheet, and it still chains.
     */
    private static final Consumer<Button> FILLED = button -> button
            .background(ACCENT).radius(4).textColor(Color.WHITE)
            .hoverBackground(Color.WHITE).hoverTextColor(ACCENT)
            .transition(220);

    private static final Consumer<Button> OUTLINED = button -> button
            .border(1, MUTED).radius(4).textColor(MUTED)
            .hoverBorder(Color.WHITE).hoverTextColor(Color.WHITE)
            .transition(220);

    private final State<Integer> swatch = state(0);
    private final State<Integer> level = state(35);
    private final State<Boolean> toggled = state(true);
    private final State<String> name = state("flog99");
    private final State<Integer> clicks = state(0);

    /**
     * Decoded once by the plugin and shared by every screen, rather than loaded here.
     *
     * <p>Both halves of that matter. The frames are megabytes, and a screen is built per player per
     * command, so loading them here would keep a copy alive for every menu anyone had opened. Decoding
     * also takes about a second, which belongs in startup rather than in whoever runs the command first.
     *
     * <p>Null when the sample is missing, so a broken resource costs a section rather than the screen.
     */
    private final VideoPlayer video;

    public GalleryScreen(VideoPlayer video) {
        this.video = video;
    }

    @Override
    public Component title() {
        return Component.text("Gallery", NamedTextColor.LIGHT_PURPLE);
    }

    @Override
    public Theme theme() {
        return THEME;
    }

    /** Stated rather than left to config, so the demo is the same whatever a server sets for everything else. */
    @Override
    public HandOptions hand() {
        return HandOptions.popup();
    }

    @Override
    protected Node build() {
        return Column(
                Row(
                        Box(ACCENT).size(7, 7).radius(3),
                        Text("Widgets").color(TEXT).shadow(),
                        Spacer(),
                        Text(() -> clicks.get() + " clicks").color(MUTED)
                ).gap(4).align(Align.CENTER),
                Divider(ACCENT),
                Scroll().gap(5).key("body").fill().children(
                        section("Text", Column(
                                Text("Left").color(TEXT),
                                Text("Centered").color(TEXT).align(TextAlign.CENTER).fillWidth(),
                                Text("Right").color(TEXT).align(TextAlign.RIGHT).fillWidth(),
                                Text("This sentence is long enough that it has to wrap onto more lines.")
                                        .color(MUTED).wrap().fillWidth()
                        ).gap(2).align(Align.STRETCH)),

                        section("Overflow", Column(
                                // Hover either of these and the whole line appears under the cursor.
                                Text("ellipsis: cut short with two dots at the end")
                                        .color(MUTED).revealOnHover().fillWidth(),
                                Text("clip: cut off at the edge, mid-letter if need be")
                                        .color(MUTED).clip().revealOnHover().fillWidth(),
                                // Slides back and forth, dwelling at each end, only while too long.
                                Text("scroll: slides so you can read all of this eventually")
                                        .color(MUTED).scroll().fillWidth()
                        ).gap(3).align(Align.STRETCH)),

                        section("Buttons", Row(
                                Button("Click me").apply(FILLED)
                                        .onClick(() -> clicks.update(value -> value + 1))
                                        .fillWidth(),
                                Button("Reset").apply(OUTLINED)
                                        .cursorIcon("RED_X")
                                        .onClick(() -> clicks.set(0))
                        ).gap(4)),

                        section("Toggle", Row(
                                Toggle(toggled::get).onChange(toggled::set),
                                Text(() -> toggled.get() ? "Enabled" : "Disabled").color(TEXT)
                        ).gap(5).align(Align.CENTER)),

                        section("Text field", Field(name)
                                .title("Your name")
                                .placeholder("click to type")
                                .background(BG).border(1, MUTED).radius(3)
                                .fillWidth()),

                        section("Justify", Column(
                                bar(Justify.START), bar(Justify.CENTER),
                                bar(Justify.END), bar(Justify.SPACE_BETWEEN)
                        ).gap(2).align(Align.STRETCH)),

                        // A badge has to escape its parent's corner to be worth anything, which is the
                        // whole point of an overlay - laid out in a row it would just push the text along.
                        section("Overlay", Overlay(
                                // A shade lighter than the section behind it, or there is no telling
                                // where the thing being overlaid starts.
                                Row(Text("Mailbox").color(TEXT)).fillWidth().height(22)
                                        .padding(6, 0).background(THEME.surfaceHigh()).radius(3)
                                        .align(Align.CENTER),
                                Text("12").color(Color.WHITE)
                                        .padding(1, 3).background(THEME.danger()).radius(4)
                                        .place(Justify.END, Align.START)
                        ).fillWidth()),

                        video == null ? null : section("Video", Column(
                                Draw(this::paintVideo).size(64, 64).radius(3),
                                Text(this::videoLabel).color(MUTED)
                        ).gap(3).align(Align.CENTER)),

                        section("Corners", Column(
                                Row(
                                        corner("round", Corner.ROUND),
                                        corner("bevel", Corner.BEVEL)
                                ).gap(4),
                                Row(
                                        corner("notch", Corner.NOTCH),
                                        corner("step", Corner.STEP)
                                ).gap(4)
                        ).gap(4).align(Align.STRETCH)),

                        section("Bevels", Row(
                                Text("raised").color(TEXT).align(TextAlign.CENTER)
                                        .height(20).padding(6, 2).background(THEME.surfaceHigh()).raised(2)
                                        .fillWidth(),
                                Text("sunken").color(TEXT).align(TextAlign.CENTER)
                                        .height(20).padding(6, 2).background(THEME.surfaceHigh()).sunken(2)
                                        .fillWidth()
                        ).gap(4)),

                        section("Rainbow", Column(
                                Text("infinite palette").color(Color.WHITE).align(TextAlign.CENTER)
                                        .height(20).padding(6, 3).radius(4)
                                        .fill(rainbow(phase(6000), 0.85f))
                                        .fillWidth(),
                                Box(null).height(10).fillWidth().radius(3)
                                        .fill(rainbow(phase(2500), 0.5f))
                        ).gap(4).align(Align.STRETCH)),

                        section("Gradients", Column(
                                Text("dithered").color(Color.WHITE).align(TextAlign.CENTER)
                                        .height(18).padding(5, 3).radius(4)
                                        .gradient(ACCENT, THEME.danger(), Fill.Direction.HORIZONTAL)
                                        .fillWidth(),
                                Row(
                                        Box(null).height(14).fillWidth().radius(3)
                                                .gradient(THEME.success(), THEME.warning(), Fill.Direction.HORIZONTAL),
                                        Box(null).height(14).fillWidth().radius(3)
                                                .gradient(Color.BLACK, Color.WHITE, Fill.Direction.HORIZONTAL)
                                ).gap(4)
                        ).gap(4).align(Align.STRETCH)),

                        section("Animation", Column(
                                // The color is not animated by hovering - it eases because the
                                // state behind it changed, and the node has a transition.
                                Row(
                                        Box(SWATCHES[swatch.get()]).key("swatch")
                                                .size(20, 20).radius(4).transition(400),
                                        Spacer(),
                                        Button("cycle color")
                                                .padding(3, 5).radius(3)
                                                .background(THEME.surfaceHigh()).textColor(TEXT)
                                                .hoverBackground(ACCENT).transition(200)
                                                .onClick(() -> swatch.update(i -> (i + 1) % SWATCHES.length))
                                ).gap(4).align(Align.CENTER),

                                // Same button drives this: the endpoints run through animateColor, so
                                // the dithered ramp eases across rather than jumping.
                                Box(null).height(14).fillWidth().radius(3).key("live-gradient")
                                        .gradient(animateColor("grad", SWATCHES[swatch.get()], 500, Easing.EASE_OUT),
                                                THEME.surface(), Fill.Direction.HORIZONTAL),

                                // An eased number of our own, which is what a size transition will
                                // feel like once nodes can animate their rects.
                                Draw(context -> {
                                    var painter = context.painter();
                                    var bounds = context.bounds();
                                    int filled = (int) Math.round(bounds.width() * animate("bar", level.get()) / 100);
                                    painter.rect(bounds, THEME.surfaceHigh(), 0, null, 3);
                                    painter.rect(new Rect(bounds.x(), bounds.y(), filled, bounds.height()), ACCENT, 0, null, 3);
                                }).preferred(110, 8).fillWidth(),

                                Row(
                                        Button("-25").padding(2, 5).radius(3).border(1, MUTED).textColor(MUTED)
                                                .hoverBorder(Color.WHITE).hoverTextColor(Color.WHITE).transition(200)
                                                .onClick(() -> level.update(v -> Math.max(0, v - 25)))
                                                .fillWidth(),
                                        Button("+25").padding(2, 5).radius(3).border(1, MUTED).textColor(MUTED)
                                                .hoverBorder(Color.WHITE).hoverTextColor(Color.WHITE).transition(200)
                                                .onClick(() -> level.update(v -> Math.min(100, v + 25)))
                                                .fillWidth()
                                ).gap(4),

                                Text("scrolling eases too").color(MUTED)
                        ).gap(4).align(Align.STRETCH))
                )
        ).gap(4).padding(6).align(Align.STRETCH);
    }

    /**
     * A decoded GIF drawn frame by frame.
     *
     * <p>The clock is {@code phase} over the video's own length, so the frame follows the same limit as
     * every other looping effect - which is also why it plays at the server's {@code loop-fps} rather
     * than as fast as it can, and why it costs nothing while scrolled out of sight.
     */
    private void paintVideo(PaintContext context) {
        int duration = video.frames().durationMs();
        video.paint(context.painter(), context.bounds(), (int) (phase(duration) * duration));
    }

    private String videoLabel() {
        return video.frames().count() + " frames, " + video.frames().durationMs() / 1000 + "s";
    }

    private Node section(String heading, Node body) {
        return Column(Text(heading).color(ACCENT), body).gap(3).padding(4).background(PANEL).radius(3).align(Align.STRETCH).fillWidth();
    }

    /** Tall enough and a big enough radius that the corner shapes are actually distinguishable. */
    private Node corner(String label, Corner shape) {
        return Text(label).color(Color.WHITE).align(TextAlign.CENTER)
                .height(22).padding(6, 3).background(ACCENT).corner(shape, 9)
                .cursorIcon("BLUE_MARKER")
                .fillWidth();
    }

    /**
     * A fill with no endpoints: hue is a function of x plus a phase that loops forever, so the ramp
     * scrolls. The map palette has nothing like a hue wheel, so this leans entirely on dithering.
     *
     * <p>Both bands here are only 10-20px tall, which is deliberate - the same effect across the full
     * canvas would send 16 KB a frame, every tick, for as long as the menu is open. A showpiece, not
     * a background.
     */
    private static Fill rainbow(double offset, float saturation) {
        return (x, y, bounds) -> {
            double across = (x - bounds.x()) / (double) Math.max(1, bounds.width());
            return Color.getHSBColor((float) ((across + offset) % 1.0), saturation, 1f);
        };
    }

    private Node bar(Justify justify) {
        return Row().gap(2).justify(justify).fillWidth().height(9).children(
                each(List.of(GREEN, AMBER, ACCENT), color -> Box(color).size(9, 9).radius(2))
        );
    }
}
