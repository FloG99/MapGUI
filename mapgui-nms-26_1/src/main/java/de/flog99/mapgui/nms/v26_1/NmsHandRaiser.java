package de.flog99.mapgui.nms.v26_1;

import de.flog99.mapgui.HandRaiser;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;

/**
 * Sends one player their own "using an item" flag, which the server never would.
 *
 * <p>An entity's flags reach everyone who can see it <b>except the player it belongs to</b> - a client is told
 * about the world, not about itself, and works its own using out from the button it saw pressed. Sending it here
 * anyway is what lets the server decide instead: {@code LocalPlayer.onSyncedDataUpdated} reads the flag, sees it
 * has not started a use of its own, and starts one. Nothing else runs, which is exactly what is wanted - the
 * animation that drops the held item lives in the press handler this goes around.
 */
public final class NmsHandRaiser implements HandRaiser {

    /** Using an item, and which hand it is in. The bits are the client's, so they are named rather than borrowed. */
    private static final int USING = 1;
    private static final int OFF_HAND = 2;

    @Override
    public void raise(Player player, EquipmentSlot hand, boolean raised) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        byte real = handle.getEntityData().get(Flags.ACCESSOR);
        // Letting go sends the truth back rather than clearing a bit, since the server may really be using
        // something - a screen opening over a player halfway through a bite has not stopped them eating.
        byte told = raised ? hand(real, hand) : real;

        handle.connection.send(new ClientboundSetEntityDataPacket(
                handle.getId(), List.of(SynchedEntityData.DataValue.create(Flags.ACCESSOR, told))));
    }

    private static byte hand(byte flags, EquipmentSlot hand) {
        int using = flags | USING;
        return (byte) (hand == EquipmentSlot.OFF_HAND ? using | OFF_HAND : using & ~OFF_HAND);
    }

    /**
     * The accessor for those flags, which {@link LivingEntity} keeps to itself and its subclasses.
     *
     * <p>Nothing is ever built from this class and nothing ever could be - it exists for the one field, which is
     * reachable by extending the class that holds it and no other way that the compiler can check.
     */
    private abstract static class Flags extends LivingEntity {

        static final EntityDataAccessor<Byte> ACCESSOR = DATA_LIVING_ENTITY_FLAGS;

        private Flags(EntityType<? extends LivingEntity> type, Level level) {
            super(type, level);
        }
    }
}
