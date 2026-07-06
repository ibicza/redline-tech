# Redline World Core

Cube-first world backend module for Redline Tech.

Current milestone implemented here:

- M0 skeleton: separate mod module, modid `redline_world_core`, metadata, initializer, debug commands.
- M1 math/API: `CubePos`, `ColumnPos`, `Region3DPos`, `CubeLocalPos`, `CubeBounds`.
- M2 early in-memory model: `CubeAccess`, `ProtoCube`, `LevelCube`, `InMemoryCubeStorage`.
- Early ticket/light API shells for later roadmap steps.

Useful dev commands:

```text
/rwc status
/rwc cube
/rwc selftest
```

## M19.0 gameplay cube-first gates

M19.0 starts moving gameplay systems behind cube tickets:

- entity tracker now classifies entities by owning `CubePos` ticket level and records `ENTITY_TICKING`, `BORDER`, blocked and always-ticking counts;
- block entity ticking gates now export profiler counters per cube ticket band;
- cube-owned scheduled block/fluid tick queues now export due/allowed/blocked counters per `BLOCK_TICKING` ticket gate;
- `/rwc gameplay status` summarizes entity, block entity and scheduled tick gate state.

This milestone is intentionally still a safe gate/diagnostic layer: vanilla tick cancellation mixins are staged after these counters prove the ticket gates are stable.
