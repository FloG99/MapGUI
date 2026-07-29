package de.flog99.mapgui.examples.claims;

import java.awt.Color;

/**
 * Who can own ground. A team <i>is</i> its color here - there is nothing else to a claim.
 *
 * <p>Deliberately few and far apart. Tinting terrain leaves very little of the color intact, so two
 * similar shades would be indistinguishable once they are on the map.
 */
public enum Team {

    RED("Team Red", new Color(220, 60, 60)),
    ORANGE("Team Orange", new Color(230, 140, 40)),
    YELLOW("Team Yellow", new Color(230, 210, 60)),
    GREEN("Team Green", new Color(80, 200, 90)),
    CYAN("Team Cyan", new Color(60, 200, 210)),
    BLUE("Team Blue", new Color(70, 120, 230)),
    PURPLE("Team Purple", new Color(160, 90, 230)),
    PINK("Team Pink", new Color(235, 120, 190));

    private final String label;
    private final Color color;

    Team(String label, Color color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return label;
    }

    public Color color() {
        return color;
    }
}
