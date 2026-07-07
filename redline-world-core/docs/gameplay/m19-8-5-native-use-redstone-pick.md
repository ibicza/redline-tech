# M19.8.5 — native use ordering, pick block and redstone MVP

## Interaction ordering

Outside the vanilla shell, client clicks now preserve vanilla's rough order: block use first for interactive blocks, item
placement second.  This prevents block items from stealing clicks on levers, trapdoors, gates, repeaters and comparators.

## Pick block

Vanilla middle-click sends `ServerboundPickItemFromBlockPacket`, which again packs `BlockPos` in the vanilla format and is
not safe for `Y=9000/-12000`.  The native interaction payload now has `PICK_BLOCK`, serialized with full `int x/y/z`, and
the server puts the clone stack into the player's hotbar.

## Redstone MVP

Vanilla redstone relies on LevelChunk neighbor notifications and scheduled ticks.  M19.8.5 adds a small cube-native
redstone bridge for the outside-shell test range:

- levers/buttons/redstone blocks emit native power;
- redstone dust power updates in a small area;
- repeaters/comparators update powered state;
- pistons get a visual/physical extended state with piston head;
- iron doors/trapdoors can react to native power.

This is not the final redstone simulator.  It is a deterministic MVP so cube-only placement/use can be tested without
returning to vanilla chunks.

## Passive spawner

The temporary automatic cow spawner from M19.7 is disabled by default.  Debug spawning through `/rwc cubic_test native_spawn cow`
still works.
