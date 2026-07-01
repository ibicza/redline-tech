package com.redline.worldcore.server.ticket;

import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.ticket.CubeTicket;
import com.redline.worldcore.api.ticket.CubeTicketLevel;
import com.redline.worldcore.api.ticket.CubeTicketType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Runtime M5 ticket registry. Real cube loading propagation will build on top of this in M6. */
public final class CubeTicketManager {
    private final Map<UUID, CubeTicket> tickets = new ConcurrentHashMap<>();

    public void add(CubeTicket ticket) {
        tickets.put(ticket.id(), ticket);
    }

    public boolean remove(UUID id) {
        return tickets.remove(id) != null;
    }

    public int removeIf(Predicate<CubeTicket> predicate) {
        int removed = 0;
        for (CubeTicket ticket : sortedTickets()) {
            if (predicate.test(ticket) && tickets.remove(ticket.id()) != null) {
                removed++;
            }
        }
        return removed;
    }

    public int clear() {
        int size = tickets.size();
        tickets.clear();
        return size;
    }

    public int size() {
        return tickets.size();
    }

    public Collection<CubeTicket> allTickets() {
        return tickets.values();
    }

    public List<CubeTicket> sortedTickets() {
        List<CubeTicket> result = new ArrayList<>(tickets.values());
        result.sort(Comparator
                .comparing(CubeTicket::type)
                .thenComparing(CubeTicket::ownerDescription)
                .thenComparing(CubeTicket::level)
                .thenComparing(CubeTicket::id));
        return result;
    }

    public Optional<CubeTicketLevel> strongestLevelFor(CubePos cubePos) {
        return tickets.values().stream()
                .filter(ticket -> ticket.shape().contains(cubePos))
                .map(CubeTicket::level)
                .max(Comparator.comparingInt(Enum::ordinal));
    }

    public List<CubeTicket> ticketsCovering(CubePos cubePos) {
        return sortedTickets().stream()
                .filter(ticket -> ticket.shape().contains(cubePos))
                .toList();
    }

    public CubeTicketSnapshot snapshot() {
        Map<CubeTicketType, Integer> byType = new EnumMap<>(CubeTicketType.class);
        Map<CubeTicketLevel, Integer> byLevel = new EnumMap<>(CubeTicketLevel.class);
        int permanent = 0;
        int temporary = 0;
        long coveredCubeRequests = 0L;

        for (CubeTicket ticket : tickets.values()) {
            byType.merge(ticket.type(), 1, Integer::sum);
            byLevel.merge(ticket.level(), 1, Integer::sum);
            coveredCubeRequests += ticket.shape().cubeCount();
            if (ticket.isPermanent()) {
                permanent++;
            } else {
                temporary++;
            }
        }

        return new CubeTicketSnapshot(tickets.size(), permanent, temporary, coveredCubeRequests, Map.copyOf(byType), Map.copyOf(byLevel));
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
