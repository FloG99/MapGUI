package de.flog99.mapgui.examples.walls;

import de.flog99.mapgui.SharedModel;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What is playing, shared by every jukebox wall.
 *
 * <p>Kept out of the screen on purpose. A screen on the wall is one object per wall, so state held in it is
 * per wall too - put up two jukeboxes and pressing play on one leaves the other insisting nothing is on. The
 * queue belongs to the plugin; the screen is a remote control for it.
 *
 * <p>The sound plays where the button was pressed rather than at every wall, because that is what a jukebox
 * does: one thing playing, in the room it was started in.
 */
final class Jukebox extends SharedModel {

    /** How far a disc carries, which is also how far away someone has to be to escape it. */
    private static final int EARSHOT = 48;

    record Track(String name, Sound disc) {
    }

    static final List<Track> TRACKS = List.of(
            new Track("Cat", Sound.MUSIC_DISC_CAT),
            new Track("Blocks", Sound.MUSIC_DISC_BLOCKS),
            new Track("Chirp", Sound.MUSIC_DISC_CHIRP),
            new Track("Far", Sound.MUSIC_DISC_FAR),
            new Track("Mall", Sound.MUSIC_DISC_MALL),
            new Track("Ward", Sound.MUSIC_DISC_WARD)
    );

    @Nullable
    private Track playing;

    @Nullable
    Track playing() {
        return playing;
    }

    /** Played into the world rather than to one player, so everyone standing there hears the same thing. */
    void play(Track track, Location at) {
        silence(at);
        playing = track;
        at.getWorld().playSound(at, track.disc(), SoundCategory.RECORDS, 4f, 1f);
        changed();
    }

    void stop(Location at) {
        silence(at);
        playing = null;
        changed();
    }

    /** A disc runs for minutes, so stopping has to reach everyone it was sent to, not just whoever pressed. */
    private static void silence(Location at) {
        for (Player nearby : at.getNearbyPlayers(EARSHOT)) nearby.stopSound(SoundCategory.RECORDS);
    }
}
