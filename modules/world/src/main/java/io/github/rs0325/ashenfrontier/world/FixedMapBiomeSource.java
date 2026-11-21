package io.github.rs0325.ashenfrontier.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import java.util.stream.Stream;
import java.util.List;

/** 地域・地表標高・緯度で固定バイオームを選ぶ。入力は標準APIのquart座標。 */
public final class FixedMapBiomeSource extends BiomeSource {
    public static final MapCodec<FixedMapBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Biome.CODEC.listOf().fieldOf("biomes").forGetter(s -> s.biomes),
            MapVersion.CODEC.fieldOf("map").forGetter(s -> s.version)
    ).apply(i, FixedMapBiomeSource::new));
    private final List<Holder<Biome>> biomes;
    private final MapVersion version;
    private final FixedMapData map;

    public FixedMapBiomeSource(List<Holder<Biome>> biomes, MapVersion version) {
        if (biomes.size() != 12) throw new IllegalArgumentException("固定マップのバイオームは12種必要です");
        this.biomes = List.copyOf(biomes);
        this.version = version;
        this.map = FixedMapData.require(version);
    }

    public MapVersion mapVersion() { return version; }

    @Override
    protected MapCodec<? extends BiomeSource> codec() { return CODEC; }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() { return biomes.stream(); }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        int bx = QuartPos.toBlock(x), bz = QuartPos.toBlock(z);
        int surface = FixedTerrain.surfaceY(map, bx, bz);
        return biomes.get(FixedTerrain.biomeIndex(map, bx, bz, surface));
    }
}
