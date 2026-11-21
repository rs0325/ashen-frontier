package io.github.rs0325.ashenfrontier.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import java.util.stream.Stream;

/** 配置図導入前は全域で単一の仮バイオームを返す。入力は標準APIのquart座標。 */
public final class FixedMapBiomeSource extends BiomeSource {
    public static final MapCodec<FixedMapBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Biome.CODEC.fieldOf("biome").forGetter(s -> s.biome)
    ).apply(i, FixedMapBiomeSource::new));
    private final Holder<Biome> biome;

    public FixedMapBiomeSource(Holder<Biome> biome) {
        this.biome = biome;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() { return CODEC; }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() { return Stream.of(biome); }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) { return biome; }
}
