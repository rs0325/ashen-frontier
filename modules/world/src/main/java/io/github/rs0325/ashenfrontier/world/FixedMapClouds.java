package io.github.rs0325.ashenfrontier.world;

import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;

/** 固定マップだけ雲を峰の上へ移す。空と霧はOverworldの標準効果を使う。 */
@EventBusSubscriber(modid = AshenFrontierWorld.MOD_ID, value = Dist.CLIENT)
public final class FixedMapClouds extends DimensionSpecialEffects {
    private final DimensionSpecialEffects delegate = new DimensionSpecialEffects.OverworldEffects();

    private FixedMapClouds() { super(552, true, SkyType.NORMAL, false, false); }

    @SubscribeEvent
    public static void register(RegisterDimensionSpecialEffectsEvent event) {
        event.register(ResourceLocation.fromNamespaceAndPath("ashen_frontier", "fixed_map"), new FixedMapClouds());
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 color, float sunHeight) {
        return delegate.getBrightnessDependentFogColor(color,sunHeight);
    }

    @Override
    public boolean isFoggyAt(int x, int z) { return delegate.isFoggyAt(x,z); }
}
