# AntiESP (Paper 1.21.11 · Java 25 · PacketEvents)

Server-side anti-ESP. It does not try to *detect* ESP. It **never sends the data** an ESP or freecam client would draw, so no client mod can bypass it.

| Cheat | What the client receives |
|---|---|
| Player ESP / tracers / nametags through walls | Players outside line of sight are despawned on the client. Every packet about them (move, equipment, metadata, effects, sounds…) is dropped. |
| Freecam peeking | The freecam camera is client-side, so visibility is checked from your real eye position (plus F5 cameras). Freecam sees empty space. |
| Storage ESP / stash finders / spawner finders | Chests, shulkers, barrels, hoppers, spawners etc. outside line of sight are sent as stone/deepslate/netherrack/end stone, and their block-entity data is removed from the chunk packet. |
| Health ESP / health bars | Other players' health is always sent as 20 and absorption as 0. Death animations still work. |
| Sound ESP / radar | Positional sounds coming from hidden players are dropped. |

## Build
Requires JDK 25.
```
./gradlew build
```
The jar ends up in `build/libs/AntiESP-1.0.0.jar`. Put it in `plugins/` next to PacketEvents.

> Bytecode targets Java 21 (Paper's minimum), so it runs on your Java 25 server. To require Java 25, set `options.release.set(25)` in `build.gradle.kts`.
> If your PacketEvents version differs, change `packetevents-spigot:2.11.2` to match (any 2.11+ works).

## Commands / permissions
- `/antiesp status` shows per-tick cost and how many things are hidden
- `/antiesp inspect <player>` shows what one player's client currently has
- `/antiesp reload`
- `antiesp.admin` (op): use the command
- `antiesp.bypass` (nobody by default): see everything. Staff who want to spot cheaters may want it.

## How it works
1. **Default deny.** When a protected entity or storage block is first sent to a client (spawn or chunk packet), it starts hidden.
2. **Raycasts on the main thread.** Every few ticks, rays go from the player's eye (and the clipped F5 front/back camera spots) to 9 points on the target's hitbox. The hitbox is grown for ping and stretched along the target's movement. If any ray gets through without hitting an occluding block, the target is revealed.
3. **Reveal = full respawn.** The server re-sends the entity with correct equipment, metadata and skin. The tab-list entry is never removed. Blocks get their real state and tile data back.
4. **Re-hide** once something has been out of sight for a couple of checks. An ESP can't remember a base you walked past.

`canSee()`, `/msg` and tab-complete are **not** affected, because nothing is hidden through the Bukkit API long-term.

## Recommended alongside it
Enable Paper's built-in anti-xray for ores and cave layout. Anti-xray and AntiESP cover different data and work together. In `config/paper-world-defaults.yml`:
```yaml
anticheat:
  anti-xray:
    enabled: true
    engine-mode: 2
```

## Tuning
- Lag: raise `entities.check-interval` to 3 and `blocks.check-interval` to 6–10, or lower `max-distance`.
- Players "popping in" at corners: raise `box-expand` or `movement-lookahead-ticks`.
- Mobs/armor stands/item frames: add them to `entities.types` (costs more CPU).

## Limits (honest)
- Something you can actually see on screen can't be hidden. ESP still shows players that are in line of sight.
- Pop-in: a hidden thing appears about 50–100 ms after it comes into view. Tune it with the settings above.
- Not Folia-compatible (uses the main-thread scheduler).
- Grim and other packet anticheats run fine next to it. If you see reach false-flags on players who just came round a corner, report it with your Grim version.
