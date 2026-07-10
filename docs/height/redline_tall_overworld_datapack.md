# Redline Tall Overworld Datapack

Standalone datapack path in this archive:

```text
/datapacks/redline_tall_overworld
/datapacks/redline_tall_overworld.zip
```

It overrides vanilla `minecraft:overworld` dimension type with the maximum height accepted by Minecraft 26.2 vanilla registry codecs:

```text
min_y = -2032
height = 4064
logical_height = 4064
valid block range = -2032..2031
```

Install options:

1. New world: add `redline_tall_overworld.zip` in the datapack selection screen before creating the world.
2. Existing world: copy `redline_tall_overworld.zip` into `saves/<world>/datapacks/`, then run `/reload`.

Best practice: create a new test world. Changing dimension height on an existing world can produce odd terrain borders and old chunks will not regenerate.

This datapack only changes the allowed build height. It does not rewrite Overworld noise settings, so terrain generation may still mostly use vanilla terrain ranges unless separate noise settings are added later.
