# Architecture

## Modules

| Module | | Published |
|---|---|---|
| `mapgui-layout` | the node tree, DSL and layout engine. **No Bukkit dependency**, so it unit tests without a server | inside `mapgui-api` |
| `mapgui-api` | what you compile against: `Screen`, `Session`, `WallDisplay`, `Marker`, prompts | yes |
| `mapgui-nms-26_2` | the things with no API equivalent, for one Minecraft version. One module per version, and the only ones that touch server internals | no |
| `mapgui-plugin` | the runtime: sessions, input, walls, commands, prompt providers | no, it *is* the plugin |
| `mapgui-preview` | renders a screen to a browser or a PNG with no server running | no |

`mapgui-layout` having no Bukkit dependency is the best decision in here and has paid for itself twice: the
unit tests and the whole headless preview only exist because of it. Anything it needs from the server arrives
as an interface - `Surface`, `Palette`, `TextFont`.

One coordinate is published, `mapgui-api`, with the layout engine's classes inside it. The two stay separate
modules because that is what keeps Bukkit out of the layout engine, but nothing consumes the layout engine on
its own, so publishing it separately would only mean two jars to find for one dependency.

## Nothing is real

There is no map. There is no map item. The server allocates no `MapView`, saves no id, and never puts anything
in anyone's inventory - the client is simply told that a hotbar slot holds a map, and told what that map looks
like.

This is the single most important decision in the plugin, because of what it deletes. An item the server does
not hold cannot be dropped, stolen, shift-clicked into a chest, hung in an item frame, duplicated, or left on
the ground when its owner dies. None of that needs guarding against, so none of it is guarded against. Earlier
versions defended each route one at a time and were still missing cases; this has no routes.

Two things make it work:

- **Map ids do not have to exist.** The client caches map pixels by id and creates an entry the first time it
  is sent data for an id it has never seen. So MapGUI picks ids counting down from `Integer.MAX_VALUE` - high
  enough that they cannot paint over a real map the player has looked at - and nothing is ever allocated or
  persisted.
- **The slots are substituted, not sent.** A single packet would be undone by the next thing that resynced
  inventory - and *canceling a right-click on a block is exactly that*, so the map vanished on every click.
  Every resync path ends up in the container menu's `ContainerSynchronizer`, so MapGUI wraps the player's and
  swaps the slots it cares about on their way out. One choke point, so no resync can reveal what is really
  there, and no timer is needed to paper over it.

The one piece of genuine server state involved is which hotbar slot the player has selected, so that is the one
thing closing a menu puts back.

The same idea carries walls: the item frames are client-only entities, never added to the level. Nothing is
placed, so nothing can be broken, and a restart leaves nothing behind. Only the *record* of a wall persists,
in `walls.yml`.

## Why the NMS modules, and how to add a version

Four things have no API equivalent, and they are the whole of `mapgui-nms-26_2`. Each is an interface in
`mapgui-api` - `MapTransport`, `PacketInput`, `RotationController`, `SavedMapPixels` - and `ServerBackend`
hands over one of each:

- **Sending map pixels and a fake item** as `ClientboundMapItemDataPacket` and
  `ClientboundSetPlayerInventoryPacket`. Bukkit's `MapRenderer` requires a real map to render, which is exactly
  what we are avoiding - so the transport goes direct. It also means MapGUI decides *when* frames go out, which
  is what the frame limits are built on, and only the changed rectangle is sent.
- **Pushing a player's pitch back into range without touching their yaw.** Yaw is the horizontal cursor axis,
  so setting both at once would send a yaw that is already a tick stale and snap their aim sideways mid-flick.

- **Writing a picture into the pixels the world itself saves for a map**, which is what makes a printed map
  survive MapGUI being uninstalled.
- **Repointing a client-only item frame at a different map id**, so a prerendered loop plays without sending
  pixels.

Everything else is plain Paper API - terrain from `BlockData#getMapColor`, most input from ordinary events. The
other modules build against `paper-api` alone, in seconds, with no dev bundle.

There is one module per Minecraft version, because each is compiled against that version's own server jar and
nothing else can be. Nothing imports one: `Backends` looks up a class name at startup from the version the
server reports, so several can sit in the same jar with only the right one ever loaded. Adding a version is
therefore mechanical, and nothing above the interfaces has to know it happened:

1. Copy `mapgui-nms-26_2` to `mapgui-nms-<version>` and point its dev bundle at the new Paper.
2. Fix whatever the compiler objects to.
3. Add it to `settings.gradle.kts`, and to the plugin's `runtimeOnly` and `shadowJar` lists.
4. Add the family and the backend's class name to the table in `Backends`.

Versions are matched by family, so `26.2.1` runs on the module built for `26.2`. A server MapGUI has no
backend for fails to enable and says which versions it knows, rather than half-working.

## Input, and why some of it is read off the connection

Most input is an ordinary event. Two gestures are not, and both for the same reason: the server decides what
happened from the item really in the player's hand, and MapGUI's map is not in their inventory at all.

- **Drop** only becomes `PlayerDropItemEvent` once there is an item entity to hand, so on an empty slot nothing
  is raised.
- **Right-click into air** is worse - the whole body of the server's handler, event included, sits behind
  `if (!itemStack.isEmpty())`. So it worked or not depending on what the player happened to be carrying.

So those are read by a netty handler placed just before the packet handler, which also has to *swallow* what it
reads: while a menu is open a right-click means "press this" and must not also open the chest behind it.

A handler that declines lets the packet through untouched, which is what lets a listener sit on a player who is
not pointing at any of our menus without eating their ordinary clicks. `InputRouter` is what makes several
things share one connection - a held menu and a wall being placed both hold a claim, offered newest first.

## Threading

Everything a screen touches is main thread. Two exceptions, both narrow:

- The packet handler runs on the **network thread**. It reads only flags the main thread keeps up to date -
  "is this player pointing at this wall" - and hops before touching anything else. A tick out of date is fine
  for that question. Anything shared across the boundary is a concurrent collection, and the reason is written
  down where it is declared.
- Prompt providers complete on **whatever thread answered**, which need not be the main one, so `promptText`
  always hops back before running your callback.

## Coordinate spaces

Worth knowing which one you are in, because there are four and they are easy to confuse.

| | |
|---|---|
| **surface pixels** | what the layout engine, `Painter`, `Marker` and every click handler speak. Origin top left of the whole canvas, so a 2x2 wall is 256x256 |
| **map-local pixels** | 0..127 inside one map. Only the transport and `WallTiles` see these |
| **icon space** | -128..127, twice as fine as pixels and centered. Only the transport, for markers |
| **blocks** | `WallLayout`, and the world |

The conversions live in one place each - `WallTiles` for pixels to maps, `WallLayout` for blocks to pixels -
so nothing above them has to know a wall is more than one map.
