package com.redline.worldcore.api.lighting;

import net.minecraft.world.phys.Vec3;

public interface DynamicLightSource {
    Vec3 position();

    int lightLevel();

    double radius();

    boolean isActive();

    DynamicLightType type();
}
