package de.flog99.mapgui.plugin.camera;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import de.flog99.mapgui.camera.CameraAssets;
import de.flog99.mapgui.plugin.MapGuiPlugin;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /mapgui camera} - the textures a capture draws with, and what captures are costing.
 *
 * <p>Taking a capture belongs to whatever plugin wants the picture, the same way opening a screen does, so there is
 * no command here that takes one. What an admin needs is the two things a plugin cannot tell them: what state the
 * textures are in and how to get them, which is the one part of the camera only a person can fix, and what the
 * captures that plugin is taking cost the server.
 */
public final class CameraCommand {

    private CameraCommand() {
    }

    public static ArgumentBuilder<CommandSourceStack, ?> camera(MapGuiPlugin plugin, java.util.function.Predicate<CommandSourceStack> allowed) {
        return Commands.literal("camera")
                .requires(allowed)
                .executes(context -> {
                    performance(context.getSource().getSender(), plugin);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("reload")
                        .executes(context -> {
                            plugin.cameraAssets().reload();
                            plugin.camera().invalidate();
                            status(context.getSource().getSender(), plugin);
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("performance")
                        .executes(context -> {
                            performance(context.getSource().getSender(), plugin);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("follow")
                                .executes(context -> {
                                    follow(context.getSource().getSender(), plugin);
                                    return Command.SINGLE_SUCCESS;
                                })));
    }

    /**
     * Whether captures will draw, what is drawing them, and for anything wrong both what is wrong and what to do.
     *
     * <p>Three questions and no more. What it used to add - a count of block textures, a percentage, the directory
     * the followed packs are kept in - are things this code knows rather than things anybody can act on. The count
     * changes nothing, the directory is the same directory every time, and an admin who wants to know how much of a
     * download is left is better served by watching the console it logs to.
     *
     * <p>Packs are named rather than counted, since "2 extra packs" and "your server pack is in the picture" are
     * different answers and only the second one is the question being asked.
     */
    private static void status(CommandSender sender, MapGuiPlugin plugin) {
        CameraAssetStore assets = plugin.cameraAssets();
        switch (assets.state()) {
            case CameraAssets.Ready ready -> sender.sendMessage(Component.text("Camera  ", NamedTextColor.GOLD)
                    .append(Component.text("ready", NamedTextColor.GREEN))
                    .append(Component.text("  Minecraft " + ready.minecraftVersion(), NamedTextColor.WHITE)));

            case CameraAssets.Loading ignored -> sender.sendMessage(Component.text("Camera  ", NamedTextColor.GOLD)
                    .append(Component.text("downloading textures", NamedTextColor.YELLOW))
                    .append(Component.text("  captures will draw when it lands", NamedTextColor.DARK_GRAY)));

            case CameraAssets.Unavailable unavailable -> {
                sender.sendMessage(Component.text("Camera  ", NamedTextColor.GOLD)
                        .append(Component.text("unavailable", NamedTextColor.RED))
                        .append(Component.text("  " + unavailable.cause(), NamedTextColor.DARK_GRAY)));
                sender.sendMessage(Component.text(unavailable.detail(), NamedTextColor.WHITE));
                sender.sendMessage(Component.text(unavailable.fix(), NamedTextColor.YELLOW));
            }
        }

        // Under the state rather than instead of it: a stack with a broken layer still reports itself ready,
        // because the layers underneath it are fine and that is what a capture is coming out of.
        if (assets.stack() == null) return;

        // Named, top first, because "ready" says nothing about whether the server's own pack made it in - which is
        // the one question an admin who set one up actually has.
        sender.sendMessage(Component.text("Drawing with  ", NamedTextColor.GOLD)
                .append(Component.text(String.join(" over ", assets.stack().layerNames()), NamedTextColor.WHITE)));

        for (String hurt : assets.stack().damage()) {
            sender.sendMessage(Component.text("Damaged  ", NamedTextColor.RED)
                    .append(Component.text(hurt, NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("Replaced while the server had it open. Restart to pick it up.", NamedTextColor.YELLOW));
        }
    }

    /**
     * Turns the per-capture, four-stage tail on or off for whoever asked.
     *
     * <p>For working out why a capture is slow rather than whether it is costing anything - {@code performance} on its own
     * answers that, for every capture on the server, whoever asked for it. This one only reports captures taken from
     * <i>this</i> player's eye, so a plugin that captures on a timer or for somebody else shows up in the first and
     * not in this.
     */
    private static void follow(CommandSender sender, MapGuiPlugin plugin) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can follow captures, since it reports the ones taken from their own eye.", NamedTextColor.RED));
            sender.sendMessage(Component.text("Run /mapgui camera performance for what every capture on the server is costing.", NamedTextColor.YELLOW));
            return;
        }

        if (plugin.camera().toggleFollow(player.getUniqueId())) {
            player.sendMessage(Component.text("Following your captures, at most one line a second. Run it again to stop.", NamedTextColor.GREEN));
            return;
        }
        player.sendMessage(Component.text("No longer following your captures.", NamedTextColor.YELLOW));
    }

    private static void performance(CommandSender sender, MapGuiPlugin plugin) {
        for (Component line : CameraReport.lines(plugin.camera().stats())) {
            sender.sendMessage(line);
        }
    }
}
