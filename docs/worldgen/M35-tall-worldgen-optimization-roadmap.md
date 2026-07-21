# M35 - Tall worldgen optimization roadmap

## Context

The current tall overworld datapack uses a 4064-block vertical build range. That
range makes the vanilla worldgen pipeline do much more vertical work than a normal
Overworld, even when most high sections are air and many deep sections are
functionally solid terrain mass.

The M34 profiling pass already removed one pathological lake/river lookup case and
left a more distributed profile: vanilla noise fill, biome queries, bounded cache
lookups, allocation pressure, surface rules, palette work, and GC all contribute.
This means a large speedup should come from reducing work volume, not only from
making individual scalar operations faster.

## Expected Outcome

Simple Java hot-path cleanup is expected to produce incremental gains. Native C++
for isolated math is not expected to deliver a 2.5x-3x overall speedup by itself,
because current evidence does not show one dominant numerical kernel large enough
to justify that bound.

A 2.5x-3x improvement is realistic only if the generator skips or defers a large
fraction of the 4064-block vertical range:

- skip air-only sections above the atlas-guided terrain envelope;
- fast-fill deep solid placeholder sections instead of evaluating density per
  block;
- defer expensive underground materialization until a section is actually needed.

## Phase 1 - Measurement Counters

Add low-overhead profiler counters before changing generation behavior:

- vertical sections visited per chunk;
- air sections skipped;
- solid placeholder sections;
- density samples evaluated;
- block writes and palette writes;
- biome queries by quart Y and XZ column;
- carver, aquifer, ore, and feature invocations by section;
- atlas cache hit/miss counts and cache sizes;
- allocation rate, GC count/time, and post-GC retained heap;
- surface-polish top scans and full-depth scans.

These counters should be reported through the existing `/rla profile` or chunk
profile output so ordinary, river, lake, ocean, and structure-heavy targets can be
compared directly.

## Phase 2 - Air Section Skip

Build a deterministic chunk-local `SectionPlan` from the atlas heightmap and water
guides. Sections above the maximum plausible surface plus a safety margin should be
marked as `AIR` and excluded from expensive generation where vanilla invariants allow
it.

The safety margin must account for:

- surface features and trees;
- structure terrain adaptation;
- river and lake containment work;
- ocean/coast depression filling;
- heightmap and lighting expectations;
- chunk-boundary consistency.

Expected gain: moderate to high on tall-world chunks, depending on how much of the
upper vertical range can be skipped without violating vanilla behavior.

## Phase 3 - Java Atlas Pipeline Cleanup

Optimize the current atlas-guided Java hot paths before adding native code:

- replace global boxed `ConcurrentHashMap<Long, ...>` hot caches with primitive or
  chunk-owned storage where ownership is clear;
- avoid global clear-all eviction in hot generation paths;
- reduce `Optional`, record, and temporary allocation in biome/noise/lake/water
  sampling;
- avoid exact river/lake/ocean scans where cached classification is sufficient;
- optimize `AtlasSurfaceMaterialPolisher.findTopTerrain` and avoid full-depth scans
  when section/heightmap evidence is enough;
- reduce full-height structure `getBaseColumn` allocation where structures only need
  a narrow vertical window.

Expected gain: 1.15x-1.4x overall in normal cases, potentially higher on lake/river
worst cases if exact scans still dominate.

## Phase 4 - GC and Memory Pressure

Treat GC as its own optimization target:

- measure retained heap after a forced settle point or heap dump;
- separate transient allocation from retained cache memory;
- keep all runtime caches bounded;
- prefer primitive arrays or fastutil maps in hot per-column/per-section paths;
- avoid per-block object creation inside density, biome, river, lake, and surface
  loops.

Expected gain: mostly stability and tail-latency reduction, with additional overall
speedup when allocation rate is high enough to keep GC visible in JFR.

## Phase 5 - Solid Section Fast Fill

Introduce `UNDERGROUND_PLACEHOLDER` sections for deep terrain that can be represented
as solid mass during initial chunk generation. These sections should be filled by
constructing or replacing the section palette with a single stone/deepslate state,
not by setting every block one by one.

The first implementation should remain Java-only and deterministic. It should prove
that a section can be filled in near-constant time while preserving heightmaps,
lighting assumptions, and downstream status invariants.

Expected gain: useful only if downstream density/carver/feature work is also skipped
or gated for placeholder sections. Bulk stone fill alone is not enough for 3x.

## Phase 6 - Lazy Underground Materialization

Add explicit persistent section states:

- `PLACEHOLDER`;
- `MATERIALIZING`;
- `MATERIALIZED`.

Materialization should run when:

- a player approaches the section by Y and distance;
- code reads or modifies a placeholder block;
- an exposed cave or neighboring full section requires continuity;
- a low-priority background queue has spare budget.

Materialization must be idempotent. It may only replace untouched placeholder blocks
and must never overwrite player edits or already-generated saved data.

This phase needs explicit rules for:

- caves and carvers;
- aquifers, lava, and underground water;
- ores and underground features;
- underground structures;
- cross-chunk continuity;
- save/load metadata;
- retry behavior after interruption.

Expected gain: this is the main path to a 2.5x-3x initial chunk-generation speedup,
because it changes when deep underground cost is paid.

## Phase 7 - Vanilla Pipeline Gating

Once placeholder sections exist, gate vanilla underground work by section state:

- skip density evaluation for placeholder sections;
- skip carvers where no underground exposure is needed yet;
- skip ore/feature placement until materialization;
- avoid biome queries across irrelevant quart-Y bands;
- avoid heightmap/light work for sections that are known uniform and sealed.

This phase has the highest behavioral risk and should be developed behind config
flags with A/B profiling and deterministic output checks.

## Phase 8 - Native C++ Evaluation

Evaluate C++ only after Java counters identify a stable batched hotspot. Native code
should not be called per block, per density sample, or per atlas sample.

Potential viable native boundaries:

- whole chunk or section density arrays;
- batched atlas sampling;
- distance fields for river/lake/coast masks;
- compact memory kernels where Java allocation remains the bottleneck.

Expected gain: useful as a final multiplier on a proven batched kernel, but not the
primary source of 2.5x-3x.

## Verification Requirements

Each phase should be checked with:

- fresh ordinary, river, lake, ocean, and structure-heavy targets;
- fixed seed and coordinate sets;
- warm and cold runs;
- JFR CPU/allocation/GC captures;
- limited-heap travel soak;
- chunk-boundary determinism checks;
- save/load checks for partially materialized underground sections.

The full project verification remains `.\scripts\verify.ps1`.
