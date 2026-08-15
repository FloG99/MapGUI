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

<img width="645" height="453" alt="382400600-716980a6-71e2-4a04-b79b-cdd2d637fde3" src="https://github.com/user-attachments/assets/1051ab81-83a8-4568-ae55-88d8a01e7fca" />

<img width="605" height="499" alt="polish_cow" src="https://github.com/user-attachments/assets/7b3f0a15-e847-4208-8e49-9c5dbcccf4e1" />

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
- **A camera.** A screenshot of what the player is looking at, with real block textures, transparency through
  glass and water, biome tints read from Minecraft's own colormaps, a sky with the sun, moon, stars and clouds
  where they actually are, and the people in view wearing their own skins. Point it back at yourself for a selfie.
- **A headless preview.** Render a screen to a browser or a PNG with no server running, and click it.
- **Not a screen you are trapped in.** Players walk, jump and sneak with a GUI open, unlike a chest GUI that
  freezes them in an inventory screen.
- **Nothing to lose.** No `MapView` to register, nothing in anyone's inventory, nothing dropped on death - the
  pixels are faked per player and go when they do. See [architecture](docs/architecture.md).

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

## Try the quick demo

**Paper 26.2 and Java 25 are both required** - an older Java will not load it at all.

1. Two jars from the [latest release](https://github.com/FloG99/MapGUI/releases/latest), both straight into
   `plugins/`:

```
plugins/
├── MapGUI-<version>.jar            the plugin
└── MapGUI-examples-<version>.jar   every demo below, sample video included
```

2. **Restart the server.** `/reload` is not enough: the examples declare `load: BEFORE` on MapGUI, and that
   ordering is only honoured at startup.
3. As an operator, run any of these. Every permission defaults to `op`, so there is nothing to configure first.
   **The demos register no commands of their own** - everything they can do is reached through `/mapgui hand open`
   and `/mapgui wall place`, so installing them costs your server no command surface at all.

| Command | Shows |
|---|---|
| `/mapgui hand open gallery` | every widget, and the layout rules side by side |
| `/mapgui hand open todo` | state, scrolling, text prompts, per-row closures |
| `/mapgui hand open minimap` | terrain rendering, and a screen with no cursor - worn in the offhand, swap hands to put it away |
| `/mapgui hand open camera` | a screenshot of the world, and a screen that aims instead of pointing - offhand, raised as a mode, swap hands to put it away |
| `/mapgui hand open claims` | a full-screen map, one `Draw` node standing in for a grid, cursor tracking |
| `/mapgui wall place draw` | a wall everyone draws on, with a palette only you can see |
| `/mapgui wall place jukebox` | a wall the room shares - registered for a hand *and* a wall |
| `/mapgui wall place polish-cow-transparent.gif` | a GIF on the wall. Shipped inside the examples jar, and no FFmpeg needed |

Move the mouse to aim, **right-click to select**, and the wheel to scroll. **Q closes** the ones that fill the
hotbar; the minimap and the camera are worn in the offhand, where **swapping hands** puts them away. Walking, jumping
and sneaking all still work. Placing a wall is left-click for the bottom-left corner, look at the far corner to
size it, then left-click again - or right-click to cancel.

Delete the examples jar when you are done; that is the whole off switch, and MapGUI keeps working without it.

The examples are one plugin depending on `mapgui-api` exactly as a third party would, so they cannot quietly use anything you cannot - one jar, one descriptor, a GUI registered per demo, which is the shape your own plugin will have.
It is not inside `MapGUI.jar` and it is not published to Maven. From a clone, `./gradlew runServer` starts a test server with it loaded instead.

## Build your own GUI

```kotlin
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.github.flog99:mapgui-api:1.1.0")
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
| [Carrying a GUI](docs/hand.md) | popup, real item, pinned slot or offhand, and who has the mouse |
| [Animation](docs/animation.md) | easing, looping effects, frame limits |
| [Video](docs/video.md) | GIF playback, fit modes, and optional MP4 and live streams |
| [Camera](docs/camera.md) | capturing the world onto a map, and where the textures come from |
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
