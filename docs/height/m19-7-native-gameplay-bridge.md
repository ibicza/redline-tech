# M19.7 — Native gameplay bridge outside vanilla shell

This patch keeps the world cube-only and extends the temporary gameplay bridge that was introduced for true cubic heights.

## Fixed/added

- Client-side local break/place sounds for outside-shell interactions.
- Server-side break/place sounds for native edits.
- Water/lava bucket placement through the RWC native interaction payload.
- A small cube-native fluid flow queue for vanilla water/lava outside the temporary vanilla shell.
- A tiny passive mob spawn bridge for outside-shell test gameplay.
- Debug command: `/rwc cubic_test native_spawn cow`.
- Gameplay counters in `/rwc gameplay status`.

## Why this exists

Vanilla block interaction, fluid ticks and natural mob spawning are all chunk/heightmap driven. At Y=9000 or Y=-12000 those systems still do not have a real vanilla `LevelChunk` section to operate on. The cube backend can store and collide there, so this patch routes the missing gameplay pieces through cube-owned code.

## Fluid MVP limitations

The M19.7 fluid queue is intentionally conservative:

- only vanilla water/lava;
- only spreads into air;
- small per-tick budget;
- not a full clone of `FlowingFluid` yet;
- no source-creation rules yet;
- no cross-mod fluid behavior yet.

The final path should execute scheduled fluid ticks from `LevelCube` directly.

## Passive spawn MVP limitations

The passive spawner is only a proof layer. It can spawn cows around outside-shell players and exposes `/rwc cubic_test native_spawn cow`. It does not replace vanilla biome/pack/light/spawn-rule logic yet.
