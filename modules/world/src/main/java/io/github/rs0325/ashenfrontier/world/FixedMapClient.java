package io.github.rs0325.ashenfrontier.world;

import com.mojang.blaze3d.shaders.FogShape;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.config.CoreConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
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
    private static final int PARTICLE_SAMPLES_PER_RADIUS = 667;
    private static final double DUST_PARTICLE_CHANCE = 0.118093334;
    private static final double SNOW_PARTICLE_CHANCE = 0.003125;
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
        var particlePosition = BlockPos.containing(position);
        var biome = level.getBiome(particlePosition);
        var biomeAtmosphere = biome.unwrapKey()
                .map(key -> BiomeAtmosphereUpdate.clientValues().get(key.location())).orElse(null);
        if (biomeAtmosphere == null) return;
        var density = AtmosphereSampler.sample(position, (x, y, z) -> {
            var sampleBiome = level.getBiomeManager().getNoiseBiomeAtQuart(x, y, z);
            var sampleAtmosphere = sampleBiome.unwrapKey()
                    .map(key -> BiomeAtmosphereUpdate.clientValues().getOrDefault(key.location(), BiomeAtmosphere.CLEAR))
                    .orElse(BiomeAtmosphere.CLEAR);
            return new Vec3(sampleAtmosphere.density(), sampleBiome.value().getBaseTemperature() < 0.15f ? 1 : 0, 0);
        });
        if (density.x <= 0 || (density.y == 0
                && biomeAtmosphere.dustParticle() == BiomeAtmosphere.DustParticle.NONE)) return;
        ParticleOptions dust = biomeAtmosphere.dustParticle() == BiomeAtmosphere.DustParticle.NONE
                ? null : biomeAtmosphere.dustParticle().options();
        for (int range = 0; range < 2; range++) {
            int radius = 16 << range;
            for (int i = 0; i < PARTICLE_SAMPLES_PER_RADIUS; i++) {
                boolean snow = RANDOM.nextDouble() < density.y;
                ParticleOptions particle = snow ? ParticleTypes.SNOWFLAKE : dust;
                if (particle == null) continue;
                double chance = density.x * quality / 2.0
                        * (snow ? SNOW_PARTICLE_CHANCE : DUST_PARTICLE_CHANCE);
                if (RANDOM.nextDouble() >= chance) continue;
                double x = player.getX() + RANDOM.nextInt(radius) - RANDOM.nextInt(radius);
                double y = player.getY() + RANDOM.nextInt(radius) - RANDOM.nextInt(radius);
                double z = player.getZ() + RANDOM.nextInt(radius) - RANDOM.nextInt(radius);
                level.addParticle(particle, x, y, z, RANDOM.nextDouble() * 0.02 - 0.01,
                        RANDOM.nextDouble() * 0.01, RANDOM.nextDouble() * 0.02 - 0.01);
            }
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
