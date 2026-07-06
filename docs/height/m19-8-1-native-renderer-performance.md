# M19.8.1 — Native cube renderer performance guardrails

M19.8 introduced a first outside-vanilla-shell renderer which submits cube-native geometry directly from `ClientCubeSectionStore`.
The first implementation proved that blocks outside the vanilla height shell can be drawn, but it was too aggressive:

- it accepted surface-projection meshes from every loaded Y cube in the player X/Z radius;
- it rendered up to 384 cube meshes per frame;
- each cube could contribute up to 8192 immediate-mode faces;
- cube boundary faces were always visible when the neighbour was in another cube, even if the neighbour cube was loaded and solid.

That could easily produce millions of faces per frame, causing 1-2 FPS.

## Changes

`ClientCubeNativeMeshBridge` now has conservative MVP guardrails:

- horizontal native render radius reduced to 4 cubes;
- vertical native render radius reduced to 3 cubes;
- surface-projection mesh discovery is disabled by default;
- tracked meshes capped at 512;
- rendered meshes capped at 64 per frame;
- rendered faces capped at 65536 per frame;
- mesh build budget reduced to avoid long client ticks;
- cross-cube neighbour occlusion now checks adjacent `ClientCubeSectionSnapshot` data before emitting boundary faces.

## Why this is still an MVP

This renderer is still intentionally not the final renderer. It is a visibility/debug layer for testing true cube-only height outside the vanilla shell.
The next real renderer should use retained GPU buffers and vanilla baked models instead of submitting map-color debug quads every frame.

## Expected result

Extreme-height platforms should stay visible without catastrophic FPS collapse. Some far outside-shell terrain may disappear until the retained renderer exists; this is acceptable for M19.8.1 because interaction and gameplay debugging near the player is the priority.
