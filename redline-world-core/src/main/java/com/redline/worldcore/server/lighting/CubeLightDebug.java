package com.redline.worldcore.server.lighting;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.cube.CubeHolder;
import com.redline.worldcore.server.cube.ServerCubeCache;

import java.util.Optional;

/** Small command-facing helper for M9 static light debug commands. */
public final class CubeLightDebug {
    private final ServerCubeCache cache;

    public CubeLightDebug(ServerCubeCache cache) {
        this.cache = cache;
    }

    public LightSourceCube cubeForRead(CubePos cubePos) {
        Optional<CubeHolder> holder = cache.holder(cubePos);
        if (holder.isPresent()) {
            return new LightSourceCube(holder.get().cube(), "cache");
        }
        Optional<LevelCube> persisted = cache.readPersisted(cubePos);
        if (persisted.isPresent()) {
            return new LightSourceCube(persisted.get(), "region3d");
        }
        LevelCube generated = cache.generateTemporary(cubePos);
        StaticBlockLightLayer.rebuild(generated);
        return new LightSourceCube(generated, "temporary-generator");
    }

    public record LightSourceCube(LevelCube cube, String source) {
    }
}
