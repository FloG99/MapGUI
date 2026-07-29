# Widgets and styling

`Row` `Column` `Overlay` `Scroll` · `Text` `Button` `Toggle` `Field` · `Spacer` `Divider` `Box` · `Draw`

All of them come from one static import:

```java
import static de.flog99.mapgui.ui.Ui.*;
```

## Layout

Three ways a node decides its size, and that is the whole model:

| | |
|---|---|
| shrink-wrap | the default - as big as its content |
| `fill()` | claim a share of the leftover space |
| `Spacer()` | eat the leftover space, which is how you push things apart |

`gap`, `padding`, `align` and `justify` do what they look like. `align(Align.STRETCH)` on a column makes its
children as wide as the widest, which is usually what you want for a stack of buttons.

## Draw

The escape hatch: raw pixel access inside an auto-laid-out box, for graphs, icons, or anything the widget
set does not cover. Canvas-style drawing and the layout engine compose rather than competing.

Its click handler is told where inside the node the click landed, so a grid drawn as one node still knows
which cell was hit:

```java
Draw(this::paintGrid).onClick((x, y) -> select(x / 16, y / 16)).fill()
```

`tracksCursor()` asks for a repaint on every pixel of cursor movement, which anything drawing at the cursor
needs - see `/claims`, where an eight by eight grid with a hover highlight is one node.

## Reusing a look

```java
private static final Consumer<Button> FILLED = b -> b
        .background(ACCENT).radius(4).textColor(WHITE)
        .hoverBackground(WHITE).hoverTextColor(ACCENT).transition(220);

Button("Save").apply(FILLED).onClick(this::save)
```

## Lists

Rows built from a list should say what identifies each one. A node with no `key` is identified by its
position in the tree, so reordering an unkeyed list makes its scroll offsets and animations follow the
position rather than the row:

```java
Column(each(tasks, Task::id, this::taskRow))
```

## Themes

Colors come from a `Theme` rather than being hardcoded per screen, so overriding `theme()` restyles
everything below it:

```java
@Override
public Theme theme() {
    return Theme.DARK.withAccent(new Color(120, 90, 240));
}
```

## Borders

Flat or bevelled. `raised(2)` and `sunken(2)` work the light and dark shades out from the background, which
is how vanilla Minecraft widgets are drawn - so a panel looks native without you picking any colors:

```java
Box(theme().surface()).raised(2)      // pops out
Box(theme().surface()).sunken(2)      // pressed in
Box(theme().surface()).bevel(2, light, dark)
```

## Gradients

A fill rather than a color, and dithered when painted:

```java
Box(null).gradient(theme().accent(), theme().danger(), Fill.Direction.HORIZONTAL)
```

The palette is a few dozen base colors times four brightnesses, so snapping a ramp to the nearest entry
gives about four visible steps between two arbitrary hues - stripes, not a gradient. Mixing the two nearest
entries in a 4x4 pattern turns those four into roughly twenty apparent shades. Flat colors are never
dithered, since that would only add noise to a solid button.

For a fill with no endpoints at all - a rainbow, a sweep - use `fill(Fill)` with `phase(millis)`:

```java
Box(null).fill((x, y, bounds) ->
        Color.getHSBColor((float) ((x - bounds.x()) / (float) bounds.width() + phase(6000) % 1), 0.85f, 1f))
```

That never settles, so read [animation](animation.md) and [performance](performance.md) before putting one
on a wall.

## Corners

More than rounding. `ROUND` `BEVEL` `NOTCH` `STEP` are pixel-art shapes CSS can only fake with a clip path:

```java
Text("tab").corner(Corner.BEVEL, 6)
```

## Cursors and captions

MapGUI owns the pointer, so a node can change it while hovered. Named as a string rather than the type, so
the layout engine stays free of any server dependency:

```java
Button("Delete").cursorIcon("RED_X")
```

Any node can carry a tooltip that sits right under the pointer, with room for a few words:

```java
Toggle(on).caption("Show other players")
```

## Long text

Four ways to handle text that does not fit:

```java
Text(name)                    // ellipsis: ends it with ".."
Text(name).clip()             // cut off at the edge
Text(name).scroll()           // slides back and forth so it can all be read
Text(name).wrap()             // more lines
```

`scroll()` is the behavior Minecraft uses for its own over-long button labels - eased, with a dwell at each
end, rather than looping round like a marquee. It only animates while the text actually overflows, so a
label that fits costs nothing. Minecraft's period works out at roughly half a minute on a canvas this
small, so the default here is faster; `scroll(millis)` sets it yourself.

While it *is* overflowing it repaints every tick and never stops - around 16 KB/s for one player, per
label. Fine for a heading, worth avoiding for every row of a long list, where `clip().revealOnHover()` gives
you the same readability for nothing:

```java
Text(name).clip().revealOnHover()
```

That only appears while the text is genuinely cut off, so it never fires on a label that fits.

## Text input

Maps have no keyboard, so `Field` hands off to a prompt provider and comes back with a string. Two ship
with the plugin - `dialog` (a native Minecraft dialog, the default) and `anvil` - and the server owner picks
the default in `config.yml`.

Registering your own is one call:

```java
MapGui.get().prompts().register("keyboard", myOnScreenKeyboard);
```

Anything that is not free text belongs in a widget instead: `Toggle` for booleans, a pushed screen for
choices, `Field` only where someone genuinely has to type.

While a prompt is open the session is suspended, so head movement and clicks do not leak through to the
menu behind it. On a shared wall that is per player, so one person typing does not freeze the buttons for
everyone else standing in front of it.
