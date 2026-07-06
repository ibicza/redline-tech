# Redline World Core — разбор vanilla worldgen Minecraft 26.2 и план cube-native клона

Дата: 2026-07-06  
Цель: понять, какие идеи ванильного worldgen-а Minecraft 26.2 стоит украсть/переписать под `redline-world-core`, не возвращаясь к настоящим ванильным колоннам.  
База анализа: decompiled Minecraft 26.2 из локального NeoForm/NeoForge cache проекта Redline Tech 26.2.  
Целевой подход: **не подключать vanilla `ChunkGenerator` как владелец мира**, а перенести его математику и правила в собственный `CubePos`/`CubeGenerator` pipeline.

---

## 0. Главный вывод

Полная совместимость vanilla worldgen-а через прямое использование `ChunkGenerator` технически возможна только как временный reference/adapter, но как production-архитектура она опасна.

Ванила везде думает колонной:

```text
ChunkPos x,z
ChunkAccess = 16 × full generation height × 16
ChunkStatus pipeline по всей колонне
Heightmap по X/Z
WorldGenRegion вокруг центрального chunk-а
features/structures/carvers вокруг ChunkPos
```

Наш core должен думать кубом:

```text
CubePos x,y,z
Cube = 16 × 16 × 16
CubeStatus pipeline по кубу
Region3D storage
CubeTicket loading/generation/ticking
Column indexes только как индексы, не как владелец блоков
```

Поэтому правильный путь:

```text
1. Взять vanilla идеи, классы, формулы, noise-схему, surface rules, biome climate model.
2. Не тащить vanilla ChunkAccess как основной storage.
3. Сделать cube-native генератор, где любой block sample зависит только от world coords + seed + settings.
4. Для дорогих/колоночных операций ввести свои ColumnIndex/ColumnWorldgenCache, но не хранить там блоки.
5. Vanilla adapter можно оставить только как debug/reference для сравнения seed → expected shape.
```

---

## 1. Короткая карта vanilla worldgen классов

### 1.1. Главный генератор

```text
net.minecraft.world.level.chunk.ChunkGenerator
net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
```

`ChunkGenerator` — абстрактный верхний слой. Он знает:

```text
- biomeSource;
- структуру generation pipeline;
- structure starts/references;
- biome decoration/features;
- поиск структур;
- mob spawn во время worldgen;
- методы getBaseHeight/getBaseColumn.
```

`NoiseBasedChunkGenerator` — основной генератор Overworld/Nether/End-style миров. Для нас особенно важны методы:

```text
createBiomes(...)
doCreateBiomes(...)
createNoiseChunk(...)
fillFromNoise(...)
doFill(...)
buildSurface(...)
applyCarvers(...)
getBaseHeight(...)
getBaseColumn(...)
iterateNoiseColumn(...)
addDebugScreenInfo(...)
```

Что украсть:

```text
- общий порядок: biomes → noise → surface → carvers → features;
- NoiseChunk cell interpolation;
- NoiseRouter model;
- aquifer logic;
- surface rules model;
- climate sampler debug values;
- preliminarySurfaceLevel для surface/aquifer.
```

Что не тащить:

```text
- ChunkAccess как владелец блоков;
- ProtoChunk/LevelChunk lifecycle;
- ChunkStatus как наш основной pipeline;
- WorldGenRegion как обязательный центр мира;
- heightmap как единственный источник правды.
```

---

## 2. Vanilla generation pipeline по статусам

В 26.2 pipeline задаётся через `ChunkStatus` и `ChunkStatusTasks`.

Порядок статусов:

```text
EMPTY
STRUCTURE_STARTS
STRUCTURE_REFERENCES
BIOMES
NOISE
SURFACE
CARVERS
FEATURES
INITIALIZE_LIGHT
LIGHT
SPAWN
FULL
```

### 2.1. EMPTY

Пустой `ProtoChunk` без содержимого.

Для нас аналог:

```text
CubeStatus.EMPTY
```

### 2.2. STRUCTURE_STARTS

Ванила решает, может ли структура стартовать в данном `ChunkPos`.

Ключевые места:

```text
ChunkGenerator.createStructures(...)
ChunkGeneratorStructureState
StructurePlacement
RandomSpreadStructurePlacement
ConcentricRingsStructurePlacement
Structure.generate(...)
StructureStart
```

Особенность: старт структуры выбирается по горизонтальному chunk-sector, seed и placement rules. Структура может занимать много чанков, но старт хранится в центральном/исходном chunk-е.

Для cube-only нельзя просто хранить это в одном кубе. Нужен отдельный 3D reservation/index слой:

```text
StructurePlanId
StructureBoundingBox3D
source ColumnPos или StructureCellPos
список затронутых CubePos
references по cube/column
```

### 2.3. STRUCTURE_REFERENCES

Ванила сканирует соседние chunks в радиусе 8 и добавляет ссылки на структуры, чьи bounding boxes пересекают текущий chunk.

Для нас это должно стать:

```text
поиск structure reservations, пересекающих CubeBounds текущего cube;
или отдельный ColumnStructureIndex, если структура поверхностная;
или Region3D-level index для крупных 3D структур.
```

### 2.4. BIOMES

Ванила заполняет biome palette по noise climate sampler.

Ключевые места:

```text
NoiseBasedChunkGenerator.createBiomes(...)
NoiseBasedChunkGenerator.doCreateBiomes(...)
NoiseChunk.cachedClimateSampler(...)
BiomeSource.getNoiseBiome(...)
MultiNoiseBiomeSource
Climate.Sampler
```

Важная деталь: biome sampling идёт в quart coords, то есть грубее блоков:

```text
quartX = blockX >> 2
quartY = blockY >> 2
quartZ = blockZ >> 2
```

Для нас это хорошо ложится на куб:

```text
Cube 16³ = 4 × 4 × 4 biome quart cells
```

То есть biome data в cube можно хранить как 4×4×4 palette, почти как vanilla section biome container.

### 2.5. NOISE

Самый важный этап. Ванила заполняет колонну stone/water/lava/air/ore-vein placeholders по density field.

Ключевые места:

