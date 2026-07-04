package com.redline.worldcore.server.cube;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.ticket.CubeTicketLevel;
import com.redline.worldcore.server.generation.CubeGenerationHasher;

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
    private boolean generationHashValid;
    private long generationHash;
    private CubeClientStage clientStage = CubeClientStage.CUBE_NATIVE_READY;
    private long clientNativeHash = Long.MIN_VALUE;
    private long vanillaShellHash = Long.MIN_VALUE;

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

    public CubeClientStage clientStage() {
        return clientStage;
    }

    public boolean clientNativeReady(long hash) {
        return clientNativeHash == hash && clientStage.ordinal() >= CubeClientStage.CLIENT_NATIVE_READY.ordinal();
    }

    public boolean vanillaShellReady(long hash) {
        return vanillaShellHash == hash && clientStage == CubeClientStage.VANILLA_SHELL_READY;
    }

    public void markClientNativeReady(long hash) {
        this.clientNativeHash = hash;
        if (clientStage.ordinal() < CubeClientStage.CLIENT_NATIVE_READY.ordinal()) {
            clientStage = CubeClientStage.CLIENT_NATIVE_READY;
        }
    }

    public void markVanillaShellQueued() {
        if (clientStage != CubeClientStage.VANILLA_SHELL_READY) {
            clientStage = CubeClientStage.VANILLA_SHELL_QUEUED;
        }
    }

    public void markVanillaShellMaterializing() {
        if (clientStage != CubeClientStage.VANILLA_SHELL_READY) {
            clientStage = CubeClientStage.VANILLA_SHELL_MATERIALIZING;
        }
    }

    public void markVanillaShellReady(long hash) {
        this.vanillaShellHash = hash;
        this.clientNativeHash = hash;
        this.clientStage = CubeClientStage.VANILLA_SHELL_READY;
    }

    public void invalidateClientViews() {
        this.clientNativeHash = Long.MIN_VALUE;
        this.vanillaShellHash = Long.MIN_VALUE;
        this.clientStage = CubeClientStage.CUBE_NATIVE_READY;
    }

    /**
     * Cached block-data hash for client mirror checks. Computing CubeGenerationSummary.from(cube).hash() scans
     * all 4096 blocks; the client bridge used to do that for every visible cube every tick.
     */
    public long generationHash() {
        if (!generationHashValid) {
            generationHash = CubeGenerationHasher.hash(cube);
            generationHashValid = true;
        }
        return generationHash;
    }

    public void invalidateGenerationHash() {
        generationHashValid = false;
    }

    public void markRequired(CubeTicketLevel level, long gameTime) {
        this.ticketLevel = Objects.requireNonNull(level, "level");
        this.lastRequiredGameTime = gameTime;
    }

    public void markDirty() {
        this.dirty = true;
        invalidateGenerationHash();
        invalidateClientViews();
    }

    public void markSaved(CubeHolderState savedState) {
        this.state = Objects.requireNonNull(savedState, "savedState");
        this.dirty = false;
    }

    public long ticksSinceRequired(long gameTime) {
        return Math.max(0L, gameTime - lastRequiredGameTime);
    }
}
