package de.flog99.mapgui.ui;

import java.util.function.UnaryOperator;

/**
 * A value that repaints the screen when it changes.
 *
 * <p>The point of routing state through this instead of a plain field is that the screen finds
 * out on its own, rather than relying on every caller to remember to invalidate.
 */
public final class State<T> {

    private T value;
    private Runnable listener = () -> {
    };

    public State(T initial) {
        this.value = initial;
    }

    public T get() {
        return value;
    }

    public void set(T next) {
        if (java.util.Objects.equals(value, next)) return;

        value = next;
        listener.run();
    }

    public void update(UnaryOperator<T> mapper) {
        set(mapper.apply(value));
    }

    /** Wired up by the screen that owns this state. */
    public void onChange(Runnable action) {
        this.listener = action;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
