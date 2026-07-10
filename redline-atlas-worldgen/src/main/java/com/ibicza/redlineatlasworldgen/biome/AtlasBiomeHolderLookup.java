package com.ibicza.redlineatlasworldgen.biome;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

public final class AtlasBiomeHolderLookup {
    private static final Map<Object, Map<ResourceKey<Biome>, Holder<Biome>>> CACHE = new WeakHashMap<>();

    public static Holder<Biome> find(Object biomeSource, ResourceKey<Biome> key, Holder<Biome> fallback) {
        if (key == null || biomeSource == null) {
            return fallback;
        }

        synchronized (CACHE) {
            Map<ResourceKey<Biome>, Holder<Biome>> holders = CACHE.computeIfAbsent(biomeSource, AtlasBiomeHolderLookup::scan);
            return holders.getOrDefault(key, fallback);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<ResourceKey<Biome>, Holder<Biome>> scan(Object biomeSource) {
        Map<ResourceKey<Biome>, Holder<Biome>> result = new HashMap<>();

        // Main path: public BiomeSource#possibleBiomes(). It exposes every holder this source can return.
        if (biomeSource instanceof BiomeSource source) {
            try {
                for (Holder<Biome> holder : source.possibleBiomes()) {
                    put(result, holder);
                }
            } catch (RuntimeException ignored) {
                // Reflection fallback below.
            }
        }

        // Best-effort fallback for custom/changed biome sources.
        Class<?> type = biomeSource.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(biomeSource);
                    if (value == null) {
                        continue;
                    }

                    if (value.getClass().getName().contains("Climate$ParameterList")) {
                        readParameterList(value, result);
                        continue;
                    }

                    try {
                        Method valueMethod = value.getClass().getMethod("value");
                        Object inner = valueMethod.invoke(value);
                        if (inner != null && inner.getClass().getName().contains("Climate$ParameterList")) {
                            readParameterList(inner, result);
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // best effort
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // best effort
                }
            }
            type = type.getSuperclass();
        }

        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void readParameterList(Object parameterList, Map<ResourceKey<Biome>, Holder<Biome>> result) {
        try {
            Method valuesMethod = parameterList.getClass().getMethod("values");
            Object valuesObject = valuesMethod.invoke(parameterList);
            if (!(valuesObject instanceof Iterable<?> iterable)) {
                return;
            }

            for (Object entry : iterable) {
                Object holderObject;
                if (entry instanceof Pair<?, ?> pair) {
                    holderObject = pair.getSecond();
                } else {
                    Method second = entry.getClass().getMethod("getSecond");
                    holderObject = second.invoke(entry);
                }

                if (holderObject instanceof Holder<?> holder) {
                    putRaw(result, holder);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // best effort
        }
    }

    private static void put(Map<ResourceKey<Biome>, Holder<Biome>> result, Holder<Biome> holder) {
        Optional<ResourceKey<Biome>> key = holder.unwrapKey();
        key.ifPresent(biomeResourceKey -> result.put(biomeResourceKey, holder));
    }

    @SuppressWarnings("unchecked")
    private static void putRaw(Map<ResourceKey<Biome>, Holder<Biome>> result, Holder<?> holder) {
        Optional<?> key = holder.unwrapKey();
        if (key.isPresent() && key.get() instanceof ResourceKey<?> resourceKey) {
            result.put((ResourceKey<Biome>) resourceKey, (Holder<Biome>) holder);
        }
    }

    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private AtlasBiomeHolderLookup() {
    }
}
