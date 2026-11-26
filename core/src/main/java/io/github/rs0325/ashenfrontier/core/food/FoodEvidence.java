package io.github.rs0325.ashenfrontier.core.food;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** 検証主体が発行する可食部の確認。scopeは食品の登録IDまたは水質IDで、別の可食部へ流用しない。 */
public record FoodEvidence(FoodOrigin origin, String scope, String profile, boolean cleanBaseline,
                           String recipe, int revision) {
    public static final Codec<FoodEvidence> CODEC = RecordCodecBuilder.create(i -> i.group(
            FoodOrigin.CODEC.fieldOf("origin").forGetter(FoodEvidence::origin),
            Codec.STRING.fieldOf("scope").forGetter(FoodEvidence::scope),
            Codec.STRING.fieldOf("profile").forGetter(FoodEvidence::profile),
            Codec.BOOL.fieldOf("clean_baseline").forGetter(FoodEvidence::cleanBaseline),
            Codec.STRING.fieldOf("recipe").forGetter(FoodEvidence::recipe),
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("revision").forGetter(FoodEvidence::revision)
    ).apply(i, FoodEvidence::new));
}
