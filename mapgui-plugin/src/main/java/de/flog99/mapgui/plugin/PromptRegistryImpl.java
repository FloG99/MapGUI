package de.flog99.mapgui.plugin;

import de.flog99.mapgui.prompt.PromptProvider;
import de.flog99.mapgui.prompt.PromptRegistry;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class PromptRegistryImpl implements PromptRegistry {

    private final Map<String, PromptProvider> providers = new LinkedHashMap<>();
    private String defaultKey = "dialog";

    @Override
    public void register(String key, PromptProvider provider) {
        providers.put(normalized(key), provider);
    }

    @Override
    public boolean unregister(String key) {
        return providers.remove(normalized(key)) != null;
    }

    @Override
    @Nullable
    public PromptProvider get(String key) {
        return key == null ? null : providers.get(normalized(key));
    }

    /**
     * The one named in config, or any other rather than nothing.
     *
     * @throws IllegalStateException if every provider has been unregistered - a text field with nowhere to
     *         send the player is worth saying out loud rather than papering over
     */
    @Override
    public PromptProvider getDefault() {
        PromptProvider provider = providers.get(defaultKey);
        if (provider != null) return provider;
        if (providers.isEmpty()) throw new IllegalStateException("No prompt provider is registered");

        return providers.values().iterator().next();
    }

    /** In registration order, so anything listing them lists the three that ship in the order config names. */
    @Override
    public Set<String> keys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(providers.keySet()));
    }

    void setDefault(String key) {
        this.defaultKey = key == null ? "dialog" : normalized(key);
    }

    private static String normalized(String key) {
        return key.toLowerCase(Locale.ROOT);
    }
}
