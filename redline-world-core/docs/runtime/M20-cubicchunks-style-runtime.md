# M20 — CubicChunks-style runtime adapter foundation

M19 proved that the cube backend can store, sync, render and interact with blocks at true cubic heights.  The bad part was
that outside the vanilla shell every vanilla subsystem was patched separately: fluids, doors, redstone, pistons, pick-block
and player fluid state all had their own one-off bridge.

M20 changes the direction.

## Old outside-shell path

```text
client/server action
  -> CubicExtremeGameplayBridge
  -> hand-written handler for this block/system
  -> ServerCubeCache mutation
  -> native render sync
```

This does not scale.  Every block that uses vanilla Level#setBlock, neighbor updates, scheduled ticks or block entities
needs another patch.

## New path

```text
vanilla block logic
  -> Level#getBlockState / getFluidState
       redirected to cube cache by existing query mixins
  -> Level#setBlock
       redirected by M20 to ServerCubeCache outside the vanilla shell
  -> CubicChunksStyleRuntime
       queues neighbor/fluid/redstone refresh from the cube-owned mutation
```

The important part is that vanilla code may still decide what block state should be written, but the write no longer needs
a real 32768-block-tall LevelChunk column.  Outside the shell the owning object is still CubePos/LevelCube.

## What landed in M20.0

- `CubicChunksStyleRuntime` — central outside-shell runtime boundary.
- `CubeRuntimeBlockGetter` — cube-backed `BlockGetter` for vanilla algorithms that only need reads.
- `CubeRuntimeSectionFacade` — lightweight 16x16x16 facade, not a fake huge `LevelChunk`.
- `Level#setBlock` HEAD redirect outside the temporary vanilla shell.
- Runtime neighbor queue that calls `BlockState#handleNeighborChanged` with cube-backed reads and redirected writes.
- Runtime write fan-out: after every cube-owned write, schedule fluid, redstone and neighbor refresh from one place.
- `CubicExtremeGameplayBridge` now tries vanilla `BlockState#useItemOn` before falling back to old narrow handlers.
- Redstone bridge now writes through the runtime adapter instead of bypassing it.
- Piston direction bug fixed: vertical pistons now keep UP/DOWN facing instead of being forced to NORTH.
- Piston head is no longer treated as a redstone conductor.
- Sticky piston no longer pulls redstone dust/redstone-style attachables.

## What this deliberately does not finish yet

This is the foundation pass, not a full rewrite of every vanilla subsystem in one commit.

Still pending for later M20.x:

- block entity runtime instance creation outside shell;
- menu/UI bridge for chest/furnace/dispenser/hopper/etc.;
- scheduled block tick execution through cube-owned queues;
- replacing the old redstone solver with vanilla-hook-driven update graph;
- real comparator inventory output;
- retained render buffers.

## Why this matches the Cubic Chunks direction better

Cubic Chunks did not make one enormous vanilla chunk.  It changed the world access/loading boundary so the world could be
made from 16x16x16 vertical sections.  M20 follows the same idea inside Redline World Core: do not stretch the vanilla shell;
make vanilla logic talk to a cube-backed runtime facade.
