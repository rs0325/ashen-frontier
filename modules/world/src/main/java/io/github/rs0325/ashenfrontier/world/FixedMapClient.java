package io.github.rs0325.ashenfrontier.world;

import com.mojang.blaze3d.shaders.FogShape;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.config.CoreConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.util.Collections;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = AshenFrontierWorld.MOD_ID, value = Dist.CLIENT)
public final class FixedMapClient {
    private static final Set<CreateWorldScreen> INITIALIZED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Random RANDOM = new Random();

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BiomeAtmosphereUpdate.clearClientValues();
    }

    @SubscribeEvent
    public static void opening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof CreateWorldScreen screen) || !INITIALIZED.add(screen)) return;
        var state = screen.getUiState();
        if (!state.getSeed().isEmpty()) return;
        var selected = state.getWorldType().preset();
        if (selected == null || !selected.is(WorldPresets.NORMAL)) return;
        var key = ResourceKey.create(Registries.WORLD_PRESET, AshenFrontierCore.id("fixed_map"));
        var preset = state.getSettings().worldgenLoadContext().registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(key);
        state.setWorldType(new WorldCreationUiState.WorldTypeEntry(preset));
    }

    @SubscribeEvent
    public static void renderFog(ViewportEvent.RenderFog event) {
        var level = Minecraft.getInstance().level;
        if (level == null || level.dimension() != Level.OVERWORLD || event.getType() != FogType.NONE) return;
        var position = event.getCamera().getPosition();
        if (!hasAtmosphere(level, position)) return;
        double density = AtmosphereSampler.sample(position, (x, y, z) ->
                new Vec3(atmosphere(level, x, y, z).density(), 0, 0)).x;
        double edge = Math.min(Math.min(position.x - MapCoordinates.MIN, MapCoordinates.MAX - position.x),
                Math.min(position.z - MapCoordinates.MIN, MapCoordinates.MAX - position.z));
        var range = AtmosphereMath.fogRange(event.getNearPlaneDistance(), event.getFarPlaneDistance(), density, edge);
        event.setNearPlaneDistance((float) range.start());
        event.setFarPlaneDistance((float) range.end());
        event.setFogShape(FogShape.SPHERE);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void fogColor(ViewportEvent.ComputeFogColor event) {
        var level = Minecraft.getInstance().level;
        if (level == null || level.dimension() != Level.OVERWORLD
                || event.getCamera().getFluidInCamera() != FogType.NONE) return;
        var position = event.getCamera().getPosition();
        if (!hasAtmosphere(level, position)) return;
        double edge = Math.min(Math.min(position.x - MapCoordinates.MIN, MapCoordinates.MAX - position.x),
                Math.min(position.z - MapCoordinates.MIN, MapCoordinates.MAX - position.z));
        double borderBlend = AtmosphereMath.edgeDensity(edge) * 0.9;
        float red = event.getRed();
        float green = event.getGreen();
        float blue = event.getBlue();
        event.setRed((float) (red * (1 - borderBlend) + 0.49 * borderBlend));
        event.setGreen((float) (green * (1 - borderBlend) + 0.52 * borderBlend));
        event.setBlue((float) (blue * (1 - borderBlend) + 0.55 * borderBlend));
    }

    @SubscribeEvent
    public static void dustParticles(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || minecraft.level == null
                || minecraft.level.dimension() != Level.OVERWORLD || minecraft.isPaused()) return;
        int quality = CoreConfig.particleQuality();
        if (quality == 0) return;
        var level = minecraft.level;
        var position = player.position();
        if (!hasAtmosphere(level, position)) return;
        var density = AtmosphereSampler.sample(position, (x, y, z) -> new Vec3(
                atmosphere(level, x, y, z).density(),
                level.getBiomeManager().getNoiseBiomeAtQuart(x, y, z).value().getBaseTemperature() < 0.15f ? 1 : 0, 0));
        double amount = density.x * quality;
        int count = (int) amount + (RANDOM.nextDouble() < amount % 1 ? 1 : 0);
        var color = AtmosphereSampler.sample(position, (x, y, z) ->
                Vec3.fromRGB24(atmosphere(level, x, y, z).dustColor()));
        for (int i = 0; i < count; i++) {
            ParticleOptions particle = RANDOM.nextDouble() < density.y ? ParticleTypes.SNOWFLAKE
                    : new DustParticleOptions(color.toVector3f(), quality == 1 ? 0.65f : 0.9f);
            double x = player.getX() + RANDOM.nextDouble() * 16 - 8;
            double y = player.getY() + RANDOM.nextDouble() * 6 - 3;
            double z = player.getZ() + RANDOM.nextDouble() * 16 - 8;
            minecraft.level.addParticle(particle, x, y, z, RANDOM.nextDouble() * 0.02 - 0.01,
                    RANDOM.nextDouble() * 0.01, RANDOM.nextDouble() * 0.02 - 0.01);
        }
    }

    private static boolean hasAtmosphere(ClientLevel level, Vec3 position) {
        return level.getBiome(BlockPos.containing(position)).unwrapKey()
                .map(key -> BiomeAtmosphereUpdate.clientValues().containsKey(key.location())).orElse(false);
    }

    private static BiomeAtmosphere.Values atmosphere(ClientLevel level, int x, int y, int z) {
        return level.getBiomeManager().getNoiseBiomeAtQuart(x, y, z).unwrapKey()
                .map(key -> BiomeAtmosphereUpdate.clientValues().getOrDefault(key.location(), BiomeAtmosphere.CLEAR))
                .orElse(BiomeAtmosphere.CLEAR);
    }
}
