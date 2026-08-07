# Changelog

Notable changes, newest first. This project follows [semantic versioning](https://semver.org/) - the public
surface is `mapgui-api` and `mapgui-layout`.

## Unreleased

### Camera

- `MapGui.camera()` - a screenshot of the world onto a map. Real block textures, transparency through glass, ice,
  water and leaves, biome tints, the sky with its sun, moon, stars and clouds, and the players and mobs in view
  turned the way they stand. `CameraOptions` sets size, field of view, range, fog, entities, clouds and selfie.
- 92 entity types are drawn from vanilla's own geometry, executed out of the client jar rather than transcribed,
  along with armor, saddles, held items and each animal's own coat. Anything without a mesh is its bounding box.
- The textures are not ours to ship, so MapGUI downloads the official client jar on the first capture, checks it
  against Mojang's SHA-1 and keeps about 3.6 MB of the 39 MB. `camera.assets.download: false` turns that off and
  reads a jar or resource pack you supply instead. `/mapgui camera status`, `fetch-assets`, `reload` and `timings`.
- Held and dropped items follow their `item_model` component rather than their material, so a stick renamed into a
  diamond sword photographs as the sword the player is looking at. Pose included, and it falls back to the material
  where the named model is one MapGUI cannot draw.
- A capture is taken in one tick and traced off it, so it is of the instant it was asked for. See
  [camera](docs/camera.md) for what it costs and [what it does not show](docs/camera.md#not-shown).
- **Dark and underwater captures read better.** The shadow lift reaches further up the light range, and water fog
  carries most of the biome's own water colour rather than the near-black the client states for it - `#050533` for
  every ocean, which on 143 colours comes out as a black rectangle rather than as being under water. Both are
  deliberate departures from the client, for the reason the night sky already was: a map has no adapted eye behind
  it. `LightTableTest` holds the one line the lift may not cross, which is that more light must never draw darker.

- **A resource pack's own items are drawn.** Asset paths are built from the namespace an id states rather than
  always from `minecraft`, so `item_model=yourpack:whatever` resolves to the pack's model instead of falling back
  to the material. And any item model carrying geometry is now baked as a shape, where the test used to be that it
  sat under `block/` - a pack's 3D item had its texture sheet extruded as if it were a 16x16 icon. Vanilla is
  unaffected either way: exactly one of its 1271 item models carries elements, and it is reached through a
  condition the server cannot evaluate.
- **Packs in `plugins/MapGUI/assets/` are used without being listed.** An empty `camera.assets.packs` now means
  "whatever is in there, sorted by name" rather than "nothing", so a server that ships a pack has one thing to do
  rather than two. Naming files still pins the exact set and their order.
- **The server's own resource pack is used, with nothing to set up.** A server that dresses its world in a pack
  was having to install it twice - once for its players, once for MapGUI - and keep the two in step forever. The
  one in `server.properties` is now found on its own, fetched once, kept under its own SHA-1 and layered under
  whatever is in `assets/`. `camera.assets.follow-server-packs: false` turns it off.
- **`Camera#useResourcePack`** - a plugin hands MapGUI a pack out of its own jar, so its custom items photograph
  as themselves rather than as the material underneath them. This is a call rather than detection because a pack
  pushed by a plugin cannot be detected: `PlayerResourcePackStatusEvent` reports a pack's id and hash and never
  its URL, and a URL is what a fetch would need. When players are sent a pack and MapGUI has none, it says so.
- **A layer that stops being readable is reported.** Replacing a pack while the server has it open leaves the
  reader following a table of contents into bytes that have moved, and every entry after that fails - as
  "not in this layer", which is how a file that was never there fails too. So captures went on working and drew
  from the layer underneath, silently, with a plugin's own items coming out as their base material. The stack
  now remembers, `/mapgui camera status` names the file, and a warning follows the next capture.

### Carrying a GUI

- `HandOptions` splits what the player appears to be holding from whether it has their mouse. A screen can be a
  popup filling the hotbar, a real `ItemStack`, a fake map pinned to one slot, or one in the offhand - and it takes
  the player's clicks in the main hand, on a gesture, always, or never.
- `MapGui.item(gui)` mints a map item that opens a registered GUI for whoever holds it, so one found in a chest
  shows its finder their own screen.
- `MapGui.openWhileHolding` opens a screen while a player holds an item of *yours* and closes it when they put it
  down - a camera in the main hand with its viewfinder in the offhand. Returns a `HeldTrigger` to cancel. It takes a
  `Focus` rather than a whole `HandOptions`, because the screen is always in the offhand: any other carry mode puts
  the map in the hotbar, where reaching for it would mean letting go of the item that opened it.
- Both are swept once a tick rather than listened for, since an item reaches a hand a dozen ways.
- **A swallowed right-click now puts the held slot back.** Eating the packet is what stops the item being used, but
  the client had already predicted that use and was never told otherwise - so a trigger item passed to
  `openWhileHolding` appeared to be consumed, scoped or drawn, and stayed that way until something unrelated
  resent the slot. A knowledge book vanished from the hand on every click. Only sent when the main hand holds a
  real item, so a popup being clicked through costs nothing.
- **A map in the offhand no longer takes over the player's aim.** The pitch clamp is for a map held up in front of
  you, so it now applies only in the main hand - an offhand viewfinder or quest log leaves your head alone whatever
  `cursor.clamp-pitch` and `Screen#clampPitch` say. Unclamped, the vertical axis follows the head as a delta the way
  the horizontal one always has, so looking back down moves the cursor back down immediately instead of waiting for
  your pitch to re-enter the range.

### Bandwidth

- Walls track what changed per map rather than per wall. Two small changes at opposite corners of a 6x6 wall
  used to send all thirty-six maps in full; now they send two rectangles.
- Every map that changed in one frame goes out in a single packet bundle, so a wall applies whole instead of
  tearing.
- `WallDisplay.Builder#prerender` - send a repeating animation once and play it by pointing clients at the
  copies they already have, which is a few bytes a frame rather than a few hundred kilobytes. Capped at 32
  steps; costs a copy of the wall per step in each client. `/mapgui wall place` uses it automatically for a
  GIF short enough, which `walls.prerender` turns off.
- A wall with an audience cuts each map's pixels out of its surface once a frame rather than once per viewer.

### Drawing

- Shapes with a fill, an outline and a line thickness: `triangle`, `polygon`, `circle`, `ellipse`, `line`,
  `polyline`, and `shape` for anything you implement `Shape#contains` for.
- **Small circles are round rather than pointed.** An exact disc ends each axis in a single pixel, because the
  boundary runs through the middle of that one and only clips its neighbours - correct, and at the sizes an icon
  is drawn at it reads as a four-pointed star. `Shape.Ellipse` measures to the outside of the boundary pixel
  instead, putting the edge on the grid rather than through it. A radius of one goes from a plus to a 3x3 block,
  which is the same fix at the smallest size it can happen.
- `PaintContext#hovered` - whether the cursor is on the node being drawn. A custom-painted mark has no background
  for `hoverBackground` to change, so it is the one widget that has to answer for its own hover state, and the
  alternative was mirroring the flag into a field of your own from `onHover`.
- `AwtFont` - any TrueType font the JVM can load, at any size, with optional anti-aliasing. A screen chooses
  its own by overriding `Screen#font()`.
- `ComponentText` - draw an Adventure component with the colors and styles it carries - and `RichText`, a node
  that puts one in a layout so it can be sized and aligned like anything else.
- Blending is done on packed pixels rather than colour objects, so a translucent fill or an anti-aliased glyph
  no longer allocates per pixel.
- `MapColors` answers from a lookup table instead of a growing map, so matching a color is a shift and an
  array read, costs no allocation, and is safe off the main thread.

### Video

- Optional FFmpeg playback for mp4 and live streams, downloaded per platform on first use and only when
  `video.ffmpeg` is turned on. `LiveSource` and `WallContent#live` for anything decoded as it plays.
- Named streams in config.yml are placed exactly like a file: `/mapgui wall place lobby-cam`.

### Versions

- The server internals live behind `ServerBackend` in one module per Minecraft version, found by name at
  startup. Adding a version is a new module and a line in a table - see
  [architecture](docs/architecture.md#why-the-nms-modules-and-how-to-add-a-version).

### Maps

- `MapGui.printer()` - print pixels or a whole capture onto real, placeable map items, cut into a grid of maps in
  reading order. Genuine vanilla maps: the picture goes into the pixels the world saves and the map is locked, so it
  survives a restart and survives MapGUI being uninstalled. Costs a permanent map id per map.

## 1.0.0

First release. Paper 26.2, Java 25.

### Menus

- `Screen`, with auto-layout: `Row` `Column` `Overlay` `Scroll` · `Text` `Button` `Toggle` `Field` ·
  `Spacer` `Divider` `Box` · `Draw`.
- Themes, bevelled and flat borders, dithered gradients, four corner shapes, four overflow modes for text.
- Eased transitions and looping effects, with per-screen and server-wide frame ceilings.
- Text input through pluggable prompt providers - a native dialog or an anvil, and your own if you register one.
- Terrain drawn under a layout, following the player or fixed to a wall.

### Walls

- Grids of maps on blocks showing a video, a shared menu, or a menu each.
- `/mapgui wall place` sizes one against a live preview and remembers where it went.
- Content can pin its own size: `fixedSize`, `sizeBetween`, `aspect`.
- `SharedModel` for state several screens draw, so one player's change redraws everyone's.

### Administration

- `MapGui.guis()` - register a GUI once and admins can reach it, with no command of your own.
  `registerOpenable` puts it in a hand, `registerPlaceable` on a wall, and one `unregister` clears both.
- `/mapgui hand open` `close` `list` and `/mapgui wall place` `remove` `list` - grouped by where the GUI is,
  with the same three verbs either side.
- `/mapgui status`, `/mapgui performance` and `/mapgui reload`, each behind its own permission under
  `mapgui.admin`.

### Video

- Animated GIF with no runtime dependencies, palette-matched once and stored a byte per pixel.
- `Fit.CONTAIN` `COVER` `STRETCH`, and transparency that composites rather than fills.

### Tooling

- A headless preview that renders a screen to a browser or a PNG, with working input and a layout inspector.
- `/mapgui performance` reports bandwidth per wall and per player.
