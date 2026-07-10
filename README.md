# Redline Tech

Clean NeoForge 26.2 starter project.

## Requirements

- JDK 25
- IntelliJ IDEA

## First checks

```bat
.\gradlew.bat --refresh-dependencies
.\gradlew.bat clean build
.\gradlew.bat runClient
.\gradlew.bat runData
```

## Included dev integrations

- JEI API: compile-only
- JEI runtime: local dev runtime only
- Jade runtime: local dev runtime only

No blocks, items, recipes, worldgen, machines, or test content are registered yet.

## M24 — CC removed, height datapack added

The experimental `cc` module has been removed from this archive. A standalone vanilla-limit height datapack is included at:

```text
datapacks/redline_tall_overworld.zip
```

It expands the Overworld dimension type to `min_y=-2032`, `height=4064`, `logical_height=4064`.

## M25 chunk priority module

This archive includes `redline-chunk-priority`, a small standalone optimization module that preloads vanilla X/Z chunks in a movement/look aware order. It is intentionally separate from `redline-tech` gameplay code.
