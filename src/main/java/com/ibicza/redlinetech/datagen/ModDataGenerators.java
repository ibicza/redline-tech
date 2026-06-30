package com.ibicza.redlinetech.datagen;


import com.ibicza.redlinetech.RedlineTech;
import net.neoforged.neoforge.data.event.GatherDataEvent;

public final class ModDataGenerators {
    public static void gatherData(GatherDataEvent.Client event) {
        RedlineTech.LOGGER.info("Redline Tech datagen event fired");

        event.createProvider(RedlineGeneratedResourcesProvider::new);

        RedlineTech.LOGGER.info("Redline Tech generated resources provider registered");
    }

    private ModDataGenerators() {
    }
}
