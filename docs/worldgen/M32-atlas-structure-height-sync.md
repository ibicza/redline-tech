# M32 — Atlas structure height synchronization

## Problem

Atlas terrain shifts vanilla density around `noise_guide.referenceSurfaceY`, but vanilla structure
starts query standalone `NoiseBasedChunkGenerator.getBaseHeight/getBaseColumn` columns before real
blocks exist. Those standalone columns did not pass through the atlas `NoiseChunk` bridge, leaving
surface structures near the old vanilla height (usually around Y 64). The lower the atlas terrain
was, the higher structures appeared to float.

## Fix

- `ChunkStatusTasks.generateStructureStarts` opens a synchronous atlas query scope.
- `NoiseBasedChunkGeneratorMixin` shifts returned base heights by the exact integer shift used by
  `AtlasNoiseGuide.shiftedBlockY`, including strength, rounding and max-shift clamping.
- Returned base columns are translated by the same amount, preserving requested heightmap behavior
  for land, ocean floor and water-aware structure checks.
- Cross-chunk corner probes use a thread-local structure scope, while normal async generation keeps
  using the existing per-chunk context map.
- No `StructureStart`, bounding box or jigsaw piece is moved after creation. Underground structures
  keep their own placement logic; only surface queries they explicitly request become atlas-aware.

## Config

```toml
[structure_guide]
enabled = true
```

## Test matrix

Use a new world or previously ungenerated chunks and verify:

- village and pillager outpost;
- desert pyramid, jungle temple and swamp hut;
- woodland mansion and ruined portal;
- beached/deep shipwreck, ocean ruin and monument;
- normal and badlands mineshafts remain underground.

Expected result: structure ground is within the normal vanilla per-structure tolerance of the
actually generated atlas terrain, with no systematic offset toward Y 64.
