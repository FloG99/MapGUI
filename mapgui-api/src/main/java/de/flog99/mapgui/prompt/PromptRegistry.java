package de.flog99.mapgui.prompt;

import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Named prompt providers. Third-party plugins register their own here - an on-screen keyboard,
 * a sign editor, whatever - and screens can then ask for it by key.
 */
public interface PromptRegistry {

    void register(String key, PromptProvider provider);

    boolean unregister(String key);

    @Nullable
    PromptProvider get(String key);

    /** The provider chosen in config, used when a field doesn't name one. */
    PromptProvider getDefault();

    Set<String> keys();
}
