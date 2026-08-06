package de.flog99.mapgui;

import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Something with a name and an id that can be handed where a viewer is expected.
 *
 * <p>A proxy rather than a class, because {@code Player} is hundreds of methods and the code under test wants
 * two of them: who this is, so a wall can remember what it has sent them. Anything else answers null, which is
 * loud enough if a test ever reaches further than it meant to.
 */
final class FakePlayer {

    private FakePlayer() {
    }

    static Player named(String name) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));

        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakePlayer(" + name + ")";
                    default -> null;
                }
        );
    }
}
