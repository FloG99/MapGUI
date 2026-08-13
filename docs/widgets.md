# Widgets and styling

`Row` `Column` `Overlay` `Scroll` · `Text` `Button` `Toggle` `Field` · `Spacer` `Divider` `Box` `Spinner` · `Draw`

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

`Gap(width, height)` is empty space of a fixed size, for holding a slot open when what goes in it is not there -
a control a server has turned off, an icon that has not loaded. `hidden(true)` takes the space with it, so a row
of three controls becomes a row of two and everything shifts.

`place(justify, align)` positions a node inside an `Overlay` - a badge in a corner, a label across the middle.
It is on `Node` itself, so a method handed plain nodes can still position them:

```java
static Node bar(Node left, Node middle, Node right) {
    return Overlay(
            left.place(Justify.START, Align.CENTER),
            middle.place(Justify.CENTER, Align.CENTER),
            right.place(Justify.END, Align.CENTER)
    ).fillWidth();
}
```

`Image(bufferedImage)` puts a picture from a file in a layout, drawn a pixel for a pixel. A null image draws
nothing, so the node's own background is what shows when an asset is missing.

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

### Shapes

The painter draws shapes, not just pixels, and every one of them takes the same two arguments a box does: a
`Fill` for the inside and a `Border` for the outline.

```java
painter.triangle(10, 40, 30, 5, 50, 40, Fill.solid(GOLD), Border.solid(2, BLACK));
painter.circle(64, 64, 20, Fill.gradient(TOP, BOTTOM, VERTICAL), Border.none());
painter.polygon(xs, ys, null, Border.solid(3, RED));
painter.line(0, 0, 128, 128, WHITE, 4);
painter.polyline(xs, ys, WHITE, 2);
```

Thickness works on all of them because the outline is defined rather than drawn: it is every pixel inside the
shape within the border's width of somewhere outside it. A shape of your own is one method - implement
`Shape.contains` and hand it to `painter.shape(..)`, and it gets fills, outlines and thickness for free.

Because a shape is only "is this pixel inside", shapes combine:

| | |
|---|---|
| `a.intersectionWith(b)` | only where both cover |
| `a.combinedWith(b)` | wherever either covers |
| `a.without(b)` | `a` with `b` cut out |
| `a.holeIn(box)` | the box with `a` punched out of it |

Which is how an area none of the factories draws gets described rather than plotted a row at a time. A camera
iris is an octagon that turns as it shrinks, and the blades are everything around it:

```java
Shape opening = Shape.regularPolygon(cx, cy, reach, 8, turnDegrees);
painter.shape(opening.holeIn(bounds), Fill.solid(BLADE), null);
```

That is not slower than plotting it yourself: a shape is drawn a row at a time via `spansAt`, so an octagon costs
eight sums per row rather than eight per pixel - outlined ones included. A shape of your own gets the slower
pixel-by-pixel path unless it implements `spansAt` too.

`Shape.sideOfLine(box, x1, y1, x2, y2)` is a straight cut across a box, keeping what is to the right of the
arrow. Several of those intersected describe any convex area between them.

`pushClip(shape)` clips to a shape instead of a box, so a picture can sit in a round window. Unlike masking
afterwards it applies to whatever draws next, including text and images, which have no shape of their own.

### Fonts

The vanilla map font is the default and needs nothing. For anything else, `AwtFont` takes any font the JVM can
load - a TrueType file shipped with your plugin, at any size - and rasterizes each glyph once. A screen picks
its font by overriding `font()`:

```java
private static final TextFont TITLE = AwtFont.load(MyPlugin.fontFile(), 16f, true);

@Override
public TextFont font() {
    return TITLE;
}
```

Load it once and hand back the same instance - a font caches a rasterized glyph per character, so building one
per call rasterizes the alphabet again every frame. One font per screen rather than per label, because
measuring and painting have to agree: a layout sized with one font and drawn with another puts the words in
the wrong place. For a heading in a different face, draw it with `ComponentText` inside a `Draw` node.

The last argument is anti-aliasing. Glyphs are rendered to coverage rather than on-or-off pixels, so
part-covered edges are blended with what is behind them. Worth having at large sizes and mostly noise at small
ones, which is why it is a choice.

### Adventure components

Anything that already exists as a `Component` - a MiniMessage string from a config, an item's display name, a
chat line - can be drawn with its own colors and styles:

```java
ComponentText.draw(painter, x, y, component, fallbackColor, true);
```

Runs are drawn in the colors the component author wrote. Bold uses the font's own bold weight where it has one
and the vanilla double-draw trick where it does not; underline and strikethrough are ruled in the run's color.
Obfuscated is not animated - a map redraws on its own clock, and scrambling it every frame would send the
whole line every frame with it.

To put one in a layout rather than paint it yourself, `RichText` is a node like any other:

```java
Column(
    RichText.of(() -> miniMessage.deserialize(config.getString("title"))).shadow(),
    Text(() -> "and an ordinary label under it")
)
```

It measures the way it draws, so it sizes and aligns like anything else. One line, though: wrapping styled text
means cutting runs at the break, which is a different job from cutting a string - so wrapping, ellipsis and
scrolling stay with `Text`, on plain text.

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

## Waiting

`Spinner()` is a ring of dots with a bright one travelling round it, for work that is happening but cannot say how
far along it is:

```java
Column(Spinner().color(theme().muted()), Text("Loading textures"))
        .gap(3).justify(Justify.CENTER).align(Align.CENTER).fill()
```

Which is most work, honestly. A progress bar needs a total, and the things a screen waits on - a download whose
length nobody was told, a capture, a query - usually cannot give one. **A percentage that sits at zero for twenty
seconds reads as broken; a spinner reads as busy**, which is the truth. Keep the number for a command, where a
reader wanting one can go and ask.

`size`, `dots`, `period` and `color` are the knobs, and the defaults are a 14-pixel ring of eight, one turn a
second. It steps from dot to dot rather than sliding between them: on a 128-pixel map of 61 colours a smooth fade
lands on the same few indices anyway, so snapping is crisper to look at *and* cheaper to send.

`size` is a limit rather than an order. A ring only lands on whole pixels when the gap between two facing dots is
even, so a size that would leave an odd one draws the largest ring under it that does, and the node measures itself
at that - never a box with a spare row and column down one side. What you get back is symmetric under a quarter
turn and under both mirrors, which is as round as eight squares on a grid this small can be.

**It never finishes by itself**, so it costs frames for as long as it is on screen - see
[animation](animation.md#frame-limits). That is fine at a dozen pixels square and is not fine across a whole
canvas, which is 16 KB a frame. Take it off screen when the thing it was waiting for arrives. A screen with
animation turned off draws it standing still rather than repainting forever.

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
