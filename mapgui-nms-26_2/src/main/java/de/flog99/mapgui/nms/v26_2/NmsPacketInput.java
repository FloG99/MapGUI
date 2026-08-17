package de.flog99.mapgui.nms.v26_2;

import de.flog99.mapgui.PacketInput;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.logging.Level;

/**
 * Takes the gestures a menu needs straight off the connection.
 *
 * <p>Sits just before the packet handler and swallows what it reads. That is the point as much as the reading
 * is: while a menu is open, a right-click means "press this" and must not also open the chest behind it.
 */
public final class NmsPacketInput implements PacketInput {

    private static final String HANDLER = "mapgui_input";

    @Override
    public void listen(Player player, Handler handler) {
        Channel channel = channel(player);
        if (channel.pipeline().get(HANDLER) != null) return;

        channel.pipeline().addBefore(HandlerNames.PACKET_HANDLER, HANDLER, new Reader(handler));
    }

    @Override
    public void forget(Player player) {
        Channel channel = channel(player);
        if (channel.pipeline().get(HANDLER) != null) {
            channel.pipeline().remove(HANDLER);
        }
    }

    private static Channel channel(Player player) {
        return ((CraftPlayer) player).getHandle().connection.connection.channel;
    }

    private static final class Reader extends ChannelDuplexHandler {

        /**
         * A right-click at a block sends the block packet and then, if the client's prediction did not consume
         * it, a use-item packet too. Both would count as a press, so a second this soon is the same click.
         * Vanilla's own repeat delay is four ticks, so no real double-click is this fast.
         */
        private static final long SAME_CLICK_MS = 50;

        private final Handler handler;
        private long lastClick;
        private boolean lastTaken;

        Reader(Handler handler) {
            this.handler = handler;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            if (taken(message)) return;

            super.channelRead(context, message);
        }

        /**
         * Whether a menu wanted this packet. Anything declined is passed on untouched, so a listener can sit
         * on a player pointing at nothing of ours without eating their ordinary clicks.
         *
         * <p>Nothing thrown here is allowed out: on the network thread an exception reaches Minecraft's own
         * handler and costs the player their connection, and an unreadable gesture is worth a log line.
         */
        private boolean taken(Object message) {
            try {
                if (message instanceof ServerboundPlayerActionPacket packet) {
                    if (isDrop(packet)) return handler.drop();

                    if (packet.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
                        // Read and passed on, never taken: a menu has no business ending a use it did not start,
                        // and on a server that is not using anything the packet does nothing to pass on.
                        handler.useReleased();
                        return false;
                    }
                }
                if (message instanceof ServerboundUseItemPacket) {
                    // The only one of the three that means the click hit nothing, which one focus mode turns on.
                    return rightClick(true);
                }
                if (message instanceof ServerboundUseItemOnPacket || message instanceof ServerboundInteractPacket) {
                    // Block and entity - one route in, whatever the player is aiming at.
                    return rightClick(false);
                }
                if (message instanceof ServerboundAttackPacket) {
                    // A client-only entity in front of a wall is what the client aims at instead of the
                    // wall, and the server has no such entity to raise an event for.
                    return handler.leftClick();
                }
                return false;
            } catch (Throwable failure) {
                Bukkit.getLogger().log(Level.SEVERE, "MapGUI failed to read a gesture", failure);
                return false;
            }
        }

        /**
         * True when the click was taken. A duplicate inside the window answers the same way the first half
         * did: taken means passing it on would act twice, declined means it was never ours to eat.
         *
         * <p>The dedup is also what keeps {@code air} honest. A click at a block sends the block packet and then
         * the use-item one, so the second would look like a click at nothing - but it arrives inside the window
         * and is answered by the first rather than asked about.
         */
        private boolean rightClick(boolean air) {
            long now = System.currentTimeMillis();
            if (now - lastClick < SAME_CLICK_MS) return lastTaken;

            lastClick = now;
            lastTaken = air ? handler.rightClickAir() : handler.rightClick();
            return lastTaken;
        }

        /** Ctrl-Q counts too, since it is the same key and the same intent. */
        private static boolean isDrop(ServerboundPlayerActionPacket packet) {
            return packet.getAction() == ServerboundPlayerActionPacket.Action.DROP_ITEM
                    || packet.getAction() == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS;
        }
    }
}
