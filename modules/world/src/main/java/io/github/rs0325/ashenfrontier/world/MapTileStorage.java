package io.github.rs0325.ashenfrontier.world;

import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** ディスク操作と縮小タイル生成を単一IOスレッドで直列化する。クライアントの描画状態には触れない。 */
final class MapTileStorage {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(
            runnable -> Thread.ofPlatform().name("ashen-frontier-map-io").daemon(true).unstarted(runnable));
    private final CompletableFuture<Path> directory;

    MapTileStorage(Path root, String identity, Path localWorld) {
        directory = CompletableFuture.supplyAsync(() -> {
            try {
                String world = localWorld == null ? identity : identity + ":"
                        + Files.readAttributes(localWorld, BasicFileAttributes.class).creationTime().toMillis();
                String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(world.getBytes(StandardCharsets.UTF_8)));
                return Files.createDirectories(root.resolve(key));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }, IO);
    }

    CompletableFuture<MapTileData> load(MapTileKey key) {
        return CompletableFuture.supplyAsync(() -> {
            var root = directory.join();
            try {
                return read(root, key);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, IO);
    }

    CompletableFuture<List<MapTileKey>> save(MapTileKey key, MapTileData snapshot) {
        return CompletableFuture.supplyAsync(() -> {
            var root = directory.join();
            try {
                var changed = new ArrayList<MapTileKey>();
                var tile = snapshot;
                var at = key;
                while (true) {
                    write(root, at, tile);
                    changed.add(at);
                    if (at.level() == MapTileKey.MAX_LEVEL) break;
                    var parent = read(root, at.parent());
                    tile.reduceInto(parent, at.x() & 1, at.z() & 1);
                    tile = parent;
                    at = at.parent();
                }
                return List.copyOf(changed);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, IO).whenComplete((result, failure) -> {
            if (failure != null) LogUtils.getLogger().error("探索地図の保存に失敗しました: {}", key, failure);
        });
    }

    private static MapTileData read(Path root, MapTileKey key) throws IOException {
        Path path = file(root, key);
        if (!Files.exists(path)) return new MapTileData();
        try (var stream = Files.newInputStream(path)) {
            return MapTileData.read(stream);
        }
    }

    private static void write(Path root, MapTileKey key, MapTileData tile) throws IOException {
        Path path = file(root, key);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (var stream = Files.newOutputStream(temporary)) {
            tile.write(stream);
        }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path file(Path root, MapTileKey key) {
        return root.resolve(key.level() + "_" + key.x() + "_" + key.z() + ".afmap");
    }

    CompletableFuture<Void> flush() { return CompletableFuture.runAsync(() -> directory.join(), IO); }

    static void shutdown() {
        IO.shutdown();
        try {
            if (!IO.awaitTermination(30, TimeUnit.SECONDS)) {
                LogUtils.getLogger().error("探索地図の保存が終了待ちの上限を超えました");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
