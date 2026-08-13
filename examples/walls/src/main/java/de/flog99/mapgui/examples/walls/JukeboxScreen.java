package de.flog99.mapgui.examples.walls;

import de.flog99.mapgui.HandOptions;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.ui.Align;
import de.flog99.mapgui.ui.Node;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;

import java.awt.Color;

import static de.flog99.mapgui.ui.Ui.Button;
import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Divider;
import static de.flog99.mapgui.ui.Ui.Row;
import static de.flog99.mapgui.ui.Ui.Spacer;
import static de.flog99.mapgui.ui.Ui.Text;
import static de.flog99.mapgui.ui.Ui.each;

/**
 * A jukebox everyone shares: one screen for the room, {@code screenForEveryone}.
 *
 * <p>The contrast with {@link DrawScreen} is the point. There the picture was shared but each viewer needed
 * tools of their own, so it had to be one screen per person over a shared model. Here nothing is private, so
 * one screen serves the room and a press changes the wall for everybody at once.
 *
 * <p>Even so the queue lives in {@link Jukebox} rather than in here, because a screen is one object per wall:
 * two jukebox walls are two screens, and state kept in the screen would leave them disagreeing about what is
 * on. Watching the model is what keeps them in step.
 *
 * <p>Note what it never does: call {@code player()} while building or painting. On a shared wall there is no
 * answer to that, and asking says so rather than quietly rendering one person's view for the room. Inside a
 * click handler it is fine, which is why it can play a sound where the presser is standing.
 */
public final class JukeboxScreen extends Screen {

    private final Jukebox jukebox;

    public JukeboxScreen(Jukebox jukebox) {
        this.jukebox = jukebox;
    }

    /** So a track put on at one wall shows as playing at every other wall too. */
    @Override
    protected void onOpen() {
        watch(jukebox);
    }

    @Override
    public Component title() {
        return Component.text("Jukebox", NamedTextColor.LIGHT_PURPLE);
    }

    @Override
    public Color background() {
        return new Color(24, 22, 34);
    }

    /** Only asked in a hand - a wall states its own size and place. A popup there, since it is buttons to press. */
    @Override
    public HandOptions hand() {
        return HandOptions.popup();
    }

    @Override
    protected Node build() {
        return Column(
                Text("Jukebox").color(Color.WHITE),
                Text(() -> jukebox.playing() == null
                        ? "nothing playing"
                        : "playing " + jukebox.playing().name()).color(new Color(150, 158, 175)),
                Divider(new Color(60, 62, 78)),
                Column(each(Jukebox.TRACKS, Jukebox.Track::name, this::row)).gap(2).align(Align.STRETCH),
                Spacer(),
                Button("stop").padding(2, 6).radius(3)
                        .background(new Color(60, 40, 46)).textColor(Color.WHITE)
                        .hoverBackground(new Color(150, 60, 70))
                        .onClick(() -> jukebox.stop(player().getLocation()))
        ).gap(3).padding(6).align(Align.STRETCH).fill();
    }

    private Node row(Jukebox.Track track) {
        boolean current = track.equals(jukebox.playing());
        return Row(
                Text(track.name()).color(current ? Color.WHITE : new Color(170, 176, 190)),
                Spacer(),
                Button(current ? "playing" : "play").padding(1, 4).radius(2)
                        .background(current ? new Color(70, 110, 80) : new Color(48, 50, 62))
                        .textColor(Color.WHITE)
                        .hoverBackground(new Color(90, 140, 100))
                        .onClick(() -> pick(track))
        ).align(Align.CENTER).gap(3);
    }

    /**
     * Puts the disc on for the room, and says who did it.
     *
     * <p>{@code player()} answers here because a click is in progress - the wall knows who is acting for as
     * long as the handler runs, and no longer. That is also the only reason this knows where it is.
     */
    private void pick(Jukebox.Track track) {
        jukebox.play(track, player().getLocation());
        player().sendActionBar(Component.text("You put on " + track.name() + " for everyone", NamedTextColor.LIGHT_PURPLE));
    }

    @Override
    public Sound clickSound() {
        return Sound.BLOCK_NOTE_BLOCK_HARP;
    }
}
