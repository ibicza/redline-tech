package com.redline.worldcore.api.ticket;

import java.util.Collection;
import java.util.UUID;

public interface CubeTicketSource {
    UUID id();

    CubeTicketType type();

    Collection<CubeTicket> tickets();

    boolean isValid();
}
