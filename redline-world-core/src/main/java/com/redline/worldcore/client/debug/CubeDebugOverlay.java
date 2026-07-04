package com.redline.worldcore.client.debug;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.ticket.CubeTicketLevel;
import com.redline.worldcore.client.lighting.ClientDynamicLightLayer;
import com.redline.worldcore.client.sync.ClientCubeSectionStore;
import com.redline.worldcore.client.sync.ClientCubeSyncState;
import com.redline.worldcore.network.CubeClientSyncPayload;
import com.redline.worldcore.server.compat.CubicClientSyncBridge;
import com.redline.worldcore.server.cube.CubeHolderState;
import com.redline.worldcore.server.cube.CubeClientStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** RWC HUD overlay fed by the server cube sync payload. */
public final class CubeDebugOverlay {
    private static final int BACKGROUND = 0x88000000;
    private static final int TEXT = 0xFFE6F7FF;
    private static final int MUTED = 0xFFB8B8B8;
    private static final int GOOD = 0xFF75FF9A;
    private static final int WARN = 0xFFFFD166;

    private CubeDebugOverlay() {
    }

    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (!ClientCubeSyncState.hasFreshPayload()) {
            return;
        }

        CubeClientSyncPayload payload = ClientCubeSyncState.latest().orElse(null);
        if (payload == null || payload.overlayMode() == CubicClientSyncBridge.OVERLAY_HIDDEN) {
            return;
        }

