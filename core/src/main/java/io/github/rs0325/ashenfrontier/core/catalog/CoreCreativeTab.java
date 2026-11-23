package io.github.rs0325.ashenfrontier.core.catalog;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import java.util.Comparator;
import java.util.HashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** カタログの種類ごとに、自Modの登録済みアイテムをまとめる。 */
public final class CoreCreativeTab {
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AshenFrontierCore.NAMESPACE);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CONTENTS = TABS.register("contents",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ashen_frontier.contents"))
                    .icon(() -> NatureContent.item("gneiss").getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        var groups = new HashMap<String, String>();
                        NatureCatalog.entries().forEach(entry -> groups.put(entry.id(), entry.kind()));
                        RelicCatalog.entries().forEach(entry -> groups.put(entry.id(), "relic_" + entry.kind()));
                        BuiltInRegistries.ITEM.stream()
                                .filter(item -> BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(AshenFrontierCore.NAMESPACE))
                                .sorted(Comparator.comparing((net.minecraft.world.item.Item item) ->
                                                groups.getOrDefault(BuiltInRegistries.ITEM.getKey(item).getPath(), "other"))
                                        .thenComparing(item -> BuiltInRegistries.ITEM.getKey(item).getPath()))
                                .forEach(output::accept);
                    }).build());

    private CoreCreativeTab() {}

    public static void register(IEventBus bus) { TABS.register(bus); }
}
