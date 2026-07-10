# M28 Atlas Landcover Biomes

Adds an atlas landcover layer based on ESA WorldCover 2021 v200 COG GeoTIFF files.

Runtime folders:

```text
config/redline-atlas-worldgen/heightmaps/   # Copernicus GLO-30/GLO-90 DEM
config/redline-atlas-worldgen/landcover/    # ESA_WorldCover_10m_2021_v200_*_Map.tif
```

Useful commands:

```text
/rla reload
/rla landcover_tiles
/rla landcover
/rla biome_sample
```

The first biome MVP intentionally ignores water/coast/river distance. Landcover class `water` falls back to vanilla biome for now.

The biome resolver uses:

```text
world x/z/y
+ atlas DEM height
+ ESA WorldCover landcover
+ atlas-derived slope
+ computed temperature from latitude/elevation/noise
+ computed humidity from landcover/elevation/slope/noise
```

Main config section:

```toml
[landcover]
tileRoot = "config/redline-atlas-worldgen/landcover"

[biome_guide]
enabled = true
strength = 1.0
cellSizeBlocks = 384
equatorTemperatureC = 30.0
latitudeTemperatureLossC = 0.42
lapseRateCPerKm = 6.5
temperatureNoiseC = 3.0
humidityNoise = 0.12
elevationDryingPerKm = 0.08
slopeDrying = 0.18
slopeRadiusBlocks = 32
steepSlope = 0.28
cliffSlope = 0.55
montaneMeters = 1200.0
alpineMeters = 2200.0
nivalMeters = 3500.0
extremePeakMeters = 3800.0
```

Biome ids are configurable in the same section, for example:

```toml
highSteepBiome = "minecraft:jagged_peaks"
highFlatBiome = "minecraft:frozen_peaks"
alpineSteepBiome = "minecraft:snowy_slopes"
alpineFlatBiome = "minecraft:meadow"
treesTemperateBiome = "minecraft:forest"
grassTemperateBiome = "minecraft:plains"
```
