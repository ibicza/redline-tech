package com.redline.worldcore.server.ticket;

import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.ticket.CubeTicket;
import com.redline.worldcore.api.ticket.CubeTicketShape;

public final class CubeTicketDebugFormatter {
    public static String shortId(CubeTicket ticket) {
        return ticket.id().toString().substring(0, 8);
    }

    public static String formatTicket(CubeTicket ticket) {
        CubeTicketShape shape = ticket.shape();
        return shortId(ticket)
                + " type=" + ticket.type()
                + " level=" + ticket.level()
                + " shape=" + shape.type()
                + " min=" + formatCube(shape.min())
                + " max=" + formatCube(shape.max())
                + " cubes=" + shape.cubeCount()
                + " ttl=" + (ticket.isPermanent() ? "permanent" : ticket.ttlTicks())
                + " owner=" + ticket.ownerDescription();
    }

    public static String formatCube(CubePos cubePos) {
        return cubePos.x() + " " + cubePos.y() + " " + cubePos.z();
    }

    private CubeTicketDebugFormatter() {
    }
}
