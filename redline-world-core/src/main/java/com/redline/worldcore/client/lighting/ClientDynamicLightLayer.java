package com.redline.worldcore.client.lighting;

import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.lighting.DynamicLightRegistry;
import com.redline.worldcore.api.lighting.DynamicLightSource;
import com.redline.worldcore.api.lighting.DynamicLightType;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Client-only M11 dynamic light MVP.
 *
 * <p>The layer deliberately keeps dynamic light out of cube storage. It only injects a tiny set of client-side vanilla
 * LIGHT blocks around the local player's held luminous item, then removes/moves them as the player changes cube, item,
 * level or position. This gives an immediate visual MVP while the later real render/light-engine mixins are still out of
 * scope for the first dynamic-light pass.</p>
 */
public final class ClientDynamicLightLayer {
    private static final int UPDATE_INTERVAL_TICKS = 1;
    private static final int SET_BLOCK_FLAGS = 2;
    private static final int MAX_CLIENT_LIGHT_BLOCKS = 5;

    private static final Set<BlockPos> appliedLightBlocks = new HashSet<>();
    private static int tickCounter;
    private static int activeSources;
    private static int activeLightLevel;
    private static int appliedBlocks;
    private static int changedBlocksLastTick;
    private static CubePos activeCube;
    private static String activeType = "none";
    private static String activeItem = "none";
    private static String lastReason = "not ticked";

    private ClientDynamicLightLayer() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        tickCounter++;

        if (level == null || player == null) {
            clearAll(null, "no client level/player");
            return;
        }
        if (!level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL)) {
            clearAll(level, "outside cubic_test");
            return;
        }
        if (tickCounter % UPDATE_INTERVAL_TICKS != 0) {
            return;
        }

        HeldItemLightSource source = HeldItemLightSource.from(player);
        if (!source.isActive()) {
            clearAll(level, "held item light=0");
            activeItem = source.itemName();
            return;
        }

        apply(level, source);
    }

    public static int activeSources() {
        return activeSources;
    }

    public static int activeLightLevel() {
        return activeLightLevel;
    }

    public static int appliedBlocks() {
        return appliedBlocks;
    }

    public static int changedBlocksLastTick() {
        return changedBlocksLastTick;
    }

    public static String activeCubeString() {
        return activeCube == null ? "none" : activeCube.x() + " " + activeCube.y() + " " + activeCube.z();
    }

    public static String activeType() {
        return activeType;
    }

    public static String activeItem() {
        return activeItem;
    }

    public static String lastReason() {
        return lastReason;
    }

    private static void apply(ClientLevel level, HeldItemLightSource source) {
        LinkedHashSet<BlockPos> desired = desiredLightBlocks(level, source);
        changedBlocksLastTick = 0;

        for (BlockPos oldPos : Set.copyOf(appliedLightBlocks)) {
            if (!desired.contains(oldPos)) {
                removeClientLight(level, oldPos);
                appliedLightBlocks.remove(oldPos);
            }
        }

        BlockState lightState = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, source.lightLevel());
        for (BlockPos pos : desired) {
            BlockState current = level.getBlockState(pos);
            if (!current.isAir() && !current.is(Blocks.LIGHT)) {
                continue;
            }
            if (!current.equals(lightState)) {
                level.setBlock(pos, lightState, SET_BLOCK_FLAGS);
                changedBlocksLastTick++;
            }
            appliedLightBlocks.add(pos.immutable());
        }

        activeSources = desired.isEmpty() ? 0 : 1;
        activeLightLevel = desired.isEmpty() ? 0 : source.lightLevel();
        activeCube = CubePos.fromBlock(Mth.floor(source.position().x), Mth.floor(source.position().y), Mth.floor(source.position().z));
        activeType = source.type().name();
        activeItem = source.itemName();
        appliedBlocks = appliedLightBlocks.size();
        lastReason = desired.isEmpty() ? "no air anchors near player" : "active";
    }

    private static LinkedHashSet<BlockPos> desiredLightBlocks(ClientLevel level, HeldItemLightSource source) {
        LinkedHashSet<BlockPos> desired = new LinkedHashSet<>();
        BlockPos center = BlockPos.containing(source.position());
        addIfAir(level, desired, center);
        addIfAir(level, desired, center.above());

        if (source.lightLevel() >= 12) {
            addIfAir(level, desired, center.north());
            addIfAir(level, desired, center.south());
            addIfAir(level, desired, center.east());
            addIfAir(level, desired, center.west());
        }

        while (desired.size() > MAX_CLIENT_LIGHT_BLOCKS) {
            BlockPos last = null;
            for (BlockPos pos : desired) {
                last = pos;
            }
            if (last == null) {
                break;
            }
            desired.remove(last);
        }
        return desired;
    }

    private static void addIfAir(ClientLevel level, Set<BlockPos> target, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || (state.is(Blocks.LIGHT) && appliedLightBlocks.contains(pos))) {
            target.add(pos.immutable());
        }
    }

    private static void clearAll(ClientLevel level, String reason) {
        changedBlocksLastTick = 0;
        if (level != null) {
            for (BlockPos pos : Set.copyOf(appliedLightBlocks)) {
                removeClientLight(level, pos);
            }
        }
        appliedLightBlocks.clear();
        activeSources = 0;
        activeLightLevel = 0;
        appliedBlocks = 0;
        activeCube = null;
        activeType = "none";
        lastReason = reason;
    }

    private static void removeClientLight(ClientLevel level, BlockPos pos) {
        if (level.getBlockState(pos).is(Blocks.LIGHT)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), SET_BLOCK_FLAGS);
            changedBlocksLastTick++;
        }
    }

    private record HeldItemLightSource(Vec3 position, int lightLevel, double radius, String itemName) implements DynamicLightSource {
        private static HeldItemLightSource from(Player player) {
            ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
            ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
            int mainLight = DynamicLightRegistry.resolveItemLight(main);
            int offLight = DynamicLightRegistry.resolveItemLight(off);
            ItemStack selected = mainLight >= offLight ? main : off;
            int light = Math.max(mainLight, offLight);
            Vec3 pos = player.position().add(0.0D, 1.0D, 0.0D);
            String name = selected.isEmpty() ? "none" : selected.getHoverName().getString();
            return new HeldItemLightSource(pos, light, DynamicLightRegistry.radiusForLightLevel(light), name);
        }

        @Override
        public boolean isActive() {
            return lightLevel > 0;
        }

        @Override
        public DynamicLightType type() {
            return DynamicLightType.HELD_ITEM;
        }
    }
}
