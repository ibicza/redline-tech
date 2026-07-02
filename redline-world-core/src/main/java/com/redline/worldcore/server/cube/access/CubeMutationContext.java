package com.redline.worldcore.server.cube.access;

import java.util.Objects;

/**
 * Options for the M14.0 cube-first block mutation pipeline.
 *
 * <p>The important boundary is that callers describe intent here, while the cache/pipeline decides how to mutate the
 * cube, mark light/index work dirty and persist the holder. Later mixins can reuse this same context instead of
 * sprinkling save/light/sync side effects around vanilla hooks.</p>
 */
public record CubeMutationContext(
        CubeMutationOrigin origin,
        boolean saveImmediately,
        boolean rebuildStaticLightNow,
        boolean rebuildSkyLightColumnNow,
        boolean markSkyLightDirty,
        boolean generateMissingHolder,
        String reason
) {
    public CubeMutationContext {
        origin = Objects.requireNonNullElse(origin, CubeMutationOrigin.UNKNOWN);
        reason = reason == null || reason.isBlank() ? origin.name().toLowerCase() : reason;
    }

    public static CubeMutationContext playerEdit(boolean saveImmediately) {
        return new CubeMutationContext(
                CubeMutationOrigin.PLAYER_EDIT,
                saveImmediately,
                true,
                false,
                true,
                true,
                "player_edit"
        );
    }

    public static CubeMutationContext command(boolean saveImmediately) {
        return new CubeMutationContext(
                CubeMutationOrigin.COMMAND,
                saveImmediately,
                true,
                true,
                true,
                true,
                "command"
        );
    }

    public static CubeMutationContext internal(String reason) {
        return new CubeMutationContext(
                CubeMutationOrigin.WORLD_CORE_INTERNAL,
                true,
                true,
                false,
                true,
                true,
                reason
        );
    }

    public CubeMutationContext withReason(String newReason) {
        return new CubeMutationContext(origin, saveImmediately, rebuildStaticLightNow, rebuildSkyLightColumnNow,
                markSkyLightDirty, generateMissingHolder, newReason);
    }
}
