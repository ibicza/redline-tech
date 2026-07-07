# M19.8.6 — water world-space vertices + native door placement

This patch fixes two regressions from the first vanilla-backed renderer/interaction pass.

## Water

`FluidRenderer` emits vertices in section-local coordinates using `pos & 15`.  The native cube renderer stores world-space
meshes, so collected fluid vertices must be translated back to the cube/world origin.  Without this, water placed at
`Y=9000` was submitted around local `Y=8..15`, which made the real water invisible from the camera.  Earlier fallback
faces appeared as an atlas mosaic only because fallback geometry used world coordinates.

## Doors

`DoorBlock#getStateForPlacement` checks `level.getMaxY()`.  In cubic worlds the loaded `DimensionType` max height is still
only the temporary vanilla shell, so vanilla door placement returns `null` outside shell even though the cube backend range
is valid.  The outside-shell bridge now builds the lower door state manually from vanilla door rules: facing, hinge,
powered/open and lower/upper halves are written directly to cube storage.
