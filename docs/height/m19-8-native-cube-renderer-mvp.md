# M19.8 — Native cube renderer MVP

## Зачем

После M19.4–M19.7 cube backend уже умеет хранить, синхронизировать, ломать, ставить, коллизить и минимально тикать блоки вне vanilla height shell. Главный блокер тестирования extreme-height мира стал визуальным: блоки на `Y=9000` и `Y=-12000` существовали только как collision/outline, но не как видимый terrain.

M19.8 вводит первый client-side renderer, который читает `ClientCubeSectionStore` напрямую и не пишет блоки во временный vanilla `ClientLevel`.

## Что сделано

### M19.8.1 Client native render section storage

`ClientCubeNativeMeshBridge` теперь хранит реальные render-mesh entries:

```text
CubePos -> NativeSectionMesh
```

Mesh хранит:

```text
snapshot hash
solid block count
solid face list
translucent face list
```

### M19.8.2 Dirty/rebuild queue

Старый счётчик mesh-прототипа заменён на очередь rebuild-а:

```text
MESH_QUEUE
TASKS
MESHES
```

Snapshot/delta/unload уже вызывали `invalidate/forget`, теперь это реально сбрасывает mesh и ставит cube на rebuild.

### M19.8.3 Solid block mesh generation

Для каждого non-air блока вне vanilla shell строятся видимые грани. Внутренние грани между opaque блоками скрываются.

На этом этапе geometry intentionally lightweight: map-color cuboids, а не полный vanilla `BakedModel` pipeline. Это даёт видимый terrain и стабильный cube-native render lifecycle, который позже можно заменить на baked models без переписывания storage/dirty/submission слоя.

### M19.8.4 Basic translucent pass

Вода, лава, glass-like и non-occluding states идут в отдельный translucent face list и отправляются отдельным custom geometry submit.

### M19.8.5 Distance culling

Renderer работает только вокруг player cube:

```text
horizontal radius = 8 cubes
vertical radius = 4 cubes
```

Inside vanilla shell renderer не вмешивается: там пока работает обычный vanilla render/mirror path.

### M19.8.6 Rebuild on block change / delta / snapshot

Full snapshot и delta уже проходят через `ClientCubeSectionStore`; при них вызывается invalidate, поэтому native mesh пересобирается после:

```text
break
place
fluid spread
server repair snapshot
unload/reload
```

### M19.8.7 Debug and commands

Добавлена client command ветка:

```text
/rwc_client native_renderer status
/rwc_client native_renderer reset
/rwc_client native_renderer enable
/rwc_client native_renderer disable
```

Overlay теперь показывает:

```text
nativeRenderer ready/meshes/queue/rendered/faces/built
nativeRenderer budget/time/invalid/hashHits/submits/enabled
```

## Ограничения M19.8

Это не финальный renderer:

```text
1. Пока не используются vanilla baked block models.
2. Stairs/slabs/fences/plants рендерятся как cuboid approximation или цветные грани.
3. Текстур нет, только map-color shading.
4. Lighting пока упрощённый цветовой shading по стороне.
5. Cross-cube neighbor occlusion пока conservative: на границах cube грани могут быть видны до следующего оптимизационного патча.
```

Но главное уже есть:

```text
1. Outside-shell блоки видны.
2. Renderer не зависит от vanilla chunk sections.
3. Renderer rebuild-ится от cube snapshots/deltas.
4. Можно тестить extreme-height gameplay глазами, а не только outline/collision.
```

## Что тестить

```text
/rwc cubic_test teleport_extreme high
/rwc_client native_renderer status
сломать блок рукой
поставить блок рукой
поставить воду/лаву ведром
/rwc_client native_renderer status

/rwc cubic_test teleport_extreme low
то же самое
```

Ожидаемо:

```text
платформа видна
break/place меняют видимый mesh после snapshot/delta
вода/лава видны как цветные translucent кубы/грани
низ не убивает void-ом
```

## Следующий слой

M19.9 должен заменить cuboid/map-color mesh на vanilla-like block model extraction:

```text
BlockStateModel / BlockStateModelPart / baked quad collection
texture atlas UV
proper render layers
better liquids
cross-cube neighbor occlusion
breaking overlay/progress polish
```
