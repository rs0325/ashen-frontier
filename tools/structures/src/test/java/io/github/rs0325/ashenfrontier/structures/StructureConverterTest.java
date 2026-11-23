package io.github.rs0325.ashenfrontier.structures;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.rs0325.ashenfrontier.world.structure.FacilityManifest;
import io.github.rs0325.ashenfrontier.world.structure.FacilityPlacement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Random;

class StructureConverterTest {
    @TempDir Path temp;
    private static Path fixture;

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        fixture = Path.of(System.getProperty("ashen_frontier.structureFixturesPath"));
    }

    @Test void allAdaptersAgreeAndKeepOffset() throws Exception {
        var nbt = StructureInput.vanilla(fixture.resolve("boundary.nbt"));
        var schem = StructureInput.sponge(fixture.resolve("boundary.schem"));
        var mca = RegionInput.read(fixture.resolve("region"), temp, new int[]{-49,-16,-1,15,33,3}, "minecraft:overworld");
        assertEquals(FixtureWriter.SIZE, nbt.size());
        assertEquals(FixtureWriter.OFFSET, schem.offset());
        assertEquals(FixtureWriter.OFFSET, mca.input().offset());
        assertSameCells(nbt, schem);
        assertSameCells(nbt, mca.input());
        assertFalse(mca.hashes().isEmpty());
        for (var entry : mca.hashes().entrySet()) assertEquals(entry.getValue(), RegionInput.hash(fixture.resolve("region").resolve(entry.getKey())));
    }

    private static void assertSameCells(StructureInput left, StructureInput right) {
        var a = new HashSet<>(normalized(left));
        var b = new HashSet<>(normalized(right));
        var missing = new HashSet<>(a); missing.removeAll(b);
        var extra = new HashSet<>(b); extra.removeAll(a);
        assertTrue(missing.isEmpty() && extra.isEmpty(), () -> "不足: " + missing.stream().limit(3).toList() + " 余分: " + extra.stream().limit(3).toList());
    }

    private static List<String> normalized(StructureInput input) {
        return input.cells().stream().map(c -> c.pos().subtract(input.offset()).toShortString() + ":" + c.state()
                + ":" + (c.nbt() == null ? "" : withoutCoordinates(c.nbt()))).sorted().toList();
    }

    private static CompoundTag withoutCoordinates(CompoundTag tag) {
        var copy = tag.copy(); copy.remove("x"); copy.remove("y"); copy.remove("z"); return copy;
    }

    @Test void unsupportedVersionsAndUnknownBlocksAreRejected() throws Exception {
        var root = NbtIo.readCompressed(fixture.resolve("boundary.schem"), NbtAccounter.unlimitedHeap());
        for (int version : new int[]{0, 1, 2, 4, 999}) {
            root.getCompound("Schematic").putInt("Version", version);
            var file = temp.resolve("v" + version + ".schem"); NbtIo.writeCompressed(root, file);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> StructureInput.sponge(file)).getMessage().contains("WorldEdit"));
        }
        var nbt = NbtIo.readCompressed(fixture.resolve("boundary.nbt"), NbtAccounter.unlimitedHeap());
        nbt.putInt("DataVersion", 9999); var file = temp.resolve("future.nbt"); NbtIo.writeCompressed(nbt, file);
        assertThrows(IllegalArgumentException.class, () -> StructureInput.vanilla(file));
        assertThrows(IllegalArgumentException.class, () -> StructureInput.state("unknown:not_registered"));
        assertThrows(IllegalArgumentException.class, () -> StructureInput.state("minecraft:oak_door[facing=up]"));
    }

    @Test void malformedVarintsAndMissingChunksAreRejected() throws Exception {
        var root = NbtIo.readCompressed(fixture.resolve("boundary.schem"), NbtAccounter.unlimitedHeap());
        root.getCompound("Schematic").getCompound("Blocks").putByteArray("Data", new byte[]{(byte)128});
        var file = temp.resolve("short.schem"); NbtIo.writeCompressed(root, file);
        assertThrows(IllegalArgumentException.class, () -> StructureInput.sponge(file));
        assertThrows(IllegalArgumentException.class, () -> RegionInput.read(fixture.resolve("region"), temp,
                new int[]{-80,-16,-1,15,33,3}, "minecraft:overworld"));
        assertThrows(IllegalArgumentException.class, () -> RegionInput.read(fixture.resolve("region"), temp,
                new int[]{-1024,-16,-1,15,33,3}, "minecraft:overworld"));
    }

    @Test void multiByteVarintAndNegativeOffset() throws Exception {
        var root = NbtIo.readCompressed(fixture.resolve("boundary.schem"), NbtAccounter.unlimitedHeap());
        var tag = root.getCompound("Schematic");
        tag.putShort("Width", (short)1); tag.putShort("Height", (short)1); tag.putShort("Length", (short)1);
        var palette = new CompoundTag(); palette.putInt("minecraft:stone", 300);
        var blocks = tag.getCompound("Blocks"); blocks.put("Palette", palette);
        blocks.putByteArray("Data", new byte[]{(byte)0xac, 2}); blocks.put("BlockEntities", new ListTag());
        var file = temp.resolve("varint.schem"); NbtIo.writeCompressed(root, file);
        var input = StructureInput.sponge(file);
        assertEquals(FixtureWriter.OFFSET, input.cells().getFirst().pos());
        assertTrue(input.cells().getFirst().state().is(Blocks.STONE));
    }

    @Test void externalChunkIsReadFromCopy() throws Exception {
        var regions = temp.resolve("original-region"); Files.createDirectory(regions);
        try (var files = Files.list(fixture.resolve("region"))) {
            for (var file : files.toList()) Files.copy(file, regions.resolve(file.getFileName()));
        }
        var pos = new ChunkPos(-4,-1);
        var info = new RegionStorageInfo("test", Level.OVERWORLD, "chunk");
        try (var region = new RegionFile(info, regions.resolve("r.-1.-1.mca"), regions, false)) {
            CompoundTag tag;
            try (var stream = region.getChunkDataInputStream(pos)) { tag = NbtIo.read(stream); }
            byte[] padding = new byte[2 * 1024 * 1024]; new Random(58).nextBytes(padding); tag.putByteArray("fixture_padding", padding);
            try (var stream = region.getChunkDataOutputStream(pos)) { NbtIo.write(tag, stream); }
        }
        var external = regions.resolve("c.-4.-1.mcc"); assertTrue(Files.isRegularFile(external));
        String before = RegionInput.hash(external);
        var result = RegionInput.read(regions, temp, new int[]{-49,-16,-1,-49,-16,-1}, "minecraft:overworld");
        assertEquals(1, result.input().cells().size());
        assertEquals(before, RegionInput.hash(external));
        assertEquals(before, result.hashes().get("c.-4.-1.mcc"));
    }

    @Test void incompleteDoorAndDamagedIndexAreRejected() throws Exception {
        var converter = new StructureConverter(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var file = config("nbt", temp.resolve("incomplete").toString());
        var cells = FixtureWriter.cells().stream().filter(c -> !c.pos().equals(new BlockPos(47,48,1))).toList();
        var input = temp.resolve("missing-door.nbt"); NbtIo.writeCompressed(StructureConverter.templateTag(FixtureWriter.SIZE, cells), input);
        var config = JsonParser.parseString(Files.readString(file)).getAsJsonObject(); config.addProperty("input", input.toString()); Files.writeString(file, config.toString());
        assertThrows(IllegalArgumentException.class, () -> converter.convert(file));
        var manifest = converter.convert(config("nbt", temp.resolve("valid").toString()));
        var json = JsonParser.parseString(StructureConverter.JSON.toJson(manifest)).getAsJsonObject();
        json.add("chunks", new JsonObject());
        var bad = StructureConverter.JSON.fromJson(json, FacilityManifest.class);
        assertThrows(IllegalArgumentException.class, bad::validate);
        assertThrows(IllegalArgumentException.class, () -> new FacilityPlacement(List.of(manifest, manifest)));
    }

    @Test void doubleChestsRequireReciprocalPartners() throws Exception {
        var converter = new StructureConverter(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        String[][] cases = {{"left", "single"}, {"single", "right"}, {"left", "left"}, {"single", "left"},
                {"right", "single"}, {"left", "right", "east"}, {"left", "right"}, {"single", "single"}};
        for (int i = 0; i < cases.length; i++) {
            var types = cases[i];
            var cells = FixtureWriter.cells().stream().map(cell -> {
                int index = cell.pos().getX() - 47;
                if (index < 0 || index > 1 || cell.pos().getY() != 1 || cell.pos().getZ() != 3) return cell;
                String facing = index == 1 && types.length == 3 ? types[2] : "north";
                return new StructureInput.Cell(cell.pos(), StructureInput.state("minecraft:chest[facing=" + facing + ",type=" + types[index] + "]"), cell.nbt());
            }).toList();
            var input = temp.resolve("chests-" + i + ".nbt");
            NbtIo.writeCompressed(StructureConverter.templateTag(FixtureWriter.SIZE, cells), input);
            var file = config("nbt", temp.resolve("chests-" + i).toString());
            var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            json.addProperty("input", input.toString()); Files.writeString(file, json.toString());
            if (i < 6) assertThrows(IllegalArgumentException.class, () -> converter.convert(file), String.join("/", types));
            else converter.convert(file).validate();
        }
    }

    @Test void decayRejectsMultipartBlocksOnBothSides() throws Exception {
        var converter = new StructureConverter(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var valid = converter.convert(config("nbt", temp.resolve("valid-decay").toString()));
        for (String block : List.of("minecraft:oak_door", "minecraft:red_bed", "minecraft:tall_grass", "minecraft:sunflower", "minecraft:chest")) {
            for (String side : List.of("input", "output")) {
                var file = config("nbt", temp.resolve("invalid-decay").toString());
                var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                json.getAsJsonArray("allowed").add(block);
                json.getAsJsonArray("decay").get(0).getAsJsonObject().addProperty(side, block);
                Files.writeString(file, json.toString());
                assertTrue(assertThrows(IllegalArgumentException.class, () -> converter.convert(file)).getMessage().contains("単一"));
                var manifestJson = JsonParser.parseString(StructureConverter.JSON.toJson(valid)).getAsJsonObject();
                manifestJson.getAsJsonArray("decay").get(0).getAsJsonObject().addProperty(side, block);
                var manifest = StructureConverter.JSON.fromJson(manifestJson, FacilityManifest.class);
                assertThrows(IllegalArgumentException.class, () -> new FacilityPlacement(List.of(manifest)));
            }
        }
    }

    @Test void lootMustUseBuildingTablesIncludingGuaranteed() throws Exception {
        var converter = new StructureConverter(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        for (String table : List.of("ashen_frontier:chests/fixture", "minecraft:buildings/temperate/fixture",
                "ashen_frontier:buildings/temperate", "ashen_frontier:buildings/../fixture")) {
            var file = config("nbt", temp.resolve("invalid-loot").toString());
            var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            json.getAsJsonArray("loot").get(0).getAsJsonObject().addProperty("table", table);
            Files.writeString(file, json.toString());
            assertThrows(IllegalArgumentException.class, () -> converter.convert(file), table);
        }
        for (String region : List.of("temperate", "guaranteed")) {
            var file = config("nbt", temp.resolve(region).toString());
            var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            String table = "ashen_frontier:buildings/" + region + "/fixture";
            json.getAsJsonArray("loot").get(0).getAsJsonObject().addProperty("table", table);
            Files.writeString(file, json.toString());
            var manifest = converter.convert(file);
            assertEquals(table, manifest.loot().getFirst().table());
        }
    }

    private Path config(String format, String output) throws Exception {
        var config = JsonParser.parseString(Files.readString(fixture.resolve("convert.json"))).getAsJsonObject();
        config.addProperty("format", format);
        config.addProperty("input", fixture.resolve(format.equals("nbt") ? "boundary.nbt" : format.equals("mca") ? "region" : "boundary.schem").toString());
        config.addProperty("output", output);
        // 単体テストはバニラ登録のみで実行し、coreへの置換はGameTestでも照合する。
        config.getAsJsonObject("mapping").addProperty("minecraft:stone_bricks", "minecraft:stone_bricks");
        config.getAsJsonObject("mapping").addProperty("minecraft:oak_door", "minecraft:oak_door");
        config.getAsJsonObject("mapping").addProperty("minecraft:iron_block", "minecraft:iron_block");
        config.getAsJsonArray("allowed").add("minecraft:stone_bricks");
        config.getAsJsonArray("allowed").add("minecraft:oak_door");
        config.getAsJsonArray("allowed").add("minecraft:iron_block");
        config.getAsJsonArray("decay").get(0).getAsJsonObject().addProperty("input", "minecraft:stone_bricks");
        var file = temp.resolve(format + ".json"); Files.writeString(file, config.toString()); return file;
    }

    @Test void conversionRoundTripSanitizationAndSplit() throws Exception {
        var converter = new StructureConverter(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        for (String format : List.of("nbt", "sponge3", "mca")) {
            var output = temp.resolve(format + "-output");
            var manifest = converter.convert(config(format, output.toString()));
            manifest.validate(); assertEquals(4, manifest.pieces().size());
            assertEquals(manifest.index(), manifest.chunks());
            assertTrue(manifest.chunks().keySet().stream().anyMatch(k -> k.startsWith("-")));
            var cells = new HashMap<BlockPos, StructureInput.Cell>();
            for (var piece : manifest.pieces()) {
                var id = ResourceLocation.parse(piece.template());
                var input = StructureInput.vanilla(output.resolve("data/" + id.getNamespace() + "/structure/" + id.getPath() + ".nbt"));
                assertTrue(input.size().getX() <= 48 && input.size().getY() <= 48);
                for (var cell : input.cells()) cells.put(cell.pos().offset(FacilityManifest.pos(piece.offset())), cell);
            }
            assertTrue(cells.get(new BlockPos(1,1,1)).state().isAir());
            assertTrue(cells.get(new BlockPos(0,2,0)).state().is(Blocks.STRUCTURE_VOID));
            for (int x : new int[]{47,48}) {
                var chest = cells.get(new BlockPos(x,1,3)); assertFalse(chest.nbt().contains("Items")); assertFalse(chest.nbt().contains("LootTable"));
                assertTrue(cells.get(new BlockPos(x,1,1)).state().getBlock() instanceof BedBlock);
            }
            assertTrue(cells.get(new BlockPos(47,48,1)).state().getBlock() instanceof DoorBlock);
            assertFalse(cells.get(new BlockPos(2,1,1)).nbt().toString().contains("clickEvent"));
            assertTrue(cells.get(new BlockPos(2,1,1)).nbt().toString().contains("境界の資料"));
            assertTrue(cells.get(new BlockPos(3,1,1)).state().is(Blocks.STRUCTURE_VOID));
            assertThrows(IllegalArgumentException.class, () -> converter.convert(config(format, output.toString())));
        }
    }

    @Test void unapprovedOutputAndRightsFailWithoutPublishing() throws Exception {
        var converter = new StructureConverter(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var file = config("nbt", temp.resolve("denied").toString());
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.getAsJsonObject("rights").addProperty("redistribution", false); Files.writeString(file, json.toString());
        assertThrows(IllegalArgumentException.class, () -> converter.convert(file));
        assertFalse(Files.exists(temp.resolve("denied")));
        var blocked = config("nbt", temp.resolve("core-forbidden").toString());
        var forbidden = JsonParser.parseString(Files.readString(blocked)).getAsJsonObject();
        forbidden.getAsJsonObject("mapping").addProperty("minecraft:iron_block", "minecraft:sculk");
        forbidden.getAsJsonArray("allowed").add("minecraft:sculk"); Files.writeString(blocked, forbidden.toString());
        assertThrows(IllegalArgumentException.class, () -> converter.convert(blocked));
        json.getAsJsonObject("rights").addProperty("redistribution", true);
        json.add("allowed", new JsonArray()); Files.writeString(file, json.toString());
        assertThrows(IllegalArgumentException.class, () -> converter.convert(file));
        assertFalse(Files.exists(temp.resolve("denied")));
    }
}
