package de.flog99.mapgui.plugin;

import de.flog99.mapgui.PacketInput;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * One packet listener per player, shared by everything that wants their clicks.
 *
 * <p>A connection takes one handler, so without this a held menu and a wall being placed fought over it.
 * Claims are offered the gesture newest first and the first to take it wins.
 *
 * <p>A claim that declines lets the packet through untouched, so something can stay claimed for as long as
 * it might be relevant - a wall a player is standing near - without eating clicks aimed elsewhere.
 *
 * <p>Read from the network thread and changed from the main one, hence the concurrent collections. A moment
 * out of date is fine: the worst case is one click going to whoever held the claim a tick ago.
 */
public final class InputRouter {

    private final PacketInput input;
    private final Map<UUID, List<PacketInput.Handler>> claims = new ConcurrentHashMap<>();

    public InputRouter(PacketInput input) {
        this.input = input;
    }

    /** Puts this handler first in line, and starts listening if nothing was. */
    public void claim(Player player, PacketInput.Handler handler) {
        List<PacketInput.Handler> queue = claims.computeIfAbsent(player.getUniqueId(), id -> new CopyOnWriteArrayList<>());

        queue.remove(handler);
        queue.add(0, handler);
        if (queue.size() == 1) {
            input.listen(player, new Dispatcher(player.getUniqueId()));
        }
    }

    /** Stops listening once the last claim on a player is gone, so idle players cost nothing. */
    public void release(Player player, PacketInput.Handler handler) {
        List<PacketInput.Handler> queue = claims.get(player.getUniqueId());
        if (queue == null) return;

        queue.remove(handler);
        if (!queue.isEmpty()) return;

        claims.remove(player.getUniqueId());
        input.forget(player);
    }

    /** For a player who has gone: their connection is closed, so only the bookkeeping is left. */
    public void releaseAll(Player player) {
        claims.remove(player.getUniqueId());
    }

    private final class Dispatcher implements PacketInput.Handler {

        private final UUID player;

        private Dispatcher(UUID player) {
            this.player = player;
        }

        @Override
        public boolean drop() {
            return offer(PacketInput.Handler::drop);
        }

        @Override
        public boolean rightClick() {
            return offer(PacketInput.Handler::rightClick);
        }

        @Override
        public boolean leftClick() {
            return offer(PacketInput.Handler::leftClick);
        }

        private boolean offer(Predicate<PacketInput.Handler> gesture) {
            List<PacketInput.Handler> queue = claims.get(player);
            if (queue == null) return false;

            for (PacketInput.Handler handler : queue) {
                if (gesture.test(handler)) return true;
            }
            return false;
        }
    }
}
