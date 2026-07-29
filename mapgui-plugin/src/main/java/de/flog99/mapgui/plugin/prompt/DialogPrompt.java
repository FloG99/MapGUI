package de.flog99.mapgui.plugin.prompt;

import de.flog99.mapgui.prompt.PromptProvider;
import de.flog99.mapgui.prompt.TextPrompt;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A native Minecraft dialog - the default, since it is a real text field rather than an
 * inventory pretending to be one, and needs nothing bundled.
 *
 * <p>Nothing tells us when a player dismisses a dialog without answering, so a timeout makes
 * sure the menu can never stay suspended forever.
 */
public final class DialogPrompt implements PromptProvider {

    private static final String FIELD = "value";
    private static final long TIMEOUT_TICKS = 20L * 30;

    private final Plugin plugin;
    private final Map<UUID, CompletableFuture<Optional<String>>> pending = new ConcurrentHashMap<>();

    public DialogPrompt(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(Capability.TITLE, Capability.PREFILL, Capability.LONG_TEXT);
    }

    @Override
    public CompletableFuture<Optional<String>> promptText(Player player, TextPrompt prompt) {
        CompletableFuture<Optional<String>> future = new CompletableFuture<>();
        pending.put(player.getUniqueId(), future);

        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(prompt.title())
                        .inputs(List.of(DialogInput.text(FIELD, Component.empty())
                                .maxLength(prompt.maxLength())
                                .initial(prompt.initial())
                                .build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(
                                Component.text("Confirm"),
                                null,
                                100,
                                DialogAction.customClick((view, audience) -> {
                                    String value = view.getText(FIELD);
                                    complete(player, prompt.accepts(value) ? Optional.of(value) : Optional.empty());
                                }, ClickCallback.Options.builder().build())),
                        ActionButton.create(Component.text("Cancel"), null, 100, null)
                ))
        ));

        player.getScheduler().runDelayed(plugin, task -> complete(player, Optional.empty()), null, TIMEOUT_TICKS);
        return future;
    }

    @Override
    public void cancel(Player player) {
        complete(player, Optional.empty());
    }

    private void complete(Player player, Optional<String> result) {
        CompletableFuture<Optional<String>> future = pending.remove(player.getUniqueId());
        if (future != null) {
            future.complete(result);
        }
    }
}
