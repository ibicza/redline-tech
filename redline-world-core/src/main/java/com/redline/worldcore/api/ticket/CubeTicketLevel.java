package com.redline.worldcore.api.ticket;

public enum CubeTicketLevel {
    UNLOADED,
    STORAGE,
    GENERATED,
    LIGHT_READY,
    BORDER,
    BLOCK_TICKING,
    ENTITY_TICKING,
    FULL,
    GENERATION_WORKER,
    LIGHT_WORKER;

    public boolean isAtLeast(CubeTicketLevel other) {
        return ordinal() >= other.ordinal();
    }
}
