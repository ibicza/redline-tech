# M19.4 — True Cubic Vertical Range

## Цель

`redline-world-core` больше не считает высоту vanilla `DimensionType` настоящей высотой мира. Vanilla height остаётся только временным compatibility shell-ом для тех мест, где Minecraft 26.2 ещё требует `ClientLevel`, `ChunkAccess`, render-section или vanilla block update.

Настоящий мир теперь задаётся внутренним cube-only диапазоном:

```text
internal cubic range:
  minCubeY = -1024
  maxCubeY = 1023

internal block Y:
  -16384 .. 16383

height:
  32768 blocks
  2048 cube layers
  128 Region3D layers по Y
```

Временный vanilla shell остаётся легальным для Minecraft:

```text
vanilla shell:
  block Y -2032 .. 2031
```

Это значит: кубы на `Y=9000` и `Y=-12000` уже являются валидными для storage/worldgen/tickets/query, даже если их нельзя материализовать в vanilla section.

## Почему так

Нельзя пытаться просто поставить в `dimension_type/cubic_test.json` высоту `-16384..16383`. Это снова заставит Minecraft мыслить огромными vanilla-колоннами, а наша цель обратная: `CubePos` должен быть владельцем мира.

Правильная модель:

```text
CubicVerticalRange:
  источник истины для storage/generation/tickets/query/collision/native sync

Vanilla shell:
  временный адаптер для vanilla-client/render/materialization
```

## Новые правила для кода

Внутри `redline-world-core` нельзя использовать как world limit:

```java
level.getMinY()
level.getHeight()
level.getMaxBuildHeight()
level.isOutsideBuildHeight(...)
```

Исключение: код, который прямо пишет во временный vanilla shell. Такой код обязан явно называться shell/materialize/render-mirror и проверять `CubicDimensionSettings.isCubeInsideVanillaShell(...)`.

Для cube backend использовать только:

```java
CubicDimensionSettings.containsBlockY(y)
CubicDimensionSettings.containsCubeY(cubeY)
CubicDimensionSettings.minBlockY()
CubicDimensionSettings.maxBlockY()
CubicDimensionSettings.blockHeight()
```

## Что вошло в M19.4

- Добавлен `CubicVerticalRange`.
- `CubicDimensionSettings.defaults()` расширен до `-16384..16383`.
- Добавлен явный vanilla shell range `-2032..2031`.
- Server/client cube `BlockGetter#getMinY/getHeight` теперь отдаёт internal cubic range, а не vanilla level range.
- Server/client cube query проверяет internal height, а не vanilla build height.
- Client sync больше не требует vanilla shell для player cube: вне shell-а он грузит holder и отправляет native section snapshot.
- Eager client loading больше не отбрасывает cube только потому, что он вне vanilla build height.
- Vanilla materialization ограничена shell-ом и теперь явно сообщает, что cube существует в cube-only storage.
- Добавлены debug-команды высоты и extreme persistence probe.

## Команды проверки

```text
/rwc cubic_test height
```

Показывает:

```text
true cubic internal range
vanilla shell range
фактический DimensionType range
попадают ли Y=9000 и Y=-12000 внутрь internal/shell
```

```text
/rwc cubic_test extreme_height_probe
```

Пишет два блока через cube backend:

```text
Y=9000    minecraft:gold_block
Y=-12000  minecraft:diamond_block
```

Затем принудительно сохраняет cube, выгружает holder, читает обратно из Region3D и сверяет state.

Ожидаемый смысл результата:

```text
internal storage OK
Region3D roundtrip OK
vanilla shell not involved
```

## Важное ограничение

После M19.4 storage/query/native sync уже не ограничены vanilla height. Но полноценная удобная игра на `Y=9000/-12000` ещё требует следующих этапов:

```text
native cube renderer вне vanilla shell
interaction/raycast/place/break вне vanilla shell
entity movement/collision без vanilla build-height assumptions
lighting finite top по internal maxY
```

M19.4 фиксирует фундамент: vanilla height больше не источник истины.
