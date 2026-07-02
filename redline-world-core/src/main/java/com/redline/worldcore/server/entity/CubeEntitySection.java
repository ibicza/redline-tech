package com.redline.worldcore.server.entity;

import com.redline.worldcore.api.pos.CubePos;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime entity index for one 16x16x16 cube.
 *
 * <p>M12 deliberately does not own or tick entities yet. The section is an index over vanilla entities that are already
 * alive in the cubic test dimension. Later M12.x work can use the same sections to decide ENTITY_TICKING/BORDER levels
 * without first guessing where entities are.</p>
 */
public final class CubeEntitySection {
    private final CubePos cubePos;
    private final Map<Integer, EntityRef> entitiesById = new LinkedHashMap<>();

    public CubeEntitySection(CubePos cubePos) {
        this.cubePos = cubePos;
    }

    public CubePos cubePos() {
        return cubePos;
    }

    public int size() {
        return entitiesById.size();
    }

    public boolean isEmpty() {
        return entitiesById.isEmpty();
    }

    public void put(EntityRef ref) {
        entitiesById.put(ref.entityId(), ref);
    }

    public EntityRef remove(int entityId) {
        return entitiesById.remove(entityId);
    }

    public Collection<EntityRef> entities() {
        return Collections.unmodifiableCollection(entitiesById.values());
    }
}
