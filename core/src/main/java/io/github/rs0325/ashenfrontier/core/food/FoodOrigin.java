package io.github.rs0325.ashenfrontier.core.food;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import java.util.UUID;

/** M2発行の根由来。配置座標ではなくロットを識別し、回収・再設置でも同じ値を移す。 */
public record FoodOrigin(String mapVersion, String manifest, String profile, UUID batch, State state) {
    public enum State { CONFIRMED, UNCONFIRMED, INVALIDATED }
    public static final Codec<FoodOrigin> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("map_version").forGetter(FoodOrigin::mapVersion),
            Codec.STRING.fieldOf("manifest").forGetter(FoodOrigin::manifest),
            Codec.STRING.fieldOf("profile").forGetter(FoodOrigin::profile),
            UUIDUtil.CODEC.fieldOf("batch").forGetter(FoodOrigin::batch),
            Codec.STRING.xmap(State::valueOf, State::name).fieldOf("state").forGetter(FoodOrigin::state)
    ).apply(i, FoodOrigin::new));
    public FoodOrigin {
        if (mapVersion.isBlank() || manifest.isBlank() || profile.isBlank()) throw new IllegalArgumentException("由来の識別が空です");
    }
}
