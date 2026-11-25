package io.github.rs0325.ashenfrontier.primitive;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;


/** Cの実際のサーバー操作が研究実績を報告する。初回着火を研究で自己ロックしない。 */
public final class PotteryActions {
    private PotteryActions() {}
    static void register() {
        ResearchManager.registerConditionType(AshenFrontierCore.id("primitive_event"),
                (player, id) -> id.equals("friction_ignition") || id.equals("ceramic_pot"));
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) -> {
            if (event.getLevel().getBlockState(event.getPos()).getBlock() instanceof ThermalStationBlock) event.setUseBlock(TriState.TRUE);
        });
        NeoForge.EVENT_BUS.addListener((ItemTooltipEvent event) -> {
            var stack = event.getItemStack();
            if (stack.has(PotteryProperties.FORM.get())) {
                event.getToolTip().add(Component.translatable(
                        "tooltip.ashen_frontier.pottery_form",
                        Component.translatable("pottery_form.ashen_frontier." + stack.get(PotteryProperties.FORM))));
                event.getToolTip().add(Component.translatable("tooltip.ashen_frontier.pottery_steps"));
            }
            if (stack.getOrDefault(NatureProperties.MOISTURE.get(), 0.0) > 0) event.getToolTip().add(
                    Component.translatable("tooltip.ashen_frontier.wet"));
            if (stack.is(PrimitiveContent.item("ceramic_pot"))) event.getToolTip().add(
                    Component.translatable("tooltip.ashen_frontier.pot_capacity"));
            if (stack.is(PrimitiveContent.item("fire_drill"))) event.getToolTip().add(
                    Component.translatable("primitive.ashen_frontier.fire_requirements"));
        });
    }
}
