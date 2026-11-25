package io.github.rs0325.ashenfrontier.core.water;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** 流域・帯水層の不変な識別子。dimensionはServerLevelの保存領域で分離する。 */
public record WaterSourceKey(MapRef map, Kind kind, String localKey) {
    public enum Kind { SURFACE, AQUIFER;
        static final Codec<Kind> CODEC = Codec.STRING.xmap(s -> valueOf(s.toUpperCase(java.util.Locale.ROOT)),
                k -> k.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** worldのMapVersionを値として渡す。coreからworldへ逆依存しない。 */
    public record MapRef(String mapVersion, int formatVersion, String manifestHash) {
        public static final Codec<MapRef> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("map_version").forGetter(MapRef::mapVersion),
                Codec.INT.fieldOf("format_version").forGetter(MapRef::formatVersion),
                Codec.STRING.fieldOf("manifest_hash").forGetter(MapRef::manifestHash)
        ).apply(i, MapRef::new));
        public MapRef {
            if (mapVersion.isBlank() || formatVersion < 1 || !manifestHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("水源のマップ参照が不正です");
        }
    }

    public static final Codec<WaterSourceKey> CODEC = RecordCodecBuilder.create(i -> i.group(
            MapRef.CODEC.fieldOf("map").forGetter(WaterSourceKey::map),
            Kind.CODEC.fieldOf("kind").forGetter(WaterSourceKey::kind),
            Codec.STRING.fieldOf("local_key").forGetter(WaterSourceKey::localKey)
    ).apply(i, WaterSourceKey::new));

    public WaterSourceKey {
        java.util.Objects.requireNonNull(map);
        java.util.Objects.requireNonNull(kind);
        if (localKey.isBlank()) throw new IllegalArgumentException("水源キーは空にできません");
    }
}
