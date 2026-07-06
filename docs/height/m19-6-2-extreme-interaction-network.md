# M19.6.2 — extreme-height interaction packet encoding

## Problem

The M19.6.1 hand-interaction bridge sent the clicked block position with the normal Minecraft `writeBlockPos` / `readBlockPos` helpers.
That is not safe for true cube-only heights.

The vanilla packed `BlockPos` network format is tied to the vanilla build-height shell. Positions such as:

```text
Y = 9000
Y = -12000
```

can be truncated/corrupted before the server sees them. The client can still animate the hand and locally raycast against the cube snapshot, but the server receives the wrong target, so the backend mutation is rejected or applied to the wrong shell-range position.

## Fix

`CubicExtremeInteractionPayload` now serializes the target block as three plain ints:

```text
x: int
y: int
z: int
```

It intentionally does not call:

```text
buffer.writeBlockPos(...)
buffer.readBlockPos(...)
BlockPos.asLong(...)
```

## Rule going forward

Any redline-world-core packet that must address real cube-only block coordinates must use explicit `int x/y/z` encoding, not vanilla packed `BlockPos` encoding.

Packed vanilla block positions are acceptable only for temporary compatibility paths that are guaranteed to stay inside the vanilla shell range.
