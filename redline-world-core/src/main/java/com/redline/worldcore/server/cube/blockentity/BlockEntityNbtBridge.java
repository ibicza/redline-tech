package com.redline.worldcore.server.cube.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Reflection based bridge for M14.3 vanilla BlockEntity NBT capture.
 *
 * <p>Minecraft/NeoForge method names and signatures around BlockEntity saving changed between versions. This bridge keeps
 * redline-world-core source-side stable by probing public save methods and tagging the captured NBT with cube ownership
 * metadata. It never calls ticking or inventory logic.</p>
 */
public final class BlockEntityNbtBridge {
    private static final String[] SAVE_METHODS = {
            "saveWithFullMetadata",
            "saveWithId",
            "saveCustomAndMetadata",
            "saveCustomOnly"
    };

    public Optional<CompoundTag> capture(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return Optional.empty();
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return Optional.empty();
        }
        for (String methodName : SAVE_METHODS) {
            Optional<CompoundTag> captured = invokeSaveMethod(level, blockEntity, methodName);
            if (captured.isPresent()) {
                CompoundTag tag = captured.get();
                tag.putInt("x", pos.getX());
                tag.putInt("y", pos.getY());
                tag.putInt("z", pos.getZ());
                tag.putBoolean("redlinePlaceholder", false);
                tag.putBoolean("redlineRealNbt", true);
                tag.putString("redlineNbtSource", "vanilla_block_entity");
                return Optional.of(tag);
            }
        }
        return Optional.empty();
    }

    private Optional<CompoundTag> invokeSaveMethod(ServerLevel level, BlockEntity blockEntity, String methodName) {
        for (Method method : blockEntity.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || !CompoundTag.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            try {
                Object result;
                if (method.getParameterCount() == 0) {
                    result = method.invoke(blockEntity);
                } else if (method.getParameterCount() == 1) {
                    result = method.invoke(blockEntity, level.registryAccess());
                } else {
                    continue;
                }
                if (result instanceof CompoundTag tag) {
                    return Optional.of(tag.copy());
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next possible signature/method name.
            }
        }
        return Optional.empty();
    }
}
