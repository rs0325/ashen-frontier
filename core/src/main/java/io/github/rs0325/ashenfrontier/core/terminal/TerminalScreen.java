package io.github.rs0325.ashenfrontier.core.terminal;

import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import io.github.rs0325.ashenfrontier.core.research.ResearchProgressUpdate;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Set;

public final class TerminalScreen extends Screen {
    private int tab;
    private Set<ResourceLocation> shownModules = Set.of();

    public TerminalScreen() {
        super(Component.translatable("terminal.ashen_frontier.title"));
    }

    public void refreshModules() {
        Set<ResourceLocation> current = TerminalModulesUpdate.installedModules();
        if (tab == 2 && !shownModules.equals(current)) {
            shownModules = current;
            clearWidgets();
            init();
        }
    }

    @Override
    public void tick() {
        super.tick();
        refreshModules();
    }

    @Override
    protected void init() {
        int left = (width - 300) / 2;
        String[] names = {"status", "research", "modules"};
        for (int i = 0; i < names.length; i++) {
            int selected = i;
            addRenderableWidget(Button.builder(
                    Component.translatable("terminal.ashen_frontier.tab." + names[i]), button -> {
                        tab = selected;
                        clearWidgets();
                        init();
                    })
                    .bounds(left + i * 100, height / 2 - 90, 100, 20).build());
        }
        if (tab == 2) {
            int row = 0;
            for (var module : TerminalModules.all().values()) {
                int rowY = height / 2 - 30 + row++ * 22;
                boolean installed = TerminalModulesUpdate.isInstalled(module.id());
                Component action = Component.translatable(installed ? "terminal.ashen_frontier.remove"
                        : "terminal.ashen_frontier.insert");
                Component label = Component.literal(module.id().toString() + ": ").append(action);
                addRenderableWidget(Button.builder(label, button ->
                        PacketDistributor.sendToServer(new TerminalModuleAction(module.id(), !installed)))
                        .bounds(left, rowY, 300, 20).build());
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = (width - 300) / 2;
        int top = height / 2 - 60;
        if (tab == 0) {
            graphics.drawString(font, Component.translatable("terminal.ashen_frontier.status.empty"),
                    left, top, 0xFFFFFF);
        } else if (tab == 1) {
            int y = top;
            for (String id : ResearchManager.tree().order()) {
                var research = ResearchManager.tree().definitions().get(id);
                graphics.drawString(font, Component.translatable(research.name()), left, y, 0xFFFFFF);
                y += 12;
                graphics.drawString(font, Component.translatable(research.description()), left + 8, y,
                        ResearchProgressUpdate.isClientUnlocked(ResourceLocation.parse(id)) ? 0x55FF55 : 0xAAAAAA);
                y += 16;
                if (!research.prerequisites().isEmpty()) {
                    graphics.drawString(font, Component.translatable("terminal.ashen_frontier.prerequisites",
                            String.join(", ", research.prerequisites())), left + 16, y, 0xAAAAAA);
                    y += 12;
                }
                for (var condition : research.conditions()) {
                    boolean complete = ResearchProgressUpdate.isClientConditionComplete(
                            ResourceLocation.parse(condition.type()), condition.id());
                    graphics.drawString(font, Component.translatable("terminal.ashen_frontier.condition",
                            condition.type() + ":" + condition.id(),
                            Component.translatable(complete ? "terminal.ashen_frontier.complete"
                                    : "terminal.ashen_frontier.incomplete")), left + 16, y,
                            complete ? 0x55FF55 : 0xAAAAAA);
                    y += 12;
                }
            }
        } else {
            if (TerminalModules.all().isEmpty()) {
                graphics.drawString(font, Component.translatable("terminal.ashen_frontier.modules.empty"),
                        left, top, 0xFFFFFF);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