```text
NoiseBasedChunkGenerator.fillFromNoise(...)
NoiseBasedChunkGenerator.doFill(...)
NoiseChunk
NoiseRouter
DensityFunction
Aquifer
OreVeinifier
```

Ванильный `doFill` идёт по noise cells внутри chunk-а:

```text
cellCountX = 16 / cellWidth
cellCountZ = 16 / cellWidth
cellCountY = generationHeight / cellHeight
```

Для Overworld settings:

```text
minY = -64
height = 384
size_horizontal = 1 → cellWidth = 4
size_vertical = 2 → cellHeight = 8
```

То есть vanilla terrain не считает полноценный дорогой noise на каждый блок. Он считает density на сетке cell corners, потом интерполирует блоки внутри cell.

Для cube-only это надо сохранить.

### 2.6. SURFACE

После NOISE колонна уже содержит default stone/water/lava/air. Surface pass заменяет верхние слои:

```text
stone → grass/dirt/sand/gravel/snow/terracotta/ice/... depending on rules
```

Ключевые места:

```text
NoiseBasedChunkGenerator.buildSurface(...)
SurfaceSystem.buildSurface(...)
SurfaceRules
SurfaceRuleData.overworld(...)
```

Ванила идёт по каждому X/Z столбу сверху вниз, считает:

```text
stoneDepthAbove
stoneDepthBelow
waterHeight
surfaceDepth
surfaceSecondary
biome
steep
abovePreliminarySurface
```

И только если текущий блок равен `defaultBlock`, применяет `SurfaceRule`.

Для cube-only это самый неприятный колоночный этап. Его можно переписать, но нельзя делать наивно только внутри текущего cube, иначе будут обрубленные поверхности на границах Y.

Нужен `ColumnSurfaceContext` или `VerticalBandSurfacePass`.

### 2.7. CARVERS

Ванила применяет cave/canyon carvers после surface. Она сканирует source chunks в радиусе 8 вокруг целевого chunk-а, потому что пещера, стартовавшая в соседнем chunk-е, может вырезать текущий.

Ключевые места:

```text
NoiseBasedChunkGenerator.applyCarvers(...)
CarvingContext
ConfiguredWorldCarver
WorldCarver
CaveWorldCarver
CanyonWorldCarver
CarvingMask
Aquifer
```

Для cube-only лучше не портировать carvers как есть. Современный vanilla terrain уже имеет много пещер в density router (`underground`, `spaghetti`, `noodle`, `pillars`). Carvers — дополнительный pass. Для нас лучше:

```text
MVP: caves через density functions, без отдельного chunk-radius carver.
Позже: cube-native 3D cave feature/reservation с bounding boxes.
```

### 2.8. FEATURES

Ванила prime-ит final heightmaps и запускает biome decoration:

```text
ChunkGenerator.applyBiomeDecoration(...)
FeatureSorter
PlacedFeature
ConfiguredFeature
PlacementModifiers
```

Features завязаны на:

```text
- biome generation settings;
- ordered decoration steps;
- random.setDecorationSeed(seed, originX, originZ);
- random.setFeatureSeed(decorationSeed, featureIndex, stepIndex);
- writable area chunk 16×height×16;
- соседние biomes/chunks.
```

Для cube-only features надо переписывать через planner:

```text
FeaturePlanId
FeatureBoundingBox3D
origin world coords
affected CubePos list
deterministic seed per feature cell
```

Особенно для деревьев, рудных жил, озёр, больших растений и структур.

### 2.9. LIGHT / SPAWN / FULL

Это уже lifecycle, а не shape generation.

Для нас:

```text
INITIALIZE_LIGHT/LIGHT → CubeLightEngine/CubeLightBootstrap
SPAWN → отдельная gameplay-система, не worldgen MVP
FULL → LevelCube ready + client snapshot/materialization
```

---

## 3. NoiseGeneratorSettings — vanilla worldgen preset

Ключевой record:

```text
NoiseGeneratorSettings(
  NoiseSettings noiseSettings,
  BlockState defaultBlock,
  BlockState defaultFluid,
  NoiseRouter noiseRouter,
  SurfaceRules.RuleSource surfaceRule,
  List<Climate.ParameterPoint> spawnTarget,
  int seaLevel,
  boolean disableMobGeneration,
  boolean aquifersEnabled,
  boolean oreVeinsEnabled,
  boolean useLegacyRandomSource
)
```

Для Overworld vanilla 26.2:

```text
noiseSettings = OVERWORLD_NOISE_SETTINGS
  minY = -64
  height = 384
  horizontal noise size = 1 → cellWidth = 4
  vertical noise size = 2 → cellHeight = 8

defaultBlock = STONE
defaultFluid = WATER
seaLevel = 63
aquifersEnabled = true
oreVeinsEnabled = true
legacyRandomSource = false
```

Для cube-only нам нужен аналог:

```java
public record CubicNoiseGeneratorSettings(
    int minBlockY,
    int maxBlockY,
    int seaLevel,
    int cellWidth,
    int cellHeight,
    BlockState defaultBlock,
    BlockState defaultFluid,
    CubicNoiseRouter router,
    CubicSurfaceRuleSource surfaceRules,
    boolean aquifersEnabled,
    boolean oreVeinsEnabled
) {}
```

Но важно: min/max у нас могут быть намного шире, например `-4096..4095`. Ванильные spline/slide параметры рассчитаны под `-64..319`. Их нельзя тупо растянуть без решения, как должен выглядеть сверхглубокий/сверхвысокий мир.

---

## 4. NoiseSettings и cell interpolation

Vanilla density не считается каждый раз с нуля на каждый блок.

### 4.1. Cell размеры

`NoiseSettings` хранит:

```text
minY
height
noiseSizeHorizontal
noiseSizeVertical
```

Методы:

```text
cellWidth = QuartPos.toBlock(noiseSizeHorizontal)
cellHeight = QuartPos.toBlock(noiseSizeVertical)
```

Так как `QuartPos.toBlock(n) = n * 4`, Overworld получает:

```text
cellWidth = 4
cellHeight = 8
```

