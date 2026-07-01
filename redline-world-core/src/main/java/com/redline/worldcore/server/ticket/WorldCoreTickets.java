package com.redline.worldcore.server.ticket;

import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class WorldCoreTickets {
    public static final CubeTicketManager MANAGER = new CubeTicketManager();

    public static void onServerTick(ServerTickEvent.Post event) {
        MANAGER.tickTtlAndRemoveExpired();
    }

    private WorldCoreTickets() {
    }
}
