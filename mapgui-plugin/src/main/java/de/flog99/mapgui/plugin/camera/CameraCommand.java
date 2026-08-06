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
 * {@code /mapgui camera} - the textures a capture draws with, and nothing about taking one.
 *
 * <p>Taking a capture belongs to whatever plugin wants the picture, the same way opening a screen does. What an
 * admin needs from here is what state the textures are in and how to get them, which is the one part of the
 * camera that can go wrong in a way only a person can fix.
 */
public final class CameraCommand {

    private CameraCommand() {
    }

    public static ArgumentBuilder<CommandSourceStack, ?> camera(MapGuiPlugin plugin, String permission) {
        return Commands.literal("camera")
                .requires(source -> source.getSender().hasPermission(permission))
                .executes(context -> {
                    status(context.getSource().getSender(), plugin.cameraAssets());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("status")
                        .executes(context -> {
                            status(context.getSource().getSender(), plugin.cameraAssets());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("fetch-assets")
                        .executes(context -> {
                            fetch(context.getSource().getSender(), plugin.cameraAssets());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("reload")
                        .executes(context -> {
                            plugin.cameraAssets().reload();
                            plugin.camera().invalidate();
                            status(context.getSource().getSender(), plugin.cameraAssets());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("timings")
                        .executes(context -> {
                            timings(context.getSource().getSender(), plugin);
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    /**
     * What state the textures are in, and for anything wrong, both what is wrong and what to do - the same two
     * lines the console gets, because the person reading this is the person who can fix it.
     */
    private static void status(CommandSender sender, CameraAssetStore assets) {
        switch (assets.state()) {
            case CameraAssets.Ready ready -> sender.sendMessage(Component.text("Camera  ", NamedTextColor.GOLD)
                    .append(Component.text("ready", NamedTextColor.GREEN))
                    .append(Component.text("  Minecraft " + ready.minecraftVersion() + ", " + ready.blockTextures() + " block textures", NamedTextColor.WHITE)));

            case CameraAssets.Loading loading -> sender.sendMessage(Component.text("Camera  ", NamedTextColor.GOLD)
                    .append(Component.text("downloading textures, " + loading.percent() + "%", NamedTextColor.YELLOW)));

            case CameraAssets.Unavailable unavailable -> {
                sender.sendMessage(Component.text("Camera  ", NamedTextColor.GOLD)
                        .append(Component.text("unavailable", NamedTextColor.RED))
                        .append(Component.text("  " + unavailable.cause(), NamedTextColor.DARK_GRAY)));
                sender.sendMessage(Component.text(unavailable.detail(), NamedTextColor.WHITE));
                sender.sendMessage(Component.text(unavailable.fix(), NamedTextColor.YELLOW));
            }
        }
    }

    /**
     * Turns the per-capture cost report on or off for whoever asked.
     *
     * <p>A player rather than the console, because what it reports is the cost of that player's own captures, and
     * because the answer arrives after the next one they take rather than now.
     */
    private static void timings(CommandSender sender, MapGuiPlugin plugin) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can be told what their own captures cost.", NamedTextColor.RED));
            return;
        }

        if (plugin.camera().toggleTimings(player.getUniqueId())) {
            player.sendMessage(Component.text("Capture timings on. Take a picture and the cost will follow it.", NamedTextColor.GREEN));
            return;
        }
        player.sendMessage(Component.text("Capture timings off.", NamedTextColor.YELLOW));
    }

    private static void fetch(CommandSender sender, CameraAssetStore assets) {
        if (assets.state() instanceof CameraAssets.Loading loading) {
            sender.sendMessage(Component.text("Already downloading, " + loading.percent() + "%.", NamedTextColor.YELLOW));
            return;
        }

        if (assets.fetchNow()) {
            sender.sendMessage(Component.text("Downloading camera textures from Mojang. Watch the console, or run /mapgui camera status.", NamedTextColor.GREEN));
            return;
        }

        sender.sendMessage(Component.text("camera.assets.download is false in config.yml, so MapGUI will not fetch anything.", NamedTextColor.RED));
        sender.sendMessage(Component.text("Set it to true and run /mapgui camera reload, or put a client jar in plugins/MapGUI/assets/ and list it under camera.assets.packs.", NamedTextColor.YELLOW));
    }
}
