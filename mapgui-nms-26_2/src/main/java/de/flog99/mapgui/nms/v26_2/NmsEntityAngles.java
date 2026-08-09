package de.flog99.mapgui.nms.v26_2;

import de.flog99.mapgui.camera.EntityAngles;
import net.minecraft.world.entity.animal.squid.Squid;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Entity;

/**
 * The squid's own two angles, straight off the entity.
 *
 * <p>Both are ordinary server-side fields that {@code Squid.aiStep} keeps up to date, so this is a read and nothing
 * more - no packet, no reflection, no state of our own.
 */
public final class NmsEntityAngles implements EntityAngles {

    @Override
    public float[] swimming(Entity entity) {
        if (!(entity instanceof CraftEntity craft) || !(craft.getHandle() instanceof Squid squid)) return null;

        return new float[]{squid.xBodyRot, squid.zBodyRot};
    }
}