Один cube 16×16×16 содержит:

```text
4 cells по X
2 cells по Y
4 cells по Z
```

Это отлично для cube generator. Можно сделать `CubicNoiseChunk` на cube или на маленький batch cubes.

### 4.2. Почему нельзя считать cube полностью независимо без border cache

Интерполяция требует значения на углах cell grid. Для блока внутри cube нужны cell corner samples на границе cube и чуть за ней.

Для cube 16³ при `cellWidth=4`, `cellHeight=8` нужно:

```text
X cell corners: 0..4   → 5 точек
Y cell corners: 0..2   → 3 точки
Z cell corners: 0..4   → 5 точек
```

Всего для одной interpolated density функции:

```text
5 × 3 × 5 = 75 corner samples
```

А не 4096 независимых expensive samples.

### 4.3. Что делает NoiseChunk

`NoiseChunk` хранит:

```text
cellCountXZ
cellCountY
cellNoiseMinY
firstCellX/firstCellZ
firstNoiseX/firstNoiseZ
interpolators
cellCaches
preliminarySurfaceLevelCache
aquifer
fullNoiseDensity
blockStateRule
```

Он поддерживает markers из `DensityFunctions`:

```text
Interpolated
FlatCache
Cache2D
CacheOnce
CacheAllInCell
BlendDensity
BeardifierMarker
```

Это не мелочь, а ключ к производительности. Если мы просто перепишем формулы без этих кэшей, генератор будет адски дорогой.

### 4.4. Cube-native аналог

Нужны классы:

```text
CubicNoiseCellSampler
CubicDensityContext
CubicDensityFunction
CubicNoiseRouter
CubicNoiseChunk или CubeNoiseFiller
CubicInterpolatedCache
CubicFlat2DCache
CubicCacheOnce
CubicCacheAllInCell
```

Минимальная версия может быть проще:

```text
- 2D climate caches per column/chunk/cube area;
- 3D density corner cache per cube;
- interpolation внутри cube;
- aquifer/ore выключить или упростить до второго этапа.
```

---

## 5. NoiseRouter — сердце terrain shape

Vanilla `NoiseRouter` — набор density functions, которые дают разные поля:

```text
barrierNoise
fluidLevelFloodednessNoise
fluidLevelSpreadNoise
lavaNoise
temperature
vegetation
continents
erosion
depth
ridges
preliminarySurfaceLevel
finalDensity
veinToggle
veinRidged
veinGap
```

### 5.1. Climate поля

```text
temperature
vegetation / humidity-ish
continents / continentalness
erosion
depth
ridges / weirdness
```

Они используются для biome selection.

### 5.2. Terrain поля

Для Overworld `NoiseRouterData` строит:

```text
continents = shiftedNoise2d(CONTINENTALNESS)
erosion = shiftedNoise2d(EROSION)
ridges = shiftedNoise2d(RIDGE)
ridges_folded = peaksAndValleys(ridges)
offset = spline(overworldOffset(continents, erosion, ridges_folded)) - 0.50375
factor = spline(overworldFactor(continents, erosion, weirdness, ridges_folded))
depth = yClampedGradient(-64, 320, 1.5, -1.5) + offset
jaggedness = spline(overworldJaggedness(...)) * jagged_noise
initialDensity = 4.0 * quarterNegative((depth + jaggedness) * factor)
slopedCheese = initialDensity + base_3d_noise
caves = min/choice(slopedCheese, entrances, underground caves)
fullNoise = min(postProcess(slideOverworld(caves)), noodle)
```

Человечески:

```text
continentalness решает океан/берег/сушу;
erosion решает сглаженность/горы/плато;
weirdness/ridges решает долины/пики/варианты;
depth задаёт вертикальную базовую форму;
jaggedness добавляет острые детали;
base_3d_noise ломает идеально гладкую поверхность;
caves/noodle/spaghetti вырезают подземные пустоты;
slide прижимает верх/низ мира, чтобы не было бесконечной каши.
```

### 5.3. Что украсть для Redline

Обязательно:

```text
- разделение на climate fields и terrain fields;
- continents/erosion/weirdness/ridges как крупная форма;
- spline-подход: не одна формула высоты, а таблица кривых;
- cell interpolation;
- preliminarySurfaceLevel;
- debug output: T/V/C/E/D/W/PV/PS.
```

Можно упростить на первом этапе:

```text
- не копировать все spaghetti/noodle caves сразу;
- не копировать ore veins сразу;
- не копировать blending старых chunks;
- не копировать amplified/large biomes сразу.
```

---

## 6. Density → BlockState

Ванильная базовая логика внутри `NoiseChunk`:

```text
fullNoiseDensity = finalDensity + beardifier
blockStateRule:
  1. aquifer.computeSubstance(context, density)
  2. ore veinifier, если включён
```

Если density > 0:

```text
solid/default block, обычно stone
```

Если density <= 0:

```text
air/water/lava выбирает aquifer/global fluid picker
```

В `NoiseBasedChunkGenerator.doFill`:

```text
state = noiseChunk.getInterpolatedState()
if state == null → defaultBlock
if state != AIR → setBlock + update heightmaps
```

То есть `null` из aquifer значит “оставить solid/default block”.

Cube-native базовая функция:

```java
BlockState sampleBaseBlock(int x, int y, int z) {
    double density = finalDensity.sample(x, y, z);
    BlockState substance = aquifer.computeSubstance(x, y, z, density);
    if (substance != null) return substance;
    return defaultBlock;
}
```

Но для air важно: aquifer может вернуть `AIR`, и тогда блок не solid.

---

## 7. Aquifer / вода / лава

Ванильный aquifer — не просто “залить всё ниже seaLevel”. Он решает подземные водоёмы и лаву.

Ключевые места:

```text
Aquifer
Aquifer.NoiseBasedAquifer
NoiseBasedChunkGenerator.createFluidPicker(...)
NoiseRouter barrier/fluid/lava noises
```

### 7.1. Global fluid picker

Для Overworld:

```text
lavaStatus: y < min(-54, seaLevel) → lava
seaStatus: below seaLevel → defaultFluid/water
emptyStatus: air если fluid generation disabled
```

