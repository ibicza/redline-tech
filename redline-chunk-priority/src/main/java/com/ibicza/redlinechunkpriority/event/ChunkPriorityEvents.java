package com.ibicza.redlinechunkpriority.event;

import com.ibicza.redlinechunkpriority.RedlineChunkPriority;
import com.ibicza.redlinechunkpriority.command.ChunkPriorityCommands;
import com.ibicza.redlinechunkpriority.core.ChunkPriorityManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = RedlineChunkPriority.MOD_ID)
public final class ChunkPriorityEvents {
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ChunkPriorityManager.tick(event.getServer(), event.hasTime());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        ChunkPriorityCommands.register(event.getDispatcher());
    }

    private ChunkPriorityEvents() {
    }
}
