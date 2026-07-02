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
        int loadedLastTick,
        int generatedLastTick,
        long loadMicrosLastTick,
        long loadMicrosMax,
        int maxLoadsPerTick,
        int maxGeneratedLoadsPerTick,
        int maxLoadMicrosPerTick,
        boolean loadGeneratedBudgetHitLastTick,
        boolean loadTimeBudgetHitLastTick,
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
        boolean pregenRunning,
        boolean pregenPaused,
        int pregenQueuedCubes,
        long pregenActiveTotalCubes,
        long pregenActiveProcessedCubes,
        long pregenTotalCompletedJobs,
        long pregenTotalProcessedCubes,
        long pregenTotalGeneratedCubes,
        long pregenTotalSkippedCubes,
        long pregenTotalFailedCubes,
        int pregenLastTickProcessed,
        int pregenLastTickGenerated,
        int pregenLastTickSkipped,
        int pregenLastTickFailed,
        long pregenLastTickMicros,
        long pregenMaxTickMicros,
        int pregenMaxCubesPerTick,
        int pregenMaxMillisPerTick,
        int pregenTargetStatusOrdinal,
        String pregenActiveJobId,
        String pregenLastError,
        int pregenMaxSkippedCubesPerTick,
        int pregenMaxGeneratedCubesPerSecond,
        int pregenExpensiveCubeMillis,
        int pregenCooldownAfterExpensiveTicks,
        int pregenThrottleCooldownTicks,
        int pregenGeneratedThisSecond,
        String pregenThrottleReason,
        int visitedColumns,
        int backfillDoneColumns,
        boolean backfillEnabled,
        int backfillPendingColumns,
        long backfillJobsStarted,
        int backfillMaxVerticalRadius,
        int backfillDelayTicks,
        String backfillTargetStatus,
        String backfillLastReason,
        boolean afkEnabled,
        int afkTrackedPlayers,
        int afkPlayers,
        long afkJobsStarted,
        int afkAfterTicks,
        int afkRadiusBlocks,
        int afkVerticalRadiusCubes,
        String afkTargetStatus,
        String afkLastReason,
        List<Entry> entries
) implements CustomPacketPayload {
    public static final Type<CubeClientSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(RedlineWorldCore.MOD_ID, "cube_client_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CubeClientSyncPayload> CODEC = StreamCodec.ofMember(CubeClientSyncPayload::write, CubeClientSyncPayload::read);

    public CubeClientSyncPayload {
        pregenActiveJobId = pregenActiveJobId == null ? "none" : pregenActiveJobId;
        pregenLastError = pregenLastError == null ? "" : pregenLastError;
        pregenThrottleReason = pregenThrottleReason == null ? "" : pregenThrottleReason;
        backfillTargetStatus = backfillTargetStatus == null ? "" : backfillTargetStatus;
        backfillLastReason = backfillLastReason == null ? "" : backfillLastReason;
        afkTargetStatus = afkTargetStatus == null ? "" : afkTargetStatus;
        afkLastReason = afkLastReason == null ? "" : afkLastReason;
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
        buffer.writeVarInt(loadedLastTick);
        buffer.writeVarInt(generatedLastTick);
        buffer.writeLong(loadMicrosLastTick);
        buffer.writeLong(loadMicrosMax);
        buffer.writeVarInt(maxLoadsPerTick);
        buffer.writeVarInt(maxGeneratedLoadsPerTick);
        buffer.writeVarInt(maxLoadMicrosPerTick);
        buffer.writeBoolean(loadGeneratedBudgetHitLastTick);
        buffer.writeBoolean(loadTimeBudgetHitLastTick);
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
        buffer.writeBoolean(pregenRunning);
        buffer.writeBoolean(pregenPaused);
        buffer.writeVarInt(pregenQueuedCubes);
        buffer.writeLong(pregenActiveTotalCubes);
        buffer.writeLong(pregenActiveProcessedCubes);
        buffer.writeLong(pregenTotalCompletedJobs);
        buffer.writeLong(pregenTotalProcessedCubes);
        buffer.writeLong(pregenTotalGeneratedCubes);
        buffer.writeLong(pregenTotalSkippedCubes);
        buffer.writeLong(pregenTotalFailedCubes);
        buffer.writeVarInt(pregenLastTickProcessed);
        buffer.writeVarInt(pregenLastTickGenerated);
        buffer.writeVarInt(pregenLastTickSkipped);
        buffer.writeVarInt(pregenLastTickFailed);
        buffer.writeLong(pregenLastTickMicros);
        buffer.writeLong(pregenMaxTickMicros);
        buffer.writeVarInt(pregenMaxCubesPerTick);
        buffer.writeVarInt(pregenMaxMillisPerTick);
        buffer.writeVarInt(pregenTargetStatusOrdinal);
        buffer.writeUtf(pregenActiveJobId);
        buffer.writeUtf(pregenLastError);
        buffer.writeVarInt(pregenMaxSkippedCubesPerTick);
        buffer.writeVarInt(pregenMaxGeneratedCubesPerSecond);
        buffer.writeVarInt(pregenExpensiveCubeMillis);
        buffer.writeVarInt(pregenCooldownAfterExpensiveTicks);
        buffer.writeVarInt(pregenThrottleCooldownTicks);
        buffer.writeVarInt(pregenGeneratedThisSecond);
        buffer.writeUtf(pregenThrottleReason);
        buffer.writeVarInt(visitedColumns);
        buffer.writeVarInt(backfillDoneColumns);
        buffer.writeBoolean(backfillEnabled);
        buffer.writeVarInt(backfillPendingColumns);
        buffer.writeLong(backfillJobsStarted);
        buffer.writeVarInt(backfillMaxVerticalRadius);
        buffer.writeVarInt(backfillDelayTicks);
        buffer.writeUtf(backfillTargetStatus);
        buffer.writeUtf(backfillLastReason);
        buffer.writeBoolean(afkEnabled);
        buffer.writeVarInt(afkTrackedPlayers);
        buffer.writeVarInt(afkPlayers);
        buffer.writeLong(afkJobsStarted);
        buffer.writeVarInt(afkAfterTicks);
        buffer.writeVarInt(afkRadiusBlocks);
        buffer.writeVarInt(afkVerticalRadiusCubes);
        buffer.writeUtf(afkTargetStatus);
        buffer.writeUtf(afkLastReason);
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
        int loadedLastTick = buffer.readVarInt();
        int generatedLastTick = buffer.readVarInt();
        long loadMicrosLastTick = buffer.readLong();
        long loadMicrosMax = buffer.readLong();
        int maxLoadsPerTick = buffer.readVarInt();
        int maxGeneratedLoadsPerTick = buffer.readVarInt();
        int maxLoadMicrosPerTick = buffer.readVarInt();
        boolean loadGeneratedBudgetHitLastTick = buffer.readBoolean();
        boolean loadTimeBudgetHitLastTick = buffer.readBoolean();
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
        boolean pregenRunning = buffer.readBoolean();
        boolean pregenPaused = buffer.readBoolean();
        int pregenQueuedCubes = buffer.readVarInt();
        long pregenActiveTotalCubes = buffer.readLong();
        long pregenActiveProcessedCubes = buffer.readLong();
        long pregenTotalCompletedJobs = buffer.readLong();
        long pregenTotalProcessedCubes = buffer.readLong();
        long pregenTotalGeneratedCubes = buffer.readLong();
        long pregenTotalSkippedCubes = buffer.readLong();
        long pregenTotalFailedCubes = buffer.readLong();
        int pregenLastTickProcessed = buffer.readVarInt();
        int pregenLastTickGenerated = buffer.readVarInt();
        int pregenLastTickSkipped = buffer.readVarInt();
        int pregenLastTickFailed = buffer.readVarInt();
        long pregenLastTickMicros = buffer.readLong();
        long pregenMaxTickMicros = buffer.readLong();
        int pregenMaxCubesPerTick = buffer.readVarInt();
        int pregenMaxMillisPerTick = buffer.readVarInt();
        int pregenTargetStatusOrdinal = buffer.readVarInt();
        String pregenActiveJobId = buffer.readUtf();
        String pregenLastError = buffer.readUtf();
        int pregenMaxSkippedCubesPerTick = buffer.readVarInt();
        int pregenMaxGeneratedCubesPerSecond = buffer.readVarInt();
        int pregenExpensiveCubeMillis = buffer.readVarInt();
        int pregenCooldownAfterExpensiveTicks = buffer.readVarInt();
        int pregenThrottleCooldownTicks = buffer.readVarInt();
        int pregenGeneratedThisSecond = buffer.readVarInt();
        String pregenThrottleReason = buffer.readUtf();
        int visitedColumns = buffer.readVarInt();
        int backfillDoneColumns = buffer.readVarInt();
        boolean backfillEnabled = buffer.readBoolean();
        int backfillPendingColumns = buffer.readVarInt();
        long backfillJobsStarted = buffer.readLong();
        int backfillMaxVerticalRadius = buffer.readVarInt();
        int backfillDelayTicks = buffer.readVarInt();
        String backfillTargetStatus = buffer.readUtf();
        String backfillLastReason = buffer.readUtf();
        boolean afkEnabled = buffer.readBoolean();
        int afkTrackedPlayers = buffer.readVarInt();
        int afkPlayers = buffer.readVarInt();
        long afkJobsStarted = buffer.readLong();
        int afkAfterTicks = buffer.readVarInt();
        int afkRadiusBlocks = buffer.readVarInt();
        int afkVerticalRadiusCubes = buffer.readVarInt();
        String afkTargetStatus = buffer.readUtf();
        String afkLastReason = buffer.readUtf();
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
                loadedLastTick,
                generatedLastTick,
                loadMicrosLastTick,
                loadMicrosMax,
                maxLoadsPerTick,
                maxGeneratedLoadsPerTick,
                maxLoadMicrosPerTick,
                loadGeneratedBudgetHitLastTick,
                loadTimeBudgetHitLastTick,
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
                pregenRunning,
                pregenPaused,
                pregenQueuedCubes,
                pregenActiveTotalCubes,
                pregenActiveProcessedCubes,
                pregenTotalCompletedJobs,
                pregenTotalProcessedCubes,
                pregenTotalGeneratedCubes,
                pregenTotalSkippedCubes,
                pregenTotalFailedCubes,
                pregenLastTickProcessed,
                pregenLastTickGenerated,
                pregenLastTickSkipped,
                pregenLastTickFailed,
                pregenLastTickMicros,
                pregenMaxTickMicros,
                pregenMaxCubesPerTick,
                pregenMaxMillisPerTick,
                pregenTargetStatusOrdinal,
                pregenActiveJobId,
                pregenLastError,
                pregenMaxSkippedCubesPerTick,
                pregenMaxGeneratedCubesPerSecond,
                pregenExpensiveCubeMillis,
                pregenCooldownAfterExpensiveTicks,
                pregenThrottleCooldownTicks,
                pregenGeneratedThisSecond,
                pregenThrottleReason,
                visitedColumns,
                backfillDoneColumns,
                backfillEnabled,
                backfillPendingColumns,
                backfillJobsStarted,
                backfillMaxVerticalRadius,
                backfillDelayTicks,
                backfillTargetStatus,
                backfillLastReason,
                afkEnabled,
                afkTrackedPlayers,
                afkPlayers,
                afkJobsStarted,
                afkAfterTicks,
                afkRadiusBlocks,
                afkVerticalRadiusCubes,
                afkTargetStatus,
                afkLastReason,
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
