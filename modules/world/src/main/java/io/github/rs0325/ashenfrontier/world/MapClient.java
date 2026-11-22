package io.github.rs0325.ashenfrontier.world;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@EventBusSubscriber(modid = AshenFrontierWorld.MOD_ID, value = Dist.CLIENT)
public final class MapClient {
    static final KeyMapping OPEN = key("open", GLFW.GLFW_KEY_M);
    static final KeyMapping MODE = key("mode", GLFW.GLFW_KEY_N);
    static final KeyMapping ZOOM_IN = key("zoom_in", GLFW.GLFW_KEY_EQUAL);
    static final KeyMapping ZOOM_OUT = key("zoom_out", GLFW.GLFW_KEY_MINUS);
    private static final Set<Long> DIRTY = new LinkedHashSet<>();
    private static ClientLevel mapLevel;
    private static MapTileCache cache;
    static boolean blocks;

    private MapClient() {}

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.ashen_frontier.map." + name, KeyConflictContext.UNIVERSAL,
                InputConstants.Type.KEYSYM, code, "key.categories.ashen_frontier");
    }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
        event.register(MODE);
        event.register(ZOOM_IN);
        event.register(ZOOM_OUT);
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        ModList.get().getModContainerById(AshenFrontierWorld.MOD_ID).orElseThrow()
                .registerExtensionPoint(IConfigScreenFactory.class,
                        ConfigurationScreen::new);
    }

    static boolean available() {
        var level = Minecraft.getInstance().level;
        return level != null && level.dimension() == Level.OVERWORLD
                && level.dimensionTypeRegistration().is(AshenFrontierCore.id("fixed_map"));
    }

    static Component modeName() {
        return Component.translatable("map.ashen_frontier." + (blocks ? "blocks" : "relief"));
    }

    static void zoom(double factor) {
        MapClientConfig.ZOOM.set(Math.clamp(MapClientConfig.ZOOM.get() * factor, 0.125, 4));
        MapClientConfig.ZOOM.save();
    }

    /** クライアントで受信・予測・巻戻しされた変更の属するチャンクだけを再計算する。 */
    public static void blockChanged(ClientLevel level, BlockPos pos) {
        if (level == Minecraft.getInstance().level && available()) {
            useLevel(level);
            int x = pos.getX() >> 4, z = pos.getZ() >> 4;
            markChunk(x, z);
            if ((pos.getX() & 15) == 15) markChunk(x + 1, z);
            if ((pos.getZ() & 15) == 15) markChunk(x, z + 1);
        }
    }

    private static void useLevel(ClientLevel level) {
        if (mapLevel == level) return;
        clear();
        mapLevel = level;
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        var local = server == null ? null : server.getWorldPath(LevelResource.ROOT).toAbsolutePath();
        String identity;
        if (local != null) identity = "local:" + local;
        else if (mc.getCurrentServer() != null) identity = "server:" + mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        else identity = "server:" + mc.getConnection().getConnection().getRemoteAddress();
        cache = new MapTileCache(new MapTileStorage(mc.gameDirectory.toPath().resolve("ashen_frontier/maps"), identity, local));
    }

    private static void clear() {
        if (cache != null) cache.close();
        cache = null;
        DIRTY.clear();
        mapLevel = null;
    }

    private static void markChunk(int x, int z) {
        if (MapCoordinates.contains(x * 16, z * 16) && mapLevel.hasChunk(x, z)) DIRTY.add(ChunkPos.asLong(x, z));
    }

    static int surfaceHeight(int x, int z) {
        if (cache == null || !MapCoordinates.contains(x, z)) return MapTileData.NO_HEIGHT;
        var data = cache.get(MapTileKey.at(0, x, z));
        return data == null ? MapTileData.NO_HEIGHT : data.heights[MapTileKey.pixel(0, z) * MapTileKey.SIZE + MapTileKey.pixel(0, x)];
    }

    private static void label(GuiGraphics graphics, Component text, int x, int y, int width) {
        var font = Minecraft.getInstance().font;
        float scale = Math.min(1, (float) width / font.width(text));
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, text, 0, 0, 0xFFFFFFFF);
        graphics.pose().popPose();
    }

    private static boolean updateChunk(LevelChunk chunk) {
        int startX = chunk.getPos().getMinBlockX(), startZ = chunk.getPos().getMinBlockZ();
        var key = MapTileKey.at(0, startX, startZ);
        var data = cache.get(key);
        if (data == null) return false;
        MapChunkSampler.sample(mapLevel, chunk, data);
        cache.changed(key);
        return true;
    }

    static void draw(GuiGraphics graphics, int left, int top, int width, int height,
                     double centerX, double centerZ, double scale) {
        if (cache == null) return;
        graphics.enableScissor(left, top, left + width, top + height);
        graphics.fill(left, top, left + width, top + height, 0xFF292D32);
        double ox = left + width / 2.0, oz = top + height / 2.0;
        int minX = (int) Math.max(MapCoordinates.MIN, Math.floor(MapViewMath.world(left, centerX, ox, scale)));
        int minZ = (int) Math.max(MapCoordinates.MIN, Math.floor(MapViewMath.world(top, centerZ, oz, scale)));
        int maxX = (int) Math.min(MapCoordinates.MAX - 1, Math.ceil(MapViewMath.world(left + width, centerX, ox, scale)));
        int maxZ = (int) Math.min(MapCoordinates.MAX - 1, Math.ceil(MapViewMath.world(top + height, centerZ, oz, scale)));
        int level = Math.clamp((int) Math.ceil(Math.log(1 / scale) / Math.log(2)), 0, MapTileKey.MAX_LEVEL);
        Set<MapTileKey> visible = new LinkedHashSet<>();
        if (minX <= maxX && minZ <= maxZ) {
            while (level < MapTileKey.MAX_LEVEL) {
                var a = MapTileKey.at(level, minX, minZ);
                var b = MapTileKey.at(level, maxX, maxZ);
                if ((b.x() - a.x() + 1) * (b.z() - a.z() + 1) <= MapTileCache.VIEW_LIMIT) break;
                level++;
            }
            var a = MapTileKey.at(level, minX, minZ);
            var b = MapTileKey.at(level, maxX, maxZ);
            for (int z = a.z(); z <= b.z(); z++) for (int x = a.x(); x <= b.x(); x++) visible.add(new MapTileKey(level, x, z));
        }
        cache.view(visible);
        for (var key : visible) cache.texture(key);
        RenderSystem.enableBlend();
        for (var key : cache.rendered()) {
            var texture = cache.texture(key);
            if (texture == null) continue;
            int x0 = (int) Math.floor(MapViewMath.screen(key.minX(), centerX, ox, scale));
            int z0 = (int) Math.floor(MapViewMath.screen(key.minZ(), centerZ, oz, scale));
            int x1 = (int) Math.floor(MapViewMath.screen(key.minX() + (MapTileKey.SIZE << key.level()), centerX, ox, scale));
            int z1 = (int) Math.floor(MapViewMath.screen(key.minZ() + (MapTileKey.SIZE << key.level()), centerZ, oz, scale));
            graphics.blit(texture, x0, z0, x1 - x0, z1 - z0, 0, 0, MapTileKey.SIZE, MapTileKey.SIZE, MapTileKey.SIZE, MapTileKey.SIZE);
        }
        RenderSystem.disableBlend();
        var player = Minecraft.getInstance().player;
        if (player != null) {
            double px = MapViewMath.screen(player.getX(), centerX, ox, scale);
            double pz = MapViewMath.screen(player.getZ(), centerZ, oz, scale);
            graphics.fill((int) px - 2, (int) pz - 2, (int) px + 3, (int) pz + 3, 0xFFFFFF40);
            double yaw = Math.toRadians(player.getYRot());
            for (int i = 4; i <= 10; i++) {
                int dx = (int) Math.round(px - Math.sin(yaw) * i), dz = (int) Math.round(pz + Math.cos(yaw) * i);
                graphics.fill(dx, dz, dx + 2, dz + 2, 0xFFFFFF40);
            }
        }
        graphics.drawString(Minecraft.getInstance().font, Component.translatable("map.ashen_frontier.north"),
                left + 4, top + 4, 0xFFFFFFFF);
        graphics.disableScissor();
    }

    @EventBusSubscriber(modid = AshenFrontierWorld.MOD_ID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

        @SubscribeEvent
        public static void load(ChunkEvent.Load event) {
            if (event.getLevel() == Minecraft.getInstance().level && available()) {
                useLevel(Minecraft.getInstance().level);
                var pos = event.getChunk().getPos();
                markChunk(pos.x, pos.z);
                markChunk(pos.x + 1, pos.z);
                markChunk(pos.x, pos.z + 1);
            }
        }

        @SubscribeEvent
        public static void unload(ChunkEvent.Unload event) {
            if (event.getLevel() != mapLevel) return;
            DIRTY.remove(event.getChunk().getPos().toLong());
        }

        @SubscribeEvent
        public static void shutdown(GameShuttingDownEvent event) {
            clear();
            MapTileStorage.shutdown();
        }

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            var mc = Minecraft.getInstance();
            if (!available()) {
                if (mapLevel != null) clear();
                return;
            }
            useLevel(mc.level);
            cache.tick(blocks);
            var iterator = DIRTY.iterator();
            int processed = 0, examined = 0;
            while (processed < 4 && examined < 32 && iterator.hasNext()) {
                long key = iterator.next();
                examined++;
                var chunk = mc.level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false);
                if (chunk == null || updateChunk(chunk)) {
                    iterator.remove();
                    processed++;
                }
            }
            if (mc.screen != null && !(mc.screen instanceof WorldMapScreen)
                    || mc.screen == null && (!MapClientConfig.ENABLED.get() || mc.options.hideGui)) cache.view(Set.of());
            if (mc.screen == null) {
                if (OPEN.consumeClick()) mc.setScreen(new WorldMapScreen());
                if (MODE.consumeClick()) blocks = !blocks;
                if (ZOOM_IN.consumeClick()) zoom(2);
                if (ZOOM_OUT.consumeClick()) zoom(0.5);
            }
        }

        @SubscribeEvent
        public static void hud(RenderGuiEvent.Post event) {
            var mc = Minecraft.getInstance();
            if (!available() || !MapClientConfig.ENABLED.get() || mc.options.hideGui || mc.screen != null || mc.player == null) return;
            var g = event.getGuiGraphics();
            int margin = Math.min(MapClientConfig.MARGIN.get(), Math.max(0, (Math.min(g.guiWidth(), g.guiHeight()) - 80) / 2));
            int size = Math.max(16, Math.min(MapClientConfig.SIZE.get(), Math.min(g.guiWidth() - 2 * margin, g.guiHeight() - 2 * margin - 20)));
            var corner = MapClientConfig.CORNER.get();
            boolean right = corner == MapClientConfig.Corner.TOP_RIGHT || corner == MapClientConfig.Corner.BOTTOM_RIGHT;
            boolean bottom = corner == MapClientConfig.Corner.BOTTOM_LEFT || corner == MapClientConfig.Corner.BOTTOM_RIGHT;
            int x = right ? g.guiWidth() - size - margin : margin;
            int y = bottom ? g.guiHeight() - size - margin - 20 : margin;
            g.fill(x - 2, y - 2, x + size + 2, y + size + 20, 0xD0000000);
            draw(g, x, y, size, size, mc.player.getX(), mc.player.getZ(), MapClientConfig.ZOOM.get());
            label(g, Component.literal(mc.player.getBlockX() + " / " + mc.player.getBlockY() + " / " + mc.player.getBlockZ()),
                    x, y + size + 3, size);
            label(g, modeName(), x, y + size + 12, size);
        }
    }
}
