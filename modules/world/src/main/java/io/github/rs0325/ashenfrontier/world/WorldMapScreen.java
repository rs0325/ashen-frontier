package io.github.rs0325.ashenfrontier.world;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

final class WorldMapScreen extends Screen {
    private double centerX;
    private double centerZ;
    private double scale;
    private int targetX;
    private int targetZ;
    private String defaultY;
    private EditBox targetY;
    private Button move;
    private Button mode;
    private boolean tab;

    WorldMapScreen() { super(Component.translatable("map.ashen_frontier.title")); }

    @Override protected void init() {
        if (scale == 0) scale = Math.min(width - 24, height - 76) / 32768.0;
        mode = addRenderableWidget(Button.builder(MapClient.modeName(), button -> toggleMode())
                .bounds(8, 6, 142, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("map.ashen_frontier.fit"), button -> {
            centerX = 0;
            centerZ = 0;
            scale = Math.min(width - 24, height - 76) / 32768.0;
            hideTab();
        }).bounds(154, 6, 90, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width - 68, 6, 60, 20).build());
        targetY = addRenderableWidget(new EditBox(font, width - 146, 88, 130, 20,
                Component.translatable("map.ashen_frontier.target_y")));
        targetY.setMaxLength(6);
        targetY.setHint(Component.translatable("map.ashen_frontier.auto_y"));
        targetY.setFilter(value -> value.matches("-?[0-9]*"));
        move = addRenderableWidget(Button.builder(Component.translatable("map.ashen_frontier.move"), button -> teleport())
                .bounds(width - 146, 114, 130, 20).build());
        targetY.visible = tab;
        move.visible = tab;
        if (tab) targetY.setValue(defaultY);
    }

    private void toggleMode() {
        MapClient.blocks = !MapClient.blocks;
        mode.setMessage(MapClient.modeName());
    }

    private void hideTab() {
        tab = false;
        targetY.visible = false;
        move.visible = false;
        setFocused(null);
    }

    private boolean canTeleport() {
        return minecraft.player != null && MapTeleportRequest.isAllowed(minecraft.player);
    }

    private void teleport() {
        if (!canTeleport()) return;
        try {
            boolean automatic = targetY.getValue().isEmpty() || targetY.getValue().equals(defaultY);
            int y = automatic ? 0 : Integer.parseInt(targetY.getValue());
            PacketDistributor.sendToServer(new MapTeleportRequest(targetX, targetZ, y, automatic));
            onClose();
        } catch (NumberFormatException e) {
            targetY.setFocused(true);
        }
    }

    private double worldX(double mouseX) { return MapViewMath.world(mouseX, centerX, width / 2.0, scale); }
    private double worldZ(double mouseY) { return MapViewMath.world(mouseY, centerZ, (height + 4) / 2.0, scale); }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!MapClient.available()) {
            onClose();
            return;
        }
        g.fill(0, 0, width, height, 0xFF161B21);
        MapClient.draw(g, 8, 32, width - 16, height - 60, centerX, centerZ, scale);
        int x = (int) Math.floor(worldX(mouseX)), z = (int) Math.floor(worldZ(mouseY));
        if (MapCoordinates.contains(x, z) && mouseY >= 32 && mouseY < height - 28) {
            int y = MapClient.surfaceHeight(x, z);
            Component heightText = y == MapTileData.NO_HEIGHT ? Component.translatable("map.ashen_frontier.unknown_y")
                    : Component.literal(Integer.toString(y));
            g.drawString(font, Component.translatable("map.ashen_frontier.position", x, z, heightText),
                    8, height - 23, 0xFFFFFFFF);
        }
        g.drawString(font, Component.translatable("map.ashen_frontier.help"), 8, height - 12, 0xFFCCCCCC);
        if (tab && !canTeleport()) hideTab();
        if (tab) {
            g.fill(width - 154, 35, width - 8, 143, 0xF0202630);
            g.drawString(font, Component.translatable("map.ashen_frontier.teleport"), width - 146, 42, 0xFFFFFFFF);
            g.drawString(font, "X " + targetX + " / Z " + targetZ, width - 146, 57, 0xFFFFFFFF);
            g.drawString(font, Component.translatable("map.ashen_frontier.target_y"), width - 146, 73, 0xFFFFFFFF);
            move.active = !targetY.getValue().equals("-");
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (y < 32 || y >= height - 28 || x < 8 || x >= width - 8) return false;
        if (tab && x >= width - 154 && y <= 143) return true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && canTeleport()) {
            targetX = (int) Math.floor(worldX(x));
            targetZ = (int) Math.floor(worldZ(y));
            if (!MapCoordinates.contains(targetX, targetZ)) {
                hideTab();
                return true;
            }
            int surface = MapClient.surfaceHeight(targetX, targetZ);
            defaultY = surface == MapTileData.NO_HEIGHT ? "" : Integer.toString(surface + 3);
            targetY.setValue(defaultY);
            tab = true;
            targetY.visible = true;
            move.visible = true;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            hideTab();
            return true;
        }
        return false;
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (super.mouseDragged(x, y, button, dx, dy)) return true;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || tab) return false;
        centerX -= dx / scale;
        centerZ -= dy / scale;
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (super.mouseScrolled(x, y, horizontal, vertical)) return true;
        if (y < 32 || y >= height - 28 || (tab && x >= width - 154 && y <= 143)) return false;
        double beforeX = worldX(x), beforeZ = worldZ(y);
        scale = Math.clamp(scale * Math.pow(1.25, vertical), 0.002, 4);
        centerX += beforeX - worldX(x);
        centerZ += beforeZ - worldZ(y);
        hideTab();
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (targetY.isFocused()) return super.keyPressed(key, scan, modifiers);
        if (MapClient.OPEN.matches(key, scan)) {
            onClose();
            return true;
        }
        if (MapClient.MODE.matches(key, scan)) {
            toggleMode();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public boolean isPauseScreen() { return false; }
}
