package io.github.rs0325.ashenfrontier.world;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** CPUデータは最大128タイル。GPUへ置くのは現在の表示タイルだけで、IO完了の待機は行わない。 */
final class MapTileCache {
    static final int LIMIT = 128;
    static final int VIEW_LIMIT = 96;
    private final MapTileStorage storage;
    private final Map<MapTileKey, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private Set<MapTileKey> visible = Set.of();
    private int tick;

    private static final class Entry {
        MapTileData data;
        CompletableFuture<MapTileData> loading;
        CompletableFuture<List<MapTileKey>> saving;
        boolean dirty;
        boolean upload;
        int savedAt;
        ResourceLocation texture;
        boolean textureBlocks;
    }

    MapTileCache(MapTileStorage storage) { this.storage = storage; }

    MapTileData get(MapTileKey key) {
        var entry = entries.get(key);
        if (entry != null) return entry.data;
        long loading = entries.values().stream().filter(value -> value.loading != null).count();
        if (loading >= 4) return null;
        if (entries.size() >= LIMIT) {
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                var candidate = iterator.next();
                var value = candidate.getValue();
                if (visible.contains(candidate.getKey()) || value.dirty || value.saving != null || value.loading != null) continue;
                release(value);
                iterator.remove();
                break;
            }
            if (entries.size() >= LIMIT) return null;
        }
        entry = new Entry();
        entry.loading = storage.load(key);
        entries.put(key, entry);
        return null;
    }

    void changed(MapTileKey key) {
        var entry = entries.get(key);
        entry.dirty = true;
        entry.upload = true;
    }

    void view(Set<MapTileKey> keys) {
        visible = Set.copyOf(keys);
        for (var entry : entries.entrySet()) {
            if (!visible.contains(entry.getKey())) release(entry.getValue());
        }
    }

    ResourceLocation texture(MapTileKey key, boolean blocks) {
        var data = get(key);
        if (data == null) return null;
        var entry = entries.get(key);
        return entry.textureBlocks == blocks ? entry.texture : null;
    }

    private static void release(Entry entry) {
        if (entry.texture == null) return;
        Minecraft.getInstance().getTextureManager().release(entry.texture);
        entry.texture = null;
    }

    void tick(boolean blocks) {
        tick++;
        var invalid = new HashSet<MapTileKey>();
        for (var item : entries.entrySet()) {
            var entry = item.getValue();
            if (entry.loading != null && entry.loading.isDone()) {
                try {
                    entry.data = entry.loading.join();
                } catch (CompletionException e) {
                    LogUtils.getLogger().error("探索地図を読めません。再受信した範囲から再生成します: {}", item.getKey(), e);
                    entry.data = new MapTileData();
                }
                entry.loading = null;
                entry.upload = true;
            }
            if (entry.saving != null && entry.saving.isDone()) {
                try {
                    for (var key : entry.saving.join()) if (key.level() > 0) invalid.add(key);
                } catch (CompletionException e) {
                    entry.dirty = true;
                }
                entry.saving = null;
            }
        }
        for (var key : invalid) {
            var entry = entries.remove(key);
            if (entry != null) release(entry);
        }
        int uploads = 0;
        int saving = (int) entries.values().stream().filter(entry -> entry.saving != null).count();
        for (var item : entries.entrySet()) {
            var entry = item.getValue();
            if (entry.data == null) continue;
            if (entry.dirty && entry.saving == null && saving < 2 && tick - entry.savedAt >= 100) {
                entry.saving = storage.save(item.getKey(), entry.data.copy());
                entry.dirty = false;
                entry.savedAt = tick;
                saving++;
            }
            if (visible.contains(item.getKey()) && (entry.texture == null || entry.upload || entry.textureBlocks != blocks) && uploads < 4) {
                var textures = Minecraft.getInstance().getTextureManager();
                var texture = entry.texture == null ? null : (DynamicTexture) textures.getTexture(entry.texture);
                var image = texture == null ? new NativeImage(MapTileKey.SIZE, MapTileKey.SIZE, true) : texture.getPixels();
                int[] pixels = blocks ? entry.data.blocks : entry.data.relief;
                for (int z = 0; z < MapTileKey.SIZE; z++) for (int x = 0; x < MapTileKey.SIZE; x++) {
                    int pixel = pixels[z * MapTileKey.SIZE + x];
                    image.setPixelRGBA(x, z, (pixel >>> 24) == 0 ? 0 : MapViewMath.abgr(pixel));
                }
                if (texture == null) {
                    var key = item.getKey();
                    entry.texture = AshenFrontierCore.id("map/tile/" + key.level() + "_" + key.x() + "_" + key.z());
                    textures.register(entry.texture, new DynamicTexture(image));
                } else texture.upload();
                entry.upload = false;
                entry.textureBlocks = blocks;
                uploads++;
            }
        }
    }

    void close() {
        for (var item : entries.entrySet()) {
            var entry = item.getValue();
            release(entry);
            // 退出後は変更されない配列の所有権を渡し、描画スレッドで全コピーしない。
            if (entry.dirty && entry.data != null) storage.save(item.getKey(), entry.data);
        }
        entries.clear();
    }
}
