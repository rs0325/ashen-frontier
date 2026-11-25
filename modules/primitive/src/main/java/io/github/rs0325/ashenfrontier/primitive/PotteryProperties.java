package io.github.rs0325.ashenfrontier.primitive;

import com.mojang.serialization.Codec;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 同じ成形品IDの土鍋/蓋/陶管の形と、乾いた部材の初期成分を登録する。 */
public final class PotteryProperties {
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(AshenFrontierCore.NAMESPACE);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> FORM = COMPONENTS.registerComponentType("pottery_form",
            b -> b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));
    private PotteryProperties() {}
    static void register(IEventBus bus) {
        COMPONENTS.register(bus);
        bus.addListener((net.neoforged.neoforge.event.ModifyDefaultComponentsEvent event) -> {
            for (String id : new String[]{"plant_fiber", "dry_branch", "pebble"}) {
                event.modify(io.github.rs0325.ashenfrontier.core.catalog.NatureContent.item(id), b -> b.set(NatureProperties.MOISTURE.get(), 0.0));
            }
            for (String id : new String[]{"tool_handle", "cordage", "fire_drill", "tinder"}) {
                event.modify(PrimitiveContent.item(id), b -> b.set(NatureProperties.MOISTURE.get(), 0.0));
            }
        });
    }
}
