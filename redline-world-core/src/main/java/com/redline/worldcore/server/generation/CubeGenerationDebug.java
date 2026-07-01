package com.redline.worldcore.server.generation;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.cube.CubeHolder;
import com.redline.worldcore.server.cube.ServerCubeCache;

import java.util.Optional;

/** Helpers used only by debug/admin commands around the M7 generator. */
public final class CubeGenerationDebug {
    private final ServerCubeCache cache;

    public CubeGenerationDebug(ServerCubeCache cache) {
        this.cache = java.util.Objects.requireNonNull(cache, "cache");
    }

    public LevelCube generated(CubePos cubePos) {
        return cache.generateTemporary(cubePos);
    }

    public LevelCube cacheOrGenerated(CubePos cubePos) {
        Optional<CubeHolder> holder = cache.holder(cubePos);
        return holder.map(CubeHolder::cube).orElseGet(() -> generated(cubePos));
    }

    public CubeGenerationSummary summary(CubePos cubePos) {
        return CubeGenerationSummary.from(cacheOrGenerated(cubePos));
    }

    public VerifyResult verify(CubePos cubePos) {
        CubeGenerationSummary first = CubeGenerationSummary.from(generated(cubePos));
        CubeGenerationSummary second = CubeGenerationSummary.from(generated(cubePos));
        return new VerifyResult(cubePos, first, second, first.sameGeneratedData(second));
    }

    public Optional<VerifyResult> verifyLoaded(CubePos cubePos) {
        Optional<CubeHolder> holder = cache.holder(cubePos);
        if (holder.isEmpty()) {
            return Optional.empty();
        }
        CubeGenerationSummary loaded = CubeGenerationSummary.from(holder.get().cube());
        CubeGenerationSummary generated = CubeGenerationSummary.from(generated(cubePos));
        return Optional.of(new VerifyResult(cubePos, loaded, generated, loaded.sameGeneratedData(generated)));
    }

    public record VerifyResult(CubePos cubePos, CubeGenerationSummary first, CubeGenerationSummary second, boolean passed) {
    }
}