        if (payload.overlayMode() == CubicClientSyncBridge.OVERLAY_COMPACT) {
            renderCompact(event.getGuiGraphics(), minecraft, payload);
            return;
        }
        renderFull(event.getGuiGraphics(), minecraft, payload);
    }

    private static void renderCompact(GuiGraphicsExtractor graphics, Minecraft minecraft, CubeClientSyncPayload payload) {
        int x = 6;
        int y = 6;
        int width = 340;
        int lines = 12;
        graphics.fill(x - 3, y - 3, x + width, y + lines * 10 + 5, BACKGROUND);
        draw(graphics, minecraft, x, y, "RWC compact", GOOD);
        y += 10;
        draw(graphics, minecraft, x, y, "cube=" + payload.playerCubeX() + " " + payload.playerCubeY() + " " + payload.playerCubeZ()
                + " loaded=" + payload.loadedCubes() + "/" + payload.requestedCubes(), TEXT);
        y += 10;
        draw(graphics, minecraft, x, y, "mat=" + payload.materializedCubes()
                + " q=" + payload.queuedMaterializations()
                + " lightQ=" + payload.lightDirtyQueue()
                + " skyQ=" + payload.skyLightDirtyColumns()
                + " skyTick=" + payload.skyLightColumnsLastTick()
                + " writes=" + payload.playerWritesSaved(), TEXT);
        y += 10;
        draw(graphics, minecraft, x, y, "load last=" + payload.loadedCubes()
                + " loadUs=" + payload.loadMicrosLastTick()
                + " max=" + payload.loadMicrosMax()
                + " genBudget=" + (payload.loadGeneratedBudgetHitLastTick() ? "hit" : "ok")
                + " time=" + (payload.loadTimeBudgetHitLastTick() ? "hit" : "ok"), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "stream h=" + payload.streamHorizontalRadius()
                + " v=" + payload.streamVerticalRadius()
                + " speed=" + payload.maxMaterializedCubesPerTick()
                + "/t", MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "dynLight src=" + ClientDynamicLightLayer.activeSources()
                + " lvl=" + ClientDynamicLightLayer.activeLightLevel()
                + " blocks=" + ClientDynamicLightLayer.appliedBlocks()
                + " cube=" + ClientDynamicLightLayer.activeCubeString(), MUTED);
        y += 10;
        ClientCubeSectionStore.SnapshotStats sectionStats = ClientCubeSectionStore.stats();
        draw(graphics, minecraft, x, y, "native sections=" + sectionStats.sections()
                + " recv=" + sectionStats.receivedSnapshots()
                + " unload=" + sectionStats.unloads()
                + " bytes~=" + sectionStats.receivedBytesEstimate(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "entities tracked=" + payload.trackedEntities()
                + " here=" + payload.entitiesInPlayerCube()
                + " sections=" + payload.entitySections()
                + " moved=" + payload.entityMovedLastTick(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "entityKinds p=" + payload.playerEntityCount()
                + " m=" + payload.mobEntityCount()
                + " i=" + payload.itemEntityCount()
                + " pr=" + payload.projectileEntityCount()
                + " o=" + payload.otherEntityCount()
                + " scanUs=" + payload.entityScanMicrosLastTick(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "pregen " + pregenState(payload)
                + " q=" + payload.pregenQueuedCubes()
                + " tick=" + payload.pregenLastTickProcessed()
                + "/" + payload.pregenMaxCubesPerTick()
                + " gen/s=" + payload.pregenGeneratedThisSecond()
                + "/" + payload.pregenMaxGeneratedCubesPerSecond()
                + " reason=" + payload.pregenThrottleReason(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "visit cols=" + payload.visitedColumns()
                + " done=" + payload.backfillDoneColumns()
                + " backfill=" + (payload.backfillEnabled() ? "on" : "off")
                + " pending=" + payload.backfillPendingColumns(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "afk=" + (payload.afkEnabled() ? "on" : "off")
                + " players=" + payload.afkTrackedPlayers()
                + " afk=" + payload.afkPlayers()
                + " jobs=" + payload.afkJobsStarted(), MUTED);
    }

    private static void renderFull(GuiGraphicsExtractor graphics, Minecraft minecraft, CubeClientSyncPayload payload) {
        int x = 6;
        int y = 6;
        int width = 430;
        int shownEntries = Math.min(8, payload.entries().size());
        int lines = shownEntries + 22;
        graphics.fill(x - 3, y - 3, x + width, y + lines * 10 + 5, BACKGROUND);

        draw(graphics, minecraft, x, y, "Redline World Core debug overlay", GOOD);
        y += 10;
        draw(graphics, minecraft, x, y, "playerCube=" + payload.playerCubeX() + " " + payload.playerCubeY() + " " + payload.playerCubeZ()
                + "  cache loaded=" + payload.loadedCubes() + " pending=" + payload.pendingLoads() + " requested=" + payload.requestedCubes(), TEXT);
        y += 10;
        draw(graphics, minecraft, x, y, "generatedTotal=" + payload.totalGenerated()
                + "  materialized=" + payload.materializedCubes()
                + " queue=" + payload.queuedMaterializations()
                + " lastTick=" + payload.materializedLastTick(), TEXT);
        y += 10;
        draw(graphics, minecraft, x, y, "loadPerf last=" + payload.loadedLastTick()
                + " gen=" + payload.generatedLastTick()
                + " pending=" + payload.pendingLoads()
                + " us=" + payload.loadMicrosLastTick()
                + " max=" + payload.loadMicrosMax()
                + " budget=" + payload.maxLoadsPerTick() + "/t gen=" + payload.maxGeneratedLoadsPerTick()
                + "/t " + payload.maxLoadMicrosPerTick() + "us"
                + " hit=" + (payload.loadGeneratedBudgetHitLastTick() ? "gen" : "-")
                + "/" + (payload.loadTimeBudgetHitLastTick() ? "time" : "-"), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "staticLight rebuiltTotal=" + payload.totalLightRebuilt()
                + " lastTick=" + payload.lightRebuiltLastTick()
                + " dirtyQ=" + payload.lightDirtyQueue(), TEXT);
        y += 10;
        draw(graphics, minecraft, x, y, "skyLight rebuiltCubes=" + payload.totalSkyLightRebuilt()
                + " columns=" + payload.totalSkyLightColumnsRebuilt()
                + " last=" + payload.skyLightColumnsLastTick()
                + " dirty=" + payload.skyLightDirtyColumns(), TEXT);
        y += 10;
        draw(graphics, minecraft, x, y, "skyPerf changed=" + payload.skyLightChangedLastTick()
                + " skipped=" + payload.skyLightSkippedUnchangedLastTick()
                + " saved=" + payload.skyLightSavedChangedLastTick()
                + " us=" + payload.skyLightRebuildMicrosLastTick()
                + " budget=" + payload.skyLightAutoColumnsPerTick()
                + "/t delay=" + payload.skyLightDirtyDelayTicks() + "t", MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "dynamicLight src=" + ClientDynamicLightLayer.activeSources()
                + " lvl=" + ClientDynamicLightLayer.activeLightLevel()
                + " blocks=" + ClientDynamicLightLayer.appliedBlocks()
                + " changed=" + ClientDynamicLightLayer.changedBlocksLastTick()
                + " cube=" + ClientDynamicLightLayer.activeCubeString()
                + " item=" + ClientDynamicLightLayer.activeItem()
                + " reason=" + ClientDynamicLightLayer.lastReason(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "entities tracked=" + payload.trackedEntities()
                + " here=" + payload.entitiesInPlayerCube()
                + " sections=" + payload.entitySections()
                + " scanned=" + payload.entityScannedLastTick()
                + " +" + payload.entityAddedLastTick()
                + " move=" + payload.entityMovedLastTick()
                + " -" + payload.entityRemovedLastTick(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "entityKinds players=" + payload.playerEntityCount()
                + " mobs=" + payload.mobEntityCount()
                + " items=" + payload.itemEntityCount()
                + " projectiles=" + payload.projectileEntityCount()
                + " other=" + payload.otherEntityCount(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "entityPerf scanUs=" + payload.entityScanMicrosLastTick()
                + " avg=" + payload.entityScanMicrosAverage()
                + " max=" + payload.entityScanMicrosMax()
                + " totalMoves=" + payload.totalEntityMoves()
                + " busiest=" + payload.busiestEntityCubeX() + " " + payload.busiestEntityCubeY() + " " + payload.busiestEntityCubeZ()
                + "/" + payload.busiestEntityCubeEntities(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "pregen " + pregenState(payload)
                + " job=" + payload.pregenActiveJobId()
                + " target=" + enumName(CubeStatus.values(), payload.pregenTargetStatusOrdinal())
                + " q=" + payload.pregenQueuedCubes()
                + " done=" + payload.pregenActiveProcessedCubes() + "/" + payload.pregenActiveTotalCubes(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "pregenTick processed=" + payload.pregenLastTickProcessed()
                + " gen=" + payload.pregenLastTickGenerated()
                + " skip=" + payload.pregenLastTickSkipped()
                + " fail=" + payload.pregenLastTickFailed()
                + " us=" + payload.pregenLastTickMicros()
                + " max=" + payload.pregenMaxTickMicros()
                + " budget=" + payload.pregenMaxCubesPerTick() + "/t " + payload.pregenMaxMillisPerTick() + "ms", MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "pregenThrottle skip=" + payload.pregenMaxSkippedCubesPerTick()
                + "/t gen=" + payload.pregenGeneratedThisSecond() + "/" + payload.pregenMaxGeneratedCubesPerSecond()
                + "/s expensive=" + payload.pregenExpensiveCubeMillis()
                + "ms cooldown=" + payload.pregenThrottleCooldownTicks()
                + " reason=" + payload.pregenThrottleReason(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "visits columns=" + payload.visitedColumns()
                + " backfillDone=" + payload.backfillDoneColumns()
                + " backfill=" + (payload.backfillEnabled() ? "on" : "off")
                + " pending=" + payload.backfillPendingColumns()
                + " jobs=" + payload.backfillJobsStarted()
                + " reason=" + payload.backfillLastReason(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "backfill cfg radiusY=" + payload.backfillMaxVerticalRadius()
                + " delay=" + payload.backfillDelayTicks()
                + "t target=" + payload.backfillTargetStatus()
                + " afk=" + (payload.afkEnabled() ? "on" : "off")
                + " afkPlayers=" + payload.afkPlayers()
                + "/" + payload.afkTrackedPlayers(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "afk cfg after=" + payload.afkAfterTicks()
                + "t radius=" + payload.afkRadiusBlocks()
                + " v=" + payload.afkVerticalRadiusCubes()
                + " target=" + payload.afkTargetStatus()
                + " jobs=" + payload.afkJobsStarted()
                + " reason=" + payload.afkLastReason(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "stream h=" + payload.streamHorizontalRadius()
                + " v=" + payload.streamVerticalRadius()
                + " speed=" + payload.maxMaterializedCubesPerTick()
                + "/t sync=" + payload.syncPacketIntervalTicks()
                + "t", MUTED);
        y += 10;
        ClientCubeSectionStore.SnapshotStats sectionStats = ClientCubeSectionStore.stats();
        draw(graphics, minecraft, x, y, "nativeSectionStore sections=" + sectionStats.sections()
                + " recv=" + sectionStats.receivedSnapshots()
                + " replaced=" + sectionStats.replacedSnapshots()
                + " unload=" + sectionStats.unloads()
                + " bytes~=" + sectionStats.receivedBytesEstimate(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "writes player=" + payload.playerWritesSaved()
                + " ignoredMat=" + payload.materializerWritesIgnored()
                + " cmd=" + payload.commandWritesSaved(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "nearest cube entries: x y z | status/state/ticket/client | hash | BL/SKY | nm", MUTED);
        y += 10;

        int shown = 0;
        for (CubeClientSyncPayload.Entry entry : payload.entries()) {
            if (shown++ >= 8) {
                break;
            }
            String line = String.format(
                    "%d %d %d | %s/%s/%s/%s | %016x | B%d/%d/%d S%d/%d/%d | %s%s%s",
                    entry.cubeX(),
                    entry.cubeY(),
                    entry.cubeZ(),
                    enumName(CubeStatus.values(), entry.statusOrdinal()),
                    enumName(CubeHolderState.values(), entry.holderStateOrdinal()),
                    enumName(CubeTicketLevel.values(), entry.ticketLevelOrdinal()),
                    enumName(CubeClientStage.values(), entry.clientStageOrdinal()),
                    entry.hash(),
                    entry.maxBlockLight(),
                    entry.litBlocks(),
                    entry.emittingBlocks(),
                    entry.maxSkyLight(),
                    entry.skyLitBlocks(),
                    entry.bottomSkyLitBlocks(),
                    entry.nativeReady() ? "N" : "-",
                    entry.materialized() ? "M" : "-",
                    entry.dirty() ? " dirty" : ""
            );
            draw(graphics, minecraft, x, y, line, entry.materialized() ? GOOD : (entry.nativeReady() ? TEXT : WARN));
            y += 10;
        }
    }

    private static String pregenState(CubeClientSyncPayload payload) {
        if (payload.pregenRunning()) {
            return payload.pregenPaused() ? "paused" : "running";
        }
        return "idle";
    }

    private static void draw(GuiGraphicsExtractor graphics, Minecraft minecraft, int x, int y, String text, int color) {
        graphics.text(minecraft.font, Component.literal(text), x, y, color, true);
    }

    private static String enumName(Enum<?>[] values, int ordinal) {
        if (ordinal < 0 || ordinal >= values.length) {
            return "?" + ordinal;
        }
        return values[ordinal].name();
    }
}
