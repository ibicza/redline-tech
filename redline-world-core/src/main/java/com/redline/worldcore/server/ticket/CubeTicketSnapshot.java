package com.redline.worldcore.server.ticket;

import com.redline.worldcore.api.ticket.CubeTicketLevel;
import com.redline.worldcore.api.ticket.CubeTicketType;

import java.util.Map;

public record CubeTicketSnapshot(
        int totalTickets,
        int permanentTickets,
        int temporaryTickets,
        long coveredCubeRequests,
        Map<CubeTicketType, Integer> byType,
        Map<CubeTicketLevel, Integer> byLevel
) {
}
