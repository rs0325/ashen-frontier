package io.github.rs0325.ashenfrontier.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** 生成器と独立して保存する照合記録。版の変更や移行は行わない。 */
public final class MapSavedData extends SavedData {
    public static final String ID = "ashen_frontier_map";
    private final MapVersion version;

    private MapSavedData(MapVersion version) { this.version = version; }

    public MapVersion mapVersion() { return version; }

    /** 固定マップのoverworldでだけ呼ぶ。初回は版を記録し、既存記録は完全一致を要求する。 */
    public static MapSavedData verify(ServerLevel level, MapVersion expected) {
        var factory = new Factory<>(() -> {
            var data = new MapSavedData(expected);
            data.setDirty();
            return data;
        }, (tag, registries) -> new MapSavedData(decode(tag)), null);
        MapSavedData data = level.getDataStorage().computeIfAbsent(factory, ID);
        requireMatch(data.version, expected);
        return data;
    }

    static MapVersion decode(CompoundTag tag) {
        return MapVersion.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }

    static void requireMatch(MapVersion saved, MapVersion expected) {
        if (!saved.equals(expected)) {
            throw new IllegalStateException("固定マップ版 " + expected.mapVersion() + ": SavedDataの記録 " + saved + " と生成器 " + expected + " が一致しません");
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return (CompoundTag) MapVersion.CODEC.encodeStart(NbtOps.INSTANCE, version).getOrThrow();
    }
}
