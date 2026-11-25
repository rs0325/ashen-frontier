package io.github.rs0325.ashenfrontier.core.water;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.rs0325.ashenfrontier.core.catalog.NatureCatalog;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/** 全区域を一度に登録する初期定義。stockなしは承認された海だけ。 */
public record WaterSourceDefinition(WaterSourceKey key, ResourceLocation quality, Optional<Stock> stock) {
    public record Stock(long capacityMg, long initialMg) {
        static final Codec<Stock> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("capacity_mg").forGetter(Stock::capacityMg),
                Codec.LONG.fieldOf("initial_mg").forGetter(Stock::initialMg)
        ).apply(i, Stock::new));
        public Stock {
            if (capacityMg <= 0 || initialMg < 0 || initialMg > capacityMg)
                throw new IllegalArgumentException("水源の容量・初期量が不正です");
        }
    }
    public static final Codec<WaterSourceDefinition> CODEC = RecordCodecBuilder.create(i -> i.group(
            WaterSourceKey.CODEC.fieldOf("key").forGetter(WaterSourceDefinition::key),
            ResourceLocation.CODEC.fieldOf("quality").forGetter(WaterSourceDefinition::quality),
            Stock.CODEC.optionalFieldOf("stock").forGetter(WaterSourceDefinition::stock)
    ).apply(i, WaterSourceDefinition::new));

    public WaterSourceDefinition {
        if (!quality.getNamespace().equals("ashen_frontier") || NatureCatalog.entries().stream()
                .noneMatch(e -> e.id().equals(quality.getPath()) && e.kind().equals("water")))
            throw new IllegalArgumentException("未定義の水質です");
        if (stock.isEmpty() && (key.kind() != WaterSourceKey.Kind.SURFACE || !key.localKey().equals("ocean")
                || !quality.getPath().equals("polluted_seawater")))
            throw new IllegalArgumentException("無限水源は海域の汚染海水だけです");
    }
}
