package de.flog99.mapgui.nms;

import de.flog99.mapgui.RotationController;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * Sets pitch absolutely while leaving position, yaw and momentum untouched.
 *
 * <p>The relative flags are the whole point: every axis listed keeps the value the client already
 * has, and only what is left - pitch - is taken from the packet. No API method can express that,
 * since they all set both rotation axes together.
 */
public final class NmsRotationController implements RotationController {

    /**
     * Everything stays as it is except pitch.
     *
     * <p>The DELTA flags matter as much as the position ones. Without them the packet's zero movement
     * vector is read as an absolute value and the player's momentum is wiped - obvious the moment you
     * open a menu mid-jump or mid-sprint.
     *
     * <p>ROTATE_DELTA is deliberately left out. It rotates existing momentum by the change in
     * rotation, so pushing someone's pitch down would tip their level movement into the ground.
     */
    private static final Set<Relative> EVERYTHING_BUT_PITCH = Set.of(
            Relative.X, Relative.Y, Relative.Z,
            Relative.Y_ROT,
            Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z
    );

    @Override
    public void setPitchKeepingYaw(Player player, float pitch) {
        ClientboundPlayerPositionPacket packet = new ClientboundPlayerPositionPacket(
                Integer.MAX_VALUE,
                new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0f, pitch),
                EVERYTHING_BUT_PITCH
        );

        ((CraftPlayer) player).getHandle().connection.send(packet);
    }
}
