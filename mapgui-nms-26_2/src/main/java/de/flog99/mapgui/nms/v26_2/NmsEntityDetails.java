package de.flog99.mapgui.nms.v26_2;

import de.flog99.mapgui.camera.EntityDetails;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.squid.Squid;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Entity;

/**
 * The few things a mob is drawn from that only the server has, straight off the entity.
 *
 * <p>All ordinary server-side state that the entity's own tick keeps up to date, so these are reads and nothing
 * more - no packet, no reflection, no state of our own.
 */
public final class NmsEntityDetails implements EntityDetails {

    @Override
    public float[] swimming(Entity entity) {
        if (!(entity instanceof CraftEntity craft) || !(craft.getHandle() instanceof Squid squid)) return null;

        return new float[]{squid.xBodyRot, squid.zBodyRot};
    }

    @Override
    public boolean offeringFlower(Entity entity) {
        return entity instanceof CraftEntity craft
                && craft.getHandle() instanceof IronGolem golem
                && golem.getOfferFlowerTick() > 0;
    }
}
