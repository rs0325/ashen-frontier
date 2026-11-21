package io.github.rs0325.ashenfrontier.core.catalog;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 容器にも引き継ぐ品質プロファイルID。混合・浄化・精製による変更は担当ドメインが決める。 */
public final class NatureProperties {
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(AshenFrontierCore.NAMESPACE);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> QUALITY = COMPONENTS.registerComponentType(
            "quality_profile", builder -> builder.persistent(ResourceLocation.CODEC).networkSynchronized(ResourceLocation.STREAM_CODEC));

    private NatureProperties() {}

    public static void register(IEventBus bus) {
        COMPONENTS.register(bus);
    }

    /** sourceのFluidStackを標準容量単位mBで作る。容器のkgとの換算には別途密度が必要。 */
    public static FluidStack fluidStack(String id, int amount) {
        var stack = new FluidStack(NatureContent.fluids().get(id).source().get(), amount);
        stack.set(QUALITY, AshenFrontierCore.id(id));
        return stack;
    }
}
