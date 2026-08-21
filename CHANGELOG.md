# Changelog

Notable changes, newest first. This project follows [semantic versioning](https://semver.org/) - the public
surface is `mapgui-api`, which carries the layout engine inside it.

## 2.0.0

A camera can now be put anywhere in the world rather than only in a player's hand, which is what a mirror needs.

Also: a wall stops sending frames to anyone who is not looking at it, a screen can read a held right-click as a
hold rather than as a stream of clicks, dark places in a capture are drawn lighter, and the map pointer reaches
every position the client can draw it at rather than every other one.

**Breaking:** `Marker#x` and `Marker#y` are `double` where they were `int`. That is the only break, and the
reason this is 2.0.0 rather than 1.2.0. A plugin that does not touch `Marker` needs no change.

### Running a server

- **An unlit block is drawn a good deal further off black**, `camera.shadow-lift` defaulting to 0.3 rather than 0.2.

  A cave read as slightly darker than it should. The lift is still spent almost entirely on the dark end: an unlit block
  goes from 28 of 255 on stone to 40, light 7 of 15 from 0.614 to 0.644, and light 11 from 0.881 to 0.885, so a lit room
  is the same lit room it was. This is about as far as the lift goes while dark still reads as dark, which is why the
  docs no longer hold 0.3 out as the point past which it stops. It stays a taste rather than a fact -
  `camera.shadow-lift: 0.2` has the old figure back, and the clamp and the shape `LightTableTest` holds are unchanged.

- **An entity's screen rect is solved for rather than approximated**, so nothing is clipped out of a frame it belongs in.

  Reported as a mirror on a ceiling showing a mirror on the wall below it with a band of that wall missing and the block
  behind it showing through, from some standing positions and not others. Nothing in the world was clipping it: the
  picture was never **offered** to those rays. `EntityScreen` gives each entity a pixel rect and only tests a ray against
  the entities whose rect covers it, and the rect took the bounding sphere's radius over its depth - which is the extent
  of a sphere sitting on the frame's own axis. Anything off to one side is further from the camera than its depth says, so
  its silhouette is wider by `1 / cos squared` of the angle it sits at: half again at the edge of an ordinary 70 degree
  frame, double at 45 degrees off, which is where a ceiling mirror looks at a wall.

  Most entities never noticed, because the sphere is a generous bound to begin with - a player's is two blocks where their
  mesh needs about one. A wall's picture is a flat square whose sphere is only half again its own size, so the shortfall
  came straight off the picture.

  Solved instead: a line at tangent `t` touches a sphere at `(along, off)` of radius `r` where
  `(off - t along)^2 = r^2 (1 + t^2)`, giving `t = (along off +- r sqrt(along^2 + off^2 - r^2)) / (along^2 - r^2)`. The two
  roots are deliberately not symmetric about `off / along` - the near side of a sphere subtends more than the far side,
  which was the other half of what the old arithmetic missed. `CeilingSeesWallTest` decides arithmetically which pixels
  should land on the picture and holds every one of them to landing on it, from four standing positions; it fails if the
  approximation is put back.

- **A wall's picture is drawn on the face of the block it hangs on**, where it used to sit five map pixels proud of it.

  Reported as a strip of block showing through: hang a mirror on each of two walls that meet at a corner, and each one
  shows the other with a hairline of the block it hangs on down the edge they touch. Looking at a wall straight on, a few
  pixels of proudness are invisible; at a corner the displacement is **sideways**, so a ray aimed into the corner passes
  in front of the other picture and lands on the block behind it.

  Four of the five pixels were the picture's own thickness. `EntityModel.picture` builds a slab straddling the position it
  is given and draws only its **front** face, so placing the slab's middle on the block face puts its picture half a slab
  proud - and half an entity pixel is four map pixels. The fifth was a deliberate hair of proudness, so the picture does
  not fight the block it hangs on; it is now an eighth of a map pixel, which is far too little to see and far more than
  the arithmetic needs to order two coincident surfaces.

  The face is the right answer rather than a compromise: `WallLayout` defines a wall's pixels as lying on the face of the
  block it hangs on, and a mirror clips its own frame at exactly that plane. `WallCornerTest` builds the reported corner
  and holds every row of the column that looks into it to showing the other mirror rather than the block.

- **The inside of a body of water is crossed without being examined.** Water is translucent, so a ray does not stop at
  the surface - it walks down through every block of the ocean to the seabed - and **every one of those blocks draws
  nothing**, because the face it would draw is culled against the identical water behind it. The renderer was asking,
  block after block, a question it had already answered.

  Measured at 256x256 over a flat sea, against the same scene built of stone:

  | water depth | stone | water |
  |---|---|---|
  | 1 block | 17 ms | 29 ms |
  | 6 blocks | 21 ms | 77 ms |
  | 18 blocks - a vanilla ocean | 16 ms | **157 ms** |

  Stone is flat whatever the depth, because the ray stops at the surface. So when the block a ray steps into is the same
  state object it just came out of, and that state is a single full cube, not opaque, with a `cullface` on the side it
  came in through, there is provably nothing to add and nothing to stop the ray - and `sample` is skipped entirely. That
  is **-28%** on the eighteen-block ocean above, 157 down to 113 ms, with stone untouched as it must be.

  Only the entered face is consulted, because a full cube is reported by the grid walk itself. Remembered per state and
  side, keyed on identity, since states are canonicalised - which the renderer already relied on for `culled`.

  `SameKindSkipTest` renders water, glass, a canopy, waterlogged blocks and a field of planes **both ways** and holds the
  frames byte-identical, the way `EmptySkipTest` does for empty space. The cases that matter are the ones where it must
  not fire: leaves carry no `cullface` so a canopy is drawn layer by layer, a waterlogged block is two elements, and a
  plane is not a full cube. Each of those three has a test that fails if its guard is removed.

  `TestWorld` had to start canonicalising its states for any of this to be testable - it handed out a fresh `BakedState`
  per block, so no two blocks in a test scene were ever the same object, and the first version of this test passed
  against a deliberately broken skip. The identity culling in `culled` was going untested for the same reason.

- **A frame remembers the blocks it has already looked at**, per tracing thread, in front of the world it is reading.

  Reading a block state out of a `ChunkSnapshot` hands back a **fresh copy every call** - that is Bukkit's contract, you
  get an object you may keep. With the lock above gone, `CraftBlockData.clone()` became the single largest cost in the
  renderer: **36.6%** of a live trace, with `SnapshotWorld.stateAt` at 53.5% of it including the cache lookup behind it.

  And a ray asks far more often than it steps. A fluid surface averages four blocks per corner, so one water block asks
  about nine positions sixteen times, and again for which way it flows; `above` asks for the block over a fluid, which is
  the one the ray steps into next; `culled` asks about the neighbour behind every face it draws; and the pixel next door
  walks the same blocks again. Measured on a frame of open water, block reads reaching the world went from **18.0 a ray
  to 6.35**, and on solid ground from 10.3 to 5.07.

  Sound because a frame's world cannot change under it - a capture is copied out of the server in one tick and then only
  read, which is already why a fluid's corners and a block's tint may be remembered. Direct-mapped over sixteen thousand
  slots, so a collision costs the re-read it would have cost anyway, and nothing is allocated or shared between threads.
  `SeenBlocksTest` holds the answers identical and the count down; the frames themselves are held by every render test in
  the module, since all of them now trace through it.

- **The trace no longer serialises itself on a hash map.** `SnapshotWorld.stateAt` and `TextureAtlas.get` read with a
  plain `get` and only fall back to `putIfAbsent`, where both used `computeIfAbsent` before.

  `ConcurrentHashMap.computeIfAbsent` answers without locking only when the key it wants is the **first** node in its
  bin. Anything further down a chain takes that bin's monitor on **every read**, hit or miss - and `stateAt` is the
  most-called method in the renderer, once per step of every ray. So the block states that happened to land second in a
  bin put the whole trace on one monitor.

  Found by dumping the threads of a live server drawing a reflection with water in it: three of six tracing threads
  `BLOCKED (on object monitor)` on the same `ConcurrentHashMap$Node`, and **50% of all the CPU the trace burned was
  inside `computeIfAbsent`** - not baking anything, walking a bin under its lock. 67% of the samples were under the fluid
  surface, which is simply where `stateAt` is called most: a fluid's corner heights average four blocks per corner, so a
  water surface asks about its neighbours 1.75 times as often as solid ground does. That frame took **350 ms**.

  Nothing about the result changes: `bake` is itself a cache keyed by the state's name, so racing threads get the same
  instance and one instance per state is still guaranteed - which the renderer needs, since `culled` compares
  neighbouring states by identity. `TextureAtlas.layered` already used exactly this pattern, and says why: a nested
  `computeIfAbsent` is something a `ConcurrentHashMap` refuses outright, intermittently, when the inner key lands in the
  bin the outer one locked.

- **A frame is cut into several bands per thread instead of one each**, so that the threads finish together. `render`
  waits for every band, which makes a frame cost the *slowest* one rather than the average - and the rows of a frame are
  nothing like equally expensive: a band of sky is a handful of steps a ray, a band across the horizon walks the whole
  distance cap through terrain, foliage and water.

  Measured on a live server tracing a mirror, six threads and one band each, cumulative CPU per thread: **85.3 s, 20.3,
  16.0, 26.8, 16.1, 73.5**. The busiest band held 36% of the work where an even split is 17%, so the frame took **2.1
  times** as long as the same work shared out. Four bands a thread averages that away, since the pool hands the next band
  to whichever thread came free - the worst a thread can end up with is its share plus one band.

  `EntityScreen` moved out of the band and is now built once per frame and shared. It covers every row of the frame rather
  than the band's own, so it was being rebuilt six times over already, and twenty-four bands would have made that worse
  than the imbalance it was fixing. Every capture gets both, a photograph as much as a reflection, and
  `FrameTracerTest.everyThreadCountDrawsTheSameFrame` still holds a banded frame byte-identical to a single-threaded one.

- **Entities in a narrow frame no longer cost the earth.** A ray is now held against an entity's bounding sphere before
  any of its mesh is walked, and the entities of a frame are handed over nearest first so that once a ray has met
  something opaque the rest are turned away by that test rather than walked.

  `EntityScreen` keeps entities affordable by testing each pixel only against the ones whose projected rect covers it,
  and that works because a rect is small when the frame is wide. A **mirror's** frame is not wide: it is fitted to the
  glass rather than described by a field of view, so a 2x2 mirror seen from twenty blocks is a window five degrees
  across, and in one of those anything within a block of the axis covers a large share of the picture however far off it
  is - a chest thirty blocks down that tube covers a third of the frame. So the rect stopped selecting, and every pixel
  was walking every entity in the tube: a player is around thirty boxes over eight parts, all of them tested however far
  the ray passes.

  Measured at 256x256: twenty players down the axis of a five degree frame cost **eight times the whole rest of the
  frame**, where the same twenty in a ninety degree frame cost nothing measurable. Both halves are needed - with the
  bounding test alone, candidates arriving furthest first still cost 2.4 us a ray against 0.8 nearest first. Nothing
  about the picture changes: the sphere is the one the rect was already derived from, and `Fragments` orders what it is
  given by depth however it arrives. `NarrowFrameEntitiesTest` holds both.

- **Dark is drawn dark, and how dark is now a setting** - `camera.shadow-lift`, defaulting to 0.2. A capture is lit the
  way the client lights it, with the brightness slider all the way up, and then the bottom of that curve is lifted by
  this much.

  There was a lift under the bottom of the light curve, and the argument for it was real: a screen draws a night in
  thousands of near-blacks and your eye adapts, while a map has 143 colours and a viewer adapted to whatever else is on
  their screen, so a faithful dark can read as a hole rather than as a cave. What settled it was the size of what that
  cost. Measured on stone, a wholly unlit block drew at **80 of 255** where the client draws it at **4** - an unlit room
  came out very nearly as bright as a lit one, which is not a legible cave, it is no cave at all.

  The bright end barely moves: full daylight is 253 either way and light 12 goes from 232 to 237. Everything below about
  light 8 gets much darker. Night is the same decision by the other lever - the client takes 11 off sky light at midnight
  and so does this, where it used to take 7 and leave midnight looking like dusk.

  Then it shipped at zero, and 4 of 255 on stone turns out to be a black rectangle rather than a room - so the honest
  answer is that this was never a constant to get right. It is a setting, defaulting to **0.2**: that block lands at 28 of
  255, dark but legibly a room, while light 11 moves by nine thousandths. Turn it up for a legible cave, down for a
  faithful one. `LightTableTest` holds the shape - more light is never darker, full light stays where the client put it -
  across the whole range the setting is clamped to, rather than only at the default.

- **Dark colours are no longer tinted red.** Two things were doing it, both at the bottom of the palette, where the darkest
  entries are TERRACOTTA_BLACK rgb(19,11,8) - warm - and COLOR_BLACK rgb(13,13,13), and the neutral ramp above them is only
  four apart.

  The **lookup table** rounded each channel down to an eight-wide cell before matching, which is wider than those gaps, so
  every dark colour was dragged toward the darkest entries there are. Measured: dark leaves at rgb(10,16,5) landed on the
  terracotta where an exact match gives the neutral black, and cobble in a dim room came out rgb(40,28,24) instead of
  rgb(40,40,40). There is now a second table over the dark corner of the cube at two to a cell, taken from the middle of
  each cell rather than its floor - another 32 KB, and the bright range is untouched.

  And **vanilla's matcher itself** leans warm down there: it counts green four times and lets blue error off lightly, which
  is a fair way to compare two colours and the wrong way to compare two greys. For rgb(33,33,33) it prefers a brown to
  rgb(40,40,40) by half a percent of its own metric. So the dark table measures its own way: for a colour with a hue, plain
  distance; for one grey enough that any hue would be invented, brightness and colour split apart with colour weighed
  twice. Nothing about fidelity required the old formula - the client draws whichever entry we hand it.

  `DarkPaletteTest` pins the outcomes rather than the arithmetic: a grey stays grey down to rgb(4,4,4), nothing
  near-neutral comes out warm, nothing dark comes out much brighter than it is, and the hue is far closer than the rounding
  it replaced.

- **A live view is a tick fresher, and often two.** A finished capture used to be handed back with its own `runTask`,
  which puts it on the main thread on the next tick but at **no particular point** in it - and a task submitted a moment
  ago sorts after a long-running repeating one, so in practice it landed *after* MapGUI's tick had already painted and
  sent every wall. The pixels then sat there until the next paint. On a wall at 10 fps, which paints every second tick,
  that was up to 100 ms of a live view trailing behind for nothing but ordering.

  Finished captures are queued and drained at the **head** of MapGUI's own tick now, before anything paints, so a trace
  landing at any point in a tick is drawn at the start of the next one. Nothing about the capture got faster; it stopped
  waiting.

  What is left is structural, and worth knowing before chasing it: one tick for the hop off the trace thread, and however
  long the pacing interval is - `camera.live.max-fps` at 10 offers a frame every 100 ms, so a view is on average half that
  behind before any of the above, and a player with several views divides that rate between them.

- **A wall with no cursor now hears its clicks.** `WallDisplay#click` turned away any screen whose `cursor()` is off,
  which made a cursorless wall the one surface in MapGUI whose clicks went nowhere: input is claimed off the connection
  for anyone aiming at a wall that carries a screen, and the click was then dropped. `Screen#clickedAnywhere` exists for
  exactly this and the held-map path has always used it.

  It is told **where** as well, which a held screen cannot be: the position is a raytrace against the wall rather than the
  player's own pointing, so there is a real pixel to report and nothing has to be asked of the player to get it. A screen
  that wants a punch rather than a press says so with `activateOn()`.

  Found by a mirror, which is a wall you have to be able to punch off and a screen that must not take your aim - it had no
  way to be removed at all.

- **A clipped frame draws what is against its plane**, which a mirror is the only user of and which two separate things
  were cutting away. Both were right for a camera in somebody's head and wrong for a frame that starts at a plane:

  The walk **skips the block it starts in**, because a camera standing inside a block should not have that block's inside
  painted over the frame. A clipped frame does not start where the camera is - it starts where the ray crosses the plane -
  so the block it skipped was the ordinary block directly in front of the glass. That hid a whole layer of the room, and
  the layer nearest the viewer: a chest pushed against a mirror, a flower on the shelf under it, the snow on the floor
  beneath one. It is walked like any other now, entered through the face the plane lies on.

  And a face is **dropped when a neighbour would hide it**, which is what `cullface` in a model is for. The face of that
  chest that touches the glass is hidden by the wall the mirror hangs on - so it was dropped, and the reflection showed
  straight through the chest. A neighbour behind the plane is not in the picture and hides nothing in it.

  `ClipPlaneTest` holds both, and an ordinary capture is untouched by either: both are guarded on there being a plane at
  all.

- **A wall's picture is drawn at its own brightness inside a capture**, rather than being lit like the surface it hangs
  on. Its pixels are a finished image - a camera capture, a video frame, a menu - so the light is already in them, and
  dimming them again by the light where the wall stands counts it twice.

  Invisible on one wall and ruinous in a loop, which is where it was found: a **mirror facing a mirror** photographs the
  other's map every frame, and every pass multiplied it by the face shade of the wall it hangs on - 0.6 on an east or west
  wall. So an infinity tunnel went 0.6, 0.36, 0.22 and was black by the fourth level. It turned **red** on the way down as
  well, which is the palette rather than the arithmetic: the darkest colours a map has are TERRACOTTA_BLACK at rgb(19,11,8)
  and COLOR_BLACK at rgb(13,13,13), so anything marching toward black lands on the warm one, and dark green leaves land
  there first.

  A map in a real item frame is untouched: that is paper on a wall and vanilla dims it too. The distinction is
  `EntitySnapshot#emissive`, and `WideFrameTest` holds both sides of it.

- **A wall's map is keyed by its face as well as its block** inside a capture. Two walls on different faces of one block -
  mirrors either side of a partition, say - published their pictures under the same name, so the second overwrote the
  first and both were drawn with whichever won: each showing the other's picture, from a face it is not on.

- **A capture can be oblong.** `CameraOptions.size(width, height)`, held to `MIN_SIZE` on each side and `MAX_PIXELS` on
  the two multiplied - so a frame that is long and thin may be far wider than the old per-side limit and still cost less
  than a square one at it. Pixels stay square: a window states both angles already, and a plain `fov` gets its horizontal
  edges widened by the aspect.

  For a surface whose shape something else decided. A wall of maps is a rectangle of whatever proportions it was built in,
  and a **row of mirrors** down a wall is one frame rather than one each - they share a camera exactly, since a reflection
  looks out along the plane's normal, so every mirror on the wall is a window onto one picture. Thirteen blocks of wall at
  a map per block is 1664x128, inside the ceiling and with the wall between the mirrors left untraced by the mask.

  A slice of a wide frame is bit for bit the frame that slice would have been alone, which `WideFrameTest` holds it to -
  otherwise sharing one would quietly mean resampling it. The renderer needed nothing for this: it has taken a width and a
  height all along, and only the options and the mask were square.

  For plugin authors: nothing to do. `CameraOptions` gained a component, which moves the canonical constructor's arity -
  so **the old seven-argument form is still there**, meaning a square capture exactly as it used to. `size(int)` still
  means a square, `size()` still answers with the side, and `defaults()` is unchanged. `CameraEye.Mask` likewise keeps its
  square constructor beside the new `(width, height, wanted)` one. That leaves this release additive across the whole of
  `mapgui-api`.

- **`/mapgui camera performance` now says how long a frame takes to trace**, rather than only when a capture happened to
  be queued at the instant somebody typed the command - which is almost never. For a live view that figure is not a cost,
  it is the latency, and it was the one number missing from a report whose every other line said the camera was free.

  How it read before: a mirror visibly trailing a fifth of a second behind the person looking at it, beside a report of
  `0.34ms/t  0.7% of every tick`. Both true - the tick share is the world copy and the entity gather, and the tracing is
  off-thread - and between them no answer to the question being asked. The line now reads `Tracing  184.0ms a frame, off
  the main thread`, in yellow past a tick and red past three.

  It also says when more than one view is open, because that is the shape the question usually arrives in: *two mirrors got
  slower than one and nothing on the tick moved*. One capture is traced at a time - it is already spread across every
  core, so a second would contend with the first rather than draw sooner - so two views add their traces together instead
  of sharing the machine. `one at a time, so 2 views wait for each other`.

- **`performance follow` states both sides of a capture and its ray count**, where it printed the width twice - which
  described the 1664x128 frame over a row of mirrors as though it were two hundred times the picture it is. The rays are
  what the trace time is a function of, and now they are on the line next to it.

- **A player with two live views open now sees both of them.** The frame budget is divided per player rather than per
  view, so the first view to ask inside an interval took that player's whole allowance and the rest were refused - and
  asked in a fixed order, the same one won every time and the others never drew a single frame. Not a slow second view: a
  blank one. Anyone holding the camera with a mirror in front of them hit this, and so did any mirror made of more than
  one panel.

  Feeds are pumped longest-waiting-first now, so a player's frames go round their own views. Two views get half the rate
  each rather than all and nothing, and a player's total cost is unchanged - sharing their frames is not the same as
  giving each view a share.

  Rotating by tick was the obvious fix and does not work: a frame is granted once per interval, every second tick at ten
  fps, so a pointer that moves once a tick keeps step with it and the same view is first on every tick that mattered.
  `CaptureBudgetTest` pins the trap, that failed fix, and the working one.

- **A wall sends no pixels to somebody who cannot see it** - one standing behind it, one turned far enough away that it
  cannot be on their screen, or one with something solid in the way. Previously anyone within `range` was streamed to
  in full whichever way they were facing, so a video wall in a corridor paid for everybody who walked past the back of
  it. On a 6x6 at 10 frames a second that is 5.8 MB/s per person, for a picture with a block in front of it.

  This is a **pause and not an eviction**: they stay a viewer, keep their maps and their frames, and a per-player
  screen keeps running with its state intact. Turning back costs one frame - 576 KB on a 6x6 - and costs *nothing at
  all* if the wall did not change while they were away. That last part is what makes it safe on a menu or a still
  picture, which would otherwise have traded a stream that costs nothing for a keyframe every time a head turned.

  Two deliberate concessions. The view assumed is **wider than anyone's** - 130 degrees tall, 160 across - because the
  server is never told the client's field-of-view slider or aspect ratio, and Minecraft widens the real one again while
  you sprint. So this saves on somebody facing away rather than trimming the edges of what they can see. And it keeps
  sending for **half a second** after they look away, since heads turn several times a second and a frame costs more
  than the glance saves.

  Whether something is in the way is **traced to nine points** across the picture rather than one, so a wall showing
  round the side of a pillar keeps being sent - a single ray down the line of sight would have called the whole thing
  hidden, and the corners are the worst places to ask, since a wall seen through a doorway has all four of them behind
  the frame. It is a sample and not a proof: one visible only through a slit narrower than a third of the wall can
  still be missed, and shows as a frozen sliver until you step into the open.

  **Glass is not what hides a wall**, nor are panes, bars, ice or barriers. All of them have full collision, so the ray
  traced to decide whether a *click* reaches a wall stops dead at them - but a film behind a window is being watched,
  and freezing it is the error all of this is meant to lean away from. So the two questions are asked separately now: a
  click asks about collision, a view asks whether what it met stops vision, block state by block state.

  A view that meets something it can see through **carries on looking** rather than giving up and calling the wall
  visible. Stopping at the first thing hit would have meant one pane of glass in front of a display switched this off
  for that display entirely - streamed to everyone in range, through any amount of stone, wherever they stood. So the
  trace steps past the far side of whatever it saw through and keeps going, by the sliver it actually crossed rather
  than by a whole block, so nothing behind a clipped corner is skipped. Up to a dozen layers, which foliage reaches long
  before any window does, and giving up there is right: a wall behind leaves is half visible anyway.

  The trace is the one expensive part of this, and neither outcome costs much. A wall in plain sight is settled by the
  first of the nine, which is a single ray; a hidden one takes all nine, but each stops at whatever is in the way
  rather than crossing the room, and it is buying back megabytes a second. Verdicts are remembered per viewer and taken
  again when somebody moves, but no more often than every three ticks - walking covers a fifth of a block a tick, so
  without that floor a crowd crossing a plaza of screens would re-trace every screen every tick for an answer that had
  barely changed. A verdict nobody has moved away from is retaken twice a second regardless, which is also how long it
  takes to notice somebody walling a screen off; stacked with the grace period, that is a second at worst. Every one of
  those delays errs towards sending.

  What none of it saves is drawing. A wall with anybody in range is painted every frame whether or not any of them is
  looking, so video decode and terrain scans are unchanged.

- **Shift+scroll counts every notch now**, where it counted one per report. The wheel reaches a server as "this
  client has slot N selected", once a client tick however many notches went by inside it - so a flick arrived as a
  single report several slots away and was read as one notch, capping a scroll at twenty rows a second whatever the
  player did with the wheel. A map pinned to one slot also has its selection put straight back, so what arrives is
  how far the selection has *drifted* rather than how far the wheel turned, and the two only agree once that answer
  has landed. `Wheel` tells those apart - further out in the same direction is one turn carrying on, anything else
  is a fresh turn from a selection that has been put back - which counts a flick exactly, up to the four notches a
  tick beyond which nine slots in a ring cannot say which way round it went.

- Fixed: **the pointer could only sit on every other position the client can draw.** A map icon's coordinate is a byte the client halves, so icons live in a space twice as fine as the map's pixels - and the pointer's position was rounded to a whole pixel before it got there, so every coordinate MapGUI ever sent was an even one and half the places a cursor can sit were unreachable.
  It follows the head at half a pixel now, which is as fine as a map goes.
  Hit testing still asks whole pixels, since a button covers pixels rather than halves of them: what changed is where the icon is drawn, not what it points at.
  **Breaking:** `Marker#x` and `Marker#y` are `double` rather than `int`, snapped to those halves on the way in - so a marker still only counts as moved once it lands somewhere the client would draw differently, and a pointer drifting a hundredth of a pixel costs no packet.
  `Marker.at` takes whole pixels as happily as fractional ones, and `pixelX()` and `pixelY()` answer which whole pixel a marker is in.

### Writing a plugin

- **`WallDisplay.Builder#cullOffScreen(boolean)`** turns the above off, for a wall that should stream to everyone in
  range however they are facing and whatever is in front of them. On by default. Ignored by `prerender`, which has no
  stream to pause and would have to re-send every layer it had already placed in the client.

- **`Camera#capture(Player, CameraEye, CameraOptions, Consumer)`** takes the frame from a point you choose rather than
  out of the viewer's eyes, and **`Camera#feed(Player, Supplier<CameraEye>, Supplier<CameraOptions>, Consumer)`** is the
  live version, asking where to look afresh every frame. For a camera that is not held: one bolted to a wall, a
  periscope, a scrying pool, a mirror.

  The player is still handed over, because a capture is always *for* somebody - they decide how far it may see, which
  walls are showing them what, and whose share of the live budget it spends - but two things change once the eye is not
  a head. **The viewer is in frame**, since the reason they are normally left out is that a capture from inside
  somebody's skull would be filled with the back of their own head. And the frame **can be clipped**.

  `CameraOptions#selfie` is ignored here. It exists to move a player's own capture off their face and put them in
  frame, and an eye you placed yourself has already done both.

- **`CameraEye#clippedTo(Location, Vector)`** confines a capture to the front of a plane: rays begin where they cross
  it and nothing behind it is drawn, entities included. Without it a reflection is not merely wrong but impossible,
  since the reflected eye is on the far side of the wall the mirror hangs on and every single ray would begin inside a
  block - a reflection of solid stone.

  Distances are measured from the crossing rather than from the eye, so haze and the distance cap start at the plane.
  For a mirror that is the better of the two readings anyway: what fades with distance in a reflection is how far away
  it looks, and a reflection looks as far from you as it is from the glass.

  Written for an eye behind the plane, which is the only place a clip is worth asking for. From there a ray either
  turns toward the kept side and crosses once, or it never enters and draws the backdrop. An eye placed in *front* of
  its own plane is already inside the half-space and is not clipped at all; there is no far bound.

- A mirror never photographs **its own glass**. A wall is the one thing in front of a camera that is not in the world,
  so MapGUI hands walls to captures itself - and the nearest wall to a mirror's own camera is the mirror, sitting on the
  very plane the frame is clipped at. It is dropped with everything else behind that plane.

- **`CameraEye#through(Window)`** states the four edges of the picture instead of a field of view, which is what makes an
  off-axis capture sharp. An angle can only describe a frame centred on where the camera points, and a reflection has to
  look straight out of the glass rather than at the mirror - that is what lets two mirrors on one wall agree about the
  room. So a mirror the viewer is not squarely in front of sits off to one side of the frame, and an angle could only
  reach it by widening until it did.

  Everything that widening added was room the mirror does not show and resolution it does not get. Measured on a 1x1
  mirror at a normal mounting height with somebody standing at it, the glass was getting **36% of the frame** and being
  blown up nearly threefold to fill its maps; framed on its own corners it gets **99%**.

  It also removes both ends of the fov clamp, and a reflection ran into each: a small mirror across the room needs a frame
  narrower than ten degrees - clamped, it drew a wider view than it should and the geometry quietly stopped being right at
  a distance - and standing against a tall one needs wider than a hundred and seventy.

  A window is four tangents rather than four angles, because that is the form a ray is already built in. `ChunkFrustum`
  and `EntityScreen` take their bounds from the same four numbers, so culling and entity rects follow the real frame
  rather than a symmetric guess - which for an off-axis capture also means **fewer chunks copied on the tick**, the one
  half of a capture that can cost TPS.

- **`CameraEye#onlyWhere(Mask)`** draws only the pixels the caller will actually look at. Every ray is independent, so a
  pixel nobody reads is work that can simply not be done: a round mirror's glass is a circle inside a square with a
  painted frame around it, and about **a third** of its capture is never read - 15% for a square one, and little for a
  large mirror, since the shape only bites at an outside edge.

  Unwanted pixels come back **transparent** rather than as whatever was in the buffer, so a masked capture composites
  straight over something else. The palette matches on colour and ignores alpha, so this is done to the indices after
  quantising rather than left to fall out of the trace.

  Off the main thread, so it buys back none of the tick and will not let more live views run - that budget is spent
  copying the world, per chunk column, which a mask does not touch. What it saves is real CPU on a busy machine.

- **`Screen#holdable()` reads a held right-click as a hold**, arriving at `Screen#onHold(x, y)` once a tick for as
  long as it is down and ending at `Screen#onHoldEnd()`, where before there was nothing to read. A client repeats a held button as a click every four ticks and says nothing whatever when it is
  let go, so a stroke could only be guessed at from the gaps between clicks - which is a dotted line for a lagging
  player and a pen that stays down after they have stopped.
  It works off the one thing a client does report: letting go of an item it was **using**. On the press MapGUI
  raises that player's hand for them - `HandRaiser` sends them the "using an item" entity flag, which a client is
  never otherwise told about itself, since it is told about the world rather than about itself. From then on the
  client says nothing until the button comes up and then says so exactly once, and the **four-tick repeat stops**,
  so one press is one click rather than five a second.
  **Raised from outside rather than by giving the map something edible to hold**, which is the difference between
  this working and this being unusable. A client that starts a use itself drops the held item to the bottom of the
  screen and springs it back - vanilla's "you used that" bob, played for food, shields and everything else - and on
  a map that bob *is* the screen, once per press. `Minecraft.startUseItem` is the only thing that plays it, and the
  flag goes around it: `LocalPlayer` reads it, starts the use quietly, and no animation runs. Nothing is eaten, no
  cooldown starts, the server is using nothing, and no other player is told.
  The map keeps its own stack but for `use_effects`, which says `can_sprint` and full speed - without it a client
  using an item walks at a fifth speed, which is right for eating and wrong for holding a button.
  `onHoldEnd` is also called when the map loses the mouse mid-hold - scrolled away from, a prompt opening over it,
  the screen closing under a stroke - since the client stops there and tells nobody, and a hand left raised would
  swallow every press after it. Every carry mode works; not a wall, where the hand holding the button is holding
  the player's own item. `onHold` arrives on the tick of the press, so a tap too short to span one still counts, and
  it rides MapGUI's own tick - a screen following the cursor needs no scheduler and no plugin of its own.
  `/mapgui hand open sketch` is the demo, and `/mapgui hand give sketch` the same one as an item.

### Under the bonnet

- The tracer walks each ray from a **clipped origin** rather than from the eye, which is the whole of the change in
  `RayCaster` - a per-ray origin instead of reading `view.x()` three times. An unclipped view computes nothing extra
  and comes out byte-identical, which `ClipPlaneTest` asserts against the shorter constructor.

  A ray that never enters the half-space is skipped rather than walked. That is not only cheaper: a walk started from
  infinity reads blocks at coordinates that are not numbers.

- **The symmetric path is kept literally, not derived.** A window reduces to a field of view algebraically, but not to
  the last bit - and several tests here hold frames to being *byte-identical* across thread counts and across the empty
  space skip. So `CameraView.Frame#direction` and `EntityScreen` branch on it and keep the expressions they always used,
  rather than being rewritten in the general form and hoping the arithmetic lands the same way. `ChunkFrustum` needed no
  branch: negating an already negated tangent is exact, so its four planes come out the same numbers either way.

## 1.1.1

A capture reads a resource pack's own item models where it used to read the vanilla fallback.

- **An item is drawn as the model its `custom_model_data` names.** A pack that keeps working for players who
  declined it points `item_model` at something vanilla and puts its own model behind a select on a
  `custom_model_data` string - and only a client with the pack runs that select, so a capture read the fallback
  and photographed the plain vanilla item. Where the string is a key naming an item definition, that definition
  is what the pack meant, so it is tried first. A string naming nothing resolves to no layers and the next id is
  tried, which is what the candidate list was already for. A namespace is required, since `custom_model_data` is
  free text and a plugin may well be keeping a count in it.
- The release no longer carries a javadoc jar - Maven Central requires one and gets one, but on a GitHub
  release it is four megabytes of html nobody unzips. The api jars are named like the rest of the assets.

## 1.1.0

The camera: the world photographed onto a map, with real block textures, the people in view wearing their own skins,
and a sky with the sun, moon and stars where they actually are.
Also real map printing, more ways to carry a GUI, and a live view driven for you.

### Running a server

- **The band at dawn and dusk is the client's own now**, in colour and in shape, where it was a falloff curve with two
  hand-picked oranges in it.
  What was wrong was the shape rather than the colour. Vanilla's band covers **the whole half of the sky the sun is
  on**, tapering to nothing at exactly a quarter turn round - so looking at the point half way between the sun and the
  moon you catch the last of it as a shallow slanted edge. Ours faded on a curve that was near enough beside the sun
  and had run out well before that, and it went on to a hard stop 46 degrees up, where vanilla's band is only about 18
  degrees tall at its middle and thinner as it fades.
  It is also **not symmetric about the skyline**, which is most of what a sunrise looks like: over the sun the fan's
  rim ends it within those 18 degrees, and under it there is no rim in the way, so the sheet carries on beneath the
  camera and the colour hangs a long way down. Eighteen degrees above the sun there is nothing left; eighteen below,
  seven tenths of it. That half is not a detail that never shows - the client's dark disc, the one thing that would
  hide it, is only drawn while the eye is *below* the world's horizon height - and it is exactly the half a capture
  puts low in the frame, past where the copied world runs out.
  Both halves are read out of the 26.2 client rather than matched by eye. The colour is the arithmetic its
  `minecraft:visual/sunrise_sunset_color` keyframes are baked from - `SunGlowTest` holds it against all 32 of them,
  tick and ARGB verbatim, and every channel of every one lands within 1 of 255, which for a keyframe rounded to a
  byte is exact. The shape is `SkyRenderer.buildSunriseFan`: an apex 100 out on the horizon at the sun's side, a rim
  of radius 120 running right round the camera lifted `40 * alpha` on that side, and the colour interpolated from one
  to the other.
  **Traced rather than rasterized**, which is the one deliberate difference. That fan is enormous, nearly flat, and
  the camera sits all but exactly in its plane - the arrangement a near plane cuts through - so on screen turning
  toward the moon can drop the orange out of the middle of the view, and the same sky at the same moment draws
  differently depending on where you are pointed. Solving for the surface along each ray asks where the band is
  rather than where it lands on a screen, so a capture of one sky is one sky whichever way the camera was turned.
  It costs nothing: the fan is a ruled surface, so where a ray meets it comes out in closed form, with no
  trigonometry and no stepping round its sixteen segments.

- **Biome tints are blended across the biomes around a block**, the way the client blends them, so a border is a ramp
  over five blocks rather than a line drawn across the ground.
  Grass, foliage and water are one flat colour per biome, so a plains meeting a forest used to change green between
  two neighbouring blocks - which the eye finds immediately, and which reads as a mown lawn rather than as a wood
  starting. A river through a swamp had its banks stencilled in. All four tints the world answers go through it:
  grass, foliage, dry foliage and water.
  The client walks all twenty-five blocks of its 5x5 square for every block it draws. This reads **four**. A biome is
  one value across a 4x4x4 cell and a run of five blocks crosses exactly one cell boundary whatever it is aligned to,
  so the square covers at most four cells, and those twenty-five samples are those four weighted by how many blocks
  each covers - the same sum over the same numbers, divided the same way, so it comes out bit-identical.
  `BiomeBlendTest` holds it against a brute-force walk of all twenty-five, at every alignment and both signs.
  **It does not cost a frame anything measurable.** Four biome reads instead of one, on the pixels that see a tinted
  face and nowhere else, and only where the four disagree is anything averaged - a square five blocks wide is inside
  one biome nearly everywhere, and the server hands back the same interned biome for each corner, so the common
  answer is three reference comparisons away. Measured over a 128x128 frame: about 7 ns more per tint resolved and
  2,200 of them in the frame, so 15 µs against a trace that takes tens of milliseconds.
  A tint is also **remembered per position for the frame**, the way a fluid's corners already were and good for the
  same reason - the world a frame traces is a snapshot and cannot change under it. That takes 42% of the tint lookups
  out of the same frame, including the ones a single sample was already paying for.
  The biome is read at the block's own height, which is what it already was: **biomes are 3D**, and a lush cave under
  a desert tints its own vines while the sand over it stays sand. The square itself is flat, as the client's is.

- **The block an enderman is carrying and the poppy an iron golem is offering are both drawn**, and both mobs take the
  pose their own model takes to hold one.
  An enderman brings both arms up and carries the block at waist height; a golem holds its right arm out, leaves the
  left one hanging, and the poppy lies flat across the fist pointing away from it rather than standing up in it. Each
  placement is the client's own layer chain - `CarriedBlockLayer` and `IronGolemFlowerLayer` - composed down to one
  offset and one turn, so what a capture shows and what a player is looking at agree.

- Fixed: **the spinner was not round.** Its ring sat a pixel high, so the top dot was half outside the box with the
  rest of it cut off and a blank row under the bottom one, and the four dots on the axes each leaned half a pixel the
  same way, which left the ring symmetric about nothing.
  A dot sits its own width in from the far edge, so the two facing each other are `size - dot` apart - and where that
  was odd their middle fell between two pixels and every dot rounded to the same side of it. The ring now gives up
  that odd pixel and lands on whole ones, which makes it **exactly itself turned a quarter circle, and under both
  mirrors too**. Eight dots on a small ring still cannot all sit on a circle, but they are now all equally not-quite
  rather than differently so, and that is the part an eye picks up.
  `size` became a limit rather than an order with it: a spinner measures itself at the ring it can actually draw, so
  there is never a spare row and column beside it for a caption to line up against. The default is 14 rather than 13,
  which is a size the ring fills exactly.

- **The examples have no commands of their own.** Every demo is reached through `/mapgui hand open` and
  `/mapgui wall place`, so a server installing them gains no command surface at all. `/todo`, `/minimap`,
  `/snapshot` and `/walls` are gone with everything that hung off them: the camera's map printing moved into the
  screen's settings, where a **Print** row hands back four real maps, and its debug dump went - what it reported is
  `/mapgui camera performance`, which stays.

- **`/mapgui camera status` and `fetch-assets` are gone.** Status said what the textures were doing, which
  `/mapgui camera reload` now reports after re-reading them, and the bare `/mapgui camera` prints the performance
  report instead. Fetching by hand only did early what the first capture does anyway. Both are out of the docs.

- **Q closes a map pinned to the main hand**, where it used to be swallowed and do nothing. The key already could not
  reach anything else there - it would have thrown away whatever real item the map is covering - so the map was the
  only thing it could mean. A screen with no mouse keeps the old behaviour, since one carried rather than used is not
  something Q should end. The action bar says so.

- **The examples state how they are carried** rather than following the server's default, so each demo is the same
  wherever it is installed. The gallery, the to-do list and the claim map are popups, since all three want the wheel
  and the clicks; the minimap and the camera are worn in the offhand, where the hotbar stays the player's. Swapping
  hands puts the minimap and the camera away, which a screen with no cursor otherwise has no key for. All seven of
  them, including the two that are a second registration rather than a demo of their own - the gallery's type page
  and the jukebox in a hand.

- Fixed: **the camera ignored the first clicks after it was opened, and swapping hands appeared to do nothing.** An
  offhand map's default is for the swap key to toggle whether the screen has the player's clicks, which the camera
  took - but a viewfinder has no cursor, so the toggle changed nothing anybody could see and the shutter was simply
  dead until the key had been pressed once, with no way to tell that from a camera that was broken. It is a mode
  now: it has the clicks from the moment it is raised, and the same key puts it away, through the `onSwapHands`
  the minimap already closes on.

- Fixed: **the action bar described the state after the focus key rather than the one it was read in.** A map that
  toggles - by swapping hands or by right-clicking the air - opens with the player carrying it rather than using it,
  and the line greeting them said what a click would do and offered to put down a map they had not yet picked up. It
  says how to raise it instead, and says it again when the key is pressed, so it tracks the toggle rather than
  standing from the moment the screen opened.

- Fixed: **FFmpeg printed a stream dump to the console every time it opened a video.** Codec tables, bitrates and
  handler names, in a log a server owner reads for their own reasons. Its level is set to errors on the way in now.

- Fixed: **a carried block wore no tint, so a grass block had a grey top.** A block being carried has no biome to ask,
  and `grass_block_top` is a flat grey until something colors it.
  Vanilla writes the answer down on the block's own item - grass at a fixed climate, a constant for every leaf - which
  is what a block in a hand already used and what a block on a mob now uses too. Endermen, minecarts and anything else
  drawn from a block state rather than an item.

- Fixed: **bamboo came out almost black.** Its leaves were multiplied by the biome's foliage green like an oak's, but
  they are already green on disk - 72,117,25 against the flat grey 144 an oak leaf is drawn as - so the tint applied a
  second time took them to 13,85,1 and a grove photographed as a dark hedge. Bamboo now draws at its own color, like
  cherry and azalea leaves.

- Fixed: **a hidden cursor moved with the player's head.** Looking around while it was hidden dragged it along, so it
  came back somewhere the player never put it. It now holds still and **appears in the middle of the map every
  time**, however it went away - the screen losing the mouse, or `cursor()` returning false for a while, which is how
  a viewfinder shows a pointer only while sneaking. With `clamp-pitch` on the head goes to mid range with it, and the
  player's next move ends that, so a screen never holds a head against them. Off, it starts nearer an edge for a
  player already looking far up or down, who has no head movement left to bring it back the other way.

- Fixed: **a trident or a shield in a hand was drawn a block and a half to one side.** The shapes the client draws in
  code arrive in the frame a mesh is built in, which is a half circle from the one a block model is built in, and the
  turn between them was never applied. It went unseen on the other eleven because their definitions centre them in the
  item's box - a banner, a head, a chest all turn onto themselves - where a trident and a shield state no translation
  at all, sit against the box corner, and so swing their whole length across it.

- **How far out leaves close up is now a setting**, `camera.leaves.near-blocks` and `camera.leaves.far-blocks`,
  where it was two constants in the tracer. It was only ever a guess at one capture size: a leaf texel falls below a
  capture pixel at a distance that depends on how wide and how large you shoot, so a big frame with a narrow field of
  view had its canopies filling in well before they needed to. **The near end now defaults to 0** rather than 16,
  which closes the gaps from the lens out - a distant hillside goes solid without a band of haze standing in front of
  it, and a tree at arm's length is a twentieth filled, which nothing can see. Set `near-blocks` back to 16 to keep
  the sky visible through an oak overhead.

- **Every number the camera trades truth for speed with is now yours to set**, under `camera.reuse:` and
  `camera.limits:` in config.yml. Each of the three caches takes a near window, a far window and the two distances
  its ramp runs between; the two caps take a count each. All default to what the camera already used, so the section
  can stay out of your config file. `camera.reuse-chunks-for-ms` still works and now reads as
  `camera.reuse.chunks.stills-for-ms`.
- **Tile entities are graded by distance now**, like the columns and the entities, rather than one flat half second
  whether a chest was under your feet or sixty blocks off. Half a second to two, over 16 to 64 blocks.
- Fixed: **the tile entity cap dropped arbitrary ones rather than the furthest.** It was applied per column as well
  as overall, so a chest wall in the chunk you stand in spent the whole budget and the chunk beside it drew nothing -
  and within a column it cut in the chunk's own order, which is no order at all. Every column in range is now
  gathered before the cap keeps the nearest. The cap defaults to 512 rather than 64, since it is a backstop against
  a scene nobody planned for and not a budget.
- **The performance reports say what things are.** `mobs` is *entities*, `copy` is *blocks*, `fittings` is *tile
  entities*, `columns` is *chunks*, `main thread` is *costs the server*, `worst single` is *slowest frame*. Each
  stage carries what it went through beside what it cost, on one line rather than two, and the section counts are
  gone - they measured something only a renderer could act on. `CameraStats.Copy#columnsEach` is `chunksEach`.
- The map id on a hand item goes through `DataComponentTypes.MAP_ID` rather than the deprecated
  `MapMeta#setMapId`. Same component either way, and still not a `MapView`, since nothing about these maps exists
  on the server.

- **What a mob looks like is kept between captures. Where it is standing never is.** A chest can be held outright
  because it does not move; a mob cannot, since a stale one is drawn where it is not. But nothing expensive about a
  mob is its position - the cost is the shape, which is a part tree, nine equipment slots, its variant and its skin,
  and none of that changes frame to frame. So the shape is held and the six numbers saying where it stands are read
  fresh every capture. What can lag is a mob's *appearance*: the sword it just drew, an archer's levelled arms, a
  sneaking player's crouch. Graded by distance like the columns, a tenth of a second up close out to two seconds at
  the far end, and live views only. On `CameraStats` as `mobsReusedPercent`.

- **A mob is culled by its own box now, not by the sixteen-block column it stands in.** A two-block ball against the
  same four side planes, so a mob anywhere in a column the frame merely clips is no longer built - and building one
  is a part tree, its equipment, its variant and its skin. Two blocks because what is drawn reaches past the mob: a
  banner on a head, a pike in a hand. Pinned by the sweep the column test already had, marching every pixel of a
  frame at five pitches and five yaws.

- **The entity gather culls before it sorts, rather than after.** A box query in a village comes back with everything
  in a 128-block cube - dropped items, paintings, frames, the lot - and all of it went through the comparator before
  a linear pass cut it to the handful actually in shot. Worse, the comparator asked each entity for its `Location`
  twice per comparison, and every one of those allocates, so ordering a list that was about to be thrown away cost
  `n log n` allocations. Each entity's position is now read once and carried through the range test, the frame test
  and the sort.
- **The tick half of a capture is reported in three stages rather than two** - the copy, the mobs, and what is
  bolted to the world - with a count beside each. One timer over both gathers could only say that "entities" were
  slow, which is two problems with entirely different answers: fewer mobs in shot against fewer chests in range.
  On `CameraStats` as `mobMillisEach` / `mobsEach` and `blockEntityMillisEach` / `blockEntitiesEach`.

- **What a column's block entities draw is kept between captures, the way the column itself is.** A chest does not
  move, and a sign changes when somebody edits it rather than every tick - so rebuilding all of them every frame was
  the same waste the chunk copy used to be, and the fix is the same: held per column, for half a second. Flat rather
  than graded by distance like the columns are, because the grading pays there only because the columns a frustum
  wants grow with distance - block entities are capped at 64 within four chunks, where that ramp has barely begun.
  Nothing that animates is on this path: an item frame, whether it holds a map filling in or one of MapGUI's own
  walls playing, is an *entity*, and the entity path holds nothing between captures at all.
- **Block entities are no longer copied out of the whole square around the camera, every frame.** The gather walked
  a 9x9 of columns and called `getTileEntities()` on each, which builds a full `BlockState` - the block's inventory
  and data included - for every tile entity in the column, and then threw nearly all of them away: most block
  entities are drawn from their own block model and need nothing here, so the 64 cap almost never tripped and a
  village of chests, barrels and lecterns was materialised in full on every capture. Three fixes, all before
  anything is built: the columns are frustum-culled like the chunk copy, the corners of that square are dropped
  (they are ninety blocks out where the limit is sixty-four), and the remaining columns are asked with Paper's
  predicate form, which tests the block's position without building a `BlockState` at all.

- Fixed: **a live view could sit at a fraction of a frame a second for ten seconds after it opened**, reporting
  itself held by a budget it was spending a tenth of. The cost estimate is what divides the budget, and the first
  capture of a cold camera copies the whole world with nothing cached, so the estimate climbed to match - then the
  cache warmed, captures got cheap, and the estimate stayed high. Which is self-sustaining rather than merely wrong:
  a view throttled to a tenth of its rate delivers a tenth of the measurements that would correct it. The estimate
  now falls fast and rises gently, because the two errors are not mirrors - guessing too low is corrected by the
  very next capture, where guessing too high starves its own correction.
- Fixed: `Camera#frameRate(player)` reported the previous division rather than the current one, which mattered most
  at exactly the moment somebody asks - when something has just changed.

- **Entities out of frame are no longer built.** A capture searched the sphere around the camera and snapshotted
  everything it found - a part tree each, with equipment, pose and skin - and then the trace threw away everything
  the frame was not pointed at. A camera sees about a quarter of what surrounds it, so most of that work was always
  discarded. They are now tested against the same frustum the chunk copy culls columns with, at the same coarse
  granularity, which is already proven not to drop a column a real ray arrives at.
- **A capture draws the entities the photographer can actually see**, rather than a flat 64 blocks. That number was
  a guess at "roughly where the client stops sending them" and it was a third short: the shipped tracking ranges are
  96 blocks for mobs and 128 for players, so a photograph quietly left out a skeleton at eighty blocks that was in
  plain view. Read per category from the server's own `entity-tracking-range`, trimmed by a tenth so nothing is drawn
  that the client might not have been sent, and falling back to the old 64 on anything that will not answer.

- **A live view no longer copies the world from scratch every frame.** Chunk reuse was off by default and stayed off
  for viewfinders, so a preview at a player's full render distance re-copied 150-odd chunk columns several times a
  second and spent an entire 1 ms/t budget on about three frames. The argument for keeping reuse opt-in is a good one
  for a *photograph* - that one is kept, and a stale column is wrong forever. It barely applies to a live view, where
  being wrong lasts until the next frame and the next frame is coming anyway. So a paced capture now reuses columns
  whatever `reuse-chunks-for-ms` says, while a still is exact unless a server opts in.
- **And that reuse is graded by distance rather than flat**, which is what makes it honest. Staleness is only worth
  what it hides: the column a photographer stands in is most of the picture and is **never** reused, the ring around
  it likewise, and from there the window ramps out to a second at about 190 blocks - where a changed block is a pixel
  or two and nobody can tell it is late. It costs almost nothing to keep the near ring fresh, because the columns a
  frustum wants grow with distance: the near few are a handful of the hundred-odd a wide capture copies, so a few
  percent of the saving buys back the whole of the staleness anyone can see.
- **The tick half of a capture is reported split into the copy and the entity gather**, and per capture as well as
  per second. The rate a live view gets is the budget divided by what one capture costs, so that is the number that
  explains a slow viewfinder - and the split says which half to go after, since a big copy wants a shorter
  `max-distance` or reuse and a big gather wants fewer entities in shot. On `CameraStats` as `mainMillisEach`,
  `copyMillisEach` and `entityMillisEach`.
- **And why the copy cost that**, on `CameraStats.copy()`: how many chunk columns one capture went through, how many
  of them came back from the cache instead of being copied, and how many of their sections held anything. Columns is
  the driver and scales with `max-distance`; the reuse percentage is what says whether a live view is getting the
  cache or re-copying the world every frame; and filled-against-total sections is the ceiling on what any smarter
  copying could ever win, since a column is copied whole - Bukkit has no way to ask for part of one - but a section
  of pure air costs almost nothing.

- Fixed: **the capture queue had no bound, and a queued capture holds a copy of the world.** Every chunk column a
  capture copied stays in memory until it is drawn - 167 `ChunkSnapshot`s at range 192 - so a plugin capturing faster
  than the machine could trace did not fall behind, it ran the server out of heap. At most three may now be waiting,
  and further captures are turned away *before* the copy rather than queued, so a capture that was never going to be
  drawn in time no longer costs the tick that would have paid for it. The caller gets a null shot, which is the same
  answer it already had to handle, and `/mapgui camera performance` counts them on their own line - turned away is
  not failed, since nothing broke.
- Fixed: **the capture pool said two threads and ran one.** A `ThreadPoolExecutor` only grows past its core size when
  the queue refuses a task, and an unbounded queue never does. It is now written as the one thread it was, which is
  also the right number: the tracer already spreads a single capture across every core, so a second concurrent trace
  would contend for the same threads and hold a second copy of the world while it did.
- Fixed: **numbers were formatted in the server's locale**, so a German-locale server read `0,34ms/t` where its
  config.yml says `1.0`. Found by the first test written against the report.
- Captures taken without asking `readyForFrame` are counted apart and reported on an `Unpaced` line. Pacing is
  opt-in, so a budget can be set and completely ignored - and until now the report showed "no live views" beside a
  busy camera, which reads as agreement rather than as a warning. Those captures also no longer feed the per-viewer
  cost measurement: a 256-pixel still copies a far wider frustum than a 64-pixel viewfinder, so it was slowing that
  player's live view over a frame that was never part of it.
- **`/mapgui` only lists the branches this server has something to administer.** MapGUI is a library, so what it can
  administer is whatever the plugins on top of it registered - and on a server that installed it for one camera,
  `/mapgui hand` and `/mapgui wall` were two branches of commands about features that will never run, which is worse
  than clutter: a tree full of things that answer "nothing to show" teaches an admin not to read it. Worked out
  rather than configured, and it corrects itself as plugins load - `hand` appears once a GUI is registered, `wall`
  once there is content to place or a wall already up, `camera` once anything asks the camera or textures are
  installed. `commands.hide-unused: false` shows the lot.
- **`commands.enabled: false` turns `/mapgui` off entirely**, never registering it rather than registering and
  refusing, so nothing of MapGUI's appears in a tab completion. For a server whose plugin ships its own commands over
  the API and does not want two ways to ask the same question.
- **`Camera#stats()` hands over every number the built-in report prints**, and the built-in report is written against
  nothing else - no private access on the side, which is how an API ends up missing the field somebody needed. Rates
  and which plugin asked, main-thread cost per tick, the worst single capture, trace time, the queue, failures with
  the last reason, and what the live views are being allowed against the two settings that decided it.
  `Camera#frameRate(player)` adds the one figure a server-wide report cannot have: what *this* player's viewfinder is
  getting. The camera example ships `/snapshot debug` built on both. See
  [the same numbers, from code](docs/camera.md#the-same-numbers-from-code).
- **A live camera view now costs the server what an admin gives it, however many people have one open.** Two settings,
  `camera.live.max-ms-per-tick` and `camera.live.max-fps`, and everything between them is spent: views take as many
  frames as the budget affords and stop at the ceiling. At the defaults, and a frame costing a millisecond of
  main-thread time, everybody gets the full 10 fps up to six viewers at once, the seventh brings them all to 8.6 and
  twelve get 5 each - so a lone viewer does not get twenty times the frames for being alone, and the seventh to open
  one slows the other six rather than costing a seventh more. What a frame costs is **measured per viewer**, so the budget is divided as time and not as frames, and
  a cheap view that would hit the ceiling on less than its share hands the rest to one that cannot. A plugin asks
  `camera().readyForFrame(player)` every tick it would like a frame; asking is what makes it a viewer, so there is
  nothing to open, nothing to close, and a screen that stops asking stops being divided by. Advisory rather than
  enforced - it is the admin's tick either way, and `/mapgui camera performance` names whoever is spending it
  and counts what it was not asked about. See
  [live views](docs/camera.md#live-views).
- **`/mapgui camera performance` now reports what every capture on the server cost, whoever asked for it.** It used to be
  a per-player switch that pushed three lines of chat after each capture taken from *that* player's eye, which only
  describes the one camera the sample plugin has - somebody aims and clicks. A plugin capturing on a timer, for a live
  view, or for a player who is not the one asking either flooded a chat or reported nothing at all, and an admin had
  no way to tell which. It is counted whether anybody is watching or not, over a rolling few seconds, and reports four
  things: **how many captures a second and which plugin is asking**, worked out from the stack rather than from a new
  API parameter; **what they took off the main thread**, as a share of the 1000 ms a second there is to take, since
  that is the only part that can cost a tick; **the worst single one**, because one 40 ms copy is a stutter an average
  hides; and **how many are queued** when the trace pool is behind, which is the line that says over capacity rather
  than busy. The four-stage per-capture tail is still there as `/mapgui camera performance follow`, now at most one line a
  second with the ones left out counted rather than dropped silently. It was `timings`, which named the four-stage
  tail rather than the question an admin has, and the answer is in the same currency as `/mapgui performance`. Costs
  read in **ms per tick** rather than per second, since that is the unit a server is read in and the unit the budget
  is written in, with the configured limit printed beside what is being used. See
  [what to watch](docs/camera.md#what-to-watch).
- **`/mapgui camera status` says only what an admin can act on**: whether captures will draw, the packs they are
  drawn with by name, and for anything wrong what is wrong and what to do. Gone are the block-texture count, the
  download percentage and the directory the followed packs live in - things this code knows rather than things
  anybody can do something about, and a percentage that sits at nought reads as broken.
- **A capture that throws is no longer invisible.** It went to the console and nowhere else, so a camera failing every
  time looked from outside exactly like a camera nothing was using. Failures are counted with everything else, and
  `/mapgui status` names the plugin and how long ago.
- `/mapgui performance` carries the camera's main-thread cost. It is the page somebody opens when a server feels slow,
  and it counted only bandwidth - which a capture does not spend. No bandwidth figure was added for it: the bytes are
  the map frame a screen paints the shot into, and those are already counted under whatever wall or player received
  them.

### Widgets

- **`place()` is on `Node`** rather than only on the concrete classes, so a method taking plain nodes can position
  them. Handing a toolbar the three marks that go on it used to mean wrapping each one in a container just to reach
  the setter.
- **`Ui.Image(image)`** - a picture from a file, drawn a pixel for a pixel and laid out like anything else. A null
  image draws nothing, so the node's background is what shows when an asset is missing.
- **`Ui.Gap(width, height)`** - empty space of a fixed size, for holding a slot open when what goes in it is not
  there. Hiding a node takes its space with it, so a row of three controls becomes a row of two and everything shifts.
- **`Screen#keepDrawing()`** - ask for frames while an animation of your own runs. `animate` and `phase` already do
  this for what they cover; a sequence with stages read off the clock could only get frames from a repeating task
  calling `invalidate()`, which every screen with a timeline ended up writing.
- **`Screen#onSwapHands()`** - the swap-hands key, when MapGUI had no use for it. The one press that costs no aim, so
  it is the shutter a viewfinder wants; reaching it used to mean a listener of your own that looked up the session and
  cast its screen. Not called under `Focus.SWAP_HANDS`, where the key is the focus toggle.
- **`Screen#onSneak(boolean)`** and `sneaking()`. Sneak is the modifier a map ends up using, since it is the one
  gesture that costs no aim - a cursor only while it is held, a wheel that means zoom - and reading it meant polling
  the player from a task of your own.
- **`Spinner()`**, a ring of dots with a bright one travelling round it, for work that is happening but cannot say
  how far along it is - which is most work: a progress bar needs a total, and a download whose length nobody was told
  cannot give one. It steps from dot to dot rather than sliding, since on a 128-pixel map of 61 colours a smooth fade
  lands on the same few indices anyway, so snapping is crisper *and* cheaper to send. `size`, `dots`, `period` and
  `color`; a screen with animation turned off draws it standing still rather than repainting forever. See
  [widgets](docs/widgets.md#waiting).
- The camera example's viewfinder uses it, and no longer says **`Textures 0%`** while the assets download. That is a
  39 MB fetch that spends its first stretch at nought, and a number that does not move reads as broken where a
  spinner reads as busy. The figure is still in `/mapgui camera status`, where somebody who wants one goes.

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

- **`camera().feed(player, options, onFrame)`** drives a live view for you. Every consumer writing a viewfinder was
  writing the same tick loop, and each thing it has to get right is invisible when it does not: one capture in flight,
  so a long tick leaves the next frame late rather than two copies of the world in memory; frames only as fast as
  `readyForFrame` allows; and none at all while the screen is put away. Return null from `options` for a tick that
  wants no frame, which pauses it without closing it and gives back its share of the budget. `readyForFrame` stays for
  anything pacing itself.
- **`useResourcePack` hands back the pack's SHA-1.** A plugin shipping a pack has to serve it to clients too, and a
  client is offered one by its hash - so both sides were digesting the same bytes, and nothing stopped the file players
  downloaded and the file captures were drawn with from being different ones.
- **`CameraStats` says entities where it used to say mobs**, since it counts players as well and everything the reports
  print already called them that: `entityMillisEach`, `entitiesEach`, `entitiesReusedPercent`. `copyMillisEach` and
  `Copy` are `blockMillisEach` and `Blocks`, matching the "blocks" the same figures are printed under. The old
  `entityMillisEach()`, which was the two gathers added together, is `gatherMillisEach()`.
- **`CameraStats.bound()`** says which of the two settings is holding the frame rate down - the fps ceiling, the tick
  budget, or neither. Every consumer was working this out again, with its own idea of how near the ceiling counts as on
  it, and it is the actionable half of the reading: three frames a second under a ten frame ceiling is a budget that
  ran out, and three under a three frame ceiling is a setting somebody chose.
- `stats().live()` is never null - `viewers()` is 0 when nobody has a view open.
- **A capture size that cannot be traced is refused rather than quietly shrunk.** Clamping was worse than it sounds:
  `MapPrinter` cuts a capture into whole maps, so a size pulled down to 512 stopped being a multiple of 128 and the
  shot came back unprintable, reported to whoever pressed the shutter as a photograph that failed. The ceiling is now
  `CameraOptions.MAX_SIZE`, four maps to a side, also readable as `MapPrinter.MAX_SIZE_MAPS`.
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
  had been using on its own. Shearing a bogged takes the mushrooms off its head, which are part of its mesh rather
  than one of these - the moss stays, because the client goes on drawing that.
- **An idle illager folds its arms, and a pillager levels its crossbow.** The pose an illager stands in is a property
  of the individual rather than of the model - its own render state starts at neither - so it is now stated per mob and
  the client's own animation is what holds the mesh in it. An evoker, an illusioner and a vindicator stand with their
  arms crossed; a pillager stands with its crossbow up.
- **A tropical fish is drawn as the fish it is.** Twelve patterns over two body shapes and two dyes each is 3072
  combinations, so the client ships two greyscale bodies and six greyscale patterns and colours them per fish - which
  is exactly what happens here now, composited into one texture. Every one of them used to be the same plain
  `tropical_a`.
- Fixed: **a bow lost most of its string, and every thin thing in an item lost pixels with it.** An icon is extruded
  into a box per run of opaque texels, and each box's rim is a single line of the texture - so which line it lands on
  comes from one coordinate. A texture is sampled by flooring, so a rectangle's far edge names the texel *past* it,
  and the right and bottom rim of every box read whatever was next door. On a bow's diagonal string, where every box
  is one texel, next door is nothing. Every face of a box is now read a hair inside its own rectangle, where it can
  only land on that box's own picture - a hair rather than the half texel that first suggests itself, since the
  picture is stretched linearly across a face and pulling both ends to the middle of their texels would leave the
  outermost texel of every run covering half the width it should. A sprite seen edge on is its own rim now rather
  than nothing at all.
- Fixed: **the overworld's haze started in the middle of the shot.** It faded over the far 45% of the view, which on
  a 96 block capture began going white at 53 blocks. The client fades over the last `clamp(distance / 10, 4, 64)`
  blocks and leaves everything nearer alone - the overworld's own fog runs to a thousand blocks and is nothing a
  photograph reaches, so this haze is not weather, it is the edge of what has been drawn being hidden.
- **A raid captain wears its banner.** A banner carries no `equippable` component whatever slot it is in, so the
  armour path resolved nothing for one and a captain went bare-headed. It is drawn as the client draws anything that
  is not a skull on a head - the item's own shape, a quarter of a block down and at five eighths - with its cloth
  woven per stack, which an ominous banner's nine patterns need.
- **A mooshroom grows its mushrooms.** Three copies of the mushroom's own block model, two on the back and one on the
  head, at the client's own offsets - they are not on the cow's mesh at all, which is why a mooshroom came out as a
  plain red cow.
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
- **`HandOptions#mapId` pins the map id a screen is drawn under**, so a resource pack can recognise one map item as
  against another and give it its own model - a phone that looks like a phone rather than a rolled-up paper map.
  The client draws a filled map from its `map_id` component and reads nothing else about it, so the id is the only
  handle a pack has, and MapGUI's own ids are picked to be unpredictable. A pinned one is stamped into the item as
  well as used for the session, so a pack recognises the item in a chest and not only the open screen.
  **The top 1024 ids are reserved for it**, `MapIds.RESERVED`, so `Integer.MAX_VALUE - 1` is a number a pack can be
  written against and keep. MapGUI's own counter starts below the band rather than at the very top, where it would
  otherwise have handed that id to the second screen opened after every restart. Ids at or below 0 are refused at the
  other end, since the server allocates real map ids upwards from there and painting one replaces the picture of a
  map somebody owns. See [carrying a GUI](docs/hand.md#giving-a-resource-pack-something-to-recognize) for the
  `items/filled_map.json` side of it.
- **A carried screen now recognises its own item by name rather than by map id.** Which hand holds it, whether it is
  still being carried, and which stack to take back when it closes were all answered by comparing the id stamped into
  the stack - fine while every item had one of its own, wrong the moment two share one. Two screens pinned to the same
  id would have resolved to whichever hand matched first, taking the cursor and the pitch clamp with it, and a swap
  between them would have left the first screen up. The GUI's name is in the item's data already, so that is what is
  asked now. Swapping between two copies of the same screen also stops throwing it away and reopening it, which
  keeps its scroll position.
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

- **Shapes combine now**: `intersectionWith`, `combinedWith`, `without`, and `holeIn` for a box with the shape punched
  out of it. A `Shape` is just "is this pixel inside", so each of these answers by asking the shapes it was built
  from - which means an area none of the factories draws can be described rather than plotted a row at a time. The
  camera's iris was 100 lines of scanline arithmetic before this and is now four calls.
- **A shape is drawn a row at a time, not a pixel at a time**, which is what makes the above affordable at map sizes:
  `Shape.spansAt(y)` returns the runs it covers on one row, so an octagon costs eight sums a row rather than eight per
  pixel - 770 against 74000 on a 96 square window. Implemented for rectangles, polygons, intersections and holes;
  anything else returns null and is asked pixel by pixel as before.
- **An outlined shape got about four times cheaper** with it. An outline is grown from the boundary, so it does need to
  know about a pixel's neighbours - but working that out from the rows is the same answer as asking each pixel, and
  every filled shape in the library takes this path.

  Measured on the camera's iris, per painted frame, against the hand-written scanline it replaced: filled, 0.017 ms
  against 0.036; filled and outlined, 0.075 ms against 0.24 before this landed.
- `Shape.regularPolygon` for a triangle, hexagon or octagon turned to any angle, and `Shape.sideOfLine` for a
  straight cut across a box. Both take doubles, and `regularPolygon` hands back its corners so anything drawing along
  its edges can read them.
- `Shape.polygon` and `Painter.line` take corners between pixels as well as on them. Rounding a computed corner
  first decides which side of an edge a pixel falls on, which shows as a stepped edge at map sizes.
- **`Painter.pushClip(Shape)`** - clip to a shape rather than a box, so a picture can sit in a round window. It
  applies to whatever draws next, including text and images, which have no shape of their own to be cut to.
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

### Packaging

- **The examples are one plugin now, `MapGUI-examples-<version>.jar`, in place of a zip that unpacked into six.** Trying MapGUI is two jars in `plugins/` and a restart: no extraction, no "not into a subfolder", and nothing to download beside them.
  The sample GIF travels inside the jar and is written into `plugins/MapGUI/videos` on first start, so `/mapgui wall place polish-cow-transparent.gif` works on a server that has changed no settings. One jar is also the shape a reader is going to write - several GUIs registered from a single descriptor, and the `dependencies` block that makes that work stated once rather than copy-pasted six times. Each demo keeps its own module and package, so lifting one into your own plugin is still a matter of copying a directory. Deleting the jar remains the whole off switch.

- Release assets are named by version instead of globbed, and an unmatched name now fails the release. `softprops/action-gh-release` will publish a release with a file missing, so `MapGUI-*.jar` would have gone out as a release with no plugin in it the day that jar was renamed.

- **`mapgui-layout` is no longer a separate Maven artifact.** Its classes ship inside `mapgui-api`, which is now the
  only coordinate published and the only jar to grab from a release. Nothing changes for anyone already depending on
  `mapgui-api`, since the layout DSL was always pulled in transitively; what goes away is the second coordinate,
  the second jar for people without a build tool, and the "you need both" caveat in the docs. It stays a separate
  module, which is what keeps Bukkit out of the layout engine.
- Gradle module metadata is no longer published for `mapgui-api`. It is generated from the real dependency graph, so
  it named a coordinate that no longer exists, and Gradle prefers it over the POM. Resolution is by POM now, which
  costs nothing for one jar on one platform.

### Tooling

- **`MapImage`** renders a node tree to an image with no server anywhere, through the real layout engine, font and
  palette - plus `scaled`, `strip` and `write` for looking at several frames at once. A map GUI is 128 pixels of 143
  colours, and an animation that is over in a fifth of a second cannot be judged in game at all.
- `Picture.paint(painter, bounds, frames)` draws one still into a box. It was already possible through `VideoPlayer`
  with a millis of 0, which works and reads as though the picture were about to move.

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
