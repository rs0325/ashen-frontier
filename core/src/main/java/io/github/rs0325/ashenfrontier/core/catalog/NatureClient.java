package io.github.rs0325.ashenfrontier.core.catalog;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

import java.util.Map;

@EventBusSubscriber(modid = AshenFrontierCore.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class NatureClient {
    private static final ResourceLocation WATER_STILL = ResourceLocation.withDefaultNamespace("block/water_still");
    private static final ResourceLocation WATER_FLOW = ResourceLocation.withDefaultNamespace("block/water_flow");
    private static final ResourceLocation WATER_OVERLAY = ResourceLocation.withDefaultNamespace("block/water_overlay");
    private static final Map<String, Integer> TINTS = Map.ofEntries(
            Map.entry("ultrapure_water", 0xeb_b6_de_f0), Map.entry("pure_water", 0xeb_91_c5_e5),
            Map.entry("clean_water", 0xeb_43_8c_c6), Map.entry("raw_water", 0xeb_54_7f_96),
            Map.entry("turbid_water", 0xeb_83_77_59), Map.entry("contaminated_water", 0xeb_6b_7a_67),
            Map.entry("toxic_water", 0xeb_68_75_48), Map.entry("polluted_seawater", 0xeb_43_67_60));

    private NatureClient() {}

    @SubscribeEvent
    public static void extensions(RegisterClientExtensionsEvent event) {
        NatureContent.fluids().forEach((id, fluid) -> {
            boolean oil = NatureCatalog.require(id).kind().equals("oil");
            event.registerFluidType(new IClientFluidTypeExtensions() {
                @Override
                public ResourceLocation getStillTexture() {
                    return oil
                            ? ResourceLocation.fromNamespaceAndPath(AshenFrontierCore.NAMESPACE, "block/" + id + "_still") : WATER_STILL;
                }

                @Override
                public ResourceLocation getFlowingTexture() {
                    return oil
                            ? ResourceLocation.fromNamespaceAndPath(AshenFrontierCore.NAMESPACE, "block/" + id + "_flow") : WATER_FLOW;
                }

                @Override
                public ResourceLocation getOverlayTexture() {
                    return oil ? null : WATER_OVERLAY;
                }

                @Override
                public int getTintColor() {
                    return oil ? 0xffffffff : TINTS.get(id);
                }
            }, fluid.type().get());
        });
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> NatureContent.fluids().forEach((id, fluid) -> {
            var layer = NatureCatalog.require(id).kind().equals("oil") ? RenderType.solid() : RenderType.translucent();
            ItemBlockRenderTypes.setRenderLayer(fluid.source().get(), layer);
            ItemBlockRenderTypes.setRenderLayer(fluid.flowing().get(), layer);
        }));
    }
}
