# M32 - Water surface support flow

## Problem

Vanilla water treats another water block below as a downward escape route while evaluating horizontal spread. In the tall atlas world this prevented intended lateral flow across existing water surfaces. Letting every water-supported block spread without a boundary check would, however, allow each new layer to support another and produce unbounded flooding over open air.

## Implementation

The existing `FlowingFluid#getSpread` mixin remains the single owner of the behavior. Atlas river channel cells still reject all vanilla horizontal spread because their source blocks are placed authoritatively by the river pipeline.

For other overworld water, the mixin only changes a spread result when the current block is supported by water. A candidate direction is retained when the target has water or a motion-blocking block below it. An air or otherwise unsupported target is rejected. Water resting on ordinary solid terrain is left entirely to vanilla, including normal edge falls.

The behavior is controlled by `river.flowPhysicsEnabled` together with the existing `overworldOnly` dimension guard. It adds no cache or shared mutable runtime state.

## Verification

Three registry-based GameTests cover spread across water support, rejection at an unsupported edge, and unchanged vanilla spread from solid support. The full project verification also runs the NeoForge GameTest server.
