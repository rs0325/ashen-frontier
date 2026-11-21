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

@EventBusSubscriber(modid = AshenFrontierCore.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class NatureClient {
    private NatureClient() {}

    @SubscribeEvent
    public static void extensions(RegisterClientExtensionsEvent event) {
        NatureContent.fluids().forEach((id, fluid) -> event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override
            public ResourceLocation getStillTexture() {
                return AshenFrontierCore.id("block/" + id + "_still");
            }

            @Override
            public ResourceLocation getFlowingTexture() {
                return AshenFrontierCore.id("block/" + id + "_flow");
            }
        }, fluid.type().get()));
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> NatureContent.fluids().values().forEach(fluid -> {
            ItemBlockRenderTypes.setRenderLayer(fluid.source().get(), RenderType.translucent());
            ItemBlockRenderTypes.setRenderLayer(fluid.flowing().get(), RenderType.translucent());
        }));
    }
}
