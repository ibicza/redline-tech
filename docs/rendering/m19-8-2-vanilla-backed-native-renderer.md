# M19.8.2 — Vanilla-backed native cube renderer

## Goal

M19.8.2 replaces the grey map-color cuboid renderer from M19.8/M19.8.1 with a cube-native renderer that reuses vanilla block and fluid rendering data.

The important split stays the same:

```text
Redline owns:
  cube snapshot source
  cube render cache
  cube dirty/rebuild queue
  outside-shell culling
  outside-shell mesh lifetime

Vanilla owns:
  baked block models
  baked quads
  block tinting where possible
  texture atlas UVs
  fluid tessellation where possible
```

## Why not vanilla SectionRenderDispatcher directly

Vanilla section rendering is tied to `ClientLevel`, `ClientChunkCache`, `LevelChunkSection` and vanilla build height. Outside the temporary shell, positions like `Y=9000` or `Y=-12000` do not have legal vanilla chunk sections. Feeding those positions into vanilla section lifecycle would pull the project back toward column-owned world state.

M19.8.2 therefore uses vanilla rendering pieces inside a Redline-owned section lifecycle.

## Implementation notes

`ClientCubeNativeMeshBridge` now builds meshes from `ClientCubeSectionStore` using:

```text
ModelBlockRenderer
BlockStateModelSet
FluidRenderer
CubeRenderView implements BlockAndTintGetter
```

`CubeRenderView` reads block/fluid state from cube snapshots first. It delegates tint/light where safe, but reports the internal cubic height range, not vanilla height.

The old cuboid renderer is still present only as a fallback when a vanilla model fails to tessellate.

## Current limitations

This is still an MVP and not the final retained-GPU renderer:

```text
1. Geometry is still submitted through NeoForge custom geometry each frame.
2. Lighting outside-shell is approximate/full-bright-ish for now.
3. Block entities are not rendered by this path yet.
4. Some complex/special render-shape blocks may use fallback faces or not render.
5. Translucent sorting is basic.
```

## What to test

```text
/rwc cubic_test teleport_extreme high
/rwc_client native_renderer status
place/break blocks by hand
place water/lava buckets
try grass/dirt/stone/log/glass/stairs/slabs/fences

/rwc cubic_test teleport_extreme low
repeat the same checks
```

Expected: blocks outside vanilla shell should now use actual texture-atlas baked quads instead of flat grey debug cuboids.
