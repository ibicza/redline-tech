package com.redline.worldcore.server.cube.blockentity;

import com.redline.worldcore.api.pos.CubePos;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Block entity runtime section for one 16x16x16 cube. */
public final class CubeBlockEntitySection {
    private final CubePos cubePos;
    private final Map<Integer, CubeBlockEntityRef> refs = new LinkedHashMap<>();

    public CubeBlockEntitySection(CubePos cubePos) {
        this.cubePos = cubePos;
    }

    public CubePos cubePos() {
        return cubePos;
    }

    public void put(CubeBlockEntityRef ref) {
        refs.put(ref.localIndex(), ref);
    }

    public Optional<CubeBlockEntityRef> remove(int localIndex) {
        return Optional.ofNullable(refs.remove(localIndex));
    }

    public Optional<CubeBlockEntityRef> get(int localIndex) {
        return Optional.ofNullable(refs.get(localIndex));
    }

    public Collection<CubeBlockEntityRef> refs() {
        return refs.values();
    }

    public int size() {
        return refs.size();
    }

    public boolean isEmpty() {
        return refs.isEmpty();
    }

    public void clear() {
        refs.clear();
    }
}
