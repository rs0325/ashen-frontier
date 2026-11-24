package io.github.rs0325.ashenfrontier.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** ワールドが固定する版。manifestHashは同梱manifestの生バイト列のSHA-256（小文字hex）。 */
public record MapVersion(String mapVersion, int formatVersion, String manifestHash) {
    /** 通常プリセットへ公開する前の検査・描画用版。Codecの保存形式は変更しない。 */
    public boolean isPreview() { return mapVersion.equals("earth_002"); }

    public static final Codec<MapVersion> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("map_version").forGetter(MapVersion::mapVersion),
            Codec.INT.fieldOf("format_version").forGetter(MapVersion::formatVersion),
            Codec.STRING.fieldOf("manifest_hash").forGetter(MapVersion::manifestHash)
    ).apply(i, MapVersion::new));
}
