# M19.8.3 — native block state placement and fluid render fix

## Problem

M19.8.2 moved outside-shell rendering to vanilla-backed baked models, but block interaction was still writing very rough
states into cube storage:

- block items used `defaultBlockState()` instead of vanilla placement state;
- the client did not send the exact hit vector, so stair/slab/door placement had too little context;
- fences, panes, walls and stairs were not refreshed after neighbor changes;
- doors only stored one half and right-click/open mutated vanilla shell state, not cube storage;
- fluid mesh colors could arrive without an alpha byte, making water effectively invisible.

## Fix

The outside-shell interaction bridge now treats placement as a cube-native version of vanilla placement:

1. `CubicExtremeInteractionPayload` serializes:
   - action;
   - X/Y/Z as full ints;
   - clicked face;
   - hand;
   - exact hit location as three doubles.
2. Server-side placement builds a real `BlockPlaceContext`.
3. Blocks use `block.getStateForPlacement(context)` instead of `defaultBlockState()`.
4. The resulting state is passed through `Block.updateFromNeighbourShapes(...)` using cube-backed `Level#getBlockState` reads.
5. Door-like double-block states write both lower and upper halves to cube storage.
6. Break removes linked double-block counterpart when present.
7. Right-click outside shell has a native USE action for simple OPEN/POWERED state toggles.
8. Neighbor-dependent blocks around the edited position are refreshed and synced back to clients.
9. Fluid vertex colors now force alpha to 255 when vanilla gives RGB-only tint.

## Still not final

This is still not the final gameplay layer. The patch intentionally does not try to solve every vanilla block entity or
all scheduled block tick semantics. It should, however, make the common visual/state problems testable:

- stairs should face the correct direction and can form corner shapes after neighbor refresh;
- fences/panes/walls should connect to neighbors after placement/break;
- doors should have two halves and toggle OPEN in cube storage;
- water/lava should be visible if FluidRenderer emits RGB-only tint;
- block state changes should rebuild native cube meshes through the existing snapshot/delta path.
