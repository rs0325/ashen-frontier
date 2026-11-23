package io.github.rs0325.ashenfrontier.structures;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.security.NoSuchAlgorithmException;

/** 原本をRegionFileで開かず、MCA/MCCの作業コピーだけを標準readerへ渡す。 */
public final class RegionInput {
    private RegionInput() {}

    public static String hash(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(path)) {
                byte[] bytes = new byte[65536];
                int count;
                while ((count = stream.read(bytes)) != -1) digest.update(bytes, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public record Result(StructureInput input, Map<String, String> hashes) {}

    public static Result read(Path regionDirectory, Path scratch, int[] range, String dimension) throws IOException {
        if (range == null || range.length != 6) throw new IllegalArgumentException("MCAには絶対範囲6要素が必要です");
        var min = new BlockPos(range[0], range[1], range[2]);
        var max = new BlockPos(range[3], range[4], range[5]);
        for (int i = 0; i < 3; i++) if (range[i] > range[i + 3]) throw new IllegalArgumentException("MCA範囲が逆転しています");
        var source = regionDirectory.toRealPath();
        var copy = Files.createTempDirectory(scratch, "region-copy-");
        var info = new RegionStorageInfo("structure_conversion", ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(dimension)), "chunk");
        Map<String, String> hashes = new LinkedHashMap<>();
        List<StructureInput.Cell> cells = new ArrayList<>();
        try {
            for (int rx = Math.floorDiv(min.getX(), 512); rx <= Math.floorDiv(max.getX(), 512); rx++) {
                for (int rz = Math.floorDiv(min.getZ(), 512); rz <= Math.floorDiv(max.getZ(), 512); rz++) {
                    var filename = "r." + rx + "." + rz + ".mca";
                    copy(source, copy, filename, hashes);
                    int x0 = Math.max(Math.floorDiv(min.getX(), 16), rx * 32), x1 = Math.min(Math.floorDiv(max.getX(), 16), rx * 32 + 31);
                    int z0 = Math.max(Math.floorDiv(min.getZ(), 16), rz * 32), z1 = Math.min(Math.floorDiv(max.getZ(), 16), rz * 32 + 31);
                    for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) {
                        String external = "c." + cx + "." + cz + ".mcc";
                        if (Files.isRegularFile(source.resolve(external))) copy(source, copy, external, hashes);
                    }
                    try (var region = new RegionFile(info, copy.resolve(filename), copy, false)) {
                        for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) {
                            var chunkPos = new ChunkPos(cx, cz);
                            if (!region.hasChunk(chunkPos)) throw new IllegalArgumentException("欠損チャンク: " + chunkPos);
                            try (var stream = region.getChunkDataInputStream(chunkPos)) {
                                if (stream == null) throw new IllegalArgumentException("チャンクを読めません: " + chunkPos);
                                var tag = NbtIo.read(stream);
                                StructureInput.version(tag);
                                if (tag.getInt("xPos") != cx || tag.getInt("zPos") != cz || !tag.getString("Status").equals("minecraft:full"))
                                    throw new IllegalArgumentException("未完成/座標不一致のチャンク: " + chunkPos);
                                readChunk(tag, chunkPos, min, max, cells);
                            }
                        }
                    }
                }
            }
            for (var entry : hashes.entrySet()) if (!hash(source.resolve(entry.getKey())).equals(entry.getValue()))
                throw new IllegalStateException("取込み中に原本が変更されました: " + entry.getKey());
            return new Result(StructureInput.checked(new StructureInput(max.subtract(min).offset(1, 1, 1), min, cells)), hashes);
        } finally {
            try (var files = Files.list(copy)) { for (var path : files.toList()) Files.delete(path); }
            Files.delete(copy);
        }
    }

    private static void copy(Path source, Path copy, String name, Map<String, String> hashes) throws IOException {
        var path = source.resolve(name);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("必要なregionファイルがありません: " + name);
        String before = hash(path);
        Files.copy(path, copy.resolve(name));
        if (!hash(copy.resolve(name)).equals(before) || !hash(path).equals(before)) throw new IOException("原本のコピー照合に失敗: " + name);
        hashes.put(name, before);
    }

    private static void readChunk(CompoundTag tag, ChunkPos chunk, BlockPos min, BlockPos max, List<StructureInput.Cell> cells) {
        Map<BlockPos, CompoundTag> entities = new HashMap<>();
        for (var entry : tag.getList("block_entities", Tag.TAG_COMPOUND)) {
            var be = (CompoundTag)entry;
            entities.put(new BlockPos(be.getInt("x"), be.getInt("y"), be.getInt("z")), be);
        }
        Map<Integer, CompoundTag> sections = new HashMap<>();
        for (var section : tag.getList("sections", Tag.TAG_COMPOUND)) {
            var sectionTag = (CompoundTag)section;
            if (sections.put((int)sectionTag.getByte("Y"), sectionTag) != null) throw new IllegalArgumentException("sectionが重複しています");
        }
        for (int sy = Math.floorDiv(min.getY(), 16); sy <= Math.floorDiv(max.getY(), 16); sy++) {
            var section = sections.get(sy);
            var states = section == null ? new CompoundTag() : section.getCompound("block_states");
            var palette = states.getList("palette", Tag.TAG_COMPOUND).stream().map(t -> StructureInput.state((CompoundTag)t)).toList();
            if (section != null && palette.isEmpty()) throw new IllegalArgumentException("section paletteがありません");
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            var storage = palette.size() > 1 ? new SimpleBitStorage(bits, 4096, states.getLongArray("data")) : null;
            for (int y = Math.max(min.getY(), sy * 16); y <= Math.min(max.getY(), sy * 16 + 15); y++)
                for (int z = Math.max(min.getZ(), chunk.getMinBlockZ()); z <= Math.min(max.getZ(), chunk.getMaxBlockZ()); z++)
                    for (int x = Math.max(min.getX(), chunk.getMinBlockX()); x <= Math.min(max.getX(), chunk.getMaxBlockX()); x++) {
                        int index = storage == null ? 0 : storage.get((y & 15) * 256 + (z & 15) * 16 + (x & 15));
                        if (!palette.isEmpty() && index >= palette.size()) throw new IllegalArgumentException("section palette番号が範囲外です");
                        var pos = new BlockPos(x, y, z);
                        cells.add(new StructureInput.Cell(pos, palette.isEmpty() ? Blocks.AIR.defaultBlockState() : palette.get(index), entities.get(pos)));
                    }
        }
    }
}
