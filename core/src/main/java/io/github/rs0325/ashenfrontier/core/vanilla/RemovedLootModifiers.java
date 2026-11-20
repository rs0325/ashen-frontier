package io.github.rs0325.ashenfrontier.core.vanilla;

import com.mojang.serialization.MapCodec;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.data.GlobalLootModifierProvider;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.concurrent.CompletableFuture;

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
        modEventBus.addListener(RemovedLootModifiers::gatherData);
    }

    private static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(event.includeServer(),
                new Provider(event.getGenerator().getPackOutput(), event.getLookupProvider()));
    }

    private static final class Provider extends GlobalLootModifierProvider {
        private Provider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, registries, AshenFrontierCore.MOD_ID);
        }

        @Override
        protected void start() {
            add("remove_tagged_items", new RemovedLootModifier(new LootItemCondition[0]));
        }
    }
}