В коде logic примерно:

```text
if y < min(-54, seaLevel): lava
else: sea water status
```

Но `FluidStatus.at(y)` возвращает fluid только если `y < fluidLevel`, иначе air.

### 7.2. NoiseBasedAquifer grid

Aquifer использует 3D grid:

```text
X/Z spacing = 16
Y spacing = 12
random point offset внутри grid cell
```

Для каждой позиции ищутся ближайшие aquifer centers, сравниваются fluid statuses и pressure/barrier noise.

Зачем:

```text
- подземные озёра не выглядят как идеальные плоские срезы;
- между разными fluid levels появляются каменные перегородки;
- вода/лава не всегда соединяются;
- fluid update можно поставить только где потенциальная граница.
```

### 7.3. Поверхность и aquifer связаны

Aquifer смотрит на `preliminarySurfaceLevel` в окрестности. Он не хочет делать подземные жидкости бессмысленно выше поверхности. Есть skip sampling above Y:

```text
skipSamplingAboveY = adjusted max preliminary surface + запас
```

Также он берёт несколько surface samples вокруг aquifer cell.

### 7.4. Что брать нам

Для M19.3/M20 cube-native terrain:

```text
1. Сначала сделать простой global fluid:
   - seaLevel water;
   - lava belt below configurable Y;
   - no flowing/source simulation during generation.

2. Затем добавить simplified aquifer:
   - 16×12×16 grid;
   - fluid level noise;
   - lava noise;
   - pressure/barrier optional.

3. Полную vanilla aquifer pressure можно перенести позже.
```

Важно для наших рек/озёр:

```text
Vanilla aquifer не решает surface rivers как физические river paths.
Surface river biome и valley shape идут через climate/noise.
Для красивых больших рек нам лучше делать свой RiverMask/RiverCarver поверх terrain shape, но вдохновляться vanilla valleys.
```

---

## 8. Biomes / Climate model

Ванила выбирает biome через `MultiNoiseBiomeSource`.

Ключевые классы:

```text
Climate
Climate.Sampler
Climate.ParameterPoint
Climate.ParameterList
Climate.RTree
MultiNoiseBiomeSource
OverworldBiomeBuilder
```

### 8.1. TargetPoint

Biome target состоит из 6 координат:

```text
temperature
humidity / vegetation
continentalness
erosion
depth
weirdness
```

`Climate.Sampler.sample(quartX, quartY, quartZ)` считает эти шесть значений через NoiseRouter.

`MultiNoiseBiomeSource.getNoiseBiome(...)` ищет ближайший `ParameterPoint` через R-tree.

### 8.2. Continentalness bands

В `OverworldBiomeBuilder` есть ключевые диапазоны:

```text
mushroomFieldsContinentalness = -1.2 .. -1.05
deepOceanContinentalness = -1.05 .. -0.455
oceanContinentalness = -0.455 .. -0.19
coastContinentalness = -0.19 .. -0.11
inlandContinentalness = -0.11 .. 0.55
nearInland = -0.11 .. 0.03
midInland = 0.03 .. 0.3
farInland = 0.3 .. 1.0
```

Это напрямую отвечает за океаны, берега и сушу.

### 8.3. Temperature/humidity bands

Temperature 5 bands:

```text
-1.0 .. -0.45
-0.45 .. -0.15
-0.15 .. 0.2
0.2 .. 0.55
0.55 .. 1.0
```

Humidity 5 bands:

```text
-1.0 .. -0.35
-0.35 .. -0.1
-0.1 .. 0.1
0.1 .. 0.3
0.3 .. 1.0
```

Они выбирают snowy/plains/forest/jungle/desert и варианты.

### 8.4. Erosion bands

Erosion 7 bands:

```text
-1.0 .. -0.78
-0.78 .. -0.375
-0.375 .. -0.2225
-0.2225 .. 0.05
0.05 .. 0.45
0.45 .. 0.55
0.55 .. 1.0
```

Низкая erosion чаще даёт горы/пики/склоны. Высокая erosion сглаживает и даёт болота/реки/плоскость.

### 8.5. Weirdness / peaks and valleys

`weirdness` режется на slices:

```text
mid/high/peaks/high/mid/low/valleys/low/mid/high/peaks/high/mid
```

Самая важная для нас часть:

```text
valleys = weirdness around -0.05 .. 0.05
```

Именно в `addValleys(...)` vanilla назначает `RIVER` / `FROZEN_RIVER` biomes для многих continentalness/erosion диапазонов.

### 8.6. Важный вывод по ванильным рекам

В новых версиях vanilla “река” — это в основном:

```text
- climate/weirdness valley band;
- biome assignment RIVER/FROZEN_RIVER;
- terrain shaping через ridges/peaksAndValleys/depth;
- surface/water rules;
```

Это не отдельный физический spline path с flow downhill. Поэтому ванильные реки не обязаны иметь реалистичный сток. Они выглядят нормально, потому что terrain field заранее делает valley forms и water/sea-level rules заполняют низкие места.

Для Redline:

```text
- можно украсть valley-band идею;
- можно использовать ridges/weirdness как river candidate mask;
- но для больших красивых рек лучше поверх vanilla-like climate добавить свой deterministic RiverNetwork mask.
```

---

## 9. SurfaceSystem / SurfaceRules

Surface pass делает мир визуально нормальным после density fill.

### 9.1. Как работает SurfaceSystem

На каждый X/Z столб:

```text
1. Берёт startingHeight = WORLD_SURFACE_WG + 1.
2. Вызывает biome at surface.
3. Для eroded badlands может достроить pillars.
4. Идёт сверху вниз до minY.
5. Следит за:
   - stoneDepthAbove;
   - stoneDepthBelow;
   - waterHeight;
   - surfaceDepth;
   - surfaceSecondary;
   - biome;
   - steep;
   - preliminary surface.
6. Если блок == defaultBlock, пробует surface rule.
7. Если rule вернула state, заменяет блок.
8. Для frozen ocean может достроить iceberg/ice.
```

### 9.2. Surface depth

