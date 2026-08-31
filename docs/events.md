# Events

MapGUI raises Bukkit events for the things another plugin might want to watch or refuse. They live in
`de.flog99.mapgui.event` and need nothing declared - register a listener the ordinary way.

| Event | Cancellable | Raised when |
|---|---|---|
| `MapGuiClickEvent` | yes | a screen is pressed, before the screen itself is told |
| `MapGuiScreenOpenEvent` | yes | a screen is about to go into a player's hands |
| `MapGuiScreenCloseEvent` | no | a screen comes off a player |
| `MapGuiWallPlaceEvent` | yes | a wall is about to go up |
| `MapGuiWallRemoveEvent` | yes | a wall is about to come down |
| `MapGuiViewerChangeEvent` | no | a wall starts or stops showing itself to somebody |

**All of them are raised on the main thread.** Two of MapGUI's gestures are read off the connection on the
network thread - see [architecture](architecture.md#input-and-why-some-of-it-is-read-off-the-connection) - and
those hop before anything here is raised, so a listener may read the world as usual.

## Protecting a region against walls

`MapGuiWallPlaceEvent` is what lets a claim or region plugin refuse a wall without MapGUI knowing anything
about claims. Cancelling it stops the wall existing: `open()` returns null and nothing was started.

```java
@EventHandler
public void onWallPlace(MapGuiWallPlaceEvent event) {
    WallLayout layout = event.layout();
    for (int row = 0; row < layout.rows(); row++) {
        for (int col = 0; col < layout.cols(); col++) {
            Block block = event.world().getBlockAt(layout.blockX(col, row), layout.blockY(col, row), layout.blockZ(col, row));
            if (!claims.canBuild(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
```

There is no placing player, because a wall need not have one - most are opened by a plugin on startup, putting
back what it saved. Gate the command or the menu that asks if you need to know who asked.

`/mapgui wall place` walls that a listener refuses are asked about once rather than every tick, so a refusal
costs one event and the wall simply does not appear. It is asked again after a restart.

`MapGuiWallRemoveEvent` is the other side and needs more care: the plugin that owns a wall calls `close()` when
it is finished with the wall, so refusing one means holding a display open on somebody else's behalf. It is not
raised where a veto could not be honored - a shutdown, a plugin unloading, a preview being let go of, or
`/mapgui wall remove`, which has already taken the wall out of `walls.yml` and would otherwise leave one
nothing owns.

## Knowing which control was pressed

`MapGuiClickEvent` carries the **node path** of whatever was pressed, not only a pixel position:

```java
@EventHandler
public void onClick(MapGuiClickEvent event) {
    // "settings/volume" rather than (61, 44)
    audit.log(event.getPlayer(), event.node());
}
```

That is the difference between an event a plugin which does not own the screen can use and one it cannot. The
path is a node's `key` if it was given one, otherwise its position in the tree - so set a key on anything a
listener might want to recognise, because a path shifts when rows move. It is null when the press landed on
nothing, and the pixel position is `-1` for a screen with no cursor.

`event.wall()` is the wall that was pressed, or null for a screen the player is holding.

Cancelling swallows the click: the screen is not told and no click sound plays. The gesture is still taken off
the player either way - the whole point of a menu is that a right-click means "press this" and not "open the
chest behind it" - so vetoing here silences the button rather than handing the click back to the world.

## What is deliberately not an event

- **Cursor movement.** A pointer moves every tick for every viewer of every wall. An event on that would cost
  more than everything watching it.
- **Whether a viewer is being sent frames.** Somebody glancing about in front of a video wall crosses that
  line several times a second - see [nobody is looking at it](walls.md#nobody-is-looking-at-it).
  `MapGuiViewerChangeEvent` is the slow question, and is batched to one event per wall per tick in which
  anything changed.
- **Closing a screen, refusably.** Closing is how a player puts a menu down and what a disconnect does. A
  listener able to refuse it would be a way to pin a screen on somebody.
