# M25 — Redline Chunk Priority

`redline-chunk-priority` is a small standalone NeoForge module that improves ordinary X/Z chunk loading order without replacing vanilla chunks.

It does **not** implement Cubic Chunks and does **not** change world height. It only nudges vanilla chunk loading/generation with short-lived loading tickets and optional `ChunkStatus.FULL` futures.

## Priority order

```text
Tier 0 — current player chunk
Tier 1 — 3×3 around player
Tier 2 — strict movement line, then movement side offsets
Tier 3 — strict look line, then look fan from center to FOV edges
Tier 4 — side chunks
Tier 5 — back chunks
Tier 6 — remaining chunks by rings
```

## Runtime behavior

Every configured interval, the module scans online players and builds an ordered chunk list. It then adds a temporary loading ticket for the top targets and optionally calls:

```java
ServerChunkCache#getChunkFuture(x, z, ChunkStatus.FULL, true)
```

This is intentionally safer than patching `ChunkMap`/`DistanceManager` directly.

## Commands

```text
/rcp status
/rcp dump
/rcp toggle
/rcp on
/rcp off
```

`/rcp dump` prints the current player's planned order, which is the main debug tool for checking whether movement/look tiers match expectation.

## Config

Generated server config:

```text
serverconfig/redline_chunk_priority-server.toml
```

Important values:

```toml
[general]
enabled = true
tickInterval = 5
ticketTimeoutTicks = 40
maxRequestsPerPlayer = 96
maxRequestsPerTick = 384
requestFullStatus = true

[movement]
minSpeedBlocksPerTick = 0.08
lineDistanceChunks = 12
maxSideOffsetChunks = 4
offsetStepDistance = 3

[look]
lineDistanceChunks = 12
fovDegrees = 90
angleStepDegrees = 10
```

## Test checklist

```text
1. Create/load a world.
2. Run /rcp status.
3. Run /rcp dump while standing still: LOOK_LINE should appear after CURRENT/NEAR_3X3.
4. Fly forward and run /rcp dump: MOVEMENT_LINE should appear before LOOK_LINE.
5. Enable debug.logTopChunks only for short tests because it is noisy.
```
