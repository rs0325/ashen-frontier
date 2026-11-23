package io.github.rs0325.ashenfrontier.screenshots;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.rs0325.ashenfrontier.screenshots.mixin.LevelRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Mod(value = "ashen_frontier_screenshots", dist = Dist.CLIENT)
public final class ScreenshotRunner {
    private final long started = System.nanoTime();
    private final String startedAt = Instant.now().toString();
    private final Path planPath;
    private final Path output;
    private final List<PointResult> results = new ArrayList<>();
    private ScreenshotPlan plan;
    private boolean creating;
    private boolean finished;
    private boolean saving;
    private boolean setupApplied;
    private int index = -1;
    private long pointStarted;
    private long readySince;
    private CompletableFuture<Void> movement;

    public ScreenshotRunner() throws IOException {
        var configured = System.getProperty("ashen_frontier.screenshotPlan");
        if (configured == null || configured.isBlank()) {
            planPath = null;
            output = null;
            return;
        }
        planPath = Path.of(configured);
        output = Path.of(System.getProperty("ashen_frontier.screenshotOutput"))
                .resolve("runs/" + UUID.randomUUID());
        Files.createDirectories(output);
        try {
            plan = ScreenshotPlan.load(planPath);
        } catch (IOException | IllegalArgumentException e) {
            finish(e.toString());
            return;
        }
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);
    }

    private void tick(ClientTickEvent.Post event) {
        if (finished) return;
        var minecraft = Minecraft.getInstance();
        try {
            long window = minecraft.getWindow().getWindow();
            if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_VISIBLE) != GLFW.GLFW_FALSE
                    || GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) != GLFW.GLFW_FALSE) {
                GLFW.glfwHideWindow(window);
                finish("撮影ウィンドウの非表示・非フォーカス制約に違反したため中止しました");
                return;
            }
            minecraft.setWindowActive(false);
            if (index < 0 && secondsSince(started) > plan.startupTimeoutSeconds()) {
                finish("ワールドの起動が上限時間を超えました");
                return;
            }
            if (!creating) {
                if (minecraft.screen == null || minecraft.getOverlay() != null) return;
                creating = true;
                configureView(minecraft);
                var settings = new LevelSettings(plan.world().name(), GameType.byName(plan.gameMode()), false,
                        Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                minecraft.createWorldOpenFlows().createFreshLevel("capture-" + UUID.randomUUID(), settings,
                        new WorldOptions(plan.world().seed(), true, false), registries -> registries
                                .registryOrThrow(Registries.WORLD_PRESET)
                                .get(ResourceLocation.parse(plan.world().preset())).createWorldDimensions(), minecraft.screen);
            }
            if (index < 0) {
                if (minecraft.player != null && minecraft.level != null
                        && minecraft.getSingleplayerServer() != null && minecraft.screen == null) {
                    next(minecraft);
                }
                return;
            }
            if (secondsSince(pointStarted) > plan.timeoutSeconds()) {
                if (index == 0 && !plan.setupCommands().isEmpty() && (!setupApplied || !movement.isDone())) {
                    finish("撮影用セットアップの待機が上限時間を超えました");
                    return;
                }
                record(minecraft, false, saving ? "PNGの保存が上限時間を超えました" : "移動・チャンク生成・描画待ちが上限時間を超えました");
                next(minecraft);
                return;
            }
            if (movement.isCompletedExceptionally()) {
                movement.join();
            }
            if (index == 0 && !setupApplied && !plan.setupCommands().isEmpty()) {
                if (!movement.isDone()) return;
                var point = plan.points().get(index);
                if (minecraft.player.position().distanceToSqr(new Vec3(point.x(), point.y(), point.z())) > 0.01) return;
                if (!chunksReady(minecraft)) return;
                setupApplied = true;
                readySince = 0;
                var server = minecraft.getSingleplayerServer();
                movement = CompletableFuture.runAsync(() -> {
                    var source = server.createCommandSourceStack().withSuppressedOutput();
                    for (var command : plan.setupCommands()) {
                        try {
                            server.getCommands().getDispatcher().execute(command, source);
                        } catch (CommandSyntaxException e) {
                            throw new IllegalArgumentException("撮影用セットアップに失敗: " + command + " (" + e.getMessage() + ")", e);
                        }
                    }
                }, server);
                return;
            }
            if (minecraft.player != null) {
                minecraft.player.setNoGravity(true);
                minecraft.player.setDeltaMovement(Vec3.ZERO);
            }
        } catch (Exception e) {
            finish(e.toString());
        }
    }

    private void configureView(Minecraft minecraft) {
        var view = plan.view();
        minecraft.options.pauseOnLostFocus = false;
        minecraft.options.hideGui = !view.hud();
        minecraft.options.fov().set(view.fov());
        minecraft.options.renderDistance().set(view.renderDistance());
        minecraft.options.bobView().set(false);
        if (minecraft.getDebugOverlay().showDebugScreen() != view.debug()) minecraft.getDebugOverlay().toggleOverlay();
        if (minecraft.getWindow().isFullscreen()) minecraft.getWindow().toggleFullScreen();
        minecraft.getWindow().setWindowed(view.width(), view.height());
    }

    private void next(Minecraft minecraft) {
        index++;
        if (index == plan.points().size()) {
            finish(null);
            return;
        }
        pointStarted = System.nanoTime();
        readySince = 0;
        saving = false;
        var point = plan.points().get(index);
        var playerId = minecraft.player.getUUID();
        var server = minecraft.getSingleplayerServer();
        movement = CompletableFuture.runAsync(() -> {
            var source = server.createCommandSourceStack().withSuppressedOutput();
            var commands = server.getCommands();
            commands.performPrefixedCommand(source, "gamerule doDaylightCycle false");
            commands.performPrefixedCommand(source, "gamerule doWeatherCycle false");
            commands.performPrefixedCommand(source, "gamerule doMobSpawning false");
            commands.performPrefixedCommand(source, "time set " + plan.environment().time());
            commands.performPrefixedCommand(source, "weather " + plan.environment().weather());
            var player = server.getPlayerList().getPlayer(playerId);
            player.setGameMode(GameType.byName(plan.gameMode()));
            player.setNoGravity(true);
            player.setDeltaMovement(Vec3.ZERO);
            player.teleportTo(server.overworld(), point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        }, server);
    }

    private void render(RenderFrameEvent.Post event) {
        if (finished || index < 0 || saving) return;
        var minecraft = Minecraft.getInstance();
        try {
            if (!ready(minecraft)) {
                readySince = 0;
                return;
            }
            if (readySince == 0) readySince = System.nanoTime();
            if (secondsSince(readySince) < Math.max(1, plan.points().get(index).waitSeconds())) return;
            saving = true;
            int capturedIndex = index;
            var image = output.resolve("screenshots/" + plan.points().get(index).name() + ".png");
            Screenshot.grab(output.toFile(), image.getFileName().toString(), minecraft.getMainRenderTarget(),
                    message -> minecraft.execute(() -> {
                        if (finished || capturedIndex != index) return;
                        boolean timedOut = secondsSince(pointStarted) > plan.timeoutSeconds();
                        boolean success = !timedOut && Files.isRegularFile(image);
                        record(minecraft, success, timedOut ? "PNGの保存が上限時間を超えました" : success ? null : message.getString());
                        next(minecraft);
                    }));
        } catch (Exception e) {
            finish(e.toString());
        }
    }

    private boolean ready(Minecraft minecraft) {
        if (index == 0 && !setupApplied && !plan.setupCommands().isEmpty()) return false;
        if (!movement.isDone() || movement.isCompletedExceptionally() || minecraft.player == null
                || minecraft.level == null || minecraft.screen != null || minecraft.getOverlay() != null) return false;
        var point = plan.points().get(index);
        if (minecraft.player.position().distanceToSqr(new Vec3(point.x(), point.y(), point.z())) > 0.01
                || Math.abs(minecraft.player.getYRot() - point.yaw()) > 0.1
                || Math.abs(minecraft.player.getXRot() - point.pitch()) > 0.1) return false;
        var target = minecraft.getMainRenderTarget();
        if (target.width != plan.view().width() || target.height != plan.view().height()) return false;
        if (!chunksReady(minecraft)) return false;
        if (minecraft.level.getLightEngine().hasLightWork() || !minecraft.levelRenderer.hasRenderedAllSections()) return false;
        var sections = ((LevelRendererAccessor) minecraft.levelRenderer).visibleSections();
        if (sections.isEmpty()) return false;
        for (var section : sections) {
            if (section.isDirty() || section.getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) return false;
        }
        return true;
    }

    private boolean chunksReady(Minecraft minecraft) {
        var center = minecraft.player.chunkPosition();
        int distance = plan.view().renderDistance();
        for (int x = center.x - distance - 1; x <= center.x + distance + 1; x++) {
            for (int z = center.z - distance - 1; z <= center.z + distance + 1; z++) {
                if (ChunkTrackingView.isInViewDistance(center.x, center.z, distance, x, z)
                        && minecraft.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) return false;
            }
        }
        return true;
    }

    private void record(Minecraft minecraft, boolean success, String error) {
        var point = plan.points().get(index);
        var player = minecraft.player;
        results.add(new PointResult(point, success, error, secondsSince(pointStarted),
                player == null ? null : new Position(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()),
                success ? "screenshots/" + point.name() + ".png" : null));
    }

    private void finish(String error) {
        finished = true;
        if (error != null && plan != null) {
            if (index >= 0 && index < plan.points().size() && results.size() == index) {
                record(Minecraft.getInstance(), false, error);
            }
            for (int i = results.size(); i < plan.points().size(); i++) {
                results.add(new PointResult(plan.points().get(i), false, error, 0, null, null));
            }
        }
        boolean success = error == null && results.stream().allMatch(PointResult::success);
        try (var writer = Files.newBufferedWriter(output.resolve("result.json"))) {
            new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(
                    new Result(planPath.toString(), startedAt, success, error, secondsSince(started), results), writer);
            LogUtils.getLogger().info("撮影結果: {}", output.resolve("result.json"));
        } catch (IOException e) {
            LogUtils.getLogger().error("撮影結果JSONを保存できません: {}", output, e);
        }
        Minecraft.getInstance().stop();
    }

    private static double secondsSince(long time) {
        return (System.nanoTime() - time) / 1_000_000_000.0;
    }

    private record Position(double x, double y, double z, float yaw, float pitch) {}
    private record PointResult(ScreenshotPlan.Point point, boolean success, String error, double elapsedSeconds,
                               Position actual, String image) {}
    private record Result(String plan, String startedAt, boolean success, String error, double elapsedSeconds,
                          List<PointResult> points) {}
}
