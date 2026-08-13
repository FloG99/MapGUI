# Configuration

## Commands

**Everything under `/mapgui` is administration.** Ordinary players never run it - reaching them is the job of
whichever plugin owns the GUI. `/mapgui` on its own lists what you can run, and every line is clickable.

Grouped by **where the GUI is**, then what to do to it. There are two places a map can be, so there are two
groups, and each takes the same three verbs.

| Command | Permission | |
|---|---|---|
| `/mapgui hand open [gui] [players]` | `mapgui.command.hand` | with a name, hands it to you or to whoever you name; without one, lists what can be opened |
| `/mapgui hand close <players>` | `mapgui.command.hand` | takes it away - the way out of a GUI a buggy plugin left someone stuck in |
| `/mapgui hand list` | `mapgui.command.hand` | who has one open, and which. Click a name to close it |
| `/mapgui wall place [content]` | `mapgui.command.wall` | with a name, starts placing it on blocks; without one, lists what there is |
| `/mapgui wall remove [name]` | `mapgui.command.wall` | the nearest within 32 blocks, or one by name |
| `/mapgui wall list` | `mapgui.command.wall` | every saved wall, with coordinates you can click to teleport |
| `/mapgui camera performance` | `mapgui.command.camera` | what captures are costing the server and which plugin is asking - see [camera](camera.md#what-to-watch) |
| `/mapgui camera reload` | `mapgui.command.camera` | re-reads the packs on disk without a restart, and says what the camera is drawing with afterwards |
| `/mapgui status` | `mapgui.command.status` | how many are in hand, how many saved walls are actually showing, and anything that is failing |
| `/mapgui performance` | `mapgui.command.performance` | what it is costing in bandwidth and main-thread time - see [performance](performance.md) |
| `/mapgui reload` | `mapgui.command.reload` | re-reads `config.yml` and applies it to walls already up |

`mapgui.admin` is the parent of all six, for the usual case of wanting the lot. All default to op.

**Branches this server has nothing to administer are not listed.** MapGUI is a library, so what it can administer is
whatever the plugins on top of it registered: `hand` appears once a plugin registers a GUI, `wall` once there is
content to place or a wall already up, `camera` once something asks the camera for anything or you have installed
textures for it. A server that installed MapGUI for one camera sees the camera branch and the three at the bottom,
and nothing about walls. Set `commands.hide-unused: false` to see the whole tree regardless.

**Or turn the lot off.** `commands.enabled: false` never registers `/mapgui` at all - not registered and refused, so
nothing of MapGUI's appears in a tab completion. For a server whose plugin ships its own commands over the API and
does not want two ways to ask the same question; everything these commands do is reachable from code, and
`MapGui.get().camera().stats()` is the same reading `/mapgui camera performance` prints. Takes effect on restart,
since a command tree is built once.

`list` means the same thing in both groups: what exists right now. `hand open` and `wall place` given no
argument list what they could take, which is the answer you want at the moment you notice you are missing an
argument. `wall remove` is the one that instead has a default - the nearest wall - because that is almost
always the one you mean, and you are standing in front of it. `/mapgui hand` and `/mapgui wall` on their own
print their own verbs, the same way `/mapgui` prints its groups.

The verbs differ between the groups on purpose. A held GUI is *opened* for a player and is gone when they put
it down; a wall is *placed* in the world and outlives everyone looking at it. Using one verb for both would
claim they are the same operation.

What `hand open` and `wall place` offer comes from whatever plugins have registered, so both work for any
plugin's GUIs without that plugin writing a command - see [walls](walls.md#making-your-gui-placeable).

Placing is ended by right-click or Q rather than a command, since the preview is in front of you.

## config.yml

```yaml
commands:
  # /mapgui and everything under it. Off means never registered, so nothing of MapGUI's shows up in a
  # tab completion at all - for a server whose plugin ships its own commands over the API. Restart to
  # change it.
  enabled: true

  # Hide branches this server has nothing to administer - no GUIs registered, no walls, no camera in
  # use. Off shows the whole tree whatever this server does with it.
  hide-unused: true

prompts:
  # How text input is asked for. "dialog" is a native Minecraft dialog and needs no
  # inventory tricks; "anvil" renames an item.
  # Other plugins can register their own and be named here.
  default: dialog

animations:
  # Ease scrolling and color changes instead of snapping.
  enabled: true

  # Ceiling on frames driven by animation. 20 is the most a map can do, since updates go out once
  # per tick. Clicks and hover always repaint at once, so lowering this costs responsiveness
  # nothing - it only makes animation coarser.
  fps: 20

  # The same for effects that loop forever - scrolling long text, an animated gradient. These are
  # where the bandwidth goes, because they never settle, so they get their own lower ceiling.
  # A full-canvas effect is 16 KB per frame per player: 320 KB/s at 20, 160 KB/s at 10.
  loop-fps: 10

cursor:
  # Head rotation is the mouse. This is the pitch range the vertical axis is mapped onto.
  min-pitch: 45.0
  max-pitch: 90.0

  # Push the player's head back inside that range, so they always have room to move both ways and
  # cannot look away from the map. Off leaves their head alone and just stops the cursor at the edge,
  # which means looking further only wastes movement - but nothing touches their view.
  #
  # A screen that turns its cursor off never restricts the head either way - there is nothing to aim at,
  # and neither does a map in the offhand: that one is glanced at while the player looks at the world.
  clamp-pitch: true

terrain:
  # Minimum ticks between terrain redraws, for screens that draw the world. Each redraw reads
  # one block column per pixel, so don't set this too low.
  min-ticks-between-refresh: 4

walls:
  # Walls placed with /mapgui wall place. Every map in a wall is 16 KB per frame, so this is the
  # setting that decides what a wall costs: a 2x2 at 10 is 640 KB/s per viewer, and a 6x6 is nine
  # times that. Only players in range are sent anything, so an empty room is free.
  fps: 10

  # How close a player has to be to be sent a wall, measured to its middle. Keep it inside your
  # view-distance - beyond that the client has unloaded the chunk and thrown the frames away.
  view-distance: 48

  # Longest edge videos are decoded at. Bigger walls upscale rather than decoding again, since a
  # resize would otherwise mean re-reading the file for every step. 256 is 1:1 for a 2x2 wall.
  #
  # This is the setting that decides memory, not the file size: a frame is one byte per pixel once
  # decoded, so at 256 it is 64 KB whatever the GIF compressed to on disk. A 20 second clip at 10 fps
  # is 200 frames, so roughly 13 MB of heap. Frames are let go once no wall shows them, so what you
  # pay for is what is up - not everything you have ever tried. Takes effect on restart.
  video-size: 256
```

A screen may ask for a *lower* frame rate than the server allows, never a higher one - the server's number is
a ceiling rather than a default. See [animation](animation.md#frame-limits).

## Files

| | |
|---|---|
| `plugins/MapGUI/config.yml` | the above |
| `plugins/MapGUI/videos/` | drop GIFs here to make them placeable |
| `plugins/MapGUI/walls.yml` | where placed walls are recorded. Written on every change, not on shutdown |

`walls.yml` holds only a position, a size and a content name. A wall whose content is missing - a deleted GIF,
a plugin that is not loaded - stays in the file and simply does not come up, and returns when its content
does. `/mapgui status` counts how many are showing against how many are saved, and `/mapgui wall list` marks
which ones are not.
