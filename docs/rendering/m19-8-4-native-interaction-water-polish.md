# M19.8.4 — native interaction and fluid visibility polish

This patch fixes several outside-vanilla-shell cases that became visible after the first vanilla-backed renderer.

## Why it was needed

The native renderer was already reading baked models from cube snapshots, but outside-shell gameplay still used a simplified native interaction layer.  Vanilla normally solves these in `Block#useWithoutItem`, `BlockPlaceContext`, neighbor updates, scheduled ticks and LevelChunk-backed state propagation.  Cube-only heights cannot call that path blindly because vanilla writes still target `LevelChunkSection` storage.

## Fixes

- Native use handling is now block-specific instead of generic `OPEN`/`POWERED` toggling.
- Repeaters cycle `RepeaterBlock.DELAY` and no longer fake redstone power or play door sounds.
- Comparators cycle comparator mode.
- Levers toggle power with lever click sound.
- Doors, trapdoors and fence gates toggle `OPEN` through dedicated handlers and sounds.
- Double-height blocks are no longer passed through generic neighbor-shape refresh while their paired half is being managed manually.
- Native model build now treats a vanilla baked model/fluid renderer that emitted zero quads as not rendered, so fallback geometry can make the block visible instead of silently invisible.
- Water/lava keep a translucent cube-native fallback over vanilla fluid quads, making physical fluids visible even when the vanilla fluid renderer culls all faces for an outside-shell view.
- Pick-block now has a client mixin path for cube-only block hits outside the vanilla shell.

## Still not final

This is still not a retained-GPU renderer and it is not a complete clone of every vanilla block interaction.  It fixes the common visible regressions: doors/repeaters/levers/pick block/fluid visibility.  More complex redstone, block entities, waterlogging and full scheduled tick behavior remain later work.
