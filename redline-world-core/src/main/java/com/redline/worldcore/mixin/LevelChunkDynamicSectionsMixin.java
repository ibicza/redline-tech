package com.redline.worldcore.mixin;

import com.redline.worldcore.runtime.DynamicVanillaSectionBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * M20.1: CubicChunks-style sparse vanilla section hook for concrete LevelChunk reads/writes.
 *
 * <p>Vanilla behavior: {@link LevelChunk} reads and writes a fixed {@code LevelChunkSection[]} inherited from
 * {@code ChunkAccess}. That array cannot cover true cubic Y such as 9000/-12000.</p>
 *
 * <p>Cubic behavior: outside the temporary vanilla shell, {@link LevelChunk} block/fluid reads and writes are redirected
 * to sparse, dynamically materialized vanilla {@link LevelChunkSection} instances keyed by real sectionY/cubeY. The
 * section is a real vanilla container, not a hand-written block simulation.</p>
 *
 * <p>Important: {@code getSection(int)} is declared on {@code ChunkAccess}, not on {@code LevelChunk} in Minecraft 26.2.
 * Hooking it here crashes during bootstrap, so that hook lives in {@link ChunkAccessDynamicSectionsMixin}.</p>
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkDynamicSectionsMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void redline$dynamicSectionGetBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        DynamicVanillaSectionBridge.levelChunkGetBlockState(chunk, pos).ifPresent(cir::setReturnValue);
    }

    @Inject(method = "getFluidState(III)Lnet/minecraft/world/level/material/FluidState;", at = @At("HEAD"), cancellable = true)
    private void redline$dynamicSectionGetFluidState(int x, int y, int z, CallbackInfoReturnable<FluidState> cir) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        DynamicVanillaSectionBridge.levelChunkGetFluidState(chunk, x, y, z).ifPresent(cir::setReturnValue);
    }

    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void redline$dynamicSectionSetBlockState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        DynamicVanillaSectionBridge.levelChunkSetBlockState(chunk, pos, state, flags).ifPresent(cir::setReturnValue);
    }
}
