package io.github.rs0325.ashenfrontier.core.catalog;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.SoilContamination;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

@EventBusSubscriber(modid = AshenFrontierCore.MOD_ID, value = Dist.CLIENT)
public final class NatureTooltips {
    private NatureTooltips() {}

    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        appendContamination(event.getItemStack(), event.getToolTip());
        var id = BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem());
        if (!id.getNamespace().equals(AshenFrontierCore.NAMESPACE)) return;
        var stack = event.getItemStack();
        RelicCatalog.entries().stream().filter(entry -> entry.id().equals(id.getPath())).findFirst().ifPresent(entry -> {
            if (!entry.behavior().equals("decoration")) event.getToolTip().add(Component.translatable("tooltip.ashen_frontier." + entry.behavior()));
            if (entry.blockItem() && !entry.recovery().equals(entry.id())) {
                event.getToolTip().add(Component.translatable("tooltip.ashen_frontier.recovery",
                        RelicContent.item(entry.recovery()).getDescription()));
            }
        });
        var quality = stack.get(NatureProperties.QUALITY);
        if (quality != null && RelicCatalog.entries().stream().anyMatch(e -> e.id().equals(id.getPath()))) {
            event.getToolTip().add(Component.translatable("tooltip.ashen_frontier.quality",
                    quality.getNamespace().equals(AshenFrontierCore.NAMESPACE)
                            && NatureCatalog.entries().stream().anyMatch(e -> e.id().equals(quality.getPath()))
                            ? Component.translatable("fluid_type.ashen_frontier." + quality.getPath()) : Component.literal(quality.toString())));
        }
        var mass = stack.get(RelicProperties.CONTENT_MASS);
        if (mass != null) event.getToolTip().add(Component.translatable("tooltip.ashen_frontier.content_mass", mass));
        var document = stack.get(RelicProperties.DOCUMENT);
        if (document != null) event.getToolTip().add(Component.translatable("tooltip.ashen_frontier.document_id", document.toString()));
        NatureCatalog.entries().stream().filter(entry -> entry.id().equals(id.getPath()) && entry.blockItem()
                && !entry.recovery().equals(entry.id())).findFirst().ifPresent(entry -> {
            var recovery = ResourceLocation.parse(entry.recovery().contains(":")
                    ? entry.recovery() : "ashen_frontier:" + entry.recovery());
            event.getToolTip().add(Component.translatable("tooltip.ashen_frontier.recovery",
                    Component.translatable(BuiltInRegistries.ITEM.get(recovery).getDescriptionId())));
        });
    }

    /** 土アイテムの清浄でない種類だけを、種類・段階の翻訳付きLoreとして末尾へ追加する。 */
    public static void appendContamination(ItemStack stack, List<Component> tooltip) {
        var stages = stack.get(ItemProperties.SOIL_CONTAMINATION);
        if (stages == null) return;
        for (var kind : SoilContamination.Kind.values()) {
            int stage = stages.stage(kind);
            if (stage > 0) tooltip.add(Component.translatable("tooltip.ashen_frontier.soil_contamination",
                    Component.translatable("contamination.ashen_frontier." + kind.key),
                    Component.translatable("contamination.ashen_frontier.stage." + stage)).withStyle(ChatFormatting.GRAY));
        }
    }
}