`SurfaceSystem.getSurfaceDepth(x,z)`:

```text
surfaceNoise * 2.75 + 3.0 + random(0..0.25)
```

То есть толщина верхнего слоя не фиксированная 3 блока, а немного шумит.

### 9.3. SurfaceRules conditions

Основные условия:

```text
ON_FLOOR
UNDER_FLOOR
DEEP_UNDER_FLOOR
VERY_DEEP_UNDER_FLOOR
ON_CEILING
UNDER_CEILING
stoneDepthCheck(...)
yBlockCheck(...)
yStartCheck(...)
waterBlockCheck(...)
waterStartCheck(...)
isBiome(...)
noiseCondition2d(...)
noiseCondition3d(...)
verticalGradient(...)
steep()
hole()
abovePreliminarySurface()
temperature()
```

Этого нам достаточно, чтобы сделать свой DSL или Java-rule chain.

### 9.4. Steep condition

Ванила считает steep по разнице высот соседних блоков в heightmap. Если соседняя высота отличается на 4+ блока, участок крутой.

Это используется для stone/gravel/cliff materials.

Для cube-only нужен `ColumnHeightIndex` или быстрый `surfaceHeight(x,z)` sampler.

### 9.5. Above preliminary surface

Surface rules используют `preliminarySurfaceLevel`, интерполированную по 16×16 cells, плюс surfaceDepth - 8.

Это защищает rules от странных замен глубоко в пещерах/под землёй.

### 9.6. Cube-native проблема surface pass

Ванильный surface pass колонночный. Если делать его только в одном cube, он не узнает:

```text
- сколько stoneAbove уже над текущим Y;
- где waterHeight выше в соседнем cube;
- где ceiling/floor пещеры;
- какая настоящая top surface;
```

Поэтому для Redline нужно одно из двух:

#### Вариант 1 — Column prepass, но без блоков

Сначала считаем индексы:

```text
ColumnSurfaceIndex:
  worldSurfaceY[x,z]
  oceanFloorY[x,z]
  firstWaterY[x,z]
  preliminarySurfaceY[x,z]
  steepness[x,z]
  biomeAtSurface[x,z]
```

Потом каждый cube применяет surface rules, используя эти индексы.

Плюс: быстро и стабильно.  
Минус: для подземных cave surfaces нужно больше данных.

#### Вариант 2 — Vertical generation window

Для surface pass генерировать не один cube, а вертикальное окно вокруг поверхности:

```text
surfaceY ± N cubes
```

Плюс: ближе к vanilla.  
Минус: хуже cube-only lazy loading, больше генерации сразу.

Лучший компромисс:

```text
ColumnSurfaceIndex для обычной поверхности
Cube-local cave surface pass для пещер позже
```

---

## 10. Heightmaps

Ванила активно использует heightmaps:

```text
WORLD_SURFACE_WG
OCEAN_FLOOR_WG
MOTION_BLOCKING
MOTION_BLOCKING_NO_LEAVES
WORLD_SURFACE
OCEAN_FLOOR
```

В NOISE pass обновляются:

```text
OCEAN_FLOOR_WG
WORLD_SURFACE_WG
```

В FEATURES pass prime-ятся final heightmaps.

Для cube-only heightmaps не должны владеть блоками, но нужны как indexes:

```text
ColumnHeightIndex:
  WORLD_SURFACE
  OCEAN_FLOOR
  MOTION_BLOCKING
  LIGHT_BLOCKING
  SOLID_SURFACE
```

Обновление:

```text
- после generation cube;
- после setBlock/breakBlock;
- при загрузке старого cube;
- lazy rebuild для column если index stale.
```

Без этого нормально не сделать:

```text
surface rules
features
mob spawning
rain/snow
maps
skylight
river/ocean shore smoothing
```

---

## 11. Carvers vs density caves

В vanilla сейчас есть два слоя пещер:

```text
1. Density caves внутри NoiseRouter:
   - cheese caves;
   - spaghetti 2D/3D;
   - entrances;
   - noodle;
   - pillars.

2. WorldCarver pass:
   - CaveWorldCarver;
   - CanyonWorldCarver;
   - biome-configured carvers.
```

Для Redline я бы на первом этапе взял только density caves, потому что они идеально подходят cube-native sampling:

```text
sample density(x,y,z)
if density <= threshold → air/fluid
```

Carvers сложнее, потому что они стартуют в source chunk и режут соседние chunks. Cube-native аналог должен быть не chunk-radius, а 3D feature reservation:

```text
CaveCarverPlan:
  seed cell
  bounding box 3D
  affected cubes
  deterministic carve function
```

Рекомендация:

```text
M20 terrain: density caves only.
M21/M22 underground polish: cube-native cave/canyon planner.
```

---

## 12. Features / decoration

Ванила features запускает после surface/carvers.

Ключевая логика:

```text
1. Собрать possible biomes в radius 1 chunk.
2. FeatureSorter строит порядок features per step.
3. Для каждого decoration step:
   - сначала structures in that step;
   - потом placed features.
4. Random seed:
   decorationSeed = setDecorationSeed(worldSeed, originX, originZ)
   featureSeed = setFeatureSeed(decorationSeed, globalFeatureIndex, stepIndex)
5. PlacedFeature сам применяет placement modifiers.
```

### 12.1. Почему нельзя тащить как есть

Vanilla features ожидают:

```text
WorldGenLevel
ChunkAccess
WorldGenRegion
heightmaps
block access по соседним chunks
writable chunk area
```

Если дать им fake region, они снова будут думать колоннами.

### 12.2. Что украсть

```text
- ordered decoration steps;
- per-biome feature list idea;
- deterministic seed formula idea;
- placement modifiers concept;
- feature sorter idea для стабильного порядка;
- heightmap-based placement для деревьев/растений.
```

### 12.3. Cube-native design

```text
CubicFeaturePlanner:
  вход: ColumnPos / FeatureCellPos / CubePos
  выход: FeaturePlacementPlan[]

FeaturePlacementPlan:
  feature id
  origin BlockPos
  bounding Box3D
  affected CubePos set
  deterministic seed
  status dependencies
```

