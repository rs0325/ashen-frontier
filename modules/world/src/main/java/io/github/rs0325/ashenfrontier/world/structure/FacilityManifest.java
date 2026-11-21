package io.github.rs0325.ashenfrontier.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 制作時に確定した施設。座標・保護箱・loot位置は分割前の共通ローカル座標。 */
public record FacilityManifest(int format, String id, int[] origin, String rotation, String mirror,
        int[] sourceOffset, Rights rights, List<Piece> pieces, List<int[]> protectedPaths,
        List<Loot> loot, List<Decay> decay, Map<String, List<Integer>> chunks) {
    public record Rights(String author, String source, String license, boolean redistribution,
                         boolean modification, String attribution, String inputSha256, String inputVersion) {}
    public record Piece(String template, int[] offset, int[] bounds) {}
    public record Loot(int[] pos, String table) {}
    /** BE・上下半分を持つブロックは、劣化の入力にも出力にも使えない。 */
    public record Decay(String input, String output, float probability) {
        public BlockState inputState() { return singleBlock(input); }
        public BlockState outputState() { return singleBlock(output); }

        private static BlockState singleBlock(String id) {
            var key = ResourceLocation.parse(id);
            if (!BuiltInRegistries.BLOCK.containsKey(key)) throw new IllegalArgumentException("未登録ブロック: " + id);
            var state = BuiltInRegistries.BLOCK.get(key).defaultBlockState();
            if (state.hasBlockEntity() || state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF))
                throw new IllegalArgumentException("劣化は単一の静的ブロック間で指定します: " + id);
            return state;
        }
    }

    public static BlockPos pos(int[] p) {
        if (p == null || p.length != 3) throw new IllegalArgumentException("座標は整数3要素です");
        return new BlockPos(p[0], p[1], p[2]);
    }

    public BlockPos transform(BlockPos local) {
        return StructureTemplate.transform(local, Mirror.valueOf(mirror), Rotation.valueOf(rotation), BlockPos.ZERO)
                .offset(pos(origin));
    }

    public BoundingBox box(int[] local) {
        if (local == null || local.length != 6) throw new IllegalArgumentException("境界箱は整数6要素です");
        for (int axis = 0; axis < 3; axis++) if (local[axis] > local[axis + 3]) throw new IllegalArgumentException("境界箱が逆転しています");
        return BoundingBox.fromCorners(transform(new BlockPos(local[0], local[1], local[2])),
                transform(new BlockPos(local[3], local[4], local[5])));
    }

    public Map<String, List<Integer>> index() {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        for (int i = 0; i < pieces.size(); i++) {
            var bounds = box(pieces.get(i).bounds);
            for (int x = Math.floorDiv(bounds.minX(), 16); x <= Math.floorDiv(bounds.maxX(), 16); x++) {
                for (int z = Math.floorDiv(bounds.minZ(), 16); z <= Math.floorDiv(bounds.maxZ(), 16); z++) {
                    result.computeIfAbsent(key(new ChunkPos(x, z)), k -> new ArrayList<>()).add(i);
                }
            }
        }
        return result;
    }

    public static String key(ChunkPos chunk) { return chunk.x + "," + chunk.z; }

    /** 不完全な制作物は生成前に停止する。版移行や索引の自動修復はしない。 */
    public void validate() {
        if (format != 1) throw new IllegalArgumentException("未対応の施設manifest版: " + format);
        resource(id);
        pos(origin);
        pos(sourceOffset);
        Mirror.valueOf(mirror);
        Rotation.valueOf(rotation);
        if (rights == null || rights.author == null || rights.author.isBlank() || rights.source == null
                || rights.source.isBlank() || rights.license == null || rights.license.isBlank()
                || !rights.redistribution || !rights.modification || rights.attribution == null
                || rights.inputSha256 == null || !rights.inputSha256.matches("[0-9a-f]{64}")
                || rights.inputVersion == null || rights.inputVersion.isBlank()) {
            throw new IllegalArgumentException("作者・出典・権利・入力版とハッシュを記録してください");
        }
        for (var piece : pieces) {
            resource(piece.template);
            var offset = pos(piece.offset);
            var b = piece.bounds;
            if (b.length != 6 || b[0] != offset.getX() || b[1] != offset.getY() || b[2] != offset.getZ())
                throw new IllegalArgumentException("断片の原点と境界が不一致です");
            for (int axis = 0; axis < 3; axis++) {
                int size = b[axis + 3] - b[axis] + 1;
                if (size < 1 || size > 48) throw new IllegalArgumentException("断片寸法は1〜48です");
            }
        }
        for (int i = 0; i < pieces.size(); i++) for (int j = 0; j < i; j++) {
            if (box(pieces.get(i).bounds).intersects(box(pieces.get(j).bounds)))
                throw new IllegalArgumentException("断片の境界が重複しています");
        }
        for (var path : protectedPaths) box(path);
        for (var marker : loot) { pos(marker.pos); lootTable(marker.table); }
        for (var rule : decay) {
            rule.inputState(); rule.outputState();
            if (!Float.isFinite(rule.probability) || rule.probability < 0 || rule.probability > 1)
                throw new IllegalArgumentException("劣化確率は0〜1です");
        }
        if (!index().equals(chunks)) throw new IllegalArgumentException("施設のチャンク索引が不一致です");
    }

    /** 一般lootと保証枠をG2の建物用テーブルへ限定する。 */
    public static ResourceLocation lootTable(String text) {
        var id = resource(text);
        if (!id.getNamespace().equals("ashen_frontier") || !id.getPath().matches("buildings/[^/]+/[^/]+"))
            throw new IllegalArgumentException("lootはashen_frontier:buildings/<region>/<facility>に指定します: " + text);
        return id;
    }

    public static ResourceLocation resource(String text) {
        var id = ResourceLocation.parse(text);
        for (String part : id.getPath().split("/")) if (part.equals(".") || part.equals("..") || part.isEmpty())
            throw new IllegalArgumentException("相対パスを含むIDは使用できません: " + text);
        return id;
    }
}
