package de.flog99.mapgui.plugin.prompt;

import de.flog99.mapgui.prompt.PromptProvider;
import de.flog99.mapgui.prompt.TextPrompt;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.AnvilView;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renames an item to collect text.
 *
 * <p>Written against Paper's {@code MenuType} API rather than pulling in a library. The repair
 * cost is zeroed so the anvil never demands levels, and the result slot is the only place a
 * click is allowed through.
 */
public final class AnvilPrompt implements PromptProvider, Listener {

    private static final int RESULT_SLOT = 2;

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(TextPrompt prompt, CompletableFuture<Optional<String>> future, AnvilView view) {
    }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(Capability.TITLE, Capability.PREFILL, Capability.LIVE_VALIDATION);
    }

    @Override
    public CompletableFuture<Optional<String>> promptText(Player player, TextPrompt prompt) {
        CompletableFuture<Optional<String>> future = new CompletableFuture<>();

        AnvilView view = MenuType.ANVIL.create(player, prompt.title());
        view.setRepairCost(0);
        view.setMaximumRepairCost(0);

        ItemStack input = new ItemStack(Material.NAME_TAG);
        // The display name seeds the rename field, and it has to be non-empty to show at all.
        input.editMeta(meta -> meta.displayName(Component.text(prompt.initial().isEmpty() ? " " : prompt.initial())));
        view.getTopInventory().setItem(0, input);

        pending.put(player.getUniqueId(), new Pending(prompt, future, view));
        player.openInventory(view);
        return future;
    }

    @Override
    public void cancel(Player player) {
        finish(player, Optional.empty());
        player.closeInventory();
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Pending waiting = pending.get(player.getUniqueId());
        if (waiting == null || !event.getView().equals(waiting.view())) return;

        event.setCancelled(true);
        if (event.getRawSlot() != RESULT_SLOT) return;

        String value = waiting.view().getRenameText();
        if (value == null) {
            value = "";
        }
        if (!waiting.prompt().accepts(value.trim())) return;

        finish(player, Optional.of(value.trim()));
        player.closeInventory();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Pending waiting = pending.get(player.getUniqueId());
        if (waiting != null && event.getView().equals(waiting.view())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        finish(player, Optional.empty());
    }

    private void finish(Player player, Optional<String> result) {
        Pending waiting = pending.remove(player.getUniqueId());
        if (waiting == null) return;

        waiting.view().getTopInventory().clear();
        waiting.future().complete(result);
    }
}