Для деревьев:

```text
1. Планируется из horizontal feature cell.
2. Bounding box заранее пересекает несколько cube.
3. Каждый cube при генерации FEATURES_READY применяет только свою часть дерева.
4. Никаких обрубков на границах cube.
```

Для руд:

```text
Можно делать cube-local или vein-planner.
Для техномода лучше vein-planner/geology layer, а не vanilla OreFeature как есть.
```

---

## 13. Structures

Vanilla structures:

```text
StructureSet
StructurePlacement
StructureStart
StructurePiece
Jigsaw/template system
Structure references
```

Главная проблема: placement горизонтальный и chunk-based, но структура сама 3D.

Для cube-only:

```text
MVP: отключить vanilla structures.
Следующий этап: свой StructureReservationPass.
Потом: adapter для vanilla template pieces.
```

Cube-native structure pipeline:

```text
STRUCTURE_RESERVED:
  выбрать стартовые structure cells;
  создать bounding boxes;
  записать references в ColumnStructureIndex / Region3D index.

STRUCTURE_PLACED:
  каждый cube применяет кусок структуры, если его CubeBounds пересекает StructureBoundingBox3D.
```

Важно:

```text
- деревни/jigsaw дороги требуют surface height sampler;
- mineshafts/ancient city требуют underground placement sampler;
- structures должны иметь stable ID, чтобы cube generation order не влиял на результат.
```

---

## 14. Random / determinism

Ванила использует разные random factories:

```text
RandomState
PositionalRandomFactory
WorldgenRandom
LegacyRandomSource
XoroshiroRandomSource
RandomSupport.generateUniqueSeed()
```

Принципы:

```text
- noise зависит от world seed;
- features получают seed от worldSeed + origin + featureIndex + stepIndex;
- aquifer centers получают positional random от grid coords;
- surface depth получает positional random от blockX,0,blockZ;
```

Для cube-only категорически важно:

```text
результат блока в (x,y,z) не должен зависеть от порядка генерации cube.
```

Правило:

```text
Все random должны быть positional или основаны на стабильном feature/structure plan id.
Нельзя использовать “текущий Random.next...” по мере обхода кубов, если обход может меняться.
```

---

## 15. Что именно переносить в Redline World Core

### 15.1. Перенести почти 1-в-1 по идее

```text
NoiseSettings:
  minY/height/cellWidth/cellHeight idea

NoiseRouter:
  именованные climate/terrain density channels

NoiseChunk:
  cell interpolation + caches

Climate.Sampler:
  T/H/C/E/D/W fields

MultiNoise biome selection:
  parameter points + nearest match

OverworldBiomeBuilder:
  bands для continentalness/erosion/temp/humidity/weirdness

SurfaceRules:
  rule chain DSL
  ON_FLOOR/UNDER_FLOOR/steep/water/y/biome/noise conditions

Aquifer:
  global fluid picker
  simplified 16×12×16 aquifer grid

Debug:
  T/V/C/E/D/W/PV/PS output
```

### 15.2. Переписать полностью под cube

```text
ChunkStatus → CubeStatusPipeline
ChunkAccess → LevelCube/ProtoCube
WorldGenRegion → CubicWorldGenRegion / CubeBlockView
Heightmap → ColumnHeightIndex
Structure references → StructureBoundingBox3D references
Feature placement → CubicFeaturePlanner
Carvers → CubicCarverPlanner или density caves
Lighting → CubeLightEngine
```

### 15.3. Не трогать пока

```text
vanilla jigsaw villages
full vanilla configured features
full biome registry/datapack compatibility
full aquifer pressure exact clone
old chunk blending
below zero retrogen
mob spawn during generation
```

---

## 16. Предлагаемый cube-native pipeline после анализа vanilla

Новый pipeline стоит сделать не как старый roadmap, а как практический rewrite:

```text
EMPTY
BIOMES
CLIMATE_READY
DENSITY_READY
TERRAIN_FILLED
SURFACE_INDEXED
SURFACE_APPLIED
AQUIFER_FILLED
CARVED
GEOLOGY_READY
FEATURES_PLANNED
FEATURES_APPLIED
LIGHT_READY
FULL
```

Но чтобы не плодить слишком много статусов, можно совместить:

```text
EMPTY
BIOMES
NOISE
SURFACE_CLASSIFIED
SURFACE_APPLIED
CARVED
GEOLOGY_READY
FEATURES_READY
LIGHT_READY
FULL
```

### 16.1. BIOMES

Для каждого cube:

```text
- посчитать 4×4×4 biome quart grid;
- сохранить palette в cube;
- использовать CubicClimateSampler.
```

### 16.2. NOISE

```text
- создать CubicNoiseChunk для CubePos;
- посчитать density cell corners;
- интерполировать 4096 блоков;
- поставить stone/air/water/lava;
- обновить local content flags;
- отправить dirty для ColumnHeightIndex.
```

### 16.3. SURFACE_CLASSIFIED

```text
- обновить/получить ColumnSurfaceIndex;
- worldSurface/oceanFloor/steep/preliminarySurface/shoreDistance;
- не хранить блоки в column.
```

### 16.4. SURFACE_APPLIED

```text
- применить surface rules к блокам cube;
- правила используют ColumnSurfaceIndex + local vertical scan;
- заменить stone на grass/dirt/sand/gravel/snow/etc.
```

### 16.5. CARVED

На первом этапе можно пропустить, если caves уже в density.

Позже:

```text
- CubicCarverPlan из 3D cells;
- carve только affected cubes;
- deterministic order.
```

### 16.6. GEOLOGY_READY

Для Redline Tech это будет важнее vanilla ore features:

```text
- слои камня;
- рудные жилы;
- пласты;
- нефть/газ;
- deep zones;
- geothermal/lava belts.
```

### 16.7. FEATURES_READY

```text
- plants/trees/rocks/snow patches;
- через planner;
- affected cubes;
- no border cuts.
```

---

## 17. Конкретно про океаны, берега, реки

### 17.1. Океаны в vanilla

Океан — это не отдельная “заливка карты”, а следствие:

