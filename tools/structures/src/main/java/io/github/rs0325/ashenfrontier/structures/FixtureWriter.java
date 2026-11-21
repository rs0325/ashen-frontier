package io.github.rs0325.ashenfrontier.structures;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.commands.arguments.blocks.BlockStateParser;

/** 外部データを使わず、同じ小さな施設を3形式へ書き出す再現用fixture。 */
public final class FixtureWriter {
    public static final BlockPos SIZE = new BlockPos(65, 50, 5);
    public static final BlockPos OFFSET = new BlockPos(-49, -16, -1);
    private FixtureWriter() {}

    public static List<StructureInput.Cell> cells() {
        Map<BlockPos, StructureInput.Cell> cells = new LinkedHashMap<>();
        for (int y = 0; y < SIZE.getY(); y++) for (int z = 0; z < SIZE.getZ(); z++) for (int x = 0; x < SIZE.getX(); x++) {
            var pos = new BlockPos(x, y, z);
            String block = y == 0 || (z == 0 && y == 1) ? "minecraft:stone_bricks" : "minecraft:structure_void";
            cells.put(pos, new StructureInput.Cell(pos, StructureInput.state(block), null));
        }
        put(cells, 1, 1, 1, "minecraft:air", null);
        put(cells, 47, 47, 1, "minecraft:oak_door[facing=east,half=lower,hinge=left]", null);
        put(cells, 47, 48, 1, "minecraft:oak_door[facing=east,half=upper,hinge=left]", null);
        put(cells, 47, 1, 1, "minecraft:red_bed[facing=east,part=foot]", null);
        put(cells, 48, 1, 1, "minecraft:red_bed[facing=east,part=head]", null);
        var chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        var items = new ListTag(); var item = new CompoundTag();
        item.putString("id", "minecraft:diamond"); item.putInt("count", 64); item.putByte("Slot", (byte)0); items.add(item);
        chest.put("Items", items); chest.putString("LootTable", "minecraft:chests/end_city_treasure");
        put(cells, 47, 1, 3, "minecraft:chest[facing=north,type=left]", chest);
        put(cells, 48, 1, 3, "minecraft:chest[facing=north,type=right]", chest);
        var sign = new CompoundTag(); var front = new CompoundTag(); var lines = new ListTag();
        lines.add(StringTag.valueOf("{\"text\":\"境界の資料 / Archive\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/kill @a\"}}"));
        for (int i = 1; i < 4; i++) lines.add(StringTag.valueOf("{\"text\":\"\"}"));
        front.put("messages", lines); sign.put("front_text", front); sign.putString("id", "minecraft:sign");
        put(cells, 2, 1, 1, "minecraft:oak_sign[rotation=3]", sign);
        var spawner = new CompoundTag(); spawner.putString("id", "minecraft:mob_spawner");
        var command = new CompoundTag(); command.putString("id", "minecraft:command_block"); command.putString("Command", "kill @a");
        put(cells, 3, 1, 1, "minecraft:spawner", spawner);
        put(cells, 4, 1, 1, "minecraft:command_block", command);
        put(cells, 5, 1, 1, "minecraft:iron_block", null);
        return List.copyOf(cells.values());
    }

    private static void put(Map<BlockPos, StructureInput.Cell> cells, int x, int y, int z, String state, CompoundTag tag) {
        var pos = new BlockPos(x, y, z);
        cells.put(pos, new StructureInput.Cell(pos, StructureInput.state(state), tag));
    }

