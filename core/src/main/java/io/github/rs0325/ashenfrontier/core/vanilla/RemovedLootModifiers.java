package io.github.rs0325.ashenfrontier.core.vanilla;

import com.mojang.serialization.MapCodec;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class RemovedLootModifiers {
    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS,
                    AshenFrontierCore.MOD_ID);

    static {
        SERIALIZERS.register("removed", () -> RemovedLootModifier.CODEC);
    }

    private RemovedLootModifiers() {}

    public static void register(IEventBus modEventBus) {
        SERIALIZERS.register(modEventBus);
    }
}