```text
continentalness низкая → ocean biome
terrain density ниже seaLevel
water fills below seaLevel через global fluid/aquifer
surface rules выбирают sand/gravel/clay/etc около воды
```

Для нас:

```text
continentalness должен стать главным ocean mask.
```

Нынешний отдельный ocean mask можно оставить, но лучше привязать к vanilla-like continentalness.

### 17.2. Берега

Vanilla coast band:

```text
continentalness -0.19 .. -0.11
```

Плюс biome-specific beach/stony shore rules.

Для Redline shore smoothing:

```text
shoreDistance = distance/gradient from continentalness coast threshold
terrainHeight = blend(oceanFloorProfile, landProfile, smoothstep(shoreDistance))
```

Не делать резкий if ocean/land.

### 17.3. Реки

В vanilla реки сидят в valley weirdness band около 0:

```text
weirdness in -0.05 .. 0.05 → valleys
river biome selected in addValleys(...)
```

Но это не гарантирует красивые широкие русла. Для нас лучше:

```text
riverCandidate = abs(peaksAndValleys/weirdness valley field) small
riverStrength = smoothstep(width, center)
riverWaterLevel = local stable level, не физический downhill waterfall
riverCarve = parabolic/rounded channel profile
```

При этом взять ванильную идею:

```text
river biome = climate valley band + temperature decides frozen/unfrozen
```

### 17.4. Почему водопады появились у нас

Скорее всего, наша старая river logic пыталась делать реку как path/height-following feature с меняющимся water level. Vanilla избегает этого, потому что не симулирует настоящий поток: вода просто оказывается в низких valley местах около seaLevel.

Для стабильного M19.3:

```text
- river water level должен быть постоянным на участке/регионе или близким к seaLevel;
- если riverLevel сильно выше соседнего terrain, не делать waterfall, а ослаблять/прерывать river mask;
- carving должен опускать terrain к riverLevel, а не ставить воду поверх стенки.
```

---

## 18. Debug tools, которые надо добавить перед rewrite worldgen

Команды:

```text
/rwc worldgen sample <x> <y> <z>
/rwc worldgen column <x> <z>
/rwc worldgen climate <x> <y> <z>
/rwc worldgen river <x> <z>
/rwc worldgen surface <x> <z>
/rwc worldgen density <x> <y> <z>
/rwc worldgen cube-dump <cubeX> <cubeY> <cubeZ>
```

Вывод для блока:

```text
pos
cubePos
biome
temperature
humidity/vegetation
continentalness
erosion
depth
weirdness
peaksAndValleys
preliminarySurfaceLevel
finalDensity
baseState before surface
surfaceState after rules
waterLevel
aquiferStatus
riverMask
riverDistance
shoreDistance
oceanMask
heightSample
```

Overlay:

```text
- climate mode T/H/C/E/D/W/PV;
- river mask;
- ocean/shore mask;
- surface height;
- density slice;
- cube generation cost.
```

Без этого worldgen дальше будет чиниться на глаз, что плохо.

---

## 19. Практический план M19.3 / M20

### M19.3 — документация и sampler skeleton

```text
1. Добавить этот документ в docs/worldgen/vanilla-worldgen-analysis.md.
2. Добавить CubicClimateSample record.
3. Добавить CubicDensitySample record.
4. Добавить debug команду /rwc worldgen sample.
5. Добавить в overlay climate debug строку.
```

### M20.0 — CubicNoiseSettings / router skeleton

```text
CubicNoiseSettings
CubicNoiseRouter
CubicDensityFunction
CubicDensityContext
CubicRandomState
```

Цель: ещё не новый красивый мир, а framework.

### M20.1 — Climate fields

```text
shifted 2D noise:
  temperature
  humidity/vegetation
  continentalness
  erosion
  weirdness/ridges

Overworld-like bands:
  ocean/coast/inland
  valley/low/mid/high/peak
```

### M20.2 — Terrain density MVP

```text
cellWidth=4
cellHeight=8
finalDensity sample
stone/air/water by seaLevel
simple slide top/bottom
```

### M20.3 — Surface rules MVP

```text
grass/dirt/sand/gravel/stone/snow
surfaceDepth noise
steep condition
shore condition
```

### M20.4 — Vanilla-like river/shore rewrite

```text
continentalness-based ocean/coast
valley/weirdness-based river candidates
constant river water level
smooth carving profile
no waterfall mode
```

### M20.5 — Simple density caves

```text
cheese/noodle simplified masks
no carver radius yet
```

### M20.6 — Geology hook

```text
M17/M-tech future:
stone layers
ore veins
fluid/gas pockets
```

---

## 20. Минимальная Java-архитектура для Redline

Пакет:

```text
com.redline.worldcore.server.generation.vanillaish
```

Классы:

```text
CubicNoiseSettings
CubicRandomState
CubicNoiseRouter
CubicDensityFunction
CubicDensityContext
CubicNoiseCellSampler
CubicClimateSampler
CubicClimateSample
CubicTerrainSample
CubicSurfaceSystem
CubicSurfaceRules
CubicSurfaceContext
CubicAquifer
CubicBiomeResolver
CubicRiverSampler
CubicShoreSampler
```

Интеграция:

```text
BasicCubicGenerator → временно оставить как fallback.
VanillaishCubicGenerator → новый generator.
CubicWorldgenPipeline → выбирать generator через config.
```

Config:

```toml
[worldgen]
generator = "vanillaish"
cellWidth = 4
cellHeight = 8
seaLevel = 63
enableAquifers = false
enableOreVeins = false
enableDensityCaves = false
enableRiverSampler = true
enableShoreSmoothing = true
```

---

## 21. Риски при переносе

### 21.1. Слишком точный clone ванилы

Если пытаться точно повторить vanilla bit-by-bit, мы утонем в registries/datapack/mapping/feature compatibility.

Наша цель не bit-perfect vanilla, а:

```text
vanilla-quality terrain shape
cube-only backend
полный контроль под Redline Tech
```

### 21.2. Surface pass может снова стать колонной

Surface logic самая опасная. Нельзя делать:

