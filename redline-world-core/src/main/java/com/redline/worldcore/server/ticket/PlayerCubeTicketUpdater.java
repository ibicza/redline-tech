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
import java.util.UUID;

public final class PlayerCubeTicketUpdater {
    private static final int PLAYER_TICKET_TTL_TICKS = 40;

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.level().isClientSide()) {
            return;
        }
        if (!CubicDimensionKeys.isCubicTest(player.level())) {
            return;
        }
        WorldCoreTickets.MANAGER.add(createPlayerTicket(player));
    }

    public static CubeTicket createPlayerTicket(ServerPlayer player) {
        CubicDimensionSettings settings = CubicTestDimensionService.SETTINGS;
        CubePos center = CubePos.fromBlock(player.blockPosition());
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

    private PlayerCubeTicketUpdater() {
    }
}
