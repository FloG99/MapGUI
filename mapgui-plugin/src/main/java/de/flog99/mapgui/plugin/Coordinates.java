package de.flog99.mapgui.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/** Coordinates you can click to go and look at whatever is being talked about. */
public final class Coordinates {

    private Coordinates() {
    }

    public static Component link(double x, double y, double z) {
        String target = Math.round(x) + " " + Math.round(y) + " " + Math.round(z);
        return Component.text("[" + target + "]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/tp " + target))
                .hoverEvent(HoverEvent.showText(Component.text("Click to teleport here")));
    }
}
