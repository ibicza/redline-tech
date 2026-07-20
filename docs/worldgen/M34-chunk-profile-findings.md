# M34 - Chunk generation profile findings

## Scope and method

The bounded M33 harness profiled fresh radius-zero centers for four atlas terrain
classes. The final comparison contains three independently classified centers per
class; all twelve reports recorded `newChunkLoads = 1` and no existing target chunk.
Minecraft stage rows measure asynchronous wall latency and JFR supplies CPU,
allocation, GC, compilation, and stack attribution. Stage totals therefore are not
added together as CPU time.

Coordinates were intentionally different so every target stayed fresh. The medians
are operational measurements rather than paired microbenchmarks: cold class loading,
JIT compilation, GeoTIFF decode, dependency chunks, and nearby structures still vary
between centers.

## Lake watchdog root cause

A cold Lake Aiguebelette point hit the watchdog after 62.89 seconds. Its report and
JFR showed the server thread inside:

```text
AtlasSurfaceMaterialPolisher.polishChunk
  -> AtlasLakeGuide.estimateDistanceToShore
  -> AtlasRiverIndex.sample
  -> ImmutableCollections$MapN.probe
```

The run made 4,753,094 river samples. Their aggregate observed latency was
100,495.5 ms; surface polishing took about 53.84 seconds and lake sampling about
82.31 seconds across overlapping calls. `MapN.probe` accounted for 67.06% of JFR CPU
samples and `AtlasRiverIndex.sample` for another 19.73%.

`AtlasRiverIndex` built a packed-long spatial grid and published it with
`Map.copyOf`. The resulting immutable `MapN` used boxed `Long.hashCode`, effectively
folding the packed `(x,z)` key to `x ^ z`. Geographic grid keys consequently formed
extreme probe clusters. The lake shore scan amplified one pathological lookup into
millions of probes.

The correction keeps the privately owned grid in a trimmed
`Long2ObjectOpenHashMap`, so exact lookup is primitive and no longer uses `MapN` or a
boxed key. Related changes replace lake basin boxed sets with `LongOpenHashSet`, mix
packed keys before bounded concurrent-cache lookup, and reuse one immutable
`RiverSample.none()` instance. Cache capacities remain unchanged and bounded.

## A/B result

A fresh nearby center in the same lake completed in 4.394 seconds without watchdog
intervention. It made a comparable 4,997,456 river samples, but their aggregate
latency fell to 178.1 ms. Surface polishing fell to 1.646 seconds and lake sampling
to 5.173 seconds.

| Observation | Before | After | Change |
| --- | ---: | ---: | ---: |
| Target wall time | 62.89 s | 4.394 s | 14.3x faster |
| River sample aggregate | 100,495.5 ms | 178.1 ms | 564x lower |
| Surface polisher | 53.84 s | 1.646 s | 32.7x lower |
| Lake sample aggregate | 82.31 s | 5.173 s | 15.9x lower |

The centers are nearby rather than identical because a valid generation comparison
requires a fresh target. The similar sample counts make the lookup comparison useful,
but the wall-time ratio is not presented as a controlled microbenchmark.

## Final terrain medians

All values are milliseconds except peak heap delta and GC count/time. Each row is the
median of three fresh target reports after the fixes.

| Class | Wall | Peak heap MiB | Noise | Biomes | Surface | Features | Light | Max tick |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Ordinary | 1439.0 | 214.6 | 212.6 | 35.3 | 13.3 | 8.6 | 7.4 | 18.6 |
| River | 1835.2 | 256.2 | 70.8 | 24.9 | 4.0 | 4.1 | 4.4 | 17.3 |
| Lake | 3096.3 | 313.6 | 179.5 | 62.8 | 4.7 | 10.0 | 21.0 | 137.5 |
| Ocean | 854.0 | 478.3 | 51.6 | 19.2 | 0.2 | 1.6 | 2.5 | 46.8 |

Peak delta is a high-water mark, not retained heap. In particular, a negative or
large end delta can be determined by GC timing. A forced post-settle GC or heap dump
is required before assigning retained ownership.

