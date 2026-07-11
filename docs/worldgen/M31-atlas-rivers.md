# M31 — Atlas rivers

## Результат

Речной слой добавлен отдельно от океанов и озёр:

```text
HydroRIVERS SHP + DBF
        +
Copernicus GLO-30
        +
ESA WorldCover water class 80
        ↓
уточнённая речная сеть
        ↓
compressed region-cache.rriver
        ↓
пространственный индекс → русло → берега → source-вода → river biome
```

HydroRIVERS задаёт глобальный граф и атрибуты реки. GLO-30 ищет тальвег и высотный профиль. WorldCover притягивает крупные русла к фактической постоянной воде и уточняет ширину.

Исходные GIS-файлы не требуются в готовом runtime pack. После первой успешной загрузки создаётся:

```text
config/redline-atlas-worldgen/rivers/region-cache.rriver
```

Если рядом остаются исходные SHP/DBF, cache проверяется по fingerprint источников, проекции и параметров уточнения. Изменившийся источник автоматически пересобирает cache. Если SHP/DBF удалить, самостоятельный `.rriver` продолжает загружаться.

## Реализованный пайплайн

1. Потоковый reader читает ESRI PolyLine/PolyLineZ/PolyLineM без GeoTools.
2. Из DBF используются `HYRIV_ID`, `NEXT_DOWN`, `MAIN_RIV`, `ORD_STRA`, `DIS_AV_CMS`, `CATCH_SKM`.
3. Сегменты фильтруются по `sourceBounds` и минимальному Strahler order до загрузки геометрии в игровой индекс.
4. Исходная линия сгущается через `refinePointSpacingBlocks`.
5. Для каждой точки строится поперечный профиль долины.
6. Кандидаты оцениваются по высоте GLO-30, расстоянию от HydroRIVERS, движению вверх и совпадению с WorldCover water.
7. Геометрия разворачивается, если DEM явно показывает обратное направление.
8. Ширина берётся как максимум из WorldCover-маски и функции расхода `factor × sqrt(DIS_AV_CMS)`.
9. Высоты воды проходят isotonic/PAVA fit: вниз по течению вода не поднимается.
10. `NEXT_DOWN` стыкует уровень притока и следующего downstream-сегмента.
11. Профиль привязывается вниз к целым Y-уровням. Получаются длинные спокойные участки и короткие пороги при накоплении перепада.
12. Внутри русла вырезается поперечный профиль с плоской центральной частью и плавными подводными краями.
13. Весь объём сразу заполняется полными vanilla water source blocks без ожидания fluid ticks.
14. Полоса берега сглаживает небольшие генераторные губы и поднимается только в пределах safety cap, если иначе вода утечёт.
15. Дно выбирает sand/gravel/clay по глубине, расходу и порядку реки.
16. River/frozen_river biome выбирается отдельным water context.

## Физика воды

Mixin действует только:

- для vanilla water;
- в разрешённом atlas dimension;
- внутри проиндексированного речного коридора;
- когда текущая вода стоит на другой воде.

В таком случае горизонтальное растекание разрешено, если под целевой колонкой есть вода или твёрдая опора. Вываливание верхнего слоя наружу над воздухом запрещено. Вода, стоящая непосредственно на каменном дне, сохраняет vanilla-поведение и может образовать нормальный порог или водопад.

## Основная конфигурация

Файл:

```text
run/config/redline_atlas_worldgen-server.toml
```

Тестовый preset:

```toml
[river_water]
tileRoot = "config/redline-atlas-worldgen/rivers"
enabled = true
sourceBounds = "44.0,47.0,5.0,9.0"
minStrahlerOrder = 3
maxSegments = 200000
writeCookedCache = true
preferCookedCache = true
refineEnabled = true
refineRadiusBlocks = 160
refineStepBlocks = 8
refinePointSpacingBlocks = 48
minWidthBlocks = 3
maxWidthBlocks = 384
minDepthBlocks = 2
maxDepthBlocks = 18
bankWidthBlocks = 6
snapProfileToBlock = true
flowPhysicsEnabled = true
```

Для первого запуска не ставить `minStrahlerOrder=1`: мелких сегментов намного больше, а качество основных рек удобнее проверить на orders 3+.

## Команды диагностики

