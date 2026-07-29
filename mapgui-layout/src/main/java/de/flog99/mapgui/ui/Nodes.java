package de.flog99.mapgui.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class Nodes {

    private Nodes() {
    }

    /** Depth-first walk including the root. */
    public static void walk(Node root, Consumer<Node> visitor) {
        if (root == null) return;

        visitor.accept(root);
        for (Node child : root.children()) {
            walk(child, visitor);
        }
    }

    public static <T extends Node> List<T> collect(Node root, Class<T> type) {
        List<T> found = new ArrayList<>();
        walk(root, node -> { if (type.isInstance(node)) { found.add(type.cast(node)); } });
        return found;
    }

    /** Innermost node of the given type at a point, for routing scroll events. */
    public static <T extends Node> T findAt(Node root, Class<T> type, int x, int y) {
        if (root == null || root.hidden() || !root.bounds().contains(x, y)) return null;

        List<Node> children = root.children();
        for (int i = children.size() - 1; i >= 0; i--) {
            T deeper = findAt(children.get(i), type, x, y);
            if (deeper != null) return deeper;
        }
        return type.isInstance(root) ? type.cast(root) : null;
    }
}
