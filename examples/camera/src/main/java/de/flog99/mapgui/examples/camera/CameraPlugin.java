package de.flog99.mapgui.examples.camera;

import com.mojang.brigadier.Command;
import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.Session;
import de.flog99.mapgui.camera.CameraOptions;
import de.flog99.mapgui.map.MapPrinter;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * A screenshot of the world onto a map - blocks with their real textures, and through glass and water.
 *
 * <p>The part worth copying is in {@link CameraScreen}: it asks whether the textures are installed and says so
 * itself, rather than taking a capture and discovering they are not.
 */
public final class CameraPlugin extends JavaPlugin implements Listener {

    private static final String NAME = "camera";

    @Override
    public void onEnable() {
        MapGui.get().guis().registerOpenable(NAME, "A screenshot of what you are looking at", player -> new CameraScreen());
        getServer().getPluginManager().registerEvents(this, this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar()
                .register(Commands.literal("snapshot")
                        .executes(context -> {
                            if (context.getSource().getSender() instanceof Player player) {
                                MapGui.get().open(player, new CameraScreen());
                            } else {
                                context.getSource().getSender().sendMessage(Component.text("Only a player has a view to capture."));
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("x4")
                                .executes(context -> {
                                    if (context.getSource().getSender() instanceof Player player) {
                                        wall(player, 2);
                                    } else {
                                        context.getSource().getSender().sendMessage(Component.text("Only a player has a view to capture."));
                                    }
                                    return Command.SINGLE_SUCCESS;
                                }))
                        // Its own, over the API, so a server can turn MapGUI's whole /mapgui tree off with
                        // commands.enabled: false and still have somewhere to look when captures feel expensive.
                        .then(Commands.literal("debug")
                                .requires(source -> source.getSender().hasPermission("mapgui.example.camera.debug"))
                                .executes(context -> {
                                    SnapshotDebug.print(context.getSource().getSender());
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .build(), "Capture what you are looking at onto a map")
        );
    }

    /**
     * One capture, cut into real maps to hang on a wall.
     *
     * <p>A capture {@code across} maps wide rather than a bigger one squeezed onto a single map: the map is 128 pixels
     * and nothing changes that, so the way to a picture with more in it is more maps. The camera is asked for exactly
     * {@code across * 128} so every tile is a whole map at one pixel per pixel, with nothing scaled.
     *
     * <p>No screen for this one. The screen is for aiming and settings, and this is a shutter with a known answer.
     */
    private void wall(Player player, int across) {
        if (!MapGui.get().camera().assets().ready()) {
            player.sendMessage(Component.text("The camera's textures are not installed yet. Try /mapgui camera status.", NamedTextColor.RED));
            return;
        }

        int size = MapPrinter.sizeFor(across);
        CameraOptions options = CameraOptions.defaults().size(size);
        player.sendMessage(Component.text("Taking a " + size + " by " + size + " capture...", NamedTextColor.GRAY));

        MapGui.get().camera().capture(player, options, shot -> {
            if (shot == null) {
                player.sendMessage(Component.text("That capture failed. The console will say why.", NamedTextColor.RED));
                return;
            }

            // Read off the shot rather than reusing `across`, since the cut has to follow the pixels that arrived.
            int grid = MapPrinter.mapsAcross(shot);
            if (grid == 0) {
                player.sendMessage(Component.text("That capture could not be cut into whole maps.", NamedTextColor.RED));
                return;
            }

            player.sendMessage(Component.text(SnapshotTiles.give(player, shot, grid) + " maps", NamedTextColor.GREEN)
                    .append(Component.text(" - place them in item frames in a " + grid + " by " + grid
                            + " square, the way their names say.", NamedTextColor.WHITE)));
        });
    }

    /** Sneak is a second shutter, and unlike a click it costs the player nothing to reach. */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;

        Session session = MapGui.get().session(event.getPlayer());
        if (session != null && session.screen() instanceof CameraScreen camera) {
            camera.sneaked();
        }
    }

    @Override
    public void onDisable() {
        MapGui.get().guis().unregister(NAME);
    }
}
