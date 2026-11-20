package io.github.rs0325.ashenfrontier.core.terminal;

import io.github.rs0325.ashenfrontier.core.research.ResearchDefinitionsUpdate;
import io.github.rs0325.ashenfrontier.core.research.ResearchProgressUpdate;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.function.IntSupplier;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class TerminalScreen extends Screen {
    private int tab;
    private Set<ResourceLocation> shownModules = Set.of();
    private List<ResearchDefinitionsUpdate.Definition> shownResearch = List.of();

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
        if (tab == 1 && !shownResearch.equals(ResearchDefinitionsUpdate.clientDefinitions())) {
            clearWidgets();
            init();
        }
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
        if (tab == 1) {
            shownResearch = ResearchDefinitionsUpdate.clientDefinitions();
            addRenderableWidget(new ResearchList(left, Math.max(36, height / 2 - 60),
                    292, Math.max(40, height - Math.max(36, height / 2 - 60) - 20)));
        } else if (tab == 2) {
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
        } else if (tab == 2) {
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

    private final class ResearchList extends AbstractScrollWidget {
        private final List<Line> lines = new ArrayList<>();

        ResearchList(int x, int y, int width, int height) {
            super(x, y, width, height, Component.translatable("terminal.ashen_frontier.tab.research"));
            for (var research : shownResearch) {
                addLines(Component.translatable(research.name()), 0, () -> 0xFFFFFF);
                addLines(Component.translatable(research.description()), 8,
                        () -> ResearchProgressUpdate.isClientUnlocked(ResourceLocation.parse(research.id()))
                                ? 0x55FF55 : 0xAAAAAA);
                if (!research.prerequisites().isEmpty()) {
                    addLines(Component.translatable("terminal.ashen_frontier.prerequisites",
                            String.join(", ", research.prerequisites())), 16, () -> 0xAAAAAA);
                }
                for (var condition : research.conditions()) {
                    BooleanSupplier complete = () -> ResearchProgressUpdate.isClientConditionComplete(
                            ResourceLocation.parse(condition.type()), condition.id());
                    IntSupplier color = () -> complete.getAsBoolean() ? 0x55FF55 : 0xAAAAAA;
                    addLines(Component.translatable("terminal.ashen_frontier.condition",
                            condition.type() + ":" + condition.id(), ""), 16, color);
                    lines.add(new Line(() -> Component.translatable(complete.getAsBoolean()
                            ? "terminal.ashen_frontier.complete" : "terminal.ashen_frontier.incomplete")
                            .getVisualOrderText(), 24, color));
                }
            }
        }

        private void addLines(Component text, int indent, IntSupplier color) {
            for (var line : font.split(text, width - totalInnerPadding() - indent)) {
                lines.add(new Line(() -> line, indent, color));
            }
        }

        @Override protected int getInnerHeight() { return lines.size() * 12 + innerPadding(); }
        @Override protected double scrollRate() { return 12; }

        @Override
        protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int y = getY() + innerPadding();
            for (Line line : lines) {
                graphics.drawString(font, line.text().get(), getX() + innerPadding() + line.indent(), y,
                        line.color().getAsInt());
                y += 12;
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private record Line(Supplier<FormattedCharSequence> text, int indent, IntSupplier color) {}
}
