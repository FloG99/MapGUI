package de.flog99.mapgui.plugin.camera;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Entity;

/**
 * The three jokes vanilla hides behind a name tag.
 *
 * <p>Called Dinnerbone or Grumm, anything alive stands on its head. Called Toast, a rabbit wears the coat of somebody's
 * lost pet. Called jeb_, a sheep's fleece will not stop changing color. All three are in the client - the first in
 * {@code LivingEntityRenderer}, the second in {@code RabbitRenderer}, the third in {@code SheepRenderState} - and all
 * three are what somebody who has bothered to name a mob is expecting to see.
 *
 * <p>The name is compared as plain text, which is the client's own rule: it strips the formatting and compares what
 * is left, so a Dinnerbone written in gold is still upside down.
 */
final class MobNames {

    /** Either of the two the client turns over, which are the two developers who were about at the time. */
    private static final String UPSIDE_DOWN = "Dinnerbone";

    private static final String ALSO_UPSIDE_DOWN = "Grumm";

    /** The rabbit, and the coat it puts on - which the assets name after it, so nothing else has to. */
    static final String TOAST = "Toast";

    /** The sheep. */
    static final String JEB = "jeb_";

    private MobNames() {
    }

    /** Whether this entity carries that name, which for an unnamed one is never. */
    static boolean named(Entity entity, String name) {
        return name.equals(of(entity));
    }

    /** Whether the client would draw this one on its head. */
    static boolean upsideDown(Entity entity) {
        String name = of(entity);
        return UPSIDE_DOWN.equals(name) || ALSO_UPSIDE_DOWN.equals(name);
    }

    /** The name on the tag as plain text, or null for a mob nobody has named. */
    private static String of(Entity entity) {
        Component name = entity.customName();
        return name == null ? null : PlainTextComponentSerializer.plainText().serialize(name);
    }
}
