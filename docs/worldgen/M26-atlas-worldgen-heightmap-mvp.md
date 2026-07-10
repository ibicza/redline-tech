# M26 — Atlas Worldgen Heightmap MVP

## Goal

`redline-atlas-worldgen` is a standalone worldgen helper module. It keeps vanilla generation as the base and then applies a lightweight atlas shaping pass to newly generated vanilla chunks.

This is intentionally not the final Earth generator. It is the first practical bridge:

```text
vanilla chunk generation
  -> ChunkEvent.Load(newChunk)
  -> next server tick terrain shaping
  -> height sample from GLO/custom tile
  -> target Minecraft Y
  -> surface column correction
```

## Supported height sources in this MVP

### Copernicus GLO-30 / GLO-90 GeoTIFF

Put Copernicus COG files into:

```text
run/config/redline-atlas-worldgen/heightmaps/
```

Supported filename format:

```text
Copernicus_DSM_COG_10_N45_00_E006_00_DEM.tif  // GLO-30 / 1 arc-second
Copernicus_DSM_COG_30_N45_00_E006_00_DEM.tif  // GLO-90 / 3 arc-second
```

Bounds are inferred from the filename:

```text
N45_00_E006_00 => lat 45..46, lon 6..7
S24_00_W047_00 => lat -24..-23, lon -47..-46
```

The module tries to read TIFF through Java ImageIO. If Java cannot decode a particular COG/GeoTIFF, convert it outside Minecraft into custom `.rheight.properties + raw` format.

### Custom converted tile format

A future 240m converter should output this kind of descriptor:

```properties
id=alps_240m_0_0
format=raw_i16_le
data=alps_240m_0_0.raw
width=512
height=512
south=45.0
north=46.0
west=6.0
east=7.0
resolutionMeters=240
priority=240
scale=1
offset=0
noData=-32768
```

Supported formats:

```text
raw_i16_le
raw_i16_be
raw_f32_le
raw_f32_be
csv
```

Lower `priority` wins when several tiles cover the same lat/lon. GLO-30 defaults to priority 10, GLO-90 defaults to priority 30, custom 240m can use priority 240 or a custom value.

## Mapping config

Server config file:

```text
run/config/redline_atlas_worldgen-server.toml
```

Important fields:

```toml
tileRoot = "config/redline-atlas-worldgen/heightmaps"
originLatitude = 45.5
originLongitude = 6.5
degreesPerBlockLatitude = 0.000053898917
degreesPerBlockLongitude = 0.000053894708
verticalMetersPerBlock = 6.0
seaLevelY = 0
```

Mapping formula:

```text
latitude  = originLatitude  - blockZ * degreesPerBlockLatitude
longitude = originLongitude + blockX * degreesPerBlockLongitude
worldY    = round(heightMeters / verticalMetersPerBlock) + seaLevelY
```

For the first Alps test, put the player near x=0,z=0 and set origin near the center of the downloaded tiles.

## Commands

```text
/rla status
/rla reload
/rla tiles [limit]
/rla sample
/rla shape_here [radiusChunks]
/rla toggle
```

`/rla sample` shows the DEM height at the command source position.

`/rla shape_here 0` queues the current chunk for manual shaping.

`/rla shape_here 2` queues a 5x5 chunk area.

## Current limitations

1. This is a post-generation sculpting pass, not a full NoiseBasedChunkGenerator replacement yet.
2. It preserves vanilla generation as a base, but the first version rewrites surface columns and can damage caves near the corrected surface.
3. Java ImageIO may fail on some GeoTIFF/COG encodings. The stable long-term path is external conversion to `.rheight.properties + raw_i16_le`.
4. No land/water mask yet. Under sea level it fills water to `seaLevelY`, above sea level it uses simple grass/dirt/stone/sand surface rules.
5. No biome steering yet.

## Next step

After this MVP works visually, replace direct GLO TIFF usage with an offline converter:

```text
Copernicus GLO-30/GLO-90 source tiles
  -> converter
  -> base_land_240m custom raw_i16 tiles
  -> redline-atlas-worldgen runtime sampler
```
