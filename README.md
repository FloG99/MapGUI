# MapGUI

Interactive GUIs drawn onto Minecraft maps, with a real auto-layout engine behind them.

Move the mouse to move your cursor, right-click to press things, scroll to scroll. You describe the
interface as a tree of nodes and MapGUI works out where every pixel goes - no coordinate arithmetic, no
constants that break when you add a row.

**Works on unmodified clients.** No resource pack, no client mod, no shaders. It is a map item, so anyone who
can join your server can use it.

[![Build](https://github.com/FloG99/MapGUI/actions/workflows/build.yml/badge.svg)](https://github.com/FloG99/MapGUI/actions/workflows/build.yml)
[![License: LGPL-3.0](https://img.shields.io/badge/license-LGPL--3.0-blue.svg)](LICENSE)

**Paper 26.2 · Java 25 · no runtime dependencies**

<!--TODO: Video demonstration -->

```java
public final class CounterScreen extends Screen {

    private final State<Integer> count = state(0);

    @Override
    protected Node build() {
        return Column(
                Text("Counter").color(Color.WHITE).shadow(),
                Spacer(),
                Button(() -> "Pressed " + count + " times")
                        .background(theme().accent()).radius(4).textColor(Color.WHITE)
                        .onClick(() -> count.update(value -> value + 1))
                        .fillWidth()
        ).gap(4).padding(6).align(Align.STRETCH);
    }
}
```

```java
MapGui.get().open(player, new CounterScreen());
```

## What it does

- **Auto-layout.** Rows, columns, overlays and scroll views that measure and arrange themselves. `Spacer()`
  eats leftover space, `fill()` claims a share, everything else shrink-wraps.
- **Widgets** - text, buttons, toggles, text fields, dividers, boxes - plus `Draw` for raw pixels inside a
  laid-out box.
- **Menus on walls.** The same `Screen` runs in the hand or on a grid of maps hung on blocks, shared by a
  room or private per viewer.
- **Video.** Animated GIF, decoded by the JDK alone, scaled and palette-matched to any box.
- **Terrain.** The world drawn underneath your layout, following the player or fixed to a wall.
- **A headless preview.** Render a screen to a browser or a PNG with no server running, and click it.
- **Not a screen you are trapped in.** Players walk, jump and sneak with a GUI open, unlike a chest GUI that
  freezes them in an inventory screen.
- **Nothing is real.** No `MapView`, no map item, nothing in anyone's inventory, nothing to lose on death.
  See [architecture](docs/architecture.md).

## What you could build

- **A claim system** - left-click a chunk to claim it, and see at a glance who holds the rest.
  `/mapgui hand open claims` is exactly that.
- **A television, or any furniture with a screen** - a grid of maps on blocks playing a video, sized and sited
  like a block, and back up by itself after a restart.
- **A map of spawn, or of a dungeon, hung on the wall** - terrain fixed to the wall rather than following a
  player, which means it is scanned once and then costs almost nothing to keep showing.
- **A notice board or a leaderboard** that changes while people are stood looking at it, for all of them at
  once.
- **A team or friends menu** with a real interface, instead of chat commands and chest GUIs.
- Anything a chest GUI does badly, in truth - the player can keep moving, the pixels are yours, and a change
  one person makes can appear on everyone else's screen.

## Try it

**Paper 26.2 and Java 25 are both required** - an older Java will not load it at all.

Two files from the [latest release](https://github.com/FloG99/MapGUI/releases/latest):

1. `MapGUI-<version>.jar` into `plugins/`.
2. `MapGUI-examples-<version>.zip` extracted into `plugins/` as well - not into a subfolder. It is packed to
   land in the right places:

```
plugins/
├── MapGUI-<version>.jar
├── MapGUI-example-claims-<version>.jar
├── MapGUI-example-gallery-<version>.jar
├── MapGUI-example-minimap-<version>.jar
├── MapGUI-example-todo-<version>.jar
├── MapGUI-example-walls-<version>.jar
└── MapGUI/
    └── videos/
        └── polish-cow-transparent.gif
```

3. **Restart the server.** `/reload` is not enough: the examples declare `load: BEFORE` on MapGUI, and that
   ordering is only honoured at startup.
4. As an operator, run any of these. Every permission defaults to `op`, so there is nothing to configure first.

| Command | Shows |
|---|---|
| `/mapgui hand open gallery` | every widget, and the layout rules side by side |
| `/mapgui hand open todo` | state, scrolling, text prompts, per-row closures |
| `/mapgui hand open minimap` | terrain rendering, and a screen with no cursor |
| `/mapgui hand open claims` | a full-screen map, one `Draw` node standing in for a grid, cursor tracking |
| `/mapgui wall place draw` | a wall everyone draws on, with a palette only you can see |
| `/mapgui wall place jukebox` | a wall the room shares - registered for a hand *and* a wall |
| `/todo` | the same list, opened by its own plugin - which is how your users reach a GUI |
| `/walls here` | a plugin placing a wall itself rather than letting an admin site it |

Move the mouse to aim, **right-click to select, Q to close**, and the wheel to scroll. Walking, jumping and
sneaking all still work. Placing a wall is left-click for the bottom-left corner, look at the far corner to
size it, then left-click again - or right-click to cancel.

Delete any example jar you do not want; that is the whole off switch, and MapGUI keeps working without them.

The examples are separate plugins depending on `mapgui-api` exactly as a third party would, so they cannot
quietly use anything you cannot. None are inside `MapGUI.jar` and none are published to Maven. From a clone,
`./gradlew runServer` starts a test server with all of them loaded instead.

## Use it in your plugin

```kotlin
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.github.flog99:mapgui-api:1.0.0")
}
```

```yaml
# paper-plugin.yml
dependencies:
  server:
    MapGUI:
      load: BEFORE
      required: true
      join-classpath: true
```

Then [getting started](docs/getting-started.md).

## Running it in production

`MapGUI.jar` on its own does nothing visible - plugins built on it provide the menus, so you only need the one
jar and whichever of those you actually want.

`/mapgui` lists what you can run. See [configuration](docs/configuration.md) for `config.yml`, the commands and
the permissions, and [performance](docs/performance.md) before putting video on a wall - a big wall with an
audience is the one thing here that costs real bandwidth.

## Documentation

| | |
|---|---|
| [Getting started](docs/getting-started.md) | depending on it, your first screen, the input model |
| [Widgets and styling](docs/widgets.md) | the widget set, themes, borders, corners, long text, text input |
| [Animation](docs/animation.md) | easing, looping effects, frame limits |
| [Video](docs/video.md) | GIF playback, fit modes, and why not MP4 |
| [Walls](docs/walls.md) | video walls, menus on walls, shared state, the placement catalog |
| [Performance](docs/performance.md) | what costs bandwidth, and how to find out what is costing it |
| [Configuration](docs/configuration.md) | `config.yml`, commands, permissions |
| [Headless preview](docs/preview.md) | save-and-look development without a server |
| [Architecture](docs/architecture.md) | the modules, and why there is no real map |
| [Design notes](docs/design-notes.md) | the reasoning behind the awkward decisions |
| [Roadmap](docs/roadmap.md) | what is worth building, and what is deliberately closed |

## Building

```
./gradlew build
```

The plugin lands in `mapgui-plugin/build/libs/`. The first build downloads a Paper dev bundle for the one
module that needs server internals, which takes a couple of minutes.

See [CONTRIBUTING.md](CONTRIBUTING.md) to work on it.

## License

**LGPL-3.0-or-later.** Copyright (c) 2026 FloG99. See [LICENSE](LICENSE), which incorporates
[LICENSE.GPL](LICENSE.GPL) by reference.

What that means in practice:

- **Running a server** - including one you make money from - carries no obligations at all. Install the jar and
  forget about it.
- **Writing a plugin against `mapgui-api`** carries none either. Your plugin is your own, closed and paid if you
  like: you depend on MapGUI, you do not distribute it, and LGPL exists precisely to permit that.
- **Modifying MapGUI itself** and shipping your version is the one case with a condition - publish those
  modifications under the same licence.

The examples are [MIT](examples/LICENSE) instead, deliberately, so you can lift code from them straight into
your own plugin without inheriting anything.

If that arrangement genuinely doesn't work for your situation, open an issue and say why.
