# Camera

A screenshot of the world, onto a map. Blocks with their real textures, transparency through glass, ice, water
and leaves, and the players and mobs in view, turned the way they stand.

```java
MapGui.get().camera().capture(player, Camera.MAP_SIZE, shot -> {
    if (shot != null) {
        this.shot = shot;
    }
});
```

A capture is a `CameraShot`, which is a `Frames` of exactly one frame - so
[`VideoPlayer`](video.md) draws it with the same code a GIF uses, scaling and fit modes included:

```java
Draw(context -> new VideoPlayer(shot).fit(VideoPlayer.Fit.COVER).paint(context.painter(), context.bounds(), 0))
```

Try it with `/mapgui hand open camera`. Aim with your head, left-click or sneak to shoot, right-click for settings,
and swap hands - F, unless you have rebound it - to put the camera away.

**Print** in those settings takes one 256x256 capture and hands back **four real map items**, a quarter each, to hang
in item frames in a 2x2 square - their names say which corner each one goes in. A map is 128 pixels and nothing
changes that, so the way to a bigger picture is more maps rather than a scaled-down one, and each tile is one pixel
per pixel with nothing resampled.

The example does not do that for itself - it is an API feature, so your plugin can print any capture onto real maps:

```java
List<ItemStack> tiles = MapGui.get().printer().print(player.getWorld(), shot);
```

Note what those are: unlike everything else here they are **genuine vanilla maps**. The picture goes into the pixels
the world itself saves for the map, and the map is locked the way a cartography table locks one, so nothing scans
terrain over it afterwards. It is a plain vanilla map from then on - **it survives a restart, and it survives MapGUI
being uninstalled**, because MapGUI is not what draws it. A MapGUI screen is the opposite: virtual, no `MapView`,
nothing saved, which is why a screen is free to open and these are not.

**Which costs map ids, permanently.** Every printed map takes an id the world keeps forever, exactly as a cartography
table would, and four go on one `x4` capture. Nothing reclaims them - breaking the item frame and throwing the map
away leaves the id spent. Fine behind a command somebody types, wrong behind anything a player can hold a button down
on.

On a server whose internals MapGUI cannot reach - a fork, a version it has not seen - it falls back to drawing the
picture itself and says so in the console once. Those maps still show, but only while MapGUI is installed. See
[`MapPrinter`](../mapgui-api/src/main/java/de/flog99/mapgui/map/MapPrinter.java) and, for how the example names each
piece, [`SnapshotTiles`](../examples/camera/src/main/java/de/flog99/mapgui/examples/camera/SnapshotTiles.java).

## Textures

The camera needs Minecraft's textures, which are not ours to ship. By default MapGUI downloads them from Mojang
the first time something takes a capture, so there is nothing to do.

**Player skins are unaffected either way.** They come from Mojang's profile service rather than from any file on
your server, so people appear with their own skin even before the block textures have arrived.

### Letting MapGUI fetch them

The default. On the first capture MapGUI downloads the official client jar for your server's version, checks it
against Mojang's own SHA-1, keeps what it needs and throws the rest away - about 3.6 MB of a 39 MB download, into
`plugins/MapGUI/cache/camera/`. Once, per version.

Nothing is downloaded at startup. Most installs of MapGUI never take a capture, and 39 MB of outbound traffic
for a feature nobody has called is not something a plugin should do unasked. The startup log says as much:

```
[MapGUI] Camera textures are not installed. They will download from Mojang the first time
[MapGUI] something takes a capture.
[MapGUI] To turn that off, set camera.assets.download to false in config.yml.
```

To turn it off entirely, set `camera.assets.download: false` - MapGUI then never makes an outbound connection. To
have the textures in place before anybody asks, take one capture yourself.

