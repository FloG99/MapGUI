# Carrying a GUI

By default a MapGUI screen is a **popup**: a map in every hotbar slot, the offhand reported empty so it draws
large, and while it is up the player is in it. That is the right answer for a menu and it is what MapGUI has always
done.

It is the wrong answer for a bingo card, a phone, a quest log or a television remote. Those want to be *carried* -
in one slot, along the player's own things, put away and taken out again. So how a screen is carried is a setting.

```java
MapGui.get().open(player, new BingoCard(player), HandOptions.pinned(8).allowOffhand(true));
```

Two questions, kept apart because they answer to different things.

## What is the player holding

| | what it is | can be taken | scroll wheel |
|---|---|---|---|
| `popup` | a fake map in **every** hotbar slot | no | **the menu's scroll** |
| `item` | a real `ItemStack` | yes - dropped, traded, stored, burned | the player's |
| `pinned` | a fake map in **one** hotbar slot | no | the player's |
| `offhand` | a fake map in the offhand, main hand untouched | no | the player's |

"Fake" is exact: the map is a lie told to one client, so the server's inventory never holds it and nothing can
drop, steal or frame it. Moving a fake map is not a move at all - MapGUI changes which slot it lies about.

**The wheel.** A popup can use it because all nine slots show the same map, so which is selected changes nothing.
For every other mode the wheel is the player's own hotbar - scrolling off the map puts it away and scrolling back
picks it up, which is how a carried thing should behave. The menu's scroll moves to **shift+scroll**, and MapGUI
puts the selection back on the map's slot when it does, because shift does not stop the client changing slots.

## Does it have the mouse

A **focused** screen draws a cursor, moves it with the player's head, and swallows their clicks. An **unfocused**
one still paints - it is a live display - but the player is looking at the world and their clicks reach it.

A popup is always focused. Everything else is focused **in the main hand**, and in the offhand follows `focus`:

| `focus` | an offhand map is focused |
|---|---|
| `main-hand` | never - to use it, hold it. The default |
| `swap-hands` | after the swap-hands key (F), which toggles it and moves nothing |
| `right-click` | after right-clicking into **empty air**, which toggles it |
| `sneak` | while sneak is held |
| `always` | always, in either hand |
| `never` | never, in either hand either - a display and nothing else |

`right-click` toggles in both directions, so the right button is spoken for: pair it with a screen whose
`activateOn()` is `Click.LEFT`.

`Session#focused()` reports it, and `Session#focus(boolean)` takes or gives back the mouse from your own code - a
"done" button that hands control back. The player's own gesture always overrules that.

## A real item

The item is a **key, not a screen**. Whoever holds it gets *their own* screen, built fresh for them, so a phone
left in a chest shows its finder their phone and a remote handed to a friend works for the friend. Nothing is
shared and nothing is owned.

Which is why it needs a **registered name** rather than a `Screen` instance - a name survives in NBT and a Java
object does not:

```java
MapGui.get().guis().registerOpenable("phone", "A phone", player -> new PhoneScreen(player));

ItemStack phone = MapGui.get().item("phone");
phone.editMeta(meta -> meta.displayName(Component.text("Phone")));
chest.getInventory().addItem(phone);
```

Three things ride on the stack: the map id in vanilla's own `map_id` component, because that is what makes a
client draw pixels for an id at all, and the GUI name plus the focus mode in the persistent data container. The
same id can show two players different pictures, because map data goes down one connection - which is exactly what
makes "everyone sees their own phone" free.

MapGUI works out who has one by **asking every player every tick which hand holds one**. An item reaches a hand a
dozen ways - scrolled to, swapped, dragged, picked off the floor, handed over, respawned with - and a listener per
route is a listener per route to get wrong.

Two different questions, and they have to be:

- **The screen opens** when the item reaches a hand, and **lives as long as the item is carried**. Scrolling to your
  sword does not end it, because a screen that ended there would come back blank - scroll position, page, everything
  gone. Put the item in a chest or hand it over and it ends.
- **The screen has the mouse** only while the item is actually in a hand, and in the offhand only per `focus`.

Closing the screen yourself while the item is still held leaves it closed, so a "done" button works. Put the item
away and take it out again to turn it back on.

`open(player, screen, HandOptions.item())` also works: it puts a real item straight into the player's hand, for that
player and that screen. With no registered name behind it, though, so when the item leaves their inventory the
screen ends and the item goes with it - MapGUI handed it over, so MapGUI takes it back. An item somebody found for
themselves is never confiscated that way.

## Giving a resource pack something to recognise

A filled map is drawn from its `map_id` component and nothing else - the client reads no NBT, no custom data, no
name. So the id is the only handle a pack has on one map item as against another, and by default MapGUI hands out
ids nobody can predict.

`mapId` pins one:

```java
HandOptions phone = HandOptions.item().mapId(Integer.MAX_VALUE - 1);

MapGui.get().guis().registerOpenable("phone", "A phone", player -> new PhoneScreen(player));
ItemStack item = MapGui.get().item("phone", phone);
```

