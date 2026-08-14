# Getting started

## Depending on it

```kotlin
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.github.flog99:mapgui-api:1.1.0")
}
```

`mapgui-api` brings the layout DSL with it, so that one line is everything.

```yaml
# paper-plugin.yml
dependencies:
  server:
    MapGUI:
      load: BEFORE
      required: true
      join-classpath: true
```

`compileOnly` on purpose: your plugin compiles against the API and the implementation arrives at runtime from
the MapGUI plugin the server owner installed. Shading it in would put a second copy on the classpath.

`join-classpath` is what lets your plugin see those classes at runtime. Without it you get a
`NoClassDefFoundError` the first time a screen is opened.

The version you compile against must not be newer than the plugin on the server, or you get a
`NoSuchMethodError` for whatever was added in between. Same number in both places is the simple rule.

Working against an unreleased change is `./gradlew publishToMavenLocal` in a clone, then `mavenLocal()` and
the `io.github.flog99:mapgui-api:1.0.0-SNAPSHOT` coordinates.

### Without a build tool

`mapgui-api.jar` is attached to every [release](https://github.com/FloG99/MapGUI/releases), with a sources
jar, for dropping into a `libs/` folder. The layout DSL is inside it, so that one file is everything, and it
belongs on the compile path only, exactly as `compileOnly` above. The `paper-plugin.yml` block is the same
either way.

## A screen

A screen is a class with one job: describe itself.

```java
import static de.flog99.mapgui.ui.Ui.*;

public final class CounterScreen extends Screen {

    private final State<Integer> count = state(0);

    @Override
    public Component title() {
        return Component.text("Counter");
    }

    @Override
    protected Node build() {
        return Column(
                Row(
                        Text("Counter").color(Color.WHITE).shadow(),
                        Spacer(),                                    // pushes the badge right
                        Text(count::toString).color(Color.WHITE)
                                .padding(1, 4).background(ACCENT).radius(5)
                ).gap(4).align(Align.CENTER),
                Divider(ACCENT),
                Spacer(),
                Button("Add one")
                        .background(ACCENT).radius(4).textColor(Color.WHITE)
                        .hoverBackground(Color.WHITE).hoverTextColor(ACCENT)
                        .onClick(() -> count.update(value -> value + 1))
                        .fillWidth()
        ).gap(4).padding(6).align(Align.STRETCH);
    }
}
```

```java
MapGui.get().open(player, new CounterScreen());
```

`build()` runs when state changes, not every tick. Hovering never rebuilds anything - it is a paint-time
style variant.

`state(...)` is the hook: assigning through it marks the screen dirty. Plain fields do not, so change one
and call `invalidate()` yourself, or reach for `state` and forget about it.

## The canvas is not always 128 pixels

`width()` and `height()` say how big it is - 128 square in the hand, and as big as its grid on a wall.
Anything centering itself or drawing to an edge should ask rather than assume.

## Lifecycle

| | |
|---|---|
| `onOpen()` | after the screen is attached to a session. Where `watch(model)` goes |
| `onClose()` | when it is popped, replaced, or its wall comes down |
| `session()` | the stack it is running in: `push`, `pop`, `close` |
| `player()` | who is looking. Always answers in the hand; see [walls](walls.md) for shared walls |

`push` opens a screen on top; `pop` returns. Closing the last one closes the menu in the hand and returns a
wall to its base screen, because a wall is furniture and does not vanish.

## Input

Right-click presses things, Q closes, the wheel scrolls. Both are read straight off the connection rather
than from events, because the events behind them only fire when the player has a real item in that slot -
and the map they can see is not in their inventory.

A screen can accept the other button, or both:

```java
@Override public Click activateOn() { return Click.BOTH; }
```

Right-click is the default because left-click plays the arm swing, which visibly jogs the map down on every
press. The client starts that before the server hears the click, so nothing can suppress it.

### Screens you only look at

A photo, a video, a minimap - nothing to press. Turning the cursor off drops the pointer, hover, clicks and
scrolling in one go, and leaves the player's head alone since there is nothing to aim at:

```java
@Override public boolean cursor() { return false; }
```

Q still closes it. That is a key, not something you point at.

### Leaving the player's head alone

By default the player's pitch is held inside the cursor's range, so they cannot look away from the map.
Anything meant to be used while moving about should turn that off and let the cursor stop at the edge:

```java
@Override public Boolean clampPitch() { return false; }
```

Null follows the server's `cursor.clamp-pitch`. A screen with no cursor never restricts the head whatever
either says, and neither does a map in the offhand - that one is glanced at while the player looks at the world,
so their aim is left alone. Movement is never restricted in any case.

The cursor appears in the middle of the map every time and does not move while it is hidden, so where it turns
up never depends on what the player was doing without it. That covers both ways it goes away: the screen losing
the mouse, and `cursor()` returning false for a while. Clamped, their head goes to the middle of the pitch range
with it; unclamped, nothing moves their head, so it starts nearer an edge when they are already looking far up or
down - there would be no head movement left to bring it back the other way.

### The wheel, when nothing scrollable is under it

```java
@Override
protected boolean onScroll(int notches) {
    palette.step(notches);
    return true;
}
```

A `Scroll` under the cursor always wins. Return true if you used it - on a wall that also decides whether
the player's hotbar selection is left alone, so claiming a turn you ignored would stop them changing items.

### The swap-hands key

```java
@Override
protected void onSwapHands() {
    shutter.press();
}
```

The one press that costs no aim, since both cursor axes come off the player's head. Called only for a map with
nothing to swap - MapGUI refuses the key there anyway - and never under `HandOptions.Focus.SWAP_HANDS`, where the
key is the focus toggle.

## Next

- [Widgets and styling](widgets.md) for what you can put in a `build()`
- [Walls](walls.md) to hang one on a block instead of a hand
- [Headless preview](preview.md) to iterate without restarting a server