```text
for every cube generate full column blocks
```

Можно делать:

```text
for every column compute indexes only
for every cube apply blocks locally using indexes
```

### 21.3. Features режут cube borders

Любая feature, которая выше/шире 16 блоков, должна быть planned заранее.

### 21.4. Random order bugs

Если генерация cube A до cube B даёт другой результат, чем B до A, архитектура сломана.

Все random должны быть positional.

### 21.5. Performance

Самые дорогие места:

```text
density functions without caches
surface vertical scans
feature planning
river distance/path search
aquifer pressure
```

Сразу нужны counters:

```text
samples per cube
density time
surface time
river time
feature time
allocations estimate
```

---

## 22. Что делать с текущим M15/M16 кодом

Текущий код не надо выбрасывать сразу. Его стоит использовать как fallback/reference для сравнения.

Но новый путь должен быть отдельным:

```text
BasicCubicGenerator         старый/текущий генератор
VanillaishCubicGenerator    новый cube-native generator
```

Перенос по шагам:

```text
1. Поднять VanillaishCubicGenerator рядом.
2. Сделать команду генерации/sampling без включения по умолчанию.
3. Сравнить shape, perf, стабильность.
4. Потом переключить cubic_test на новый generator.
5. Старый оставить config fallback до стабилизации.
```

---

## 23. Итоговая рекомендация

Дальше не чинить реки/берега точечно на старой логике.

Правильный следующий технический шаг:

```text
M19.3 — vanillaish worldgen foundation
```

Состав:

```text
1. CubicClimateSampler
2. CubicNoiseRouter skeleton
3. CubicNoiseCellSampler with interpolation/caches
4. CubicTerrainSample debug
5. CubicRiver/Shore sampler draft
6. /rwc worldgen sample
7. overlay/debug counters
```

После этого уже делать новый terrain fill.

Главная идея:

```text
Ванильная математика качества мира — да.
Ванильная колонночная архитектура — нет.
```

---

## 24. Appendix A — vanilla classes to keep open while coding

```text
net.minecraft.world.level.chunk.ChunkGenerator
net.minecraft.world.level.chunk.status.ChunkStatus
net.minecraft.world.level.chunk.status.ChunkStatusTasks
net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
net.minecraft.world.level.levelgen.NoiseGeneratorSettings
net.minecraft.world.level.levelgen.NoiseSettings
net.minecraft.world.level.levelgen.NoiseChunk
net.minecraft.world.level.levelgen.NoiseRouter
net.minecraft.world.level.levelgen.NoiseRouterData
net.minecraft.world.level.levelgen.DensityFunction
net.minecraft.world.level.levelgen.DensityFunctions
net.minecraft.world.level.levelgen.Aquifer
net.minecraft.world.level.levelgen.OreVeinifier
net.minecraft.world.level.levelgen.SurfaceSystem
net.minecraft.world.level.levelgen.SurfaceRules
net.minecraft.data.worldgen.SurfaceRuleData
net.minecraft.data.worldgen.TerrainProvider
net.minecraft.world.level.biome.Climate
net.minecraft.world.level.biome.BiomeSource
net.minecraft.world.level.biome.MultiNoiseBiomeSource
net.minecraft.world.level.biome.OverworldBiomeBuilder
net.minecraft.world.level.levelgen.carver.WorldCarver
net.minecraft.world.level.levelgen.feature.Feature
net.minecraft.world.level.levelgen.placement.PlacedFeature
net.minecraft.world.level.levelgen.structure.Structure
net.minecraft.world.level.levelgen.structure.StructureStart
net.minecraft.world.level.levelgen.structure.placement.StructurePlacement
```

---

## 25. Appendix B — Redline classes to connect with

```text
com.redline.worldcore.api.pos.CubePos
com.redline.worldcore.api.cube.CubeAccess
com.redline.worldcore.api.cube.ProtoCube
com.redline.worldcore.api.cube.LevelCube
com.redline.worldcore.api.cube.CubeStatus
com.redline.worldcore.api.generation.CubeGenerator
com.redline.worldcore.api.generation.CubeGenerationContext
com.redline.worldcore.server.generation.BasicCubicGenerator
com.redline.worldcore.server.generation.CubicWorldgenPipeline
com.redline.worldcore.server.generation.M15Noise
com.redline.worldcore.server.generation.M15TerrainModel
com.redline.worldcore.server.generation.M16WaterSampler
com.redline.worldcore.server.generation.M16RiverProfile
com.redline.worldcore.server.cube.ServerCubeCache
com.redline.worldcore.server.cube.WorldCoreCubeLoading
com.redline.worldcore.server.debug.RedlineWorldCoreCommands
```

---

## 26. Appendix C — минимальные acceptance tests для нового generator-а

### Determinism

```text
Generate cube A then B.
Clear storage.
Generate cube B then A.
All overlapping/global samples identical.
```

### Cube border continuity

```text
Generate neighboring cubes.
Compare density/surface/biome on shared faces.
No seams.
```

### Surface continuity

```text
Surface crosses cubeY boundary.
Grass/dirt depth remains continuous.
No hard layer cut at Y multiple of 16.
```

### Shoreline

```text
Ocean to land transition has no 2+ block vertical wall for normal coast cases.
Stony shore/mountain coast can be steep intentionally.
```

### River

```text
River has stable water level.
River does not create waterfall unless explicit waterfall feature enabled.
River carving lowers terrain to water, not water floating on wall.
```

### Client sync

```text
Generated cube materializes without block update.
Solid generated cube has both collision and visual snapshot.
Breaking generated block removes visual immediately.
```

---

## 27. Bottom line

Мы остаёмся cube-only и не тащим vanilla chunk ownership назад.

Но качество мира надо строить на ванильных принципах:

```text
climate fields
continentalness/erosion/weirdness
spline terrain shaping
density cell interpolation
surface rule DSL
aquifer-like fluid decisions
feature/structure planning with deterministic seeds
```

Это даст нормальный Minecraft-like мир, но с нашей архитектурой, нашей высотой, нашей геологией, нашими жидкостями/газами и полной властью над Redline Tech worldgen.
