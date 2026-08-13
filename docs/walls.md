# Walls

A grid of maps hung on blocks, showing one picture - a video, a mural, or a menu you can use.

None of it is real. The item frames exist only on clients: invisible, so a grid reads as one picture, and
glowing, so it stays lit at night. There is no frame to break, no map inside it, and nothing left behind by
a restart.

## Video walls, without writing anything

Drop a GIF in `plugins/MapGUI/videos/`, then:

```
/mapgui wall place bunny.gif
```

Left-click the block you want as the **bottom left** corner, look at the far corner to size it - up to 6x6 -
then left-click to place, or right-click to cancel.

What you drag out is the real thing, held on one frame: the preview *is* a wall whose only viewer is you, so
there is no separate preview to disagree with the result. Blocks that are covered or missing are painted
over in red and refuse to be placed.

`/mapgui wall place` with no argument lists everything placeable, and every line is clickable.
`/mapgui wall list` shows what exists. `/mapgui wall remove` takes down the nearest one within 32 blocks, or
name one to reach further.

Only the *wall* persists, in `walls.yml`. That is what lets a player who joins next week see it: they are
sent it when they come into range, exactly like someone walking up to it. The same has to happen for anyone
who walks out and back, since clients throw away entities whose chunk unloads.

### A loop sent once instead of forever

A wall that plays the same few seconds over and over does not have to be streamed. Ask for it to be
prerendered and MapGUI paints the loop up front, sends every frame once under its own set of map ids, and then
plays it by telling each client which set to show:

```java
MapGui.get().wall()
        .at(block, face)
        .size(2, 2)
        .content(WallContent.video(video))
        .prerender(video.frames().count(), video.frames().durationMs())
        .open();
```

Measured side by side on one server, one viewer, two walls: the streamed one ran at about 3 Mbit/s and the
prerendered one settled at nothing at all once it had arrived. The trade is memory and a burst: every step is a complete copy of the wall held here and in each viewer's client,
and all of it goes out at once when somebody walks into range. Twelve steps of a 3x3 wall is 1.7 MB per
client, which pays for itself in a couple of seconds of playback. Steps are capped at 32.

Only for `content`, and only when the content repeats exactly - the steps are painted once and never again, so
anything reading the world, the clock or the viewer freezes as it was. A menu cannot be prerendered at all,
since it has to answer clicks.

### Floors and ceilings

They work, with one thing you do not get to choose: which way the picture faces. An item frame on a
horizontal face always has a yaw of zero, and the client recomputes that from the facing it is sent rather
than reading ours, so every floor frame is drawn at the same angle - top toward north on a floor, toward
south on a ceiling. MapGUI matches that instead of letting you pick, because a picture and a cursor a
quarter turn apart is worse than a picture facing a direction you did not ask for.

> [!WARNING]
> A wall multiplies bandwidth by the number of maps. Each is 16 KB per frame, so a 2x2 at 10 fps is
> 640 KB/s - **5.2 Mbit/s per viewer** - and a 6x6 is nine times that. Only players within
> `walls.view-distance` are sent anything, and only the tiles that actually changed, so a mostly-still wall
> in an empty room costs nothing. Full-screen video with an audience does not. See
> [performance](performance.md).

## Menus on walls

The same `Screen` runs on a wall or in the hand - there is no wall-specific subclass. Your plugin opens one
and decides who shares it:

```java
WallDisplay wall = MapGui.get().wall()
        .at(block, BlockFace.SOUTH)   // bottom-left block and the face, straight from a click
        .size(2, 2)
        .screenForEveryone(new JukeboxScreen())      // one menu the room shares
        .open();

wall.close();                         // when whatever owns it goes away
```

`screenPerPlayer(player -> new JukeboxScreen(jukebox))` instead gives every viewer their own, and hands the
factory whoever it is being built for - take `_` if the screen does not care who it belongs to. Nothing is
saved either way: your plugin already knows where its walls are, and a second copy of that here could only
disagree.

The choice is about **what is shared**, not about bandwidth. Map packets go to each client separately in both
modes, so one viewer receives the same bytes for the same pixels whichever you pick. What a per-player wall
multiplies is server-side: a surface pair, a paint pass and a terrain scan per viewer.

**Cursors are per viewer either way**, because they are drawn as map markers rather than pixels: the same
picture goes to everyone with a different pointer on top, for a few bytes rather than a frame. Other
people's pointers are off unless you ask for `showOtherCursors(true)`.

### Pointing

A wall is not a block, so aiming is solved as geometry rather than a ray trace against something real: where
the player's line of sight crosses the wall's plane, in front of it rather than behind, with nothing solid
in between. So it works from any angle and at any distance, a wall never answers to someone standing behind
it, and it never swallows a click meant for whatever is in front of it.

Where two menus line up, only the nearest one takes the click. The one behind gets no cursor, no hover and
no scroll either - not merely a click that goes elsewhere.

### Reaching the edges

The last row of pixels on a map is a strip a fraction of a block wide, which is genuinely hard to hold a ray
inside. `aimMargin` lets a viewer overshoot and keeps the cursor pinned to the edge:

```java
.aimMargin(20)     // about a sixth of a block past the border still counts
```

Around 20 suits drawing. Leave it at nought for a menu, where overshooting a button should miss it like any
other miss.

### Sizes your content actually works at

Not every layout survives being stretched to 6x6, and a picture drawn for one map gains nothing from being
placed on nine. Content can say so, and the placement gesture is then held inside what it allows:

```java
.fixedSize(2, 2)                  // this and nothing else
.sizeBetween(2, 1, 4, 3)          // min cols, min rows, max cols, max rows
.aspect(2, 1)                     // 2x1, 4x2 or 6x3 - whichever is nearest the drag
```

