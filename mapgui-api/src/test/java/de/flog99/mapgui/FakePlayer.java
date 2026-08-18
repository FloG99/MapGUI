package de.flog99.mapgui;

import org.bukkit.Location;
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
 *
 * <p>{@link Watcher} adds the third thing a wall reads - where they are and which way they are facing.
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

    /** The same with somewhere to stand, for a wall that decides what to send by where a viewer is looking. */
    static Watcher watching(String name, Location eye) {
        return new Watcher(name, eye);
    }

    /**
     * A viewer whose eye can be moved between ticks, which is the whole of what a wall reads off them.
     *
     * <p>Assignable rather than passed per call, because turning round mid-tick is not a thing that happens
     * and a test that reads like one would be lying about what it exercises.
     */
    static final class Watcher {

        final UUID id;
        final Player player;

        Location eye;

        private Watcher(String name, Location eye) {
            this.id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
            this.eye = eye;
            this.player = (Player) Proxy.newProxyInstance(
                    Player.class.getClassLoader(),
                    new Class<?>[]{Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getEyeLocation", "getLocation" -> this.eye;
                        case "getUniqueId" -> id;
                        case "getName" -> name;
                        case "hashCode" -> id.hashCode();
                        case "equals" -> proxy == args[0];
                        case "toString" -> "FakeWatcher(" + name + ")";
                        default -> null;
                    }
            );
        }
    }
}
