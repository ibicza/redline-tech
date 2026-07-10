# M22 — clean-room CC112 -> CC26 restart

This repository revision intentionally removes the previous `redline-world-core` module and all old M19/M20 world-core design notes from the working tree.

The new module is:

```text
cc
modid: redline_cc
package: com.redline.cc
```

Allowed code/design sources for this module:

```text
1. Cubic Chunks 1.12.2 source archive and MIT notice.
2. Minecraft / NeoForge 26.2 vanilla APIs.
3. Public NeoForge documentation.
```

Forbidden sources for this module:

```text
1. old redline-world-core implementation classes;
2. previous M20 dynamic-section/materializer bridge;
3. previous M21 hybrid cc reset.
```

Current foundation:

```text
CubePos / ColumnPos / Coords
Cube 16x16x16 block owner
Column x/z shell
CubeMap sparse cube storage
CubicLevel per-Level backend
Level#getBlockState redirect
Level#setBlock redirect
Level height checks redirect
```

The immediate next work is to replace the temporary block-array cube with a vanilla-facing `LevelChunkSection` owner and then add packet streaming, Region3D persistence, entity sections, scheduled ticks and lighting.
