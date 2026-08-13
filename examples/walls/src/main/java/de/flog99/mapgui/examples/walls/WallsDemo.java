package de.flog99.mapgui.examples.walls;

import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.GuiCatalog;

/**
 * Two interactive walls, put up from the catalog.
 *
 * <p><b>The catalog</b> is the {@code register} calls: an admin sizes and places them with
 * {@code /mapgui wall place draw} and {@code /mapgui wall place jukebox}, and MapGUI saves where they went and puts them
 * back after a restart. No command, no config and no listener needed for that.
 *
 * <p>A plugin that already knows where its walls belong - furniture, a television, a painting - opens one itself
 * through {@link MapGui#wall()} instead, and is then responsible for closing it and for remembering where it went.
 *
 * <p><b>The registrations also show the thing that is easy to get wrong: where you build a
 * {@link de.flog99.mapgui.SharedModel} decides how far it is shared.</b> Nothing in the API says so - it falls
 * out of ordinary Java scope, which is why it is worth pointing at:
 *
 * <ul>
 *   <li>A <b>field</b> here is one model for the server, since the plugin builds this class once. Every jukebox
 *       wall plays the same track, which is the point of a jukebox.
 *   <li>Built <b>inside the registration</b> it is one model per wall. Two drawing boards are two pictures,
 *       which is the point of a whiteboard.
 * </ul>
 */
public final class WallsDemo {

    private static final String DRAW = "draw";
    private static final String JUKEBOX = "jukebox";

    /**
     * One jukebox for the whole server, so two of them agree on the track.
     *
     * <p>A field, which is what makes it server-wide. The drawing boards do the opposite and build their model
     * inside the registration instead - see {@link #register}.
     */
    private final Jukebox jukebox = new Jukebox();

    public void register() {
        GuiCatalog screens = MapGui.get().guis();

        // A screen each over one shared picture, so the drawing is common and the palette is private.
        //
        // Built here rather than as a field, which is what makes it one canvas per wall. A resize while placing
        // runs this again and throws the last one away, so anything heavier than a byte array belongs outside.
        screens.registerPlaceable(DRAW, "Drawing board - shared picture, private palette", wall -> {
            Drawing drawing = new Drawing();
            wall.screenPerPlayer(_ -> new DrawScreen(drawing))
                    // One canvas everyone at this wall shares, so its size is this plugin's to decide rather
                    // than the admin's - the picture would not survive being placed at a different one.
                    .fixedSize(2, 2)
                    // A margin so a stroke can run along the border without the cursor sliding off.
                    .aimMargin(20);
        });

        // One screen for everybody, because the queue is the whole state and nothing about it is private.
        screens.registerPlaceable(JUKEBOX, "Jukebox - one queue the whole room shares", wall -> wall.screenForEveryone(new JukeboxScreen(jukebox)));

        // The same jukebox in a hand as well, which is all it takes for one screen to work in both places.
        screens.registerOpenable(JUKEBOX, "Jukebox - one queue the whole room shares", player -> new JukeboxScreen(jukebox));
    }

    /**
     * Taken back out, so MapGUI stops offering something this plugin can no longer draw.
     *
     * <p>Walls placed from the catalog close themselves with it and stay in {@code walls.yml}, so putting the
     * plugin back brings them back.
     */
    public void unregister() {
        GuiCatalog screens = MapGui.get().guis();
        screens.unregister(DRAW);
        // One call, even though the jukebox was registered for both surfaces.
        screens.unregister(JUKEBOX);
    }
}
