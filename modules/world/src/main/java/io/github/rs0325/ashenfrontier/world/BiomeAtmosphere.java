package io.github.rs0325.ashenfrontier.world;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** バイオームIDに対応する静的な大気定義を読み込み、参加時とリロード時に同期する。 */
final class BiomeAtmosphere {
    static final Values CLEAR = new Values(0, DustParticle.NONE);
    private static Map<ResourceLocation, Values> values = Map.of();

    private BiomeAtmosphere() {}

    static Map<ResourceLocation, Values> values() { return values; }

    static void register(IEventBus bus) {
        bus.addListener(BiomeAtmosphereUpdate::register);
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(new Loader()));
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> {
            var packet = new BiomeAtmosphereUpdate(values);
            event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, packet));
        });
    }

    static final class Loader extends SimpleJsonResourceReloadListener {
        Loader() { super(new Gson(), "biome_atmosphere"); }

        @Override protected void apply(Map<ResourceLocation, JsonElement> json,
                                       ResourceManager resources, ProfilerFiller profiler) {
            Map<ResourceLocation, Values> loaded = new HashMap<>();
            json.forEach((id, element) -> {
                var value = element.getAsJsonObject();
                double density = value.get("density").getAsDouble();
                DustParticle dustParticle = DustParticle.valueOf(value.get("dust_particle").getAsString().toUpperCase(Locale.ROOT));
                if (!Double.isFinite(density) || density < 0 || density > 1) {
                    throw new IllegalArgumentException("バイオーム大気の濃度が範囲外です: " + id);
                }
                loaded.put(id, new Values(density, dustParticle));
            });
            values = Map.copyOf(loaded);
        }
    }

    record Values(double density, DustParticle dustParticle) {}

    enum DustParticle {
        NONE, ASH, WHITE_ASH;

        ParticleOptions options() {
            return switch (this) {
                case ASH -> ParticleTypes.ASH;
                case WHITE_ASH -> ParticleTypes.WHITE_ASH;
                case NONE -> throw new IllegalStateException("砂塵のないバイオームです");
            };
        }
    }
}