```text
/rla reload
/rla status
/rla river_segments
/rla river_segments 32
/rla river_sample
/rla nearest_river
/rla nearest_river 8192
/rla finish_here 0
/rla finish_here 2
/rla biome_sample
/rla profile
```

`/rla nearest_river` использует spatial index, а не перебирает каждый блок в радиусе. Команда сразу печатает безопасную `/tp`, точные lat/lon, river ID, order, ширину и уровень воды.

## Тестовая область: Альпы и долина Роны

Проекция уже имеет подходящие defaults:

```toml
[mapping]
originLatitude = 45.5
originLongitude = 6.5
degreesPerBlockLatitude = 0.000053898917
degreesPerBlockLongitude = 0.000053894708
horizontalMetersPerBlock = 6.0
verticalMetersPerBlock = 6.0
seaLevelY = 0
```

### 1. HydroRIVERS Europe

Официальная страница:

```text
https://www.hydrosheds.org/products/hydrorivers
```

Прямая загрузка:

```powershell
$riverRoot = "run/config/redline-atlas-worldgen/rivers"
New-Item -ItemType Directory -Force $riverRoot | Out-Null
Invoke-WebRequest `
  "https://data.hydrosheds.org/file/HydroRIVERS/HydroRIVERS_v10_eu_shp.zip" `
  -OutFile "$riverRoot/HydroRIVERS_v10_eu_shp.zip"
Expand-Archive "$riverRoot/HydroRIVERS_v10_eu_shp.zip" -DestinationPath $riverRoot -Force
```

Bash/curl:

```bash
mkdir -p run/config/redline-atlas-worldgen/rivers
curl -L "https://data.hydrosheds.org/file/HydroRIVERS/HydroRIVERS_v10_eu_shp.zip" \
  -o run/config/redline-atlas-worldgen/rivers/HydroRIVERS_v10_eu_shp.zip
unzip -o run/config/redline-atlas-worldgen/rivers/HydroRIVERS_v10_eu_shp.zip \
  -d run/config/redline-atlas-worldgen/rivers
```

В папке обязательно должны оказаться одноимённые `.shp` и `.dbf`. `.shx` reader не требуется, но его можно оставить.

### 2. GLO-30: 12 одноградусных плиток

Источник:

```text
s3://copernicus-dem-30m/
https://registry.opendata.aws/copernicus-dem/
```

PowerShell + AWS CLI:

```powershell
$heightRoot = "run/config/redline-atlas-worldgen/heightmaps"
New-Item -ItemType Directory -Force $heightRoot | Out-Null

foreach ($lat in 44..46) {
    foreach ($lon in 5..8) {
        $ns = "N{0:D2}_00" -f $lat
        $ew = "E{0:D3}_00" -f $lon
        $id = "Copernicus_DSM_COG_10_${ns}_${ew}_DEM"
        aws s3 cp --no-sign-request `
          "s3://copernicus-dem-30m/$id/$id.tif" `
          "$heightRoot/$id.tif"
    }
}
```

Bash + AWS CLI:

```bash
mkdir -p run/config/redline-atlas-worldgen/heightmaps
for lat in 44 45 46; do
  for lon in 005 006 007 008; do
    id="Copernicus_DSM_COG_10_N${lat}_00_E${lon}_00_DEM"
    aws s3 cp --no-sign-request \
      "s3://copernicus-dem-30m/${id}/${id}.tif" \
      "run/config/redline-atlas-worldgen/heightmaps/${id}.tif"
  done
done
```

### 3. WorldCover: четыре плитки 3×3 градуса

Источник:

```text
s3://esa-worldcover/v200/2021/map/
https://esa-worldcover.org/en/data-access
```

PowerShell:

```powershell
$landcoverRoot = "run/config/redline-atlas-worldgen/landcover"
New-Item -ItemType Directory -Force $landcoverRoot | Out-Null
$worldCoverTiles = @("N42E003", "N42E006", "N45E003", "N45E006")
foreach ($tile in $worldCoverTiles) {
    $name = "ESA_WorldCover_10m_2021_v200_${tile}_Map.tif"
    aws s3 cp --no-sign-request `
      "s3://esa-worldcover/v200/2021/map/$name" `
      "$landcoverRoot/$name"
}
```

Bash:

```bash
mkdir -p run/config/redline-atlas-worldgen/landcover
for tile in N42E003 N42E006 N45E003 N45E006; do
  name="ESA_WorldCover_10m_2021_v200_${tile}_Map.tif"
  aws s3 cp --no-sign-request \
    "s3://esa-worldcover/v200/2021/map/${name}" \
    "run/config/redline-atlas-worldgen/landcover/${name}"
done
```

