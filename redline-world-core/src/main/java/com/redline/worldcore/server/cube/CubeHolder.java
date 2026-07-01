package com.redline.worldcore.server.cube;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.ticket.CubeTicketLevel;

import java.util.Objects;

/**
 * M6 runtime holder for one 16x16x16 cube.
 *
 * <p>This is deliberately independent from vanilla ChunkHolder. Vanilla chunks are still only the compatibility shell;
 * this holder tracks the cube-first backend state that later M7/M8/M9 systems will extend with generation, sync and light.</p>
 */
public final class CubeHolder {
    private final CubePos cubePos;
    private final LevelCube cube;
    private final long loadedGameTime;
    private CubeTicketLevel ticketLevel;
    private CubeHolderState state;
    private long lastRequiredGameTime;
    private boolean dirty;

    public CubeHolder(CubePos cubePos, LevelCube cube, CubeTicketLevel ticketLevel, CubeHolderState state, long loadedGameTime) {
        this.cubePos = Objects.requireNonNull(cubePos, "cubePos");
        this.cube = Objects.requireNonNull(cube, "cube");
        this.ticketLevel = Objects.requireNonNull(ticketLevel, "ticketLevel");
        this.state = Objects.requireNonNull(state, "state");
        this.loadedGameTime = loadedGameTime;
        this.lastRequiredGameTime = loadedGameTime;
    }

    public CubePos cubePos() {
        return cubePos;
    }

    public LevelCube cube() {
        return cube;
    }

    public CubeTicketLevel ticketLevel() {
        return ticketLevel;
    }

    public CubeHolderState state() {
        return state;
    }

    public long loadedGameTime() {
        return loadedGameTime;
    }

    public long lastRequiredGameTime() {
        return lastRequiredGameTime;
    }

    public boolean dirty() {
        return dirty;
    }

    public void markRequired(CubeTicketLevel level, long gameTime) {
        this.ticketLevel = Objects.requireNonNull(level, "level");
        this.lastRequiredGameTime = gameTime;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void markSaved(CubeHolderState savedState) {
        this.state = Objects.requireNonNull(savedState, "savedState");
        this.dirty = false;
    }

    public long ticksSinceRequired(long gameTime) {
        return Math.max(0L, gameTime - lastRequiredGameTime);
    }
}
