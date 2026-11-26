package io.github.rs0325.ashenfrontier.core.food;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/** 調理後の個別栄養と、既存の保存種子・保存菌類の種別を保持する。 */
public final class FoodProperties {
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(AshenFrontierCore.NAMESPACE);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<java.util.UUID>> STORAGE = COMPONENTS.registerComponentType(
            "food_storage", b -> b.persistent(net.minecraft.core.UUIDUtil.CODEC).networkSynchronized(ByteBufCodecs.fromCodecWithRegistries(net.minecraft.core.UUIDUtil.CODEC)));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<NutritionData>> NUTRITION = COMPONENTS.registerComponentType(
            "nutrition", b -> b.persistent(NutritionData.CODEC).networkSynchronized(ByteBufCodecs.fromCodecWithRegistries(NutritionData.CODEC)));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> CROP_SPECIES = COMPONENTS.registerComponentType(
            "crop_species", b -> b.persistent(ResourceLocation.CODEC).networkSynchronized(ResourceLocation.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FoodOrigin>> ORIGIN = COMPONENTS.registerComponentType(
            "food_origin", b -> b.persistent(FoodOrigin.CODEC).networkSynchronized(ByteBufCodecs.fromCodecWithRegistries(FoodOrigin.CODEC)));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FoodEvidence>> EVIDENCE = COMPONENTS.registerComponentType(
            "food_evidence", b -> b.persistent(FoodEvidence.CODEC).networkSynchronized(ByteBufCodecs.fromCodecWithRegistries(FoodEvidence.CODEC)));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> PROFILE = COMPONENTS.registerComponentType(
            "food_profile", b -> b.persistent(com.mojang.serialization.Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Double>> ACTIVITY_UNCERTAINTY = COMPONENTS.registerComponentType(
            "food_activity_uncertainty", b -> b.persistent(com.mojang.serialization.Codec.doubleRange(0, Double.MAX_VALUE)).networkSynchronized(ByteBufCodecs.DOUBLE));

    private FoodProperties() {}

    public static void register(IEventBus bus) {
        io.github.rs0325.ashenfrontier.core.ItemProperties.registerHalfLife("ashen_frontier:cs137", IntakeRules.CS137_HALF_LIFE);
        COMPONENTS.register(bus);
        bus.addListener((RegisterDataMapTypesEvent event) -> event.register(NutritionData.TYPE));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.ItemTooltipEvent event)->{
            var stack=event.getItemStack();
            var evidence=stack.get(EVIDENCE);
            var origin=evidence==null?stack.get(ORIGIN):evidence.origin();
            if(origin!=null) {
                event.getToolTip().add(net.minecraft.network.chat.Component.translatable("intake.ashen_frontier.origin",origin.profile(),
                        net.minecraft.network.chat.Component.translatable("intake.ashen_frontier.state."+origin.state().name().toLowerCase(java.util.Locale.ROOT))));
            }
            if(evidence!=null) event.getToolTip().add(net.minecraft.network.chat.Component.translatable("intake.ashen_frontier.check_on_use"));
        });
    }
}
