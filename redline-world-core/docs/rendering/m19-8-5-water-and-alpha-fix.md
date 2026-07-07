# M19.8.5 — water render fallback and baked quad alpha

## Problem

M19.8.4 made fluids visible by always drawing a translucent fallback cube over vanilla fluid output.  That fallback used
`0..1` UV coordinates on the block atlas render type, so water sampled the whole texture atlas and appeared as a cursed
blue mosaic.

Some baked model quads can also arrive with RGB-style vertex colors.  Treating those as ARGB made alpha equal to zero and
could hide thin/cutout models.

## Fix

- Fluid fallback is now used only when `FluidRenderer` emitted no quads at all.
- Baked quad colors are forced to visible alpha when the alpha byte is missing.
- Fallback geometry pins UV to a single atlas point instead of sampling the entire atlas.

This keeps the renderer cube-native while using vanilla fluid/model geometry whenever it is available.
