# M20.1 — Dynamic Vanilla Sections

This milestone replaces the previous per-block outside-shell gameplay direction with a CubicChunks-style section adapter.

## Goal

Do not stretch the temporary vanilla shell to the full internal height and do not keep adding hand-written behavior for every block. Instead, materialize sparse vanilla `LevelChunkSection` objects for real cube Y coordinates:

```text
LevelChunk x,z
  dynamic sectionY -> LevelChunkSection 16x16x16
  dynamic sectionY -> CubePos x,y,z
```

A section exists only when a loaded cube/client snapshot/edit touches it. This avoids a dense 2048-section column for `-16384..+16383`.

## What this patch changes

- Adds `DynamicVanillaSectionBridge`.
- Adds `LevelChunkDynamicSectionsMixin`.
- `LevelChunk#getBlockState`, `getFluidState`, `setBlockState`, and `getSection(index)` can now use sparse dynamic sections outside the vanilla shell.
- Client cube snapshots/deltas are mirrored into real client-side `LevelChunkSection` containers.
- Server-side dynamic section writes are synced back to cube storage through the existing cube mutation/snapshot pipeline.
- `/rwc gameplay status` reports dynamic section counters.

## Current boundary

This is the foundation for moving vanilla logic/rendering onto real dynamic sections. It is not the final full Cubic Chunks replacement yet.

Still staged after this patch:

- sparse vanilla render-section lifecycle outside `LevelRenderer.ViewArea`;
- full BlockEntity/menu ownership through cube NBT;
- replacing native redstone/fluid MVPs with vanilla scheduled tick flow;
- long-term `BlockPos`/section-key strategy for all vanilla maps that still pack Y into 12-bit coordinates.

## Debug

Use:

```text
/rwc gameplay status
```

Look for:

```text
RWC dynamic vanilla sections: levels=..., sections=..., serverMaterialized=..., clientMaterialized=..., reads/writes=...
```
