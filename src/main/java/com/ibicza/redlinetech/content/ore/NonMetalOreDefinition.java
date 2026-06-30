package com.ibicza.redlinetech.content.ore;

import com.ibicza.redlinetech.content.block.MiningTier;
import com.ibicza.redlinetech.content.block.MiningTool;
import com.ibicza.redlinetech.content.worldgen.OreWorldgenProfile;

public record NonMetalOreDefinition(
        String id,
        String ruName,
        String enName,
        String materialId,
        NonMetalDropStyle dropStyle,
        float hardness,
        float resistance,
        MiningTool tool,
        MiningTier tier,
        OreWorldgenProfile worldgen,
        String baseTexture,
        String overlayTexture,
        int color,
        boolean hasStoneVariant,
        boolean hasDeepslateVariant
) implements OreLikeDefinition {
}