GEBCO для этих четырёх внутриконтинентальных тестов не обязателен. Он понадобится для проверки устьев.

## Координаты проверки

Сначала телепортироваться примерно в район реки, затем выполнить `/rla nearest_river 2048` и использовать напечатанную командой точную `/tp`.

| Река и место | Географические координаты | Minecraft X/Z при default mapping | Черновая команда |
|---|---:|---:|---|
| Isère, Albertville | 45.6745, 6.3900 | -2041, -3238 | `/tp @p -2041 300 -3238` |
| Isère/Drac, Grenoble | 45.1908, 5.7181 | -14508, 5737 | `/tp @p -14508 300 5737` |
| Arve, Chamonix | 45.9237, 6.8694 | 6854, -7861 | `/tp @p 6854 300 -7861` |
| Durance, Sisteron | 44.1969, 5.9436 | -10324, 24177 | `/tp @p -10324 300 24177` |

Минимальный цикл теста:

```text
/rla reload
/rla river_segments 16
/tp @p -2041 300 -3238
/rla nearest_river 2048
/rla river_sample
/rla finish_here 2
/rla biome_sample
/rla profile
```

Новый мир предпочтительнее: автоматический surface polish по умолчанию применяется только к новым чанкам. Для уже существующих тестовых чанков используется `/rla finish_here`.

## Что проверять визуально

1. Центр русла лежит в долине, а не на склоне.
2. На равнине нет подъёмов воды вниз по течению.
3. Перепады Y образуют короткий порог, а не вертикальную водяную стену по всей ширине.
4. Приток и основная река соединены на одном уровне.
5. WorldCover расширяет крупную реку, но не превращает пересечение озера в канал максимальной ширины.
6. Центральная часть дна глубже, край плавно выходит к берегу.
7. Верхняя вода не вываливается с края глубокого русла над воздухом.
8. Вода на твёрдом дне всё ещё может образовать обычный водопад.
9. После повторного `/rla reload` лог сообщает загрузку cooked `.rriver`, а не повторную обработку SHP.

## Ограничения этого этапа

- Прямой SHP reader предназначен для региональной конвертации и тестов. Глобальный raw HydroRIVERS нельзя грузить с пустым `sourceBounds` в обычный игровой JVM.
- WorldCover содержит только постоянную воду. Сезонные и сухие русла пока задаются HydroRIVERS как обычные малые реки.
- Дельты и очень широкие многорукавные системы позже должны получать отдельные 10m water-mask overlays; одна центральная линия не может полностью описать их топологию.
- HydroLAKES/GLOBathy остаются отдельным слоем и имеют приоритет над речной линией внутри озёр.

## M31.1 — привязка реки к окружающему рельефу

Исправлена ситуация, когда приготовленный уровень реки оказывался выше одной из сторон долины и создавал акведук/висящую водяную плиту.

Новая схема состоит из двух ограничителей:

```text
1. converter guard:
   для каждой профильной точки GLO-30 снимается несколько проб с левого и правого берега;
   candidateWater = min(centerDem, leftSupport, rightSupport);

2. generated-terrain guard:
   перед вырезанием русла те же стороны проверяются уже по реальным блокам загруженных чанков;
   effectiveWaterY = min(cookedWaterY, lowerGeneratedBankY).
```

Профиль больше не использует усредняющую isotonic-регрессию, которая могла поднять низкую точку. Все исправления теперь lower-only:

```text
water[i] = min(candidate[i], water[i - 1])
```

Слишком резкий перепад также сглаживается только опусканием верхней точки. Нижняя долина никогда не поднимается ради монотонности.

Речные берега больше не достраиваются вверх до ошибочного уровня воды. `riverBankMaxRaiseBlocks` оставлен в TOML только для совместимости со старыми конфигами и кодом не используется.

После обновления `/rla reload` автоматически отвергнет старый `region-cache.rriver`, потому что fingerprint профиля изменён на `river-profile-bank-support-v4`. Удалять кэш вручную не требуется.

Для чистой визуальной проверки нужен новый мир или новые чанки. Старые искусственные насыпи, уже записанные предыдущей версией в сохранение, автоматически не восстанавливают исходный рельеф.
