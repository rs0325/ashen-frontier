package io.github.rs0325.ashenfrontier.world;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** マップ版に属する地下制作データ。depthは中心の地表から下へのブロック数。 */
public record UndergroundLayout(String map_version, int format_version, long fixed_seed, String salt,
                                List<Deposit> deposits, List<Space> spaces, D4 d4) {
    public record Deposit(String id, String block, String host, int x, int z, int depth,
                          List<Integer> radii, String shape, boolean required, boolean lens, Boolean vanilla,
                          Integer center_y, Integer residual_blocks, String deposit_type, List<OreColumn> ore_columns) {}
    public record Space(String id, String kind, String facility, int x, int z, int end_x, int end_z,
                        int depth, int width, int height) {}

    /** 残鉱そのものの絶対座標。ysは同じX/Zに属する鉱石のY。D5が掘削から保護する。 */
    public record OreColumn(int x, int z, List<Integer> ys) {}

    /** D4の初期生成区間とD5向けの掘削保護。実際の採取残量や仮想深部資源は持たない。 */
    public record D4(int schema_version, List<List<String>> palette, List<VoxelColumn> columns,
                     List<Handoff> handoffs, Cave cave) {}
    /** runsは絶対Yの[下端, 上端（含む）, palette番号]の反復。列内では昇順・非重複。 */
    public record VoxelColumn(int x, int z, List<Integer> runs) {}
    /** boundsは[x0,y0,z0,x1,y1,z1]の上限非包含。正確な残鉱保護はdepositsのore_columnsを使う。 */
    public record Handoff(String id, int residual_blocks, List<Integer> bounds, List<List<Integer>> faces,
                          List<List<Integer>> necessary_portals, String surface_clue, String mine_id, boolean oil_seal) {}
    /** entranceは[X,Z,床Y]、axesの各点は[X,Z,床Y,全幅,高さ]。人工坑道を含まない。 */
    public record Cave(String id, List<Integer> entrance, List<List<List<Double>>> axes,
                       int minimum_roof, int portal_transition) {}

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