Then a pack overrides `assets/minecraft/items/filled_map.json` and gives that id its own model, so a phone looks
like a phone rather than a rolled-up paper map:

```json
{
  "model": {
    "type": "minecraft:select",
    "property": "minecraft:component",
    "component": "minecraft:map_id",
    "cases": [
      { "when": 2147483646, "model": { "type": "minecraft:model", "model": "myserver:item/phone" } }
    ],
    "fallback": {
      "type": "minecraft:model",
      "model": "minecraft:item/filled_map",
      "tints": [
        { "type": "minecraft:constant", "value": -1 },
        { "type": "minecraft:map_color", "default": 4603950 }
      ]
    }
  }
}
```

A map id serialises as a plain integer, so `when` is the number itself. Keep the `fallback` as vanilla's own
definition or every other map in the game loses its colours.

**Take one off the top**, `Integer.MAX_VALUE - 1` and downwards. The range has three parts:

| Range | Whose |
|---|---|
| 0 upwards | the server's real maps. Painting one replaces the picture of a map somebody owns, which is why ids at or below 0 are refused outright |
| **the top `MapIds.RESERVED` (1024), down to `MapIds.LOWEST_PINNABLE`** | **yours to pin.** MapGUI never hands these out |
| everything below that, downwards | MapGUI's own, from `MapIds` - wall tiles, video frames, sessions |

Nothing polices which of the thousand you take, so two plugins both reaching for `MAX_VALUE - 1` would collide. If
yours is one of several on a server, count down from somewhere of your own rather than from the top.

A collision costs you the model and nothing else: MapGUI recognises its own items by the GUI name in their
persistent data, not by the id, so two screens sharing one still work.
Each player is sent their own pixels for it, so a pinned id shows everybody their own screen - two copies in sight at
once are the only exception, and they show one picture between them.

## Your item, MapGUI's screen

The other shape of the same idea: the item is **yours** and the map sits somewhere else. A camera with its
viewfinder in the offhand, a compass with a map beside it, a wand with its own panel.

```java
HeldTrigger camera = MapGui.get().openWhileHolding(
        stack -> stack.getType() == Material.SPYGLASS,
        HandOptions.Focus.ALWAYS,
        CameraScreen::new);
```

The screen opens while a matching item is in the **main hand** and closes when it leaves. Main hand and not either
hand deliberately: a trigger found in the offhand would be a screen drawn over the very item that opened it.

`Carry.OFFHAND` fakes the offhand alone and leaves all nine hotbar slots to the player, so your item renders as
itself while the GUI sits next to it. `Focus.ALWAYS` is what makes that GUI clickable in the offhand - it costs the
player their attack and place clicks while the screen is up, which is the trade for a menu that does not need a
gesture to reach.

**The screen is always in the offhand, which is why this takes a `Focus` and not a whole `HandOptions`.** No other
carry mode composes: the rest put the map in the hotbar, and a hotbar map counts as held only while its own slot is
selected - so reaching for it means letting go of the trigger item, which closes the screen you were reaching for.
Rather than document that and let people find out in game, the parameter simply cannot say it. The rest of
`HandOptions` would be dead weight anyway: `slot` and `movable` are ignored for an offhand map and `offhandAllowed`
is implied.

So the only choice is focus: `ALWAYS` for a menu, `SWAP_HANDS` or `SNEAK` for one reached by a gesture, `NEVER` for
something only ever looked at.

Swept once a tick, for the reason above, so the predicate is called for one stack per player per tick - keep it to
a material or a tag. A screen that closes itself stays closed until the item is put down and picked up again, and
nothing opens over a screen the player already has up.

Cancel the trigger in `onDisable`, the same as `guis().unregister`: `camera.cancel()`.

## Defaults, and who wins

Closest opinion to the screen wins: what the caller passed to `open`, else `Screen#hand()`, else the `hand:`
section of `config.yml`. The config is a default rather than a ceiling, since a carry mode is not a cost to cap.

`/mapgui hand give <gui>` hands out the item for a registered GUI, which is how to try all of this without writing
a plugin first.

## What it does not do

- **Give the wheel back to a non-popup screen.** Plain scroll belongs to the player, and shift+scroll is what is
  left. A screen that needs heavy scrolling is a popup.
- **Let a faked map be dragged with the mouse.** MapGUI would have to fake the carried slot as well as the
  inventory. `movable` uses the number keys over the map's slot instead, which is a real vanilla gesture and needs
  nothing pretended about the cursor.
- **Keep the real item in a covered slot usable.** A pinned map hides whatever is really in its slot, and an
  offhand map hides a shield. Both are inert while the screen is up and both come straight back when it closes -
  nothing is destroyed, and nothing can be dropped by accident either, since Q never reaches the covered item. On a
  pinned map in the main hand Q closes the screen instead; on one with no mouse it does nothing at all.
- **Survive a logout by itself.** A real item does, being an item, and the screen opens again the moment its owner
  picks it up. A faked map is session state and goes when the session does.
