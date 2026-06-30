package com.ibicza.redlinetech.event;

import com.ibicza.redlinetech.RedlineTech;
import com.ibicza.redlinetech.content.block.RedlineGasBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

@EventBusSubscriber(modid = RedlineTech.MOD_ID)
public final class RedlineGasInteractionEvents {
    @SubscribeEvent
    public static void cancelGasLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().getBlockState(event.getPos()).getBlock() instanceof RedlineGasBlock) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void cancelGasBreak(BreakBlockEvent event) {
        if (event.getState().getBlock() instanceof RedlineGasBlock) {
            event.setCanceled(true);
            event.setNotifyClient(true);
        }
    }

    private RedlineGasInteractionEvents() {
    }
}