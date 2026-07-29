package de.flog99.mapgui.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public abstract class AbstractContainer<S extends AbstractContainer<S>> extends AbstractNode<S> {

    protected final List<Node> childNodes = new ArrayList<>();

    public S children(Node... nodes) {
        for (Node node : nodes) {
            if (node != null) {
                childNodes.add(node);
            }
        }
        return self();
    }

    public S children(Collection<? extends Node> nodes) {
        for (Node node : nodes) {
            if (node != null) {
                childNodes.add(node);
            }
        }
        return self();
    }

    @Override
    public List<Node> children() {
        return childNodes;
    }

    protected List<Node> visibleChildren() {
        List<Node> visible = new ArrayList<>(childNodes.size());
        for (Node node : childNodes) {
            if (!node.hidden()) {
                visible.add(node);
            }
        }
        return visible;
    }

    @Override
    protected void paintContent(Painter painter) {
        for (Node node : childNodes) {
            node.paint(painter);
        }
    }
}
