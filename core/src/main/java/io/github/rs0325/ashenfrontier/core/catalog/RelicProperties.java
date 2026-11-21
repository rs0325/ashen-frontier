package io.github.rs0325.ashenfrontier.core.catalog;

import com.mojang.serialization.Codec;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 品質はNatureProperties.QUALITYを共有し、量はkg、試薬と資料は名前空間付きIDで保持する。 */
public final class RelicProperties {
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(AshenFrontierCore.NAMESPACE);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Double>> CONTENT_MASS = COMPONENTS.registerComponentType(
            "content_mass_kg", builder -> builder.persistent(Codec.doubleRange(0, Double.MAX_VALUE)).networkSynchronized(ByteBufCodecs.DOUBLE));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> REAGENT = COMPONENTS.registerComponentType(
            "reagent", builder -> builder.persistent(ResourceLocation.CODEC).networkSynchronized(ResourceLocation.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> DOCUMENT = COMPONENTS.registerComponentType(
            "document", builder -> builder.persistent(ResourceLocation.CODEC).networkSynchronized(ResourceLocation.STREAM_CODEC));

    private RelicProperties() {}

    public static void register(IEventBus bus) { COMPONENTS.register(bus); }

    /** 配置・ルート用の密封容器を作る。qualityはBの水/油ID、試薬ID。量はkgで、流体のmBとは換算しない。 */
    public static ItemStack container(String id, ResourceLocation quality, double massKg) {
        if (!RelicCatalog.require(id).behavior().equals("sealed_container") || !Double.isFinite(massKg) || massKg <= 0) {
            throw new IllegalArgumentException("密封容器のIDまたは内容量が不正");
        }
        if (id.equals("sealed_water_container") || id.equals("sealed_oil_container")) {
            String kind = id.equals("sealed_water_container") ? "water" : "oil";
            if (!quality.getNamespace().equals(AshenFrontierCore.NAMESPACE)
                    || !NatureCatalog.require(quality.getPath()).kind().equals(kind)) {
                throw new IllegalArgumentException("容器と品質の種類が不一致");
            }
        }
        var stack = new ItemStack(RelicContent.item(id));
        stack.set(NatureProperties.QUALITY, quality);
        stack.set(CONTENT_MASS, massKg);
        if (id.equals("sealed_reagent_container")) stack.set(REAGENT, quality);
        return stack;
    }

    /** 資料IDを持つ配布用ItemStackを作る。読む際に所属チームの知識へ記録される。 */
    public static ItemStack document(ResourceLocation id) {
        var stack = new ItemStack(RelicContent.item("archive_document"));
        stack.set(DOCUMENT, id);
        return stack;
    }
}
