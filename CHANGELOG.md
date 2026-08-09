# Changelog

Notable changes, newest first. This project follows [semantic versioning](https://semver.org/) - the public
surface is `mapgui-api` and `mapgui-layout`.

## Unreleased

### Running a server

- **`/mapgui camera timings` now reports what every capture on the server cost, whoever asked for it.** It used to be
  a per-player switch that pushed three lines of chat after each capture taken from *that* player's eye, which only
  describes the one camera the sample plugin has - somebody aims and clicks. A plugin capturing on a timer, for a live
  view, or for a player who is not the one asking either flooded a chat or reported nothing at all, and an admin had
  no way to tell which. It is counted whether anybody is watching or not, over a rolling few seconds, and reports four
  things: **how many captures a second and which plugin is asking**, worked out from the stack rather than from a new
  API parameter; **what they took off the main thread**, as a share of the 1000 ms a second there is to take, since
  that is the only part that can cost a tick; **the worst single one**, because one 40 ms copy is a stutter an average
  hides; and **how many are queued** when the trace pool is behind, which is the line that says over capacity rather
  than busy. The four-stage per-capture tail is still there as `/mapgui camera timings follow`, now at most one line a
  second with the ones left out counted rather than dropped silently. See
  [what to watch](docs/camera.md#what-to-watch).
- **A capture that throws is no longer invisible.** It went to the console and nowhere else, so a camera failing every
  time looked from outside exactly like a camera nothing was using. Failures are counted with everything else, and
  `/mapgui status` names the plugin and how long ago.
- `/mapgui performance` carries the camera's main-thread cost. It is the page somebody opens when a server feels slow,
  and it counted only bandwidth - which a capture does not spend. No bandwidth figure was added for it: the bytes are
  the map frame a screen paints the shot into, and those are already counted under whatever wall or player received
  them.

### Sending frames

- **A map whose changes are in two places is sent as two updates, not as the box around them.** A map update
  carries one rectangle, so a header and a footer used to drag the whole 16 KB body between them onto the wire.
  Which is cheaper is arithmetic and it is now done per map, per frame: two widgets in opposite corners cost 512
  bytes instead of 16384, a header and a scrollbar 1504. A packet is priced at 1024 bytes, so anything changing
  in one piece is still exactly one packet and up to 1024 unchanged bytes are still resent rather than split off.
  See [design notes](docs/design-notes.md#one-map-several-rectangles) for the numbers.
- Fixed: a pixel update carried an empty marker list, which does not mean "no markers in this update" but "this
  map has none", so every frame cleared the map's markers and relied on the cursor being resent afterwards in the
  same bundle to put them back. Updates now leave markers alone unless they carry some.
- A held map's frame is bundled, as a wall's already was, so a screen that now takes several packets cannot be
  drawn half-new and half-old.

### Camera

- **What is written on a sign is drawn**, front and back, in the sign's own dye and dimmed the way the client dims it
  unless it has been glow-inked. A sign's text is four strings and nothing else - the client rasterises them with its
  font every frame - so they are rasterised here too, with MapGUI's map font, which is Minecraft's own glyphs and was
  already in the plugin. Placed by the client's own transform chain. Hanging signs are left out: their board is a
  different size on a different chain, and text in the wrong place is worse than none.
- **A capture shows what MapGUI's own walls are playing.** A wall is the one thing in front of a camera that is not
  in the world - its maps and the frames holding them are sent to each viewer's client and nothing is placed - so a
  photograph of a cinema used to come back with bare stone where the screen is. The camera now asks the walls what
  they are showing *this photographer*, which is the same picture for everybody on a shared wall and each person's
  own on a per-player one, and hangs it on the face of the block it is mounted to. A wall nobody is watching from
  over here has been sent nothing and photographs nothing.
- **A squid is drawn pointing where it is really swimming.** Its renderer does not use the yaw and pitch every other
  mob is turned by - it reads two fields the squid keeps for itself and eases a tenth of the way toward its heading
  each tick - so a squid that has stopped is still pointing wherever it last went. Those are read off the animal now
  rather than guessed from its velocity, which could say nothing at all about one that is drifting.
- **The layers a mob's renderer draws over its skin are drawn.** A stray's frost, a bogged's moss and a drowned's
  outer skin are not part of those mobs' meshes at all - each is a second copy of the body, grown by a fraction of a
  pixel, over a texture of its own. Without them the three of them stood there as a plain skeleton and a plain zombie
  in odd colours. A mob may now wear any number of these where it used to wear one, which is what the sheep's fleece
  had been using on its own.
- **An idle illager folds its arms, and a pillager levels its crossbow.** The pose an illager stands in is a property
  of the individual rather than of the model - its own render state starts at neither - so it is now stated per mob and
  the client's own animation is what holds the mesh in it. An evoker, an illusioner and a vindicator stand with their
  arms crossed; a pillager stands with its crossbow up.
- **The three jokes behind a name tag work.** A mob called `Dinnerbone` or `Grumm` stands on its head, and every layer
  of it goes over with it - its armour, its fleece, whatever it is holding. A rabbit called `Toast` wears the lost
  pet's coat. A sheep called `jeb_` cycles the sixteen fleece colours a colour every twenty-five ticks, blended across
  in between, which is the client's own arithmetic rather than a rainbow of our own.
- **A fox carries what it has in its mouth.** Drawn off the head rather than out of a hand, at the transform the item
  would be lying on the ground at, and turned a quarter circle so it lies flat in the jaws - with the client's own
  four offsets for a fox that is grown or a cub, awake or asleep.
- Fixed: **a trader llama was undecorated, and a carpet on one drew nothing.** Its decoration is the one piece of
  equipment with no item behind it - the client names the asset outright - and its carpet is drawn on a llama's body
  under a llama's layer, which the naming rule reached as `trader_llama_body` and did not find.
- **A map hung in an item frame shows its picture.** It is the one thing in a capture whose picture is nowhere in the
  assets: a map's pixels live in the world's own saved data, one byte of palette index each, so they are read from
  there and widened into a texture per capture. Per capture rather than cached, since a map is not a fixed picture -
  it fills in as somebody walks around with it. The unexplored parts stay transparent, which is what lets the frame
  show through the middle of a fresh one.
- Fixed: **an evoker drew a spare arm on its side, and a vindicator held its axe with its arms crossed.** A model may
  hide half of itself per pose - `IllagerModel` builds both a crossed pair of arms and two separate ones and shows
  whichever the pose wants - and the extraction read only the nine pose fields off each part, never the flag that says
  whether it is drawn at all. It reads that now, which is general: any mob whose model hides parts is baked without
  them. The held item follows for free, since an item hangs off the arm part and there is no longer one to hang it off.
- **Item frames are drawn, and what is hanging in them.** The frame is its own block model - the glow one and the
  map-sized one included - centred on the block's middle and pushed 0.46875 blocks out along the face it is on, and
  the item hangs at the front of the backplate at half size, turned by whichever eighth of a circle the frame was
  clicked round to. A frame on a floor or a ceiling is tipped a quarter circle on top of that, carried in the model
  rather than in the yaw so that the two rotations end up in the client's own order. A framed **map** gets the frame
  vanilla keeps for one, with the border a map fills.
- **What is standing on a shelf is drawn**, at the three places `ShelfRenderer` puts it - a fifth of a block either
  side of the middle, a quarter forward, quarter size, and each item hung by its own middle so a tall one and a flat
  one sit on the same point.
- Both of those needed the item's `fixed` and `on_shelf` display transforms, which are now read the way the two held
  ones already were. Not decoration: an icon is a picture on one side of a one-pixel quad, and `item/generated` states
  the half turn that stops every item in every frame showing you its back.
- **Decorated pots, copper golem statues, paintings and the enchanting table's book are drawn.** A pot comes out as
  its clay body plus its four sides, each in whichever sherd was pressed into it - grouped by sherd, so a plain pot is
  one layer and a fully decorated one is four. A statue is the golem's own mesh in whichever of vanilla's four pose
  layers the block states, weathered to match the block it is. A painting is a slab a sixteenth of a block thick at
  its variant's own size, the picture on the front and the planks it is nailed to around the rest. The book is shut
  and tipped eighty degrees, which is where `EnchantTableRenderer` leaves it when nobody is standing there.
- **Banner patterns are drawn.** Vanilla ships one white cloth and one white mask per pattern and draws each in the
  dye that layer was made with, so the picture is not in the pngs at all - it is in the order and the colours. Those
  layers are now composited into a texture of their own, since a snapshot carries one colour and a banner has as many
  as it has layers. Sixteen dyes over forty-odd patterns is far too many combinations to hold as files.
- **A decorated pot's mesh is reachable at all.** Its geometry is as plain as any other, but the class that builds it
  maps every sherd to a sprite in its static fields, so loading it reads the pattern registry - and a registry that
  has not been bootstrapped throws, which used to take the mesh with it. The extraction now makes a second pass for
  whatever the first one missed, opening the registries and filling only the ones those classes read. Not
  `Bootstrap.bootStrap()`, which builds every block, item and entity type in the game: five seconds and a hundred
  megabytes a call, it replaces the JVM's `System.out` and `System.err` on its way out, and it leaves log4j pinning a
  loader that was meant to be thrown away.
- Fixed: **a dropped item rested on the floor and sank into it at the bottom of its bob.** The client measures the
  model's box after the `ground` transform, lifts it by its lowest point and then adds a sixteenth of a block, so an
  item never quite touches the ground however far it has bobbed down. That is now what happens here, which also means
  the `ground` translation no longer has to be read - whatever it moves the shape by, the lift puts it back.
- Fixed: **an ender dragon's head swung most of a right angle to the wrong side.** Its head does not follow the head
  yaw at all: `EnderDragonModel` lays the neck and the head along the path the dragon has just flown, out of a flight
  history the client keeps to itself. Drawn straight now, which is what one flying level looks like.
- Fixed: a definition may state the transform that places a special on the branch above it rather than on the special
  itself - a shield states it on the `condition` wrapping its two poses, a copper golem statue on the `select`
  wrapping its four - and only the special's own was read, so both were placed as if they had none.
- Fixed: a special's texture may be a whole file path rather than the one bare word a chest uses, which is how a
  copper golem statue names its own.
- **Minecarts and boats are drawn**, along with the block a minecart carries. Both were left to the bounding box
  fallback, which found a texture for a minecart and drew the cart's sheet stretched over a coffin, and found none for
  a boat or a chest minecart and drew nothing at all. Their renderers turn a model over like a mob's and then stand it
  0.375 blocks up rather than vanilla's 1.501, and a boat carries a further quarter turn because its hull is built
  along its side - so a boat now points its bow where it is going instead of sailing broadside. What makes a cart a
  tnt or a hopper minecart is the block it displays, drawn from that block's own <i>block</i> model at three quarters
  size the way the client draws it - a hopper's item model is a flat icon and its block model is the funnel, and a
  cart carrying the first is a cart carrying a picture. A chest minecart gets the chest mesh, which is where the
  client gets it too: `chest.json` has no geometry and the client keeps a built-in model for it.
- **The shapes the client draws in code are drawn here too.** A chest, a shulker box, a conduit, a shield, a banner
  and a trident all say `minecraft:special` in their item definition and name no model at all, so every one of them
  drew nothing in a hand and nothing on the floor - or worse, fell through to a cube of whatever texture happened to
  be named after the block, which is what made a dropped conduit a small brown box with gaps in it. Each is now drawn
  from the same mesh its block entity is, placed inside the item's box by the transform the definition itself states:
  the translation, the scale and the pair of quaternions are read rather than guessed, which is what puts a shulker
  box a block and a half up and a banner at two thirds size without a table here saying so. A banner comes out as its
  pole and its cloth, the cloth in the dye its definition names.
- Fixed: a definition may reach its model through a branch a capture cannot evaluate - a shield is drawn one way while
  its holder is blocking, a chest wears tinsel between the 24th and the 26th of December - and reading only the top
  level left both resolving to their own name, so they had neither a shape nor the pose their model states. Each
  branch is now read at its own default.
- **Shulker boxes, conduits, banners and bells are drawn where they stand.** A shulker box sits on whichever of its
  block's six faces it was placed against, turned the way `Direction#getRotation` turns it, and wears its dye. A
  banner is its pole and its cloth, standing at whichever sixteenth of a circle it was placed at or hung flat against
  a wall, in its base colour - **its patterns are not drawn**, since each is a mask tinted by its own dye laid over
  the last and compositing here takes no colour per layer. A bell is the bell itself: what holds it up is in the
  block model and was always drawn, and the thing hanging between the posts was not.
- **Heads are drawn, placed and dropped and in a hand.** All seven, from `SkullModel` the way chests are drawn from
  `ChestModel`, standing on the block rather than a block and a half above it and turned to whichever sixteenth of a
  circle they were placed at - or hung a quarter block off the wall they are on. A player head wears its owner's skin,
  fetched the same way a player's is and shared with them when it is their own face.
- **A dropped item is the picture its model names, extruded**, rather than a texture named after the item. Dead coral
  is drawn from `block/dead_tube_coral` and has no icon of its own, so the name rule found nothing and it fell through
  to the six-sided cube a block with no model gets - a stalk of coral drawn as a brick. The icon now comes from the
  model's own `layer0`, and it is extruded along its outline the way a held one already was.
- Fixed: a dropped item is shrunk by what its own model's `ground` transform states rather than by one number per kind
  of shape. Half for an icon and a quarter for a block are what `item/generated` and `block/block` say, so they are
  right for nearly everything by inheritance and wrong for whatever states its own - heavy core says a half, and lay
  on the floor at half the size the client draws it.
- Fixed: a face may name its texture variable without the leading `#`, which vanilla's own `heavy_core` does and the
  client allows. Read literally it is a texture nobody has, so a placed heavy core was missing-texture purple and a
  dropped one fell back to a cube of the right texture in the wrong shape.
- Fixed: a face's `rotation` turns which corner of the stated uv rect lands on which corner of the face, which is not
  the same as turning the texture coordinate afterwards - the two agree only on a rect that is the whole texture the
  right way up. Spore blossom states a mirrored rect and a quarter turn on each of its four leaves, and the east and
  west ones came out pointing inward.
- **Chests are drawn.** Their block json carries no geometry - the client builds them from `ChestModel` like a mob -
  so they used to be a hole you could see the wall through. The mesh is now baked out of the client the same way mob
  geometry already was, with the flip and ground lift suppressed: a block entity's model is authored the way the block
  sits, 0 to 14 upward off the floor, where a mob's hangs downward off the neck. Single and double, every wood and
  every copper state, turned the way the block faces.
- **Coats come from the client's own renderers** rather than a table here. Parrots drew all five variants as the red
  one, because the rule that reaches `parrot_blue` from `parrot_red_blue` cannot exist - and dyed shulkers drew undyed,
  which nobody had noticed. Both are read by invoking the renderer's own variant function out of the jar, which needs
  no dependency: the libraries those classes want are Minecraft's own and a server already has them.
- **Water and lava stand at the depth their level says.** Every fluid was a full cube, so a stream was a trench full
  to the brim. A source is eight ninths and each step away loses another ninth. Fluid under more of its own fluid
  stays full, or an ocean would come out as steps.
- **A fluid's surface is a sheet through its four corner heights**, not a flat lid, so a stream tilts the way it
  runs. Each corner is the weighted average of the four blocks touching it, which is what makes two neighbouring
  blocks agree along the edge they share - and that agreement is why the face between them can be dropped whole, as
  the client drops it. Drawn as flat boxes they disagreed, and the step between two depths was a gap you could see
  the riverbed through.
- **Moving fluid is drawn with the flowing texture, turned downhill.** The still texture has no direction in it at
  all, so no amount of turning it would have shown a current. The angle is the surface's own gradient.
- **Layers you can see into.** An entity texel is carried at its texture's own alpha instead of being rounded to
  solid, and the ray walks on to whatever is behind it in the same mesh. A slime's inner cube, its eyes, and the
  block put inside a sulfur cube were all sitting behind a shell that had been drawn opaque.
- **A sneaking player is drawn sneaking**, in the client's own numbers: the torso tips over its own neck so the hips
  go back, the head drops under it, and the legs slide back to stay beneath. Armor follows, which needed the legs
  split out of the torso into parts of their own - the shape vanilla has always had.
- **Dropped items turn** as the client turns them, by age rather than facing the camera.
- The sulfur cube is drawn at its own size and height. Its model is the one built around its middle rather than hung
  off a neck, so the standard lift put it a block up and at twice its size.
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
