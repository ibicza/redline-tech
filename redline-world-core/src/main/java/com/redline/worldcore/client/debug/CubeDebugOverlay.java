package com.redline.worldcore.client.debug;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.ticket.CubeTicketLevel;
import com.redline.worldcore.client.sync.ClientCubeSyncState;
import com.redline.worldcore.network.CubeClientSyncPayload;
import com.redline.worldcore.server.compat.CubicClientSyncBridge;
import com.redline.worldcore.server.cube.CubeHolderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** M8/M8.1 HUD overlay fed by the server cube sync payload. */
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
        int width = 272;
        int lines = 4;
        graphics.fill(x - 3, y - 3, x + width, y + lines * 10 + 5, BACKGROUND);
        draw(graphics, minecraft, x, y, "RWC M10.1 compact", GOOD);
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
        draw(graphics, minecraft, x, y, "stream h=" + payload.streamHorizontalRadius()
                + " v=" + payload.streamVerticalRadius()
                + " speed=" + payload.maxMaterializedCubesPerTick()
                + "/t", MUTED);
    }

    private static void renderFull(GuiGraphicsExtractor graphics, Minecraft minecraft, CubeClientSyncPayload payload) {
        int x = 6;
        int y = 6;
        int width = 374;
        int shownEntries = Math.min(8, payload.entries().size());
        int lines = shownEntries + 10;
        graphics.fill(x - 3, y - 3, x + width, y + lines * 10 + 5, BACKGROUND);

        draw(graphics, minecraft, x, y, "Redline World Core M10.1 sync/light", GOOD);
        y += 10;
        draw(graphics, minecraft, x, y, "playerCube=" + payload.playerCubeX() + " " + payload.playerCubeY() + " " + payload.playerCubeZ()
                + "  cache loaded=" + payload.loadedCubes() + " pending=" + payload.pendingLoads() + " requested=" + payload.requestedCubes(), TEXT);
        y += 10;
        draw(graphics, minecraft, x, y, "generatedTotal=" + payload.totalGenerated()
                + "  materialized=" + payload.materializedCubes()
                + " queue=" + payload.queuedMaterializations()
                + " lastTick=" + payload.materializedLastTick(), TEXT);
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
        draw(graphics, minecraft, x, y, "stream h=" + payload.streamHorizontalRadius()
                + " v=" + payload.streamVerticalRadius()
                + " speed=" + payload.maxMaterializedCubesPerTick()
                + "/t sync=" + payload.syncPacketIntervalTicks()
                + "t", MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "writes player=" + payload.playerWritesSaved()
                + " ignoredMat=" + payload.materializerWritesIgnored()
                + " cmd=" + payload.commandWritesSaved(), MUTED);
        y += 10;
        draw(graphics, minecraft, x, y, "nearest cube entries: x y z | status/state/ticket | hash | BL/SKY | m", MUTED);
        y += 10;

        int shown = 0;
        for (CubeClientSyncPayload.Entry entry : payload.entries()) {
            if (shown++ >= 8) {
                break;
            }
            String line = String.format(
                    "%d %d %d | %s/%s/%s | %016x | B%d/%d/%d S%d/%d/%d | %s%s",
                    entry.cubeX(),
                    entry.cubeY(),
                    entry.cubeZ(),
                    enumName(CubeStatus.values(), entry.statusOrdinal()),
                    enumName(CubeHolderState.values(), entry.holderStateOrdinal()),
                    enumName(CubeTicketLevel.values(), entry.ticketLevelOrdinal()),
                    entry.hash(),
                    entry.maxBlockLight(),
                    entry.litBlocks(),
                    entry.emittingBlocks(),
                    entry.maxSkyLight(),
                    entry.skyLitBlocks(),
                    entry.bottomSkyLitBlocks(),
                    entry.materialized() ? "M" : "-",
                    entry.dirty() ? " dirty" : ""
            );
            draw(graphics, minecraft, x, y, line, entry.materialized() ? GOOD : WARN);
            y += 10;
        }
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
