package com.redline.worldcore.server.ticket;

import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.ticket.CubeTicket;
import com.redline.worldcore.api.ticket.CubeTicketLevel;
import com.redline.worldcore.api.ticket.CubeTicketShape;
import com.redline.worldcore.api.ticket.CubeTicketType;
import com.redline.worldcore.server.dimension.CubicTestDimensionService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerCubeTicketUpdater {
    private static final int PLAYER_TICKET_TTL_TICKS = 40;
    private static final int PLAYER_TICKET_REFRESH_INTERVAL_TICKS = 10;
    private static final Map<UUID, PlayerTicketState> PLAYER_TICKET_STATES = new HashMap<>();

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.level().isClientSide()) {
            return;
        }
        if (!CubicDimensionKeys.isCubicTest(player.level())) {
            removePlayerTicket(player);
            return;
        }
        CubePos center = CubePos.fromBlock(player.blockPosition());
        PlayerTicketState state = PLAYER_TICKET_STATES.get(player.getUUID());
        int tick = player.tickCount;
        if (state != null && center.equals(state.center()) && tick < state.nextRefreshTick()) {
            return;
        }
        WorldCoreTickets.MANAGER.add(createPlayerTicket(player, center));
        PLAYER_TICKET_STATES.put(player.getUUID(), new PlayerTicketState(center, tick + PLAYER_TICKET_REFRESH_INTERVAL_TICKS));
    }

    /**
     * Removes the stable player ticket when the player leaves the cubic test dimension.
     * Player tickets are dimension-scoped runtime requests; they must never keep cubic_test loaded from Overworld.
     */
    public static boolean removePlayerTicket(ServerPlayer player) {
        PLAYER_TICKET_STATES.remove(player.getUUID());
        return WorldCoreTickets.MANAGER.remove(stablePlayerTicketId(player));
    }

    public static boolean isCubicTestPlayer(ServerPlayer player) {
        return CubicDimensionKeys.isCubicTest(player.level());
    }

    public static CubeTicket createPlayerTicket(ServerPlayer player) {
        return createPlayerTicket(player, CubePos.fromBlock(player.blockPosition()));
    }

    private static CubeTicket createPlayerTicket(ServerPlayer player, CubePos center) {
        CubicDimensionSettings settings = CubicTestDimensionService.SETTINGS;
        int minCubeY = Math.max(settings.minCubeY(), center.y() - settings.verticalLoadDistance());
        int maxCubeY = Math.min(settings.maxCubeY(), center.y() + settings.verticalLoadDistance());
        CubeTicketShape shape = CubeTicketShape.cuboid(
                new CubePos(center.x() - settings.horizontalLoadDistance(), minCubeY, center.z() - settings.horizontalLoadDistance()),
                new CubePos(center.x() + settings.horizontalLoadDistance(), maxCubeY, center.z() + settings.horizontalLoadDistance())
        );
        return new CubeTicket(
                stablePlayerTicketId(player),
                CubeTicketType.PLAYER,
                CubeTicketLevel.FULL,
                shape,
                PLAYER_TICKET_TTL_TICKS,
                "player:" + player.getScoreboardName()
        );
    }

    private static UUID stablePlayerTicketId(ServerPlayer player) {
        String value = "redline_world_core:player_ticket:" + player.getUUID();
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private record PlayerTicketState(CubePos center, int nextRefreshTick) {
    }

    private PlayerCubeTicketUpdater() {
    }
}
