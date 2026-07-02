package com.redline.worldcore.network;

import com.redline.worldcore.RedlineWorldCore;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * RWC client-bound debug/sync payload for the cubic test dimension.
 *
 * <p>This is intentionally metadata-first: it gives the client a stable view of the cube backend before the later risky
 * packet/mixin work starts replacing vanilla chunk packets. The vanilla column shell is still the compatibility boundary.</p>
 */
public record CubeClientSyncPayload(
        int playerCubeX,
        int playerCubeY,
        int playerCubeZ,
        int loadedCubes,
        int pendingLoads,
        int requestedCubes,
        long totalGenerated,
        long totalLightRebuilt,
        int lightRebuiltLastTick,
        int lightDirtyQueue,
        long totalSkyLightRebuilt,
        long totalSkyLightColumnsRebuilt,
        long totalSkyLightSkippedUnchanged,
        long totalSkyLightSavedChanged,
        int skyLightColumnsLastTick,
        int skyLightDirtyColumns,
        int skyLightChangedLastTick,
        int skyLightSkippedUnchangedLastTick,
        int skyLightSavedChangedLastTick,
        long skyLightRebuildMicrosLastTick,
        long skyLightRebuildMicrosMax,
        int skyLightAutoColumnsPerTick,
        int skyLightDirtyDelayTicks,
        int materializedCubes,
        int queuedMaterializations,
        int materializedLastTick,
        int overlayMode,
        int streamHorizontalRadius,
        int streamVerticalRadius,
        int maxMaterializedCubesPerTick,
        int syncPacketIntervalTicks,
        long playerWritesSaved,
        long materializerWritesIgnored,
        long commandWritesSaved,
        int trackedEntities,
        int entitySections,
        int entitiesInPlayerCube,
        int entityScannedLastTick,
        int entityAddedLastTick,
        int entityMovedLastTick,
        int entityRemovedLastTick,
        long totalEntityMoves,
        int busiestEntityCubeX,
        int busiestEntityCubeY,
        int busiestEntityCubeZ,
        int busiestEntityCubeEntities,
        int playerEntityCount,
        int mobEntityCount,
        int itemEntityCount,
        int projectileEntityCount,
        int otherEntityCount,
        long entityScanMicrosLastTick,
        long entityScanMicrosAverage,
        long entityScanMicrosMax,
        List<Entry> entries
) implements CustomPacketPayload {
    public static final Type<CubeClientSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(RedlineWorldCore.MOD_ID, "cube_client_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CubeClientSyncPayload> CODEC = StreamCodec.ofMember(CubeClientSyncPayload::write, CubeClientSyncPayload::read);

    public CubeClientSyncPayload {
        entries = List.copyOf(entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(playerCubeX);
        buffer.writeVarInt(playerCubeY);
        buffer.writeVarInt(playerCubeZ);
        buffer.writeVarInt(loadedCubes);
        buffer.writeVarInt(pendingLoads);
        buffer.writeVarInt(requestedCubes);
        buffer.writeLong(totalGenerated);
        buffer.writeLong(totalLightRebuilt);
        buffer.writeVarInt(lightRebuiltLastTick);
        buffer.writeVarInt(lightDirtyQueue);
        buffer.writeLong(totalSkyLightRebuilt);
        buffer.writeLong(totalSkyLightColumnsRebuilt);
        buffer.writeLong(totalSkyLightSkippedUnchanged);
        buffer.writeLong(totalSkyLightSavedChanged);
        buffer.writeVarInt(skyLightColumnsLastTick);
        buffer.writeVarInt(skyLightDirtyColumns);
        buffer.writeVarInt(skyLightChangedLastTick);
        buffer.writeVarInt(skyLightSkippedUnchangedLastTick);
        buffer.writeVarInt(skyLightSavedChangedLastTick);
        buffer.writeLong(skyLightRebuildMicrosLastTick);
        buffer.writeLong(skyLightRebuildMicrosMax);
        buffer.writeVarInt(skyLightAutoColumnsPerTick);
        buffer.writeVarInt(skyLightDirtyDelayTicks);
        buffer.writeVarInt(materializedCubes);
        buffer.writeVarInt(queuedMaterializations);
        buffer.writeVarInt(materializedLastTick);
        buffer.writeVarInt(overlayMode);
        buffer.writeVarInt(streamHorizontalRadius);
        buffer.writeVarInt(streamVerticalRadius);
        buffer.writeVarInt(maxMaterializedCubesPerTick);
        buffer.writeVarInt(syncPacketIntervalTicks);
        buffer.writeLong(playerWritesSaved);
        buffer.writeLong(materializerWritesIgnored);
        buffer.writeLong(commandWritesSaved);
        buffer.writeVarInt(trackedEntities);
        buffer.writeVarInt(entitySections);
        buffer.writeVarInt(entitiesInPlayerCube);
        buffer.writeVarInt(entityScannedLastTick);
        buffer.writeVarInt(entityAddedLastTick);
        buffer.writeVarInt(entityMovedLastTick);
        buffer.writeVarInt(entityRemovedLastTick);
        buffer.writeLong(totalEntityMoves);
        buffer.writeVarInt(busiestEntityCubeX);
        buffer.writeVarInt(busiestEntityCubeY);
        buffer.writeVarInt(busiestEntityCubeZ);
        buffer.writeVarInt(busiestEntityCubeEntities);
        buffer.writeVarInt(playerEntityCount);
        buffer.writeVarInt(mobEntityCount);
        buffer.writeVarInt(itemEntityCount);
        buffer.writeVarInt(projectileEntityCount);
        buffer.writeVarInt(otherEntityCount);
        buffer.writeLong(entityScanMicrosLastTick);
        buffer.writeLong(entityScanMicrosAverage);
        buffer.writeLong(entityScanMicrosMax);
        buffer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            entry.write(buffer);
        }
    }

    private static CubeClientSyncPayload read(RegistryFriendlyByteBuf buffer) {
        int playerCubeX = buffer.readVarInt();
        int playerCubeY = buffer.readVarInt();
        int playerCubeZ = buffer.readVarInt();
        int loadedCubes = buffer.readVarInt();
        int pendingLoads = buffer.readVarInt();
        int requestedCubes = buffer.readVarInt();
        long totalGenerated = buffer.readLong();
        long totalLightRebuilt = buffer.readLong();
        int lightRebuiltLastTick = buffer.readVarInt();
        int lightDirtyQueue = buffer.readVarInt();
        long totalSkyLightRebuilt = buffer.readLong();
        long totalSkyLightColumnsRebuilt = buffer.readLong();
        long totalSkyLightSkippedUnchanged = buffer.readLong();
        long totalSkyLightSavedChanged = buffer.readLong();
        int skyLightColumnsLastTick = buffer.readVarInt();
        int skyLightDirtyColumns = buffer.readVarInt();
        int skyLightChangedLastTick = buffer.readVarInt();
        int skyLightSkippedUnchangedLastTick = buffer.readVarInt();
        int skyLightSavedChangedLastTick = buffer.readVarInt();
        long skyLightRebuildMicrosLastTick = buffer.readLong();
        long skyLightRebuildMicrosMax = buffer.readLong();
        int skyLightAutoColumnsPerTick = buffer.readVarInt();
        int skyLightDirtyDelayTicks = buffer.readVarInt();
        int materializedCubes = buffer.readVarInt();
        int queuedMaterializations = buffer.readVarInt();
        int materializedLastTick = buffer.readVarInt();
        int overlayMode = buffer.readVarInt();
        int streamHorizontalRadius = buffer.readVarInt();
        int streamVerticalRadius = buffer.readVarInt();
        int maxMaterializedCubesPerTick = buffer.readVarInt();
        int syncPacketIntervalTicks = buffer.readVarInt();
        long playerWritesSaved = buffer.readLong();
        long materializerWritesIgnored = buffer.readLong();
        long commandWritesSaved = buffer.readLong();
        int trackedEntities = buffer.readVarInt();
        int entitySections = buffer.readVarInt();
        int entitiesInPlayerCube = buffer.readVarInt();
        int entityScannedLastTick = buffer.readVarInt();
        int entityAddedLastTick = buffer.readVarInt();
        int entityMovedLastTick = buffer.readVarInt();
        int entityRemovedLastTick = buffer.readVarInt();
        long totalEntityMoves = buffer.readLong();
        int busiestEntityCubeX = buffer.readVarInt();
        int busiestEntityCubeY = buffer.readVarInt();
        int busiestEntityCubeZ = buffer.readVarInt();
        int busiestEntityCubeEntities = buffer.readVarInt();
        int playerEntityCount = buffer.readVarInt();
        int mobEntityCount = buffer.readVarInt();
        int itemEntityCount = buffer.readVarInt();
        int projectileEntityCount = buffer.readVarInt();
        int otherEntityCount = buffer.readVarInt();
        long entityScanMicrosLastTick = buffer.readLong();
        long entityScanMicrosAverage = buffer.readLong();
        long entityScanMicrosMax = buffer.readLong();
        int count = buffer.readVarInt();
        List<Entry> entries = new ArrayList<>(Math.min(count, 256));
        for (int i = 0; i < count; i++) {
            entries.add(Entry.read(buffer));
        }
        return new CubeClientSyncPayload(
                playerCubeX,
                playerCubeY,
                playerCubeZ,
                loadedCubes,
                pendingLoads,
                requestedCubes,
                totalGenerated,
                totalLightRebuilt,
                lightRebuiltLastTick,
                lightDirtyQueue,
                totalSkyLightRebuilt,
                totalSkyLightColumnsRebuilt,
                totalSkyLightSkippedUnchanged,
                totalSkyLightSavedChanged,
                skyLightColumnsLastTick,
                skyLightDirtyColumns,
                skyLightChangedLastTick,
                skyLightSkippedUnchangedLastTick,
                skyLightSavedChangedLastTick,
                skyLightRebuildMicrosLastTick,
                skyLightRebuildMicrosMax,
                skyLightAutoColumnsPerTick,
                skyLightDirtyDelayTicks,
                materializedCubes,
                queuedMaterializations,
                materializedLastTick,
                overlayMode,
                streamHorizontalRadius,
                streamVerticalRadius,
                maxMaterializedCubesPerTick,
                syncPacketIntervalTicks,
                playerWritesSaved,
                materializerWritesIgnored,
                commandWritesSaved,
                trackedEntities,
                entitySections,
                entitiesInPlayerCube,
                entityScannedLastTick,
                entityAddedLastTick,
                entityMovedLastTick,
                entityRemovedLastTick,
                totalEntityMoves,
                busiestEntityCubeX,
                busiestEntityCubeY,
                busiestEntityCubeZ,
                busiestEntityCubeEntities,
                playerEntityCount,
                mobEntityCount,
                itemEntityCount,
                projectileEntityCount,
                otherEntityCount,
                entityScanMicrosLastTick,
                entityScanMicrosAverage,
                entityScanMicrosMax,
                entries
        );
    }

    public record Entry(
            int cubeX,
            int cubeY,
            int cubeZ,
            int statusOrdinal,
            int holderStateOrdinal,
            int ticketLevelOrdinal,
            long hash,
            int maxBlockLight,
            int litBlocks,
            int emittingBlocks,
            long lightHash,
            int maxSkyLight,
            int skyLitBlocks,
            int bottomSkyLitBlocks,
            long skyLightHash,
            boolean dirty,
            boolean materialized
    ) {
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(cubeX);
            buffer.writeVarInt(cubeY);
            buffer.writeVarInt(cubeZ);
            buffer.writeVarInt(statusOrdinal);
            buffer.writeVarInt(holderStateOrdinal);
            buffer.writeVarInt(ticketLevelOrdinal);
            buffer.writeLong(hash);
            buffer.writeVarInt(maxBlockLight);
            buffer.writeVarInt(litBlocks);
            buffer.writeVarInt(emittingBlocks);
            buffer.writeLong(lightHash);
            buffer.writeVarInt(maxSkyLight);
            buffer.writeVarInt(skyLitBlocks);
            buffer.writeVarInt(bottomSkyLitBlocks);
            buffer.writeLong(skyLightHash);
            buffer.writeBoolean(dirty);
            buffer.writeBoolean(materialized);
        }

        private static Entry read(RegistryFriendlyByteBuf buffer) {
            return new Entry(
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readLong(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readLong(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readLong(),
                    buffer.readBoolean(),
                    buffer.readBoolean()
            );
        }
    }
}