Sizing still works, it just has nothing left to do: the preview stays put however far the corner is dragged,
and the action bar says why. `aspect` is coarse by nature - a map is the unit, so a six-map side has a
handful of steps and a ratio like 16:9 has none at all.

## Shared and private at once

A map tile is one pixel buffer, so the moment any pixel differs between two viewers they each need their own
copy of that buffer. There is no arrangement where the picture is one buffer and each viewer's overlay
another - a map is delivered as finished pixels, with no layering on the client to exploit.

So "everyone edits the same thing, but with private controls" is `screenPerPlayer` over a **shared model
object**. Extend `SharedModel`, call `changed()` when it changes, and `watch(model)` from `onOpen()`:

```java
final class Jukebox extends SharedModel {
    private Track playing;

    void play(Track track) {
        playing = track;
        changed();            // every screen watching this redraws
    }
}
```

```java
final class JukeboxScreen extends Screen {
    private final Jukebox jukebox;

    @Override
    protected void onOpen() {
        watch(jukebox);
    }
}
```

That is what makes it *look* shared. Without it every screen but the one that was clicked keeps drawing what
it last read, so a claim someone else takes does not appear on your open map and two jukeboxes disagree
about the track.

Watching ends when the screen closes. There is deliberately no unwatch, because a listener holding a screen
keeps that screen, its state and its viewer alive for as long as the model does.

### Where you build the model decides how far it is shared

This is the one that bites, and nothing in the API can warn you about it - it falls out of ordinary Java
scope. A screen is one object **per wall**, so two walls are two screens, and what they have in common is
whatever model they were both handed.

```java
private final Jukebox jukebox = new Jukebox();          // a field: one for the whole server

guis().registerPlaceable("jukebox", "...", wall -> wall.screenForEveryone(new JukeboxScreen(jukebox)));
```

Every jukebox wall now plays the same track, which is the point of a jukebox.

```java
guis().registerPlaceable("draw", "...", wall -> {
    Drawing drawing = new Drawing();                    // inside: one per wall
    wall.screenPerPlayer(_ -> new DrawScreen(drawing)).fixedSize(2, 2);
});
```

Two drawing boards are now two pictures, which is the point of a whiteboard. Hoist that `Drawing` to a field
and every board in the world silently becomes the same canvas.

A resize while placing runs the registration again and discards the previous wall, so anything heavier than a
byte array belongs outside it - keyed by position, if it has to be per wall.

`examples/walls` is exactly this contrast: `draw` is per wall with a private palette, `jukebox` is one queue
for the server and shared by everyone looking.

## What a shared screen cannot do

Two things follow from there being one screen and many people:

- `player()` answers **only inside an input handler**. During `build()` or painting it throws, because the
  pixels go to everybody - failing loudly beats quietly drawing one person's data onto the room's wall.
  `screenPerPlayer` always answers.
- Hover and press highlights are pixels, so on a shared wall they follow whoever moved last.

Terrain works, centered on the **wall** rather than a player - and it is much cheaper than in the hand,
because a wall never moves, so the ground is scanned once and kept. Text fields work too; the prompt goes to
whoever clicked, and only they are suspended while typing.

## Making your GUI placeable

Opening walls yourself is right when your plugin knows where they go - furniture, a television, a painting.
When it does not, register the GUI instead and let an admin put it wherever they like:

```java
MapGui.get().guis().registerPlaceable("jukebox", "Jukebox - one queue the whole room shares", wall -> wall.screenForEveryone(new JukeboxScreen(jukebox)));
```

That is the entire integration. `/mapgui wall place jukebox` now sizes it against a live preview, saves where it
went, and puts it back up after a restart - MapGUI does the placing, the persistence and the reloading, and
your plugin never sees a command or a config file.

The builder arrives already positioned, sized and tuned from `config.yml`; override `fps`, `range` or the size
limits if your content needs its own.

### The same GUI in a hand

`registerOpenable` is the other half of the same catalog, and a GUI that suits either place just calls both
under one name:

```java
MapGui.get().guis().registerOpenable("jukebox", "Jukebox - one queue the whole room shares", player -> new JukeboxScreen(jukebox));
```

`/mapgui hand open jukebox` then hands it to somebody, and `/mapgui wall place jukebox` still hangs it on
blocks. One
name, one entry - see [configuration](configuration.md#commands) for the whole admin surface.

Note this is *administration*, not how your users reach a GUI. That stays yours: a command, an item, an NPC,
anything, calling `MapGui.get().open(player, screen)`.

### Unregistering

Only the name is stored, so register again on every startup - and **unregister when you disable**, which
closes anything showing it while your classes are still loaded. Placed walls stay in `walls.yml` and come back
when your plugin does.

```java
@Override
public void onDisable() {
    MapGui.get().guis().unregister("jukebox");   // both surfaces, one call
}
```

One call whether you registered one surface or both, which is the point of a single catalog: a missed
`unregister` leaves an entry pointing at classes that are about to be unloaded.

`examples/walls` is those registrations. The other route - a plugin opening a wall itself through `MapGui.wall()`
and holding onto it - is a few lines from here, and is what furniture with a screen wants.

## Driving maps MapGUI is not putting up

For maps you place yourself - in real item frames, on your own furniture - `MapGui.get().transport()` is the
packet layer. It sends pixels to any map id, whether the server allocated it or not. Take ids from
`MapIds.next()` so they cannot collide with MapGUI's own, and note that nothing there is remembered for you:
a viewer who reloads their chunks has to be sent it again.
