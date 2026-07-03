package com.redline.worldcore.server.cube.ownership;

import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.ticket.CubeTicketLevel;
import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.server.cube.CubeHolderState;
import com.redline.worldcore.server.cube.dirty.CubeContentSummary;

/** M14.9 one-cube ownership consistency report used before entering the worldgen stage. */
public record CubeOwnershipValidationSnapshot(
        CubePos cubePos,
        boolean holderLoaded,
        boolean dirty,
        CubeStatus status,
        CubeHolderState holderState,
        CubeTicketLevel ticketLevel,
        int blockEntities,
        int scheduledBlockTicks,
        int scheduledFluidTicks,
        boolean clientSyncDirty,
        CubeContentSummary contentSummary,
        boolean ok,
        String problem
) {
    public CubeOwnershipValidationSnapshot {
        problem = problem == null ? "none" : problem;
    }

    public static CubeOwnershipValidationSnapshot missing(CubePos cubePos) {
        return new CubeOwnershipValidationSnapshot(cubePos, false, false, CubeStatus.EMPTY, null,
                CubeTicketLevel.UNLOADED, 0, 0, 0, false, CubeContentSummary.empty(), true, "holder_not_loaded");
    }

    public String oneLine() {
        return "cube=" + cubePos.x() + " " + cubePos.y() + " " + cubePos.z()
                + ", loaded=" + holderLoaded
                + ", dirty=" + dirty
                + ", status=" + status
                + ", holder=" + holderState
                + ", ticket=" + ticketLevel
                + ", be=" + blockEntities
                + ", blockTicks=" + scheduledBlockTicks
                + ", fluidTicks=" + scheduledFluidTicks
                + ", clientDirty=" + clientSyncDirty
                + ", ok=" + ok
                + ", problem=" + problem
                + ", content=" + contentSummary.compact();
    }
}
