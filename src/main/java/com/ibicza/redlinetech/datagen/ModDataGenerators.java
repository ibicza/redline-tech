package com.ibicza.redlinetech.datagen;


import com.ibicza.redlinetech.RedlineTech;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

@EventBusSubscriber(modid = RedlineTech.MOD_ID)
public final class ModDataGenerators {
    @SubscribeEvent
    public static void gatherData(GatherDataEvent.Client event) {
        event.createProvider(RedlineGeneratedResourcesProvider::new);
    }

    private ModDataGenerators() {
    }
}