## Remaining hotspots

The final river/ocean JFR interval lasted 10.0 seconds. Allocation averaged about
1.04 GiB/s and 29 collections consumed 1.383 seconds, or 13.8% of the interval.
The leading exclusive CPU samples were now mostly vanilla or normal constant-time
lookups:

- `NoiseBasedChunkGenerator.doFill`: 5.28%;
- bounded `ConcurrentHashMap.get`: 4.41%;
- thread-local lookup: 4.33%;
- simplex, Perlin, and improved noise together: 9.69%;
- climate R-tree lookup: 2.60%;
- palette re-encoding: 2.76%;
- voxel-shape joins: 2.52%;
- surface rules: 1.89%.

The pathological `MapN.probe` and `ConcurrentHashMap.TreeNode` stacks disappeared.
An ordinary-terrain interval instead emphasized surface-rule tests (8.65%), noise
fill (7.97%), climate lookup (4.67%), and lazy surface conditions (4.26%).

Across dependency chunks in the final river/ocean run, noise had the largest stage
latency (358 calls, 28.08 seconds aggregate), followed by biomes (566 calls,
13.56 seconds). Structure starts were usually cheap but highly skewed: four taiga
village starts totaled 2.10 seconds and five trial chambers totaled 0.79 seconds.
Those figures overlap asynchronous work and identify follow-up experiments; they are
not additive target-chunk costs.

Allocation samples still attribute substantial boxed-long traffic to bounded atlas
caches, especially `AtlasNoiseContext.biomeQueryInfo`, `AtlasBiomeResolver.column`,
`AtlasLakeGuide`, and `AtlasNoiseGuide`. Their fixed capacities prevent unbounded
growth, but the boxed concurrent maps plausibly cost tens of MiB each at full size.
That estimate must be replaced with a post-GC retained-size measurement before
changing capacity.

A separate `-Xmx2g` fresh target completed in 3.112 seconds without OOM. The point
was expected to be a lake but classified as ordinary, so the harness correctly failed
the plan marker. Its performance report is valid for the heap smoke test, not for the
terrain comparison.

## Optimization decisions

The next work should follow measured ownership rather than replace broad vanilla code:

1. Measure a fixed-size primitive or per-`NoiseChunk` column cache for
   `AtlasNoiseGuide`, `AtlasBiomeResolver`, and `AtlasNoiseContext`; verify allocation,
   eviction, thread ownership, and deterministic output.
2. Profile taiga villages and Jigsaw placement in isolated fresh regions before any
   structure mixin.
3. Re-measure `Climate.TargetPoint` arrays, `NoiseChunk` coordinate boxing, palette
   re-encoding, and surface-rule conditions with allocation stacks before selecting a
   minimal vanilla mixin.
4. Run a limited-heap travel soak and obtain a post-GC old-object or heap view to
   separate transient generation pressure from retained cache memory.

Native C++ is not justified by the current evidence. Exclusive numerical vanilla
hotspots account for roughly 25-35% of sampled CPU. Even an ideal replacement is
therefore bounded to about 1.33-1.54x overall, while a 4x native kernel is bounded to
about 1.23-1.36x before JNI and marshalling costs. Calling JNI per noise sample would
erase that gain; only a deterministic batched slice or chunk kernel could be viable.

The per-target stage medians are even more restrictive. Doubling noise alone gives an
approximate Amdahl bound of 1.08x for ordinary terrain, 1.02x for rivers, and 1.03x
for lakes or oceans. These estimates inherit the asynchronous-stage caveat.

The installed JDK has no `hsdis` library in its standard locations, so actual C2
assembly was not available. JFR compilation events show that `doFill` reaches C2
after cold failed/retried compilations, and `javap` confirms the corrected river hot
lookup invokes primitive `Long2ObjectOpenHashMap.get(long)` without `Long.valueOf`.
Assembly work should wait until a narrower Java hot method survives the allocation
and cache fixes above.