    public static void write(Path directory) throws IOException {
        Files.createDirectories(directory);
        var cells = cells();
        var testTemplate = StructureConverter.templateTag(new BlockPos(144, 56, 144), List.of());
        var testPath = directory.resolve("test-resources/data/ashen_frontier_world/structure/structure_boundary.nbt");
        Files.createDirectories(testPath.getParent());
        NbtIo.writeCompressed(testTemplate, testPath);
        var vanilla = StructureConverter.templateTag(SIZE, cells);
        var entities = new ListTag(); var entity = new CompoundTag();
        entity.putString("id", "minecraft:creeper"); entities.add(entity); vanilla.put("entities", entities);
        NbtIo.writeCompressed(vanilla, directory.resolve("boundary.nbt"));
        var sponge = new CompoundTag();
        sponge.putInt("Version", 3); sponge.putInt("DataVersion", StructureInput.DATA_VERSION);
        sponge.putShort("Width", (short)SIZE.getX()); sponge.putShort("Height", (short)SIZE.getY()); sponge.putShort("Length", (short)SIZE.getZ());
        sponge.putIntArray("Offset", new int[]{OFFSET.getX(), OFFSET.getY(), OFFSET.getZ()});
        var palette = new CompoundTag(); var blocks = new CompoundTag(); var blockEntities = new ListTag();
        var bytes = new ByteArrayOutputStream();
        List<BlockState> states = new ArrayList<>();
        for (var cell : cells) {
            int index = states.indexOf(cell.state());
            if (index < 0) { index = states.size(); states.add(cell.state()); palette.putInt(BlockStateParser.serialize(cell.state()), index); }
            int value = index;
            do { int b = value & 127; value >>>= 7; bytes.write(value == 0 ? b : b | 128); } while (value != 0);
            if (cell.nbt() != null) {
                var be = new CompoundTag(); be.putString("Id", cell.nbt().getString("id"));
                be.putIntArray("Pos", new int[]{cell.pos().getX(), cell.pos().getY(), cell.pos().getZ()});
                var data = cell.nbt().copy(); data.remove("id"); be.put("Data", data); blockEntities.add(be);
            }
        }
        blocks.put("Palette", palette); blocks.putByteArray("Data", bytes.toByteArray()); blocks.put("BlockEntities", blockEntities);
        sponge.put("Blocks", blocks); sponge.put("Entities", entities); var root = new CompoundTag(); root.put("Schematic", sponge);
        NbtIo.writeCompressed(root, directory.resolve("boundary.schem"));
        var regionDir = directory.resolve("region"); Files.createDirectories(regionDir);
        var info = new RegionStorageInfo("fixture", ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld")), "chunk");
        Map<ChunkPos, List<StructureInput.Cell>> chunks = new LinkedHashMap<>();
        for (var cell : cells) {
            var absolute = cell.pos().offset(OFFSET);
            chunks.computeIfAbsent(new ChunkPos(absolute), k -> new ArrayList<>()).add(new StructureInput.Cell(absolute, cell.state(), cell.nbt()));
        }
        var names = chunks.keySet().stream().map(p -> "r." + p.getRegionX() + "." + p.getRegionZ() + ".mca").distinct().toList();
        for (String name : names) Files.deleteIfExists(regionDir.resolve(name));
        for (var entry : chunks.entrySet()) {
            var pos = entry.getKey();
            var path = regionDir.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
            try (var region = new RegionFile(info, path, regionDir, false)) {
                var tag = new CompoundTag(); tag.putInt("DataVersion", StructureInput.DATA_VERSION);
                tag.putInt("xPos", pos.x); tag.putInt("zPos", pos.z); tag.putString("Status", "minecraft:full");
                var sections = new ListTag(); var beList = new ListTag();
                for (int sy = Math.floorDiv(OFFSET.getY(), 16); sy <= Math.floorDiv(OFFSET.getY() + SIZE.getY() - 1, 16); sy++) {
                    var section = new CompoundTag(); section.putByte("Y", (byte)sy);
                    var storage = new SimpleBitStorage(4, 4096); var sectionPalette = new ListTag();
                    List<BlockState> sectionStates = new ArrayList<>();
                    sectionStates.add(StructureInput.state("minecraft:air"));
                    for (var cell : entry.getValue()) if (Math.floorDiv(cell.pos().getY(), 16) == sy) {
                        int index = sectionStates.indexOf(cell.state());
                        if (index < 0) { index = sectionStates.size(); sectionStates.add(cell.state()); }
                        storage.set((cell.pos().getY() & 15) * 256 + (cell.pos().getZ() & 15) * 16 + (cell.pos().getX() & 15), index);
                        if (cell.nbt() != null) {
                            var be = cell.nbt().copy(); be.putInt("x", cell.pos().getX()); be.putInt("y", cell.pos().getY()); be.putInt("z", cell.pos().getZ()); beList.add(be);
                        }
                    }
                    sectionStates.forEach(state -> sectionPalette.add(NbtUtils.writeBlockState(state)));
                    var data = new CompoundTag(); data.put("palette", sectionPalette);
                    if (sectionStates.size() > 1) data.putLongArray("data", storage.getRaw());
                    section.put("block_states", data); sections.add(section);
                }
                tag.put("sections", sections); tag.put("block_entities", beList);
                try (var stream = region.getChunkDataOutputStream(pos)) { NbtIo.write(tag, stream); }
            }
        }
    }
}
