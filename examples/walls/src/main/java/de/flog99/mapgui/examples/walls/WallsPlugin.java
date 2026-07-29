package de.flog99.mapgui.examples.walls;

import com.mojang.brigadier.Command;
import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.GuiCatalog;
import de.flog99.mapgui.WallDisplay;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Two interactive walls, and both ways a plugin can put one up.
 *
 * <p><b>The catalog</b> is the {@code register} calls: an admin then sizes and places them with
 * {@code /mapgui wall place draw} and {@code /mapgui wall place jukebox}, and MapGUI saves where they went and puts them
 * back after a restart. No command, no config and no listener needed for that.
 *
 * <p><b>{@code /walls here}</b> is the other way, for a plugin that already knows where its walls belong -
 * furniture, a television, a painting. It opens one itself through {@link MapGui#wall()} and holds onto it,
 * which means it is also responsible for closing it and for remembering where it went. This one deliberately
 * remembers nothing, so its walls are gone on restart; a real plugin would save the coordinates alongside
 * whatever the wall belongs to.
 *
 * <p><b>The two of them also show the thing that is easy to get wrong: where you build a
 * {@link de.flog99.mapgui.SharedModel} decides how far it is shared.</b> Nothing in the API says so - it falls
 * out of ordinary Java scope, which is why it is worth pointing at:
 *
 * <ul>
 *   <li>A <b>field</b> on the plugin is one model for the server. Every jukebox wall plays the same track,
 *       which is the point of a jukebox.
 *   <li>Built <b>inside the registration</b> it is one model per wall. Two drawing boards are two pictures,
 *       which is the point of a whiteboard.
 * </ul>
 */
public final class WallsPlugin extends JavaPlugin {

    private static final String DRAW = "draw";
    private static final String JUKEBOX = "jukebox";

    /**
     * One jukebox for the whole server, so two of them agree on the track.
     *
     * <p>A field, which is what makes it plugin-wide. The drawing boards do the opposite and build their model
     * inside the registration instead - see {@link #onEnable}.
     */
    private final Jukebox jukebox = new Jukebox();

    /** Ours to close, since nothing else knows about a wall this plugin opened. */
    private final List<WallDisplay> owned = new ArrayList<>();

    @Override
    public void onEnable() {
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

        registerOwnCommand();
    }

    /**
     * Taken back out, so MapGUI stops offering something this plugin can no longer draw.
     *
     * <p>Walls placed from the catalog close themselves with it and stay in {@code walls.yml}, so putting the
     * plugin back brings them back. The ones opened by {@code /walls} are this plugin's own, and nothing else
     * will close them.
     */
    @Override
    public void onDisable() {
        GuiCatalog screens = MapGui.get().guis();
        screens.unregister(DRAW);
        // One call, even though the jukebox was registered for both surfaces.
        screens.unregister(JUKEBOX);

        for (WallDisplay wall : owned) wall.close();
        owned.clear();
    }

    private void registerOwnCommand() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar()
                .register(Commands.literal("walls")
                        .requires(source -> source.getSender().hasPermission("mapgui.command.wall"))
                        .then(Commands.literal("here").executes(context -> {
                            if (context.getSource().getSender() instanceof Player player) {
                                placeJukebox(player);
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("clear").executes(context -> {
                            for (WallDisplay wall : owned) wall.close();
                            context.getSource().getSender().sendRichMessage("<gray>Closed " + owned.size() + " wall(s).");
                            owned.clear();
                            return Command.SINGLE_SUCCESS;
                        }))
                        .build(), "Put up a wall this plugin owns")
        );
    }

    /** Straight onto whatever block face the player is looking at, with no sizing gesture. */
    private void placeJukebox(Player player) {
        RayTraceResult hit = player.rayTraceBlocks(6);
        Block block = hit == null ? null : hit.getHitBlock();
        BlockFace face = hit == null ? null : hit.getHitBlockFace();
        if (block == null || face == null) {
            player.sendRichMessage("<red>Look at a block within six blocks.");
            return;
        }

        owned.add(MapGui.get().wall()
                .at(block, face)
                .size(2, 1)
                .screenForEveryone(new JukeboxScreen(jukebox))
                .open()
        );
        player.sendRichMessage("<green>Put a jukebox up. <gray>/walls clear takes it down.");
    }
}