The mob shapes are baked out of the same download and kept in the same file, because they are not in the assets to
copy - see [how mobs and items are drawn](#how-mobs-and-items-are-drawn). Nothing extra comes down for them.

A cached copy also carries a stamp saying which subset it is, and a MapGUI upgrade that starts reading something
new bumps it. An older copy is then treated as not installed and replaced, because the alternative is worse than
an error: it loads, reports itself ready, and quietly draws a checkerboard where the sun should be.

### The pack your players already have

**Nothing to set up.** If this server hands its players a resource pack, captures are drawn with it. MapGUI
fetches it once, keeps it under its own SHA-1 in `plugins/MapGUI/cache/camera/packs/`, and layers it over
vanilla - so a photograph looks like what the people in it are looking at.

This is the pack named in `server.properties`, which the API hands over outright.

**A pack a plugin pushes at runtime cannot be found this way**, and that is a limit worth stating plainly rather
than working around: nothing reports one. `PlayerResourcePackStatusEvent` fires, but it carries the pack's id and
hash and never its URL, and a URL is what a fetch needs. So a plugin serving its own pack has to say so:

```java
String sha1 = MapGui.get().camera().useResourcePack(this, "pack/my-pack.zip");
```

That reads the zip out of your own jar, keeps it under its own SHA-1 and layers it - which is what makes **a
plugin's own items** photograph as themselves instead of as the material underneath them. Call it whenever;
unchanged bytes on the next startup write nothing and reload nothing. It works with `follow-server-packs` off,
since a plugin asking directly is not MapGUI going looking.

**Use the hash it hands back** when you offer the pack to clients. You have to serve the file yourself - Minecraft
only takes a pack from a URL - and a client is offered one by its hash, so both sides were digesting the same bytes
and nothing stopped the copy players download and the copy captures are drawn with from being different files.

If players are sent a pack and none of this has happened, MapGUI says so once rather than quietly drawing
vanilla.

```yaml
camera:
  assets:
    follow-server-packs: true
```

Three things worth knowing before leaving it on:

- **It is an outbound connection**, to whatever address the pack is served from. If the reason `download` is
  off is that this plugin must never reach out, turn this off with it.
- **The address is written for clients.** Usually the server can reach it too, and when it cannot, the fetch
  fails at INFO and captures stay vanilla - the same as before.
- **It is server-wide.** Players can be sent different packs and can decline the one they were sent; captures
  use whatever was found either way. Per-player layers would mean a baked model set per player, and nearly
  every server sends everyone the same pack or none.

Anything in `assets/` still wins over anything followed, since one is a decision and the other is a
convenience. And a followed pack is never used as the *base*, however complete it looks.

### Supplying them yourself

For a server with no outbound route, one that already ships a resource pack, or an admin who would rather not
have a plugin reach out. Put a client jar or a resource pack zip in `plugins/MapGUI/assets/`.

**That is the whole of it.** With `packs` left empty, every zip and jar in that directory is used, sorted by
name. A server that already ships a pack to its players gets the same look in a capture by dropping a copy in
there, with nothing to configure and nothing to keep in step.

Name them when you want a particular order, or want some of them ignored:

```yaml
camera:
  assets:
    packs:
      - "my-server-pack.zip"    # yours wins where it has a texture
      - "26.2.jar"              # vanilla fills in the rest
```

A client jar is already on any machine with the game installed:

```
%APPDATA%\.minecraft\versions\26.2\26.2.jar                       Windows
~/Library/Application Support/minecraft/versions/26.2/26.2.jar     macOS
~/.minecraft/versions/26.2/26.2.jar                                Linux
```

Copy it as-is - don't rename it and don't unzip it. `%APPDATA%\.minecraft\assets\` is *not* the place to look:
that directory holds sounds and languages in a content-addressed store, and the block textures were never in it.

Packs are layered top-down the way the client stacks them, so a server that ships its own pack gets its own look
in a capture. Anything in `plugins/MapGUI/assets/` is only ever read - a file you pinned deliberately is never
replaced.

A pack's **own** items are drawn too, not only its retextures of vanilla ones. An id carrying a namespace is
resolved under that namespace, so an item whose `minecraft:item_model` is `yourpack:camera` is looked for at
`assets/yourpack/items/camera.json` and drawn from the model it names, geometry and all. Vanilla is the default
for an id that states no namespace, which is how vanilla's own files are written.

> A pack replaced while the server has it open cannot be picked up, and worse, cannot go on being read: the
> table of contents was read at open time and now points into bytes that have moved. MapGUI notices and says so
> after the next capture, and `/mapgui camera reload` names the layer - but the fix is a restart. If a plugin of
> yours installs a pack here, write it before MapGUI enables.

A client jar also carries the mob shapes, and one you supply is read for them the same way a downloaded one is. A
resource pack alone cannot: entity geometry is not in the assets, so a pack-only setup draws mobs as bounding
boxes.

`mapgui camera reload` picks up changes without a restart.

### When it goes wrong

`/mapgui camera reload` re-reads the packs and then says what state the textures are in, and for anything wrong,
both what is wrong and what to do about it. Check `Camera#assets()` from code rather than after the fact:

```java
if (MapGui.get().camera().assets().ready()) {
    // offer the capture
}
```

A version mismatch is a hard failure rather than a silent one - a capture that quietly draws the previous
version's grass is a confusing bug report. With downloading on it fixes itself; with it off you are told which
file to replace. For a snapshot server or a fork, where the matching assets may not exist to download at all,
`camera.assets.allow-version-mismatch: true` loads them anyway and anything added or renamed since renders as
the missing-texture checkerboard.

## Settings

`CameraOptions` is a record with withers, so a screen can keep one and change a field:

```java
CameraOptions options = CameraOptions.defaults().size(96).entities(false);
MapGui.get().camera().capture(player, options, shot -> ...);
```

| | |
|---|---|
| `size` | pixels square. 128 fills a map; smaller quarters the work and the palette hides much of the loss |
| `fov` | vertical degrees. 70 is the client's default, and the server cannot see what a player set theirs to |
| `maxDistance` | blocks to trace, or 0 to follow the viewer's own render distance. Capped by the server's view distance either way |
| `fog` | fades the far distance toward the sky, which also stops the distance cap reading as an edge |
| `entities` | mobs and players, or a clean landscape without them |
| `clouds` | the cloud sheet, from Minecraft's own cloud texture |
| `selfie` | shoot from arm's length in front of the holder, facing back at them |

The sun, the moon, the glow at dawn and dusk and the stars at night are not on this list, because they are not
optional. They are where they are, and a camera with a button to take the sun out of the sky is a camera with a
button nobody wants.

`maxDistance` **is** readable from the client - the render distance arrives in its settings packet, which is why 0
can mean "as far as this viewer can see". Field of view and the cloud toggle are never sent at all, so those stay
settings rather than readings.

## How it is lit

Three things multiply together, and the second is the one with a trap in it.

**Face direction.** Vanilla's own multipliers, since there is no lighting model to ask: up is full, north and south
0.8, east and west 0.6, down 0.5. That is what makes a corner read as a corner.

**Light level, with the time of day taken off the sky part.** The light a server stores is not the light you see.
Sky light means how much of the sky reaches a block and nothing more, so on open ground it is 15 at midnight as
surely as at noon - the day cycle is applied when the world is drawn, not when it is lit. Reading it straight makes
a midnight capture as bright as a noon one. Block light is left alone, because a torch is as bright at night as it
is by day.

The curve from level to multiplier is the client's own, reproduced rather than approximated, with the brightness
slider at 90%. It is not linear and no guess at its shape is close: light 7 is a fifth of full and not a half, and
then the slider blends that toward a gentler curve, and then the whole table is pulled four percent toward grey,
which is why full daylight comes out at 0.99 rather than 1.

**Then the dark end is lifted, which is the one place this parts company with the client on purpose.** A screen draws
a night in thousands of near-blacks and your eye adapts to them. A map has 143 colors and a viewer whose eye is
adapted to whatever else is on their screen, so faithfully dark reads as a hole in the picture rather than as a cave.
The lift is weighted as `(1 - light)²`, and the shape is the point: a plain floor under everything is affine, so
raising it enough to make a cave wall visible lifts a shaded wall at noon by the same proportion and flattens the
whole picture to pay for the cave. Weighted, it lands 0.5 at light 0 and 0.01 at light 10 - measured on stone, an unlit
cave wall goes from 41 out of 255 to 56, which on this palette is the difference between its near-black and its darkest
stone, while a torchlit surface gains one unit and a daylit one none.

Night is lifted the same way and for the same reason, but by the other lever: the client takes 11 off sky light at
midnight, leaving open ground at level 4, and this takes **7**, leaving it at 8. Open ground at midnight comes out
around 120 of 255 rather than 84, which is dusk-ish to look at and leaves the shape of the land legible.

**The night sky stops at a very dark blue rather than at black**, and that is a palette decision rather than an
astronomical one. Dimming the dome's blue to nothing is what the client's own curve does, and it came out *reddish*:
the two darkest colors a map has are TERRACOTTA_BLACK at rgb(19,11,8) and COLOR_BLACK at rgb(13,13,13), and the match
weights blue error above red, so pure black lands on the terracotta. Stopping at rgb(6,8,16) lands it on COLOR_BLACK
instead - the nearest thing to a neutral near-black in the palette - and the horizon stops one step lighter so the
dome keeps a faint gradient instead of flattening to one shade. Warm sky belongs to dawn and dusk, where the glow
band puts it near the horizon on the sun's own side.

**The band at dawn and dusk is the client's own, colour and shape both.** Its colour comes out of the same arithmetic
the client bakes its `minecraft:visual/sunrise_sunset_color` keyframes from, and its shape is the fan the client
draws: an apex on the horizon where the sun is going down, a rim that runs the whole way round the camera, and the
colour interpolated between. That is worth taking rather than approximating, because the shape is not something an
eye can tune from a screenshot - the band covers **the whole half of the sky the sun is on**, tapering to nothing at
exactly a quarter turn round, and it is only about 18 degrees tall at its middle, less as it fades. Any falloff
written to look right beside the sun is wrong ninety degrees away from it, in a way you only notice standing there.

**And it hangs far deeper than it rises**, which is the other half of what a sunrise looks like. Above the sun the
fan's rim ends it inside those 18 degrees; below there is no rim in the way, so the sheet carries on under the camera
and the colour goes down with it - eighteen degrees over the sun there is nothing left, eighteen under there is still
seven tenths. Nothing hides that half either: the client's dark disc is drawn only while the eye is *below* the
world's horizon height, so anybody standing on the surface at dawn is looking at the underside of this wherever the
world does not cover it. In a photograph that is the bottom of the frame, past where the copied world runs out.

One thing is deliberately not copied. On screen that fan is enormous, nearly flat, and the camera sits all but
exactly in its plane, so turning toward the moon can drop the orange out of the middle of the view - the same sky at
the same moment drawing differently depending on where you are pointed. A photograph cannot have that, so the band
is solved for along each ray instead: one sky, whichever way the camera was turned.

There is no brightness setting. There was one, with presets, and it existed to escape a curve that was wrong.

Entities go through the same three. Drawn at their texture's own brightness they are lit for noon wherever they
stand, and a cave full of fully lit zombies looks pasted on rather than photographed.

**Under water, everything carries the water with it.** A camera inside water is looking through water at every
single thing in the frame, so the whole picture fades into the biome's own `water_fog_color` - a near-black navy in an
ocean, a murky green in a swamp - and there is no sky to see, since the fog closes long before the surface does. The
range is the client's own pair, from eight blocks behind the camera to twenty-four in front of it, which is what a
diver who has just gone under sees. Starting behind the lens is why a block right in front of it is already a quarter
faded rather than untouched.

**Each dimension's own ambient light**, which the client takes from its `dimension_type` and adds to every level:
nothing in the overworld, a tenth in the Nether, a quarter in the End. It matters most exactly where it sounds least
important - there is no sky light in either of those two, so nearly every surface sits at the bottom of the curve,
which is where a tenth of the way to full is a third again as bright.

**The Nether's air hides distance by itself.** The client's fog there is not optional and not far off: from a twentieth
of the render distance out to half of it, with the distance capped at 192 blocks first, so nothing is ever sharper than
96 blocks away. Its color is the biome's own `fog_color` rather than one constant for the dimension - a crimson forest
is dark red, a soul sand valley teal, basalt deltas grey - and that color is read from the same place the grass and
water colors are.

## Distant leaves

Leaves close up with distance. Untouched they stay a cutout at any range, so a ray finds a gap, carries on through
the whole forest and comes out at the sky - and a hillside of trees photographs as a haze of twigs with daylight
behind it, when the same hillside on screen is a solid green mass.

That is a resolution problem rather than a taste one. A leaf texture is 16x16 across a one block face, so past some
distance a single texel is smaller than a pixel of the capture, and a gap no longer has a pixel of its own to be seen
through: what the pixel covers is part leaf and part gap. So from there the gaps fill in, over the texture's own
average color across its drawn texels, reaching fully opaque further out. The client arrives at the same place from
the other direction, by mipmapping - a canopy at range is the average of what is in it.

Where that crossover actually falls depends on the size and field of view the capture is taken at, so both ends are
settings rather than the constants they started as:

```yaml
camera:
  leaves:
    near-blocks: 0
    far-blocks: 50
```

`near-blocks: 0` closes the gaps from the lens out, so a distant canopy is solid with no band of haze in front of it -
a tree at arm's length is a twentieth filled, which is not visible. Raising it leaves near trees alone instead, so
standing under an oak you see the sky through it. A big capture with a narrow field of view resolves further and wants
both pushed out; a half-size map wants them pulled in.

Only leaves do this. Every other cutout - bars, ladders, glass panes, grass - keeps its gaps at any distance, because
you are never looking through a hundred of them at once.

## Live views

A still and a viewfinder are not the same problem. A still happens once, somebody pressed a button, and they are
waiting for it - take it. A viewfinder wants every frame it can get, forever, and how many that should be depends on
how many other people have one open. Only the server can see all of them, so the server drives it:

```java
@Override
protected void onOpen() {
    feed = MapGui.get().camera().feed(player(), this::framing, shot::set);
}

@Override
protected void onClose() {
    feed.close();
}
```

That is the whole of it. Every tick the feed asks `framing` what to capture and hands the result to `shot::set`,
and what it saves you is the three things that are invisible when got wrong:

- **One capture in flight at a time**, so a tick that runs long leaves the next frame late rather than leaving two
  copies of the world in memory.
- **Frames only as fast as the budget allows**, so five people holding a camera divide the server's time rather than
  costing five times as much.
- **No frames while the screen is put away** or covered by an inventory, since those are frames nobody sees.

Return `null` from `framing` for a tick that wants no frame - mid-animation, mid-shutter, still loading textures. That
pauses the feed without closing it, and a paused feed stops counting as a viewer, so it gives its share back while it
waits. `onFrame` only ever sees real frames; a capture that fails is dropped rather than handed on as a null.

If you are pacing yourself - a view that only wants a frame when something in the world changed - ask directly
instead:

```java
if (MapGui.get().camera().readyForFrame(player)) {
    MapGui.get().camera().capture(player, options, shot -> this.shot.set(shot));
}
```

Ask every tick you would like a frame and take one when it says yes. There is nothing to open and nothing to close:
asking is what makes you a viewer, and a screen that stops asking stops being divided by, so a plugin cannot leak a
viewfinder and a player who logs out takes their share with them.

Two settings decide the rate, and everything between them is spent:

```yaml
camera:
  live:
    max-ms-per-tick: 3.0
    max-fps: 10
```

`max-ms-per-tick` is the budget, in main-thread milliseconds - the only kind of time a capture can take from the
server, since the trace runs on its own threads. `max-fps` is the ceiling, where a viewfinder stops looking better and
only costs more. So with those defaults, and a frame that costs a millisecond to copy:

| viewers | each gets | total main-thread cost |
|---|---|---|
| 1 | 10 fps | 0.5 ms/t |
| 6 | 10 fps | 3.0 ms/t |
| 8 | 7.5 fps | 3.0 ms/t |
| 12 | 5 fps | 3.0 ms/t |

One viewer does not get twenty times the frames for being alone, and the seventh to open one slows the other six
rather than costing you a seventh more. Either setting takes `0` to mean no limit of that kind - `max-ms-per-tick` at
0 lets everybody have the ceiling however many there are, `max-fps` at 0 lets one lonely viewer take the whole budget.

Only paced captures feed the measurement below, and only they count against the budget. A still taken by the same
player is a capture of something else: a 256-pixel photograph copies a far wider frustum than a 64-pixel viewfinder,
so letting one into the average would slow that player's view for a second over a frame that was never part of it.

**What a frame costs is measured, per viewer.** A 64-pixel viewfinder pointed at a wall copies a fraction of what a
128-pixel one pointed across a valley does, so the budget is divided as *time* rather than as frames, and a view that
would hit the ceiling on less than its share hands the rest back to the ones that cannot. That is what makes it spend
the budget rather than merely respect it. The first frame or two of a new view are paced on an assumption, which the
real measurements replace within a second.

It is **advisory**. Nothing stops a plugin capturing without asking - it is the admin's tick either way, and
[`/mapgui camera performance`](#what-to-watch) names whoever is spending it and counts those captures on an
`Unpaced` line, so a budget being ignored does not look like a budget nothing needed. That report also carries a `Live views` line
with what the viewers are getting and the two settings that decided it, since 6.7 fps means one thing when the budget
ran out and quite another when the ceiling is 6.

A still taken by a plugin that never asks is not paced at all, which is the intended behaviour: one photograph is one
photograph. `camera.reuse.chunks.stills-for-ms` is worth turning on alongside a live view - see
[reuse and caps](#reuse-and-caps).

## What it costs

Measured on a wooded, hilly scene with water in it, against the real 26.2 assets, at 128x128. Median of nine runs
after warmup, all of it off the main thread:

| | range 128 | range 192 |
|---|---|---|
| one thread | 45 ms | 45 ms |
| six threads | **14 ms** | **20 ms** |

A frame is traced as bands of rows across a small pool, which is worth having because the rays of a frame do not
interact at all: the world was already copied out of the server, the textures are immutable, and each ray writes one
pixel. The test for it asserts the frame comes out **byte-identical** however many threads drew it.

Mobs are the one thing that got dearer rather than cheaper, because they are now vanilla's real geometry rather than
a box each: the full cap of 48 in frame costs **29 ms** on one thread, and an ender dragon filling the frame **52
ms**. Entity cost tracks the screen area they cover and the parts they are made of, not how many there are, so a
crowd in the distance is nearly free and one warden up close is not. It goes through the same pool as the blocks.

Quantizing the finished frame to map colors is **0.02 ms** and never worth thinking about. The palette table costs
about **80 ms to build, once**, on the first capture a server ever takes.

Where the time goes, measured by running the same camera over an empty world to isolate the walk: at range 128 the
walk is a quarter of it and the surfaces three quarters; at range 192 the walk is over half. Shading cost is
constant and walking cost grows with range, so the walk is where the remaining work was - and what went into it is
[`EmptySpace`](../mapgui-camera/src/main/java/de/flog99/mapgui/render/EmptySpace.java): every 16 block cell carries
the size of the largest empty cube around it, built up a level at a time and then collapsed into one byte per cell,
so a ray in open sky is handed a 256 block box from a single array read. A cell counts as empty only when every
block in it is, which is the same section flag the block read already trusts, so nothing new is being believed.

Measured separately from the table above - a synthetic scene of the same shape on a different machine, one thread,
128x128, best of three medians of eleven runs - so these are worth reading as ratios rather than against those
milliseconds:

| looking at | walking | skipping |
|---|---|---|
| the horizon, range 128 | 23.1 ms | **15.5 ms** |
| the horizon, range 192 | 28.4 ms | **19.1 ms** |
| 25 degrees up, range 192 | 36.4 ms | **25.2 ms** |
| straight up at empty sky, range 192 | 11.8 ms | **8.8 ms** |
| the walk alone, over an empty world, range 192 | 32.6 ms | **18.2 ms** |

The last row is longer than the rows above it on purpose: with nothing in the world every ray runs to the far plane,
where terrain stops most of them early. It is the walk with the shading taken out of it, which is what the skip acts
on.

What actually went away is the column reads: **1.58 million `columnTop` calls a frame became 327 thousand** at range
192. The `stateAt` and `lightAt` counts come out identical to the call in every one of those shots, which is the
profile saying what the test says - the same blocks are looked at either way, and every cell that was skipped had
nothing in it. Building the structure costs **1 to 2 ms** for the 729 chunks a range 192 capture covers, on the tick
that copies the world.

It has to be free in quality as well as cheap, and that is the part with a test rather than an argument behind it:
`EmptySkipTest` renders a cave under solid stone, an overhang, a floating island with sky above and below it, water,
a waterlogged block, cross-plane plants, a mob standing in open air and a camera pointed at nothing, each one with
the skip on and off, and asserts the frames are **byte-identical**. They are, and so is every shot in the table
above at full 128x128, on one thread and on six. That is why the ray still crosses an empty cell one block at a time rather than jumping to
the far side of it: the steps are the same additions in the same order, so the numbers a ray arrives at a surface
with are the same numbers. Jumping the ray's parameter would be faster again and would change them in their last
bits, which is not a trade this makes.

Two cases it does not help. A camera **inside solid rock** skips nothing, because every cell around it holds
something, and pays about two percent for asking - the one measurement that is worse rather than better. And a ray
travelling **just above the ground** stays inside cells that hold the ground, so it walks them; helping that wants a
maximum height per cell rather than a flag, and measuring every column to get one is main-thread work in the tick
this is trying to keep cheap.

To see it on your own server rather than in this table, run **`/mapgui camera performance follow`** and take a picture. It
reports the four stages and their total: the copy, with how many **chunks** it took and how many of their **sections**
held anything; the entity gather, with how many were in frame; the trace; and the palette. A section is the 16x16x16
subchunk Minecraft divides a chunk into, and the filled count is the interesting half of it - a snapshot costs nothing
for a section of pure air, so that ratio is how much of the copy could ever be avoided. It follows captures taken from
your own eye, at most one line a second, and stays on until you run it again.

For whether the camera is costing the *server* anything, which is a different question, see
[what to watch](#what-to-watch).

Only work is counted. A capture crosses threads twice - out to a thread for the trace, then back onto the main thread
to hand over the shot, because touching the Bukkit API anywhere else is not allowed - and **the hop back costs about
40 ms whatever the camera does.** It is posted as a zero-delay main-thread task, so it runs on the next tick, and a
tick is 50 ms. Reporting it made every capture look like a 50 ms one when the work was 15, so it is left out.

The number that does move is the trace, and the reasons are outside this code. **`#n`**, the capture number since
startup, is there for the biggest of them: the first several captures are the JIT compiling the tracer rather than the
tracer being slow, so expect them to be multiples of the steady-state figure. After that it is the machine - six trace
threads only go six times as fast if six cores are free, and a server sharing a desktop with the game client is not a
quiet machine. A garbage collection landing mid-trace is added to it too, since a pause stops every thread.

None of this is work, and none of it costs TPS - the server is not blocked while a task sits in a queue. It is
latency, and it only matters for a live view, where it is a fixed tick of delay rather than a limit on how often you
can capture. The number to watch for the health of a server is `copy`, which is the only one on the tick.

**The main-thread half is not in that table**, and on a wide capture it is the part to watch. Copying the world has to
happen in one tick, and each `ChunkSnapshot` is a copy of a whole chunk column of blocks and light. What a snapshot
costs in milliseconds needs a running server to measure, so that number is honestly unknown rather than small - but
how many of them a capture asks for is exact, and that is where the work went.

[`ChunkFrustum`](../mapgui-plugin/src/main/java/de/flog99/mapgui/plugin/camera/ChunkFrustum.java) now culls in three
dimensions rather than as a flat cone. Averaged over 72 yaws and 13 pitches at fov 70:

| | chunks before | after |
|---|---|---|
| range 96 | 145 | **54** |
| range 192 | 463 | **167** |

So **64% fewer snapshots**, and at steep pitches, where a flat cone gives up and copies the whole square, 70 to 76%
fewer. Two tests were added: the range bound is a real 3D distance, since the square's corners were always about 40%
further out than a ray can reach, and a height interval per chunk solved from the four side planes of the pyramid,
which also drops chunks the frustum never passes over at all. Both carry `1 + sqrt(3)` blocks of slack, because the
tracer reads one block past the visible one for face culling and for the light falling on that face.

Dropping a chunk a ray could reach would put a hole in the picture, so the test for it is the same shape as the one
for the skip: every pixel of a frame marches through the real `CameraView.direction` half a block at a time, and every
chunk a ray is over while inside the world must survive `mightSee`. Over fov 10 to 170, eleven pitches, thirty-three
yaws, six eye heights and off-origin coordinates: **14.5 million chunk-hits reached by a real ray, none dropped**.
Three mutations of the bounds - negative slack, a 10% tighter range, the wrong corner of the column - each fail four
of those tests, so the sweep is not passing by being vacuous.

### Reuse and caps

Three things are kept between captures, all of them under `camera.reuse:` - copied **chunks**, what a column's
**tile entities** draw, and what an **entity looks like**. Together they are most of the copy and most of the gather
gone: over 90% chunk reuse for a player walking and shooting.

They are the only fast paths here that are not exact. There is no way to know something has changed - block events
miss pistons, fluid, growth, explosions and every other plugin; a chunk holds light as well as blocks, so a torch
placed next door changes it without being touched; and nothing reports that a mob has drawn its sword. So each is a
timer rather than a guarantee, and each ramps with distance, because staleness is only worth what it hides: what is
at your feet is most of the picture, what is at the horizon is a few pixels.

Two asymmetries are worth knowing. A **still photograph** reuses nothing unless you ask - `stills-for-ms` is zero by
default, since a photograph is kept and no frame follows to correct it. And an **entity's position is never reused**,
whatever the window says: only its shape is held, so a reused mob is always drawn where it actually is, and what can
lag is the sword it just drew or a player's crouch.

`camera.limits:` caps what one capture may draw at all, so a mob farm or a storage room in shot cannot fill a frame.
Both keep the nearest, so what gets dropped is whatever was furthest away.

None of this makes a smaller `size` help the copy: the trace gets cheaper and the copy does not.

The split is the whole design. Copying the world has to happen on the main thread and has to be quick, so one
tick takes `ChunkSnapshot`s and reads the player's eye; the trace runs on another thread; the result comes back
on the main thread. A capture is therefore of the instant it was asked for rather than of whenever the callback
runs.

A still costs nothing to keep showing. It goes dirty once, sends 16 KB once, and then never again - see
[performance](performance.md). A *live* view is the opposite: every pixel changes every frame, which defeats the
dirty rectangle entirely and costs 2.6 Mbit/s per viewer at 20fps. Render time is not what would stop you there.

Two limits worth knowing:

- **A capture cannot see further than the server keeps loaded.** Unloaded chunks are left out and the ray draws
  sky through them, the same rule [terrain](../mapgui-api/src/main/java/de/flog99/mapgui/TerrainRenderer.java)
  follows. Reading a block in an unloaded chunk would generate it, and a wide capture would generate hundreds
  inside one tick.
- **Entities are capped** at 48 in frame, nearest first, and 64 blocks out. Each one is now a part tree of real
  cubes rather than a single box, so a mob farm in shot would otherwise be tens of thousands of slab tests.

### What to watch

Everything above is for deciding whether the renderer could be faster. **`/mapgui camera performance`** is for deciding
whether a server is in trouble, which is a much shorter question, so it answers four things and nothing else:

```
Camera - what captures are costing, over the last few seconds
Captures  3.2/s   PhotoBooth 3.0/s, Surveillance 0.2/s
Costs the server  0.34ms/t  0.7% of every tick   slowest frame 4.1ms
Each capture  6.8ms on the tick   blocks 6.0ms (152 chunks, 78% reused), entities 0.5ms (8, 62% reused), tile entities 0.3ms (4)
Live views  3 viewers at 6.7 fps   0.92 of 1.0ms/t, 10 fps cap
```

- **Captures, and by whom.** Rate is the multiplier on everything else, and the plugin names are the actionable half
  of it - a camera is always somebody's, and turning one down means turning down whatever asked for it.
- **Costs the server.** The only number that can cost TPS, and the one to watch. Per **tick** rather than per second,
  since that is the unit a server is read in and the unit `camera.live.max-ms-per-tick` is written in, so the two can
  be held against each other directly. A tick is 50 ms: under 1% of it is nothing, 5% is a server you can feel.
- **Each capture**, split into the three stages that have different answers. **Blocks** is chunk columns copied and
  wants a shorter `max-distance`; **entities** is what is standing in shot; **tile entities** is chests and signs.
  Each carries what it went through beside what it cost, because a slow stage is either a lot of things or expensive
  things, and the reuse percentages say whether the caches are doing their job - see [reuse](#reuse-and-caps).
- **Live views**, when any are open: what they are getting, and what the rates handed out add up to against what
  they were allowed. The pair is what says *which* of the two settings is binding - well under the budget means the
  fps cap is what is holding them, and the budget is not the number to change.
- **Slowest frame.** An average of 2 ms with one 40 ms copy in it is a stutter a player saw, and the average hides it.
  Kept because one long tick is a different problem from a busy one.
- **Waiting**, when anything is. Captures are traced one at a time, since the trace itself already spreads across
  every core, and at most three may be copied and waiting. A plugin asking for more than the machine can draw sees
  the queue fill and then **turned away**: further captures are refused before the copy rather than queued, and the
  caller gets a null shot. The bound is for memory rather than latency - a queued capture holds the chunk snapshot
  of every column it copied, 167 of them at range 192, so an unbounded queue runs a server out of heap rather than
  merely falling behind. Both lines are left out when nothing is waiting.
- **Unpaced**, when a budget is set and something is capturing without asking for it. Pacing is opt-in: a plugin
  that never calls `readyForFrame` is never paced, and without this line an admin who set `max-ms-per-tick` would
  see "no live views" beside a camera capturing twenty times a second and read it as agreement.

Failures get a line here and on `/mapgui status`, which is the only place they show at all: a camera that throws on
every capture logs to the console and otherwise looks exactly like a camera nothing is using.

There is no bandwidth figure, on purpose. A capture sends nothing - what reaches a client is the map frame a screen
paints it into, and [`/mapgui performance`](performance.md) already counts those bytes under whatever wall or player
received them. Counting them twice would only make the camera look expensive in a currency it does not spend.

### The same numbers, from code

Every figure above comes out of `CameraStats`, and the built-in command has no private access to any of it:

```java
CameraStats stats = MapGui.get().camera().stats();
```

Which is deliberate. A built-in command working from a wider view than the API offers is how an API ends up missing
the field somebody needed - so `/mapgui camera performance` is written against exactly what your own debugging
command can get. Rates, per-tick cost, the slowest single capture, each stage with what it went through, trace time, the queue, failures with
the last reason, which plugin asked, and what the live views are being allowed against the two settings that decided
it.

`stats.bound()` answers the one question a rate cannot answer by itself - whether the fps ceiling or the tick budget
is what is holding the views down. Three frames a second under a ten frame ceiling is a budget that ran out; three
under a three frame ceiling is a setting somebody chose. They call for opposite actions and look identical from the
number alone.

Plus one that is per player rather than server-wide, for a plugin debugging its own viewfinder:

```java
double fps = MapGui.get().camera().frameRate(player);
```

Nothing has to be switched on for any of this; it is counted whether anybody is looking or not, over a rolling few
seconds, and a `/mapgui reload` starts it over.

## Blocks the client draws itself

A handful of blocks carry no geometry in their model json at all, because the client renders them in code. An **end
portal** is one: its json states a particle texture and nothing else, so a bake of it produced no elements and the
block came out invisible - you looked straight through the portal at whatever was under it. It now gets a built-in
stand-in, its own texture on a surface three quarters of a block up, emissive, top face only. Same for an **end
gateway**. Approximated deliberately: vanilla's is a scrolling parallax of sixteen layers and none of that survives one
byte per pixel.

**Emissive parts** are data-driven and not a special case. A model element may state `light_emission`, and a firefly
bush is four planes of leaves over four more that state 15 - so the leaves take the light of the night around them and
the fireflies do not. Anything in the game that marks an element that way glows here.

## Not shown

Everything below is absent from a capture on purpose or for a stated reason. It is the list to check before
filing a bug.

**Left out of the frame entirely**

- **Arrows and every other projectile**, **primed TNT**, **falling blocks**, **experience orbs**, **fishing
  bobbers**, **leash knots**, **lightning**, **area effect clouds**, and the **display and marker** entities.

One rule behind all of those: an entity is drawn from vanilla's geometry where
[`EntityMeshes`](../mapgui-camera/src/main/java/de/flog99/mapgui/render/EntityMeshes.java) names a mesh for it (every
mob a normal world contains, plus armor stands, end crystals, minecarts and every kind of boat), and from its bounding
box where the assets carry a texture at `entity/<type>`. A type with neither is left out rather than drawn as a grey
box or as the missing-texture checkerboard.

Paintings and item frames are not among them. A painting is the slab the client draws, at its variant's own size,
with the picture on the front and the planks it is nailed to around the rest. An item frame is its own block model
with whatever is hanging in it, at the place and size the client hangs one.

- **The text on a sign** is drawn, front and back, in the sign's own dye and at the place the client's own transform
  puts it: from the middle of the block, turned to the way the sign faces, dropped and pushed back against the wall
  for a wall sign, then a third of a block up and a hair proud of the board. The lettering is drawn per capture with
  MapGUI's map font rather than read from anywhere - a sign's text is four strings, and the client rasterises them
  every frame too. Hanging signs are the exception: their board is a different size on a different chain, so their
  text is left out rather than drawn in the wrong place.

- **The blocks the client draws from a block entity renderer** are drawn where they stand: chests, heads, shulker
  boxes, conduits, banners, bells, decorated pots, copper golem statues, and the book over an enchanting table. Each
  mesh is baked out of the client's own model classes the way a mob's is, and placed the way its own renderer places
  one. End portals and end gateways get a built-in stand-in instead, since the client draws those from a shader
  rather than from a mesh. What is standing on a shelf is drawn too, at the three places the client stands it -
  though a shelf can be told to drop its items to the bottom instead, and that flag is on the block entity's own
  data with nothing in Bukkit to read it, so they are always drawn centred.

- **The items the client draws in code** - a chest, a shulker box, a conduit, a shield, a banner, a trident, a head, a
  decorated pot, a copper golem statue - are drawn from the same mesh the block entity is, placed inside the item's
  box by the transform the item definition states.

  **A held or dropped one carries no data:** a banner is drawn in its base colour with no patterns, a pot with four
  plain sides, and a statue in the standing pose whichever it really is. All three live in the stack's components,
  which the asset layer that resolves an item model never sees. Where they stand in the world they are drawn in full.

**Drawn, but not fully**

- **Ambient occlusion.** Corners and overhangs are flatter than on screen.
- **Any animation.** Meshes are baked in the client's rest pose, so a walking mob has still legs, a swimming one is
  level and nothing is caught mid-swing. A capture is one instant and the phase of a swing is not something the
  server hands over. An archer levelling its bow is the one exception.
- **Particles, weather, fire, beacon beams and enchantment glint.**
- **Name tags**, and a creeper's charge.
- **Armor trims.** And a baby wears the adult armor mesh, since vanilla builds a separate baby set and this asks for
  one mesh per slot.
- **A villager's hood under a profession hat**, on the two trades that wear a headband rather than a hat.
- **Panda and tropical fish variants.** Bukkit exposes no variant accessor for either, so both draw the default.
- **Items whose model is chosen by condition** - a trident, a spyglass, a crossbow being drawn - fall back to their
  inventory model, since the condition is client state the server does not carry.
- **Glowing eyes are lit rather than emissive.** The client draws them fullbright, so a vanilla enderman's eyes glow
  in the dark and these do not.
- **Sky color per biome.** It is a constant per dimension. The client does derive one from the biome's temperature,
  but across the whole overworld the result spans eleven of 255 in one channel, which is less than the map palette
  can express.
- **Biome tints for a datapack biome exactly.** Vanilla biomes are exact - their definitions ship inside the client
  jar. A biome a datapack invented has no definition to read, so its climate is asked of the server and the colormap
  does the rest, which gets the green right and leaves the water at the default blue.
- **Biome borders ramp straight rather than raggedly.** Tints are blended across the 5x5 square of biomes around a
  block, as the client blends them, so a border is a gradient over five blocks and not a line drawn across the grass.
  What is missing is the wobble under it: the client jitters the biome lookup itself with a hash of the world's seed,
  which frays the edge, and a snapshot reads the biome straight off the 4 block grid it is stored on. Eight hashes per
  sample is not a thing to put in front of every tinted pixel for an edge that is already five blocks wide.
- **The clouds the viewer is actually seeing.** Whether a client draws clouds at all, and which of the two kinds, is
  not in the settings packet, so it is a setting on the capture rather than a reading. Same for field of view.
  Render distance *is* sent, which is why `maxDistance` can follow it.

## How mobs and items are drawn

- **Every mob shape.** 92 entity types are drawn from vanilla's own geometry, plus armor stands and end crystals,
  and fourteen more meshes cover armor, saddles and body armor. Equipment meshes are asked for by name rather than
  hung off a species, because one armor mesh is worn by everything humanoid and one mob may wear four at once.

  That geometry is not written here and never will be. It is compiled into the client rather than shipped as data,
  so it is *executed* out of the client jar: the classes that build it touch no OpenGL and no world, so running them
  and walking the result gives the real cubes with the real texture coordinates. It happens when the textures are
  unpacked and lands in the same cache, because nothing Mojang-derived belongs in this repository. On a server whose
  library versions are too far from the client's it fails, and every mob goes back to being a bounding box.

- **Which coat an individual wears**, read from the game's own variant registries rather than guessed. A cat's eleven
  coats, a wolf's nine, a frog's three and the temperate, cold and warm forms of the cow, the pig and the chicken are
  entries in `data/minecraft/<type>_variant/`, each naming its texture and its young form outright - so the lookup is
  one rule over the registry name and needs no table, and a coat a datapack adds resolves as well as a vanilla one. A
  wolf's entry states three textures, wild, tame and angry, and its mood picks between them, which no rule over names
  could reach. The mobs whose variants are still written into the client - a rabbit, a fox, a llama, a parrot - fall
  back to a rule of spelling: the species texture with its last word swapped for the variant.

  Getting the *name* of the variant out of the server is the other half. Bukkit calls the same idea `getVariant` on
  the mobs whose variants became registry entries, `get<Type>Type` on the ones that predate it and `getColor` on a
  llama, so asking only for the first found nothing on the rest - and finding nothing is silent. Every cat in the
  world was a tabby, every rabbit brown, every fox red and every llama creamy. All three names are tried now.

- **How a mob stands still**, which is the client's own answer rather than a list kept here. Every mesh is handed to
  its model class's own `setupAnim` with a render state that describes standing still, and whatever that leaves
  behind is the pose - so the undead hold their arms out, a vex holds its arms up and a spider splays its legs,
  without any of those angles being written down here. It replaced a table of transcribed numbers.

  That rest pose is not always the standing one. A fox, a cat and a turtle rest partly below their own feet, a
  ghast's tentacles hang at full stretch, and the elder guardian's spines are out. Where the pose belongs to a class
  other than the one the mesh is built from, the table says so - a zombie is drawn from the plain humanoid mesh but
  stands the way `ZombieModel` stands it. Armor follows the body it is on, matched part by name, or a reaching
  zombie's chestplate would hang where its chest is not.

  An archer levelling its bow is the one pose that cannot be baked: the same skeleton has its arms down until it has
  something to shoot at. It is applied per capture from aggression and what is in the hand, and it follows the head,
  because a skeleton shoots at what it is looking at.

  The head turns too, **clamped the way the model clamps it**. The server stores a head yaw that can lead the body by
  75 degrees and most models draw whatever it says, but the equines cap it at twenty - drawn uncapped, a donkey
  stares straight at the camera while the animal in front of you has barely turned its head.

- **The layers a mob's renderer draws over its skin**: a sheep's fleece, a stray's frost, a bogged's moss, a
  drowned's outer skin, a sulfur cube's shell. Each is its own mesh - the body grown by the fraction of a pixel the
  client grows it by - over a texture of its own, which is why it cannot be a second pass on the same one. A mob may
  wear several. Without them a stray is a plain skeleton and a drowned a green zombie.

- **A villager's clothes, a horse's markings, a tropical fish's pattern and glowing eyes**, each a second pass over
  the same mesh, composited rather than layered. Eyes are what made an enderman's white: the skin holds white eyes
  and the layer over it holds pink ones, so a capture that skipped the layer drew the pair vanilla never shows
  anybody. A tropical fish is the extreme case - two greyscale textures and two dyes rather than one of 3072
  pictures.

  Compositing costs the one thing ordering gave vanilla - it cannot hide the robe's hood where a hat covers it - and
  it is what a single-surface trace can do: two snapshots of one mesh are two surfaces at the same depth, and which
  of them a ray kept would be arbitrary.

- **Equipment, driven by the item rather than by a table here.** Every equippable stack carries vanilla's own
  `equippable` component naming its asset - `iron`, `saddle`, `red_carpet` - so a datapack piece that sets one is
  drawn as correctly as an iron helmet. Armor is the humanoid mesh inflated by `INNER_ARMOR_DEFORMATION` for the
  leggings and `OUTER_ARMOR_DEFORMATION` for the rest, read off the client's constants rather than chosen: guess an
  inflation and a chestplate ends up inside the chest.

  What each piece is made of comes from its own json rather than from its name, which matters for leather - a
  greyscale shape the client multiplies by a dye colour, and so one that drew as iron when probed for by name. The
  json says the two things a name cannot: that a layer may be several passes, and that a pass is dyeable and with
  what colour when nobody has dyed it.

- **An in-hand item**, out of the item model's own `display` block and the client's own hand chain, so a plain item
  lies flat with its face to the sky, a sword stands up across the body and a bow is upright and turned a further
  forty degrees. The item is placed off the holder's own mesh and inherits the arm's rotation, so a piglin holds one
  where a piglin's arm ends. A mob with no arm to find holds nothing, which is also true of a cow. One skeleton in
  twenty is left-handed, and the client poses a held item by the arm rather than by the hand, so the pose follows the
  arm and the off arm swings clear of the string on whichever side the bow is not.

  A held picture is a picture *a pixel thick*, extruded along its own outline the way `ItemModelGenerator` extrudes
  it: the icon is walked for runs of opaque texels and each run becomes a box, rows merged where they match. The
  alternative is a rim around the 16x16 frame, which is a rim around nothing for most icons - **347 of 26.2's 796
  item icons never touch their frame**, and measured edge on, a bow, an apple, a stick, a pickaxe and a hoe drew
  nothing at all. Seen face on the picture is unchanged, checked over all 796 icons for zero pixels differing in
  colour.

  **What the stack says it draws as wins over what it is.** Vanilla's `item_model` component overrides the model an
  item uses, so a stick given `item_model=minecraft:diamond_sword` is a sword to everybody looking at it, and a
  capture that read the material would photograph a stick. The pose is read from the same id as the shape, or the
  sword would lie flat the way a stick does. A component naming something this cannot draw falls back to the
  material, so an unknown model is a stick rather than nothing.

  A block in hand is a block: its item definition names a block model, and both the pose and the shape come from
  there, so a log has its rings on top and a furnace has a front. A model is authored looking at its front from `+Z`
  while a mesh here faces `-Z`, so a block turns a half circle about Y on the way in - the same half turn the item
  sprite carries, which is what makes the two sample identically. A snapshot samples one texture and a block model
  states up to seven, so a held block is one snapshot per texture; 606 of 731 block items state a single texture and
  cost what they always did.

  Which colour a tinted face takes comes from the item's definition rather than from the block, and the two differ:
  the same `oak_leaves` model is a fixed green in a hand and the biome's green in the world. A **dropped** block is
  the same model under the client's `ground` transform rather than its `thirdperson` one - a quarter of a block
  rather than three eighths. A dropped *sprite* stays a single flat quad: it is turned to face whoever is looking, so
  its one pixel of thickness never comes into view.
