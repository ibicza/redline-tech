package com.redline.worldcore.server.ticket;

import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.ticket.CubeTicket;
import com.redline.worldcore.api.ticket.CubeTicketLevel;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime MVP ticket registry. Real loading propagation will build on top of this. */
public final class CubeTicketManager {
    private final Map<UUID, CubeTicket> tickets = new ConcurrentHashMap<>();

    public void add(CubeTicket ticket) {
        tickets.put(ticket.id(), ticket);
    }

    public boolean remove(UUID id) {
        return tickets.remove(id) != null;
    }

    public Collection<CubeTicket> allTickets() {
        return tickets.values();
    }

    public Optional<CubeTicketLevel> strongestLevelFor(CubePos cubePos) {
        return tickets.values().stream()
                .filter(ticket -> ticket.shape().contains(cubePos))
                .map(CubeTicket::level)
                .max(Comparator.comparingInt(Enum::ordinal));
    }

    public int tickTtlAndRemoveExpired() {
        int removed = 0;
        for (CubeTicket ticket : tickets.values().toArray(CubeTicket[]::new)) {
            if (ticket.isPermanent()) {
                continue;
            }
            CubeTicket next = ticket.tickTtl();
            if (next.expired()) {
                tickets.remove(ticket.id());
                removed++;
            } else {
                tickets.put(ticket.id(), next);
            }
        }
        return removed;
    }
}
