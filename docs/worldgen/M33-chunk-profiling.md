# M33 - Bounded chunk profiling

## Problem

The original `/rla profile` counters were useful for spot checks but could not run a
repeatable chunk-generation experiment. They did not own a fixed region, distinguish
new and already loaded chunks, cover every vanilla chunk status, collect heap/GC
deltas, or emit machine-readable results. Comparing ordinary terrain with rivers,
lakes, and oceans therefore depended on manual teleporting and mixed unrelated work
into the observations.

## Runtime design

`AtlasWorldgenProfiler` now owns at most one `ChunkProfileSession`. A session is tied
to one dimension and one square chunk region. It adds force tickets, observes
`ChunkEvent.Load`, waits for all target chunks plus a settling interval, writes the
report, and removes only the tickets that it added. A manual stop or startup failure
uses the same cleanup path.

The hard radius limit is seven chunks, so the largest session owns 225 entries. Chunk
completion uses a fixed `AtomicIntegerArray`; ticket ownership uses a fixed boolean
array. Session counters are capped at 128 names and legacy global counters at 256.
No block, column, sample, or visited-chunk history is retained.

The `ChunkStatusTasks` mixin measures these stages from method entry until the
returned future completes:

- structure starts and references;
- biomes and noise;
- surface, carvers, and features;
- light initialization and lighting;
- spawn and full conversion.

Stage rows are restricted to chunks inside the active region. Other RLA counters in
the report are process-wide observations made during the session because those
existing probes do not all carry chunk coordinates. Stage durations are wall-clock
latencies and asynchronous stages can overlap; their totals must not be summed as CPU
time. JFR remains the source of CPU samples, allocation stacks, locks, and thread
attribution.

Each report contains:

- requested/completed, new/existing, and preloaded chunk counts;
- stop reason, duration, timeout, and settling interval;
- per-stage count, total, average, maximum, and failure count;
- server tick timing during the session;
- used-heap start/peak/end and peak delta;
- garbage-collection count and time deltas;
- bounded RLA counter snapshots.

JSON and CSV files are written under
`redline-atlas-worldgen/run/profile-results/`.

## Commands

The commands require game-master permission:

```text
/rla chunk_profile start <label> <blockX> <blockZ> <radiusChunks> [timeoutTicks] [settleTicks]
/rla chunk_profile status
/rla chunk_profile stop
```

`blockX` and `blockZ` select the center chunk. The radius is `0..7`, timeout is
`20..72000` ticks, and settling is `0..1200` ticks and cannot exceed the timeout.
Defaults are 12000 timeout ticks and 100 settling ticks.

## Automated run

`scripts/profile-atlas-chunks.ps1` starts the NeoForge dedicated dev server in
automatic plan mode. The environment passes the validated plan path and a unique run
ID to `ChunkProfilePlanRunner`; no Gradle console forwarding or RCON is involved.

On the first server tick the runner starts Minecraft's built-in `JvmProfiler`,
temporarily disables the dedicated server's playerless pause, and executes points
sequentially through the same bounded session API as the commands. It restores the
pause setting, stops JFR, atomically writes `<run-id>.run.json`, and asks Minecraft to
shut down normally. The script watches the per-point reports, prints the six slowest
stages, and reads the final marker. On a host-side timeout it writes a bounded
cancellation marker so the runner can release tickets and stop JFR before shutdown.

After JFR has stopped, the runner classifies every measured center against the atlas
pipelines as `ordinary`, `river`, `lake`, or `ocean`. A point may declare an expected
`terrainClass`; the run marker then records the expected class, actual class,
requested coordinates, and resolved coordinates. A mismatch makes the automated run
fail without discarding the per-point performance report.

Before startup the script inventories these configured atlas layers and refuses to
run if one is absent or empty:

- `heightmaps`;
- `landcover`;
- `ocean_bathymetry`;
- `rivers`.

Example plan:

```json
{
  "radiusChunks": 1,
  "timeoutTicks": 12000,
  "settleTicks": 100,
  "points": [
    { "label": "ordinary-a", "blockX": 120000, "blockZ": 120000, "terrainClass": "ordinary" },
    { "label": "river-a", "blockX": -180000, "blockZ": 90000, "terrainClass": "river", "nearestRiverRadiusBlocks": 4096 },
    { "label": "lake-a", "blockX": 60000, "blockZ": -150000, "terrainClass": "lake" },
    { "label": "ocean-a", "blockX": -210000, "blockZ": -210000, "terrainClass": "ocean" }
  ]
}
```

The coordinates above only illustrate the schema. A real plan must use points
classified against the active atlas data. Points should also be far from spawn and
from earlier experiments so every measured region consists of new chunks.

`terrainClass` is optional and accepts only the four names above. For river points,
`nearestRiverRadiusBlocks` may be `0..32768`; the runner resolves the requested point
to the nearest indexed river segment before loading chunks. Other terrain classes use
the requested coordinates unchanged. Comparative runs must also require
`newChunkLoads > 0`; an existing-chunk report measures loading, not generation.

Run from the repository root:

```powershell
.\scripts\profile-atlas-chunks.ps1 -PlanPath .\tmp\atlas-profile-plan.json
```

Add `-ValidateOnly` to check the plan and atlas inventory without starting Gradle
or Minecraft. Add `-VerboseServerOutput` when the complete Gradle/FML debug stream
is needed; the default view keeps warnings, errors, lifecycle, JFR, and profiler
messages.

When a cached NeoForm launcher manifest is available in the workspace or user Gradle
cache, the script selects it and runs Gradle offline. The final run marker and script
output contain the path of the `.jfr` recording.

## Interpretation guardrails

A first run includes class loading, JIT compilation, and cold GeoTIFF decode costs.
Use a warm-up region that is not part of the comparison, then repeat each terrain
class at multiple fresh coordinates. Keep radius, timeout, settling interval, heap,
seed, JVM, and atlas files constant. Use report stage data to locate the expensive
phase and JFR to identify methods and allocations inside that phase before proposing
a mixin, native code, or cache change.

## Verification

- `:redline-atlas-worldgen:compileJava` completed successfully.
- `:redline-atlas-worldgen:runGameTestServer` passed all four required tests twice
  consecutively, including a repeated run against the same test world.
- `scripts/verify.ps1` completed all 18 Gradle tasks successfully.
- An end-to-end radius-zero smoke plan generated a JSON/CSV report, an atomic run
  marker, and a readable JFR recording before a normal server shutdown.
