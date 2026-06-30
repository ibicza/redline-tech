package com.ibicza.redlinetech.registry;

import com.ibicza.redlinetech.RedlineTech;
import com.ibicza.redlinetech.effect.ChemicalBurnEffect;
import com.ibicza.redlinetech.effect.OilCoatedEffect;
import com.ibicza.redlinetech.effect.RadiationEffect;
import com.ibicza.redlinetech.effect.ToxicExposureEffect;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEffects {
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, RedlineTech.MOD_ID);

    public static final Holder<MobEffect> RADIATION =
            EFFECTS.register("radiation", RadiationEffect::new);

    public static final Holder<MobEffect> CHEMICAL_BURN =
            EFFECTS.register("chemical_burn", ChemicalBurnEffect::new);

    public static final Holder<MobEffect> TOXIC_EXPOSURE =
            EFFECTS.register("toxic_exposure", ToxicExposureEffect::new);

    public static final Holder<MobEffect> OIL_COATED =
            EFFECTS.register("oil_coated", OilCoatedEffect::new);

    private ModEffects() {
    }
}