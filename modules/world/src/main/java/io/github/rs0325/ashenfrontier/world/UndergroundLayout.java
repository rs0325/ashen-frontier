package io.github.rs0325.ashenfrontier.world;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** マップ版に属する地下制作データ。depthは中心の地表から下へのブロック数。 */
public record UndergroundLayout(String map_version, int format_version, long fixed_seed, String salt,
                                List<Deposit> deposits, List<Space> spaces) {
    public record Deposit(String id, String block, String host, int x, int z, int depth,
                          List<Integer> radii, String shape, boolean required, boolean lens, Boolean vanilla) {}
    public record Space(String id, String kind, String facility, int x, int z, int end_x, int end_z,
                        int depth, int width, int height) {}

    /** 呼出し元でmanifestを照合した後に読む。欠落・版違いは生成前に停止する。 */
    public static UndergroundLayout read(MapVersion version) {
        String path = "/data/ashen_frontier/fixed_map/" + version.mapVersion() + "/underground.json";
        try (var stream = UndergroundLayout.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("地下資産がありません: " + version.mapVersion());
            var result = new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), UndergroundLayout.class);
            if (!result.map_version.equals(version.mapVersion()) || result.format_version != version.formatVersion()) {
                throw new IllegalStateException("地下資産の版が一致しません: " + version.mapVersion());
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("地下資産を読めません: " + version.mapVersion(), e);
        }
    }
}
