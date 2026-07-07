# M19.9 — Sparse vanilla-section renderer

M19.9 keeps the world cube-only, but stops trying to stretch the vanilla `LevelRenderer`/`ViewArea` vertically.

The important split:

- **Not used as world owner:** `ClientChunkCache`, `LevelChunk`, vanilla `LevelChunkSection[]`, vanilla vertical render section grid.
- **Still reused:** vanilla baked block models, vanilla block tint/light interfaces, vanilla render layers where they work.
- **Owned by RWC:** sparse `CubePos` render discovery, dirty queue, mesh lifetime, outside-shell culling, cube snapshot lookup.

Why not stretch vanilla render height:

A 32k block internal range is 2048 render-section layers.  A normal render distance would force millions of vanilla render sections if `ViewArea` is made to believe the dimension is 32k blocks tall.  RWC must render only the cube snapshots that actually exist around the player.

Current fluid note:

The vanilla fluid compiler is still not safe through the current custom-geometry submit layer at extreme Y: water/lava can compile into a physically present but visually omitted translucent surface.  M19.9 routes fluid blocks through a separate sparse color-only fallback submitted on a debug/color geometry layer.  This is intentionally stable-first; the final version should upload retained per-layer GPU buffers and then re-enable the exact vanilla fluid compiler.

Expected status line includes:

```text
mode=sparse-vanilla-section
fluidFallback=<built faces>
submittedFluid=<submitted faces>
```

If water is still invisible after this patch, the status line should immediately show whether fluid fallback faces are being built and submitted.
