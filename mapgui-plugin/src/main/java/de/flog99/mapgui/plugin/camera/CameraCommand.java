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

    public static ArgumentBuilder<CommandSourceStack, ?> camera(MapGuiPlugin plugin, String permission) {
        return Commands.literal("camera")
                .requires(source -> source.getSender().hasPermission(permission))
                .executes(context -> {
                    status(context.getSource().getSender(), plugin);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("status")
                        .executes(context -> {
                            status(context.getSource().getSender(), plugin);
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
                            status(context.getSource().getSender(), plugin);
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("timings")
                        .executes(context -> {
                            for (Component line : CameraReport.lines(plugin.camera())) {
                                context.getSource().getSender().sendMessage(line);
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("follow")
                                .executes(context -> {
                                    follow(context.getSource().getSender(), plugin);
                                    return Command.SINGLE_SUCCESS;
                                })));
    }

    /**
     * What state the textures are in, and for anything wrong, both what is wrong and what to do - the same two
     * lines the console gets, because the person reading this is the person who can fix it.
     */
    private static void status(CommandSender sender, MapGuiPlugin plugin) {
        CameraAssetStore assets = plugin.cameraAssets();
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

        // The layers by name, because "ready" says nothing about whether the server's own pack made it in - which
        // is the question anybody following one is actually asking.
        if (assets.stack() != null) {
            sender.sendMessage(Component.text("Layers  ", NamedTextColor.GOLD)
                    .append(Component.text(String.join(" over ", assets.stack().layerNames()), NamedTextColor.WHITE)));
        }

        sender.sendMessage(Component.text("Extra packs  ", NamedTextColor.GOLD)
                .append(Component.text(plugin.serverPacks().followed().size()
                        + " kept in cache/camera/packs/", NamedTextColor.WHITE)));

        // Under the state rather than instead of it: a stack with a broken layer still reports itself ready,
        // because the layers underneath it are fine and that is what a capture is coming out of.
        if (assets.stack() == null) return;

        for (String hurt : assets.stack().damage()) {
            sender.sendMessage(Component.text("Damaged layer  ", NamedTextColor.RED)
                    .append(Component.text(hurt, NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("Replaced while the server had it open. Restart to pick it up.", NamedTextColor.YELLOW));
        }
    }

    /**
     * Turns the per-capture, four-stage tail on or off for whoever asked.
     *
     * <p>For working out why a capture is slow rather than whether it is costing anything - {@code timings} on its own
     * answers that, for every capture on the server, whoever asked for it. This one only reports captures taken from
     * <i>this</i> player's eye, so a plugin that captures on a timer or for somebody else shows up in the first and
     * not in this.
     */
    private static void follow(CommandSender sender, MapGuiPlugin plugin) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can follow captures, since it reports the ones taken from their own eye.", NamedTextColor.RED));
            sender.sendMessage(Component.text("Run /mapgui camera timings for what every capture on the server is costing.", NamedTextColor.YELLOW));
            return;
        }

        if (plugin.camera().toggleTimings(player.getUniqueId())) {
            player.sendMessage(Component.text("Following your captures, at most one line a second. Run it again to stop.", NamedTextColor.GREEN));
            return;
        }
        player.sendMessage(Component.text("No longer following your captures.", NamedTextColor.YELLOW));
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
