package com.redline.worldcore.api.ticket;

import java.util.Objects;
import java.util.UUID;

public record CubeTicket(
        UUID id,
        CubeTicketType type,
        CubeTicketLevel level,
        CubeTicketShape shape,
        int ttlTicks,
        String ownerDescription
) {
    public CubeTicket {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(shape, "shape");
        if (ttlTicks < 0) {
            throw new IllegalArgumentException("ttlTicks must be >= 0");
        }
        ownerDescription = ownerDescription == null ? "unknown" : ownerDescription;
    }

    public static CubeTicket permanent(CubeTicketType type, CubeTicketLevel level, CubeTicketShape shape, String ownerDescription) {
        return new CubeTicket(UUID.randomUUID(), type, level, shape, 0, ownerDescription);
    }

    public static CubeTicket temporary(CubeTicketType type, CubeTicketLevel level, CubeTicketShape shape, int ttlTicks, String ownerDescription) {
        if (ttlTicks <= 0) {
            throw new IllegalArgumentException("temporary ticket ttlTicks must be > 0");
        }
        return new CubeTicket(UUID.randomUUID(), type, level, shape, ttlTicks, ownerDescription);
    }

    public boolean isPermanent() {
        return ttlTicks == 0;
    }

    public CubeTicket tickTtl() {
        if (isPermanent()) {
            return this;
        }
        return new CubeTicket(id, type, level, shape, Math.max(0, ttlTicks - 1), ownerDescription);
    }

    public boolean expired() {
        return !isPermanent() && ttlTicks <= 0;
    }
}
