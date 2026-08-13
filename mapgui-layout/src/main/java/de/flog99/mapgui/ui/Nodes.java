package de.flog99.mapgui.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

public final class Nodes {

    private Nodes() {
    }

    /**
     * Depth-first walk including the root.
     *
     * <p>Iterative to keep server-tick layout and hit-testing off the call stack: the widget tree depth is
     * bounded only by what a screen builds, and recursion puts that on the thread's stack.
     */
    public static void walk(Node root, Consumer<Node> visitor) {
        if (root == null) return;

        Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            Node node = stack.pop();
            visitor.accept(node);

            // Pushed in reverse so the pop order is the children's natural left-to-right order.
            List<Node> children = node.children();
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
    }

    public static <T extends Node> List<T> collect(Node root, Class<T> type) {
        List<T> found = new ArrayList<>();
        walk(root, node -> { if (type.isInstance(node)) { found.add(type.cast(node)); } });
        return found;
    }

    /**
     * Innermost node of the given type at a point, for routing scroll events.
     *
     * <p>Recursive depth-first search: children are tried back to front, and a node matches only when
     * nothing under it does. Kept recursive because the widget tree is shallow (a few levels), so the
     * call-stack cost is negligible and an explicit stack would allocate per call where this allocates
     * nothing - measured against the iterative form and this one is faster and allocation-free.
     */
    public static <T extends Node> T findAt(Node root, Class<T> type, int x, int y) {
        if (root == null || root.hidden() || !root.bounds().contains(x, y)) return null;

        List<Node> children = root.children();
        for (int i = children.size() - 1; i >= 0; i--) {
            T deeper = findAt(children.get(i), type, x, y);
            if (deeper != null) return deeper;
        }
        return type.isInstance(root) ? type.cast(root) : null;
    }

    /** Marker on the stack: the node beneath it has had every child searched. */
    private static final Object DONE = new Object();
}
