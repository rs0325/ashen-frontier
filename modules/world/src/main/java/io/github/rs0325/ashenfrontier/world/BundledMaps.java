package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.HashSet;

/** クラスパスに同梱した版だけを解決する。ワールドやデータパックから資産を上書きしない。 */
public final class BundledMaps {
    private static final Set<MapVersion> VERIFIED = new HashSet<>();
    private BundledMaps() {}

    /** 指定版のmanifestと資産を照合し、不足・不一致は版名を含む例外にする。 */
    public static synchronized void require(MapVersion version) {
        if (VERIFIED.contains(version)) return;
        if (!version.mapVersion().matches("[a-z0-9_-]+")) {
            throw new IllegalStateException("固定マップ版 " + version.mapVersion() + ": 不正な版名です");
        }
        String root = "/data/ashen_frontier/fixed_map/" + version.mapVersion() + "/";
        byte[] bytes = read(root + "manifest.json", version.mapVersion());
        var manifest = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!manifest.get("map_version").getAsString().equals(version.mapVersion())
                || manifest.get("format_version").getAsInt() != version.formatVersion()
                || version.formatVersion() != 1 || !hash(bytes).equals(version.manifestHash())) {
            throw new IllegalStateException("固定マップ版 " + version.mapVersion() + ": manifestの版・形式・ハッシュが一致しません");
        }
        var files = manifest.getAsJsonObject("files");
        if (!files.keySet().containsAll(Set.of("height.png", "region.png", "water.png", "soil.png",
                "geology.png", "coast_river.png", "index.json", "geology_profiles.json", "underground.json"))) {
            throw new IllegalStateException("固定マップ版 " + version.mapVersion() + ": 必須レイヤー・索引が不足しています");
        }
        for (var entry : files.entrySet()) {
            if (!hash(read(root + entry.getKey(), version.mapVersion())).equals(entry.getValue().getAsString())) {
                throw new IllegalStateException("固定マップ版 " + version.mapVersion() + ": 資産ハッシュ不一致 " + entry.getKey());
            }
        }
        VERIFIED.add(version);
    }

    private static byte[] read(String path, String version) {
        try (var stream = BundledMaps.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("固定マップ版 " + version + ": 同梱資産が不足しています " + path);
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("固定マップ版 " + version + ": 資産を読めません " + path, e);
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
