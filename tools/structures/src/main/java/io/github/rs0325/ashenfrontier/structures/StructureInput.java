package io.github.rs0325.ashenfrontier.structures;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.github.rs0325.ashenfrontier.world.structure.FacilityManifest;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.util.HashSet;

/** 入力アダプター共通の、原点正規化前のブロック集合。 */
public record StructureInput(BlockPos size, BlockPos offset, List<Cell> cells) {
    public static final int DATA_VERSION = 3955;
    public record Cell(BlockPos pos, BlockState state, CompoundTag nbt) {}

    public static void version(CompoundTag tag) {
        if (tag.getInt("DataVersion") != DATA_VERSION)
            throw new IllegalArgumentException("Minecraft 1.21.1 (DataVersion=3955) で保存し直してください: " + tag.getInt("DataVersion"));
    }

    public static BlockState state(String text) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), text, false).blockState();
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("未対応のブロック状態: " + text, e);
        }
    }

    public static BlockState state(CompoundTag tag) {
        StringBuilder text = new StringBuilder(tag.getString("Name"));
        var properties = tag.getCompound("Properties");
        if (!properties.isEmpty()) {
            text.append('[');
            for (String key : properties.getAllKeys().stream().sorted().toList())
                text.append(key).append('=').append(properties.getString(key)).append(',');
            text.setCharAt(text.length() - 1, ']');
        }
        return state(text.toString());
    }

    public static ListTag ints(int... values) {
        var result = new ListTag();
        for (int value : values) result.add(IntTag.valueOf(value));
        return result;
    }

    public static BlockPos listPos(ListTag tag) {
        if (tag.size() != 3 || tag.getElementType() != Tag.TAG_INT) throw new IllegalArgumentException("座標/sizeはTAG_List<int>の3要素です");
        return new BlockPos(tag.getInt(0), tag.getInt(1), tag.getInt(2));
    }

    public static StructureInput vanilla(Path path) throws IOException {
        var tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        version(tag);
        if (!tag.contains("blocks", Tag.TAG_LIST) || !tag.contains("palette", Tag.TAG_LIST))
            throw new IllegalArgumentException("構造物NBTにはblocksとpaletteが必要です");
        if (tag.contains("palettes")) throw new IllegalArgumentException("複数paletteは事前に1つへ確定してください");
        var palette = tag.getList("palette", Tag.TAG_COMPOUND).stream().map(t -> state((CompoundTag)t)).toList();
        var size = listPos(tag.getList("size", Tag.TAG_INT));
        List<Cell> cells = new ArrayList<>();
        for (var entry : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag)entry;
            int index = block.getInt("state");
            if (index < 0 || index >= palette.size()) throw new IllegalArgumentException("palette番号が範囲外です");
            cells.add(new Cell(listPos(block.getList("pos", Tag.TAG_INT)), palette.get(index),
                    block.contains("nbt", Tag.TAG_COMPOUND) ? block.getCompound("nbt") : null));
        }
        var input = checked(new StructureInput(size, BlockPos.ZERO, cells));
        var template = new StructureTemplate();
        template.load(BuiltInRegistries.BLOCK.asLookup(), tag);
        if (!template.getSize().equals(size)) throw new IllegalArgumentException("標準templateの寸法が不一致です");
        return input;
    }

    public static StructureInput sponge(Path path) throws IOException {
        var root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        var tag = root.contains("Schematic") ? root.getCompound("Schematic") : root;
        int format = tag.getInt("Version");
        if (format != 3) throw new IllegalArgumentException("Sponge v" + format + "は未対応です。WorldEditでv3へ保存し直してください");
        version(tag);
        var size = new BlockPos(tag.getShort("Width") & 65535, tag.getShort("Height") & 65535, tag.getShort("Length") & 65535);
        var rawOffset = tag.contains("Offset") ? tag.getIntArray("Offset") : new int[3];
        var offset = FacilityManifest.pos(rawOffset);
        var blocks = tag.getCompound("Blocks");
        Map<Integer, BlockState> palette = new HashMap<>();
        var entries = blocks.getCompound("Palette");
        for (String key : entries.getAllKeys()) {
            int index = entries.getInt(key);
            if (index < 0 || palette.put(index, state(key)) != null) throw new IllegalArgumentException("palette番号が重複/負数です");
        }
        Map<BlockPos, CompoundTag> entities = new HashMap<>();
        for (var entry : blocks.getList("BlockEntities", Tag.TAG_COMPOUND)) {
            var be = (CompoundTag) entry;
            var data = be.getCompound("Data").copy();
            data.putString("id", be.getString("Id"));
            var pos = FacilityManifest.pos(be.getIntArray("Pos"));
            if (entities.put(pos, data) != null) throw new IllegalArgumentException("BlockEntity座標が重複しています");
        }
        byte[] data = blocks.getByteArray("Data");
        int cursor = 0;
        long volume = (long)size.getX() * size.getY() * size.getZ();
        if (volume <= 0 || volume > Integer.MAX_VALUE) throw new IllegalArgumentException("不正な寸法です");
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < volume; i++) {
            int value = 0, shift = 0;
            while (true) {
                if (cursor == data.length || shift > 28) throw new IllegalArgumentException("varintが途中で終了/長すぎます");
                int b = data[cursor++] & 255;
                if (shift == 28 && (b & 0xf8) != 0) throw new IllegalArgumentException("varintが整数範囲外です");
                value |= (b & 127) << shift;
                if ((b & 128) == 0) break;
                shift += 7;
            }
            var state = palette.get(value);
            if (state == null) throw new IllegalArgumentException("未定義palette番号: " + value);
            var pos = new BlockPos(i % size.getX(), i / (size.getX() * size.getZ()), i / size.getX() % size.getZ());
            cells.add(new Cell(pos.offset(offset), state, entities.remove(pos)));
        }
        if (cursor != data.length || !entities.isEmpty()) throw new IllegalArgumentException("余分なブロック/範囲外のBlockEntityがあります");
        return checked(new StructureInput(size, offset, cells));
    }

    public static StructureInput checked(StructureInput input) {
        if (input.size.getX() < 1 || input.size.getY() < 1 || input.size.getZ() < 1) throw new IllegalArgumentException("寸法は正の整数です");
        var occupied = new HashSet<BlockPos>();
        for (var cell : input.cells) {
            var p = cell.pos.subtract(input.offset);
            if (p.getX() < 0 || p.getY() < 0 || p.getZ() < 0 || p.getX() >= input.size.getX()
                    || p.getY() >= input.size.getY() || p.getZ() >= input.size.getZ() || !occupied.add(p))
                throw new IllegalArgumentException("範囲外/重複したブロック座標: " + cell.pos);
        }
        return input;
    }
}
