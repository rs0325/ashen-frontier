package io.github.rs0325.ashenfrontier.structures;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import io.github.rs0325.ashenfrontier.core.vanilla.RemovedItems;
import io.github.rs0325.ashenfrontier.world.structure.FacilityManifest;
import io.github.rs0325.ashenfrontier.core.catalog.RelicEquipmentEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 開発時だけ実行する。許可・対応関係を明示した設定から標準NBTと索引を生成する。 */
public final class StructureConverter {
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public record Config(String format, String input, String output, String dimension, int[] range,
            String mapVersion, String id, int[] origin, String rotation, String mirror,
            FacilityManifest.Rights rights, Map<String, String> mapping, Set<String> allowed,
            List<int[]> protectedPaths, List<FacilityManifest.Loot> loot, List<FacilityManifest.Decay> decay) {}
    private static final Set<String> REMOVED = Set.of("minecraft:spawner", "minecraft:trial_spawner", "minecraft:command_block",
            "minecraft:chain_command_block", "minecraft:repeating_command_block", "minecraft:structure_block", "minecraft:jigsaw");
    private static final Set<String> FORBIDDEN = forbidden();
    private final HolderLookup.Provider registries;

    public StructureConverter(HolderLookup.Provider registries) { this.registries = registries; }

    private static Set<String> forbidden() {
        try (var stream = RemovedItems.class.getResourceAsStream("/data/ashen_frontier/tags/item/removed.json")) {
            if (stream == null) throw new IllegalStateException("coreの禁止品定義がありません");
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Set<String> ids = new HashSet<>();
                for (var value : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("values")) ids.add(value.getAsString());
                return Set.copyOf(ids);
            }
        } catch (IOException e) { throw new IllegalStateException("coreの禁止品定義を読めません", e); }
    }

    /** 設定内のパスは設定ファイルの親から解決。既存の出力先は上書きしない。 */
    public FacilityManifest convert(Path configFile) throws IOException {
        Config config;
        try (var reader = Files.newBufferedReader(configFile)) { config = JSON.fromJson(reader, Config.class); }
        var root = configFile.toAbsolutePath().getParent();
        var input = root.resolve(config.input()).normalize();
        var output = root.resolve(config.output()).normalize();
        if (Files.exists(output)) throw new IllegalArgumentException("出力先は未作成のディレクトリを指定してください: " + output);
        Files.createDirectories(output.getParent());
        var declared = config.rights();
        if (declared == null || declared.author() == null || declared.author().isBlank() || declared.source() == null
                || declared.source().isBlank() || declared.license() == null || declared.license().isBlank()
                || !declared.redistribution() || !declared.modification() || declared.attribution() == null)
            throw new IllegalArgumentException("入力を読む前に作者・出典・再配布/改変許諾・表示条件を記録してください");
        StructureInput model;
        Map<String, String> sourceHashes;
        switch (config.format()) {
            case "nbt" -> { sourceHashes = Map.of(input.getFileName().toString(), RegionInput.hash(input)); model = StructureInput.vanilla(input); }
            case "sponge3" -> { sourceHashes = Map.of(input.getFileName().toString(), RegionInput.hash(input)); model = StructureInput.sponge(input); }
            case "mca" -> { var result = RegionInput.read(input, output.getParent(), config.range(), config.dimension()); model = result.input(); sourceHashes = result.hashes(); }
            default -> throw new IllegalArgumentException("未対応の入力形式: " + config.format());
        }
        var rights = config.rights();
        String inputHash = config.format().equals("mca") ? hashText(JSON.toJson(sourceHashes)) : sourceHashes.values().iterator().next();
        rights = new FacilityManifest.Rights(rights.author(), rights.source(), rights.license(), rights.redistribution(),
                rights.modification(), rights.attribution(), inputHash, config.format() + ":1.21.1/3955");
        return write(config, model, rights, sourceHashes, output);
    }

    private static String hashText(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private FacilityManifest write(Config config, StructureInput input, FacilityManifest.Rights rights,
            Map<String, String> sourceHashes, Path output) throws IOException {
        var id = FacilityManifest.resource(config.id());
        if (!config.mapVersion().matches("[a-z0-9_]+")) throw new IllegalArgumentException("不正なマップ版です");
        Map<BlockPos, StructureInput.Cell> cells = new TreeMap<>(Comparator.<BlockPos>comparingInt(p -> p.getY())
                .thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX));
        List<String> removed = new ArrayList<>();
        for (var raw : input.cells()) {
            var local = raw.pos().subtract(input.offset());
            String source = BuiltInRegistries.BLOCK.getKey(raw.state().getBlock()).toString();
            BlockState state;
            if (REMOVED.contains(source)) { state = Blocks.STRUCTURE_VOID.defaultBlockState(); removed.add(source + "@" + local); }
            else state = map(raw.state(), config.mapping());
            String target = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (REMOVED.contains(target) || FORBIDDEN.contains(target) || (!state.is(Blocks.STRUCTURE_VOID) && !config.allowed().contains(target)))
                throw new IllegalArgumentException("未承認の出力ID: " + target + " (入力 " + source + ")");
            var tag = sanitize(local, state, raw.nbt());
            cells.put(local, new StructureInput.Cell(local, state, tag));
        }
        validatePairs(cells);
        var markerPositions = new HashSet<BlockPos>();
        for (var marker : config.loot()) {
            FacilityManifest.lootTable(marker.table());
            var pos = FacilityManifest.pos(marker.pos());
            var cell = cells.get(pos);
            if (!markerPositions.add(pos) || cell == null || !(entity(cell.state(), pos) instanceof RandomizableContainer))
                throw new IllegalArgumentException("lootマーカーは重複しない容器座標に指定します: " + pos);
        }
        for (var rule : config.decay()) {
            rule.inputState();
            rule.outputState();
            if (!config.allowed().contains(rule.output())
                    || REMOVED.contains(rule.output()) || FORBIDDEN.contains(rule.output())) throw new IllegalArgumentException("劣化は承認済みの単一静的ブロック間で指定します");
        }
        var stage = Files.createTempDirectory(output.getParent(), "structure-output-");
        boolean published = false;
        try {
            List<FacilityManifest.Piece> pieces = new ArrayList<>();
            Map<String, String> hashes = new TreeMap<>();
            for (int y = 0; y < input.size().getY(); y += 48) for (int z = 0; z < input.size().getZ(); z += 48)
                for (int x = 0; x < input.size().getX(); x += 48) {
                    var offset = new BlockPos(x, y, z);
                    var size = new BlockPos(Math.min(48, input.size().getX() - x), Math.min(48, input.size().getY() - y), Math.min(48, input.size().getZ() - z));
                    var fragment = cells.values().stream().filter(c -> inside(c.pos().subtract(offset), size)).map(c ->
                            new StructureInput.Cell(c.pos().subtract(offset), c.state(), c.nbt())).toList();
                    if (fragment.isEmpty()) continue;
                    String name = id.getPath() + "/" + x + "_" + y + "_" + z;
                    var templateId = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), name);
                    var path = stage.resolve("data/" + id.getNamespace() + "/structure/" + name + ".nbt");
                    Files.createDirectories(path.getParent());
                    var template = new StructureTemplate();
                    var expected = templateTag(size, fragment);
                    template.load(BuiltInRegistries.BLOCK.asLookup(), expected);
                    NbtIo.writeCompressed(template.save(new CompoundTag()), path);
                    var restored = StructureInput.vanilla(path);
                    if (!restored.size().equals(size) || !same(fragment, restored.cells())) throw new IllegalStateException("断片の再読込み照合が失敗しました: " + name);
                    var reloaded = new StructureTemplate();
                    reloaded.load(BuiltInRegistries.BLOCK.asLookup(), NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()));
                    if (!reloaded.save(new CompoundTag()).equals(template.save(new CompoundTag()))) throw new IllegalStateException("標準templateの往復照合に失敗しました");
                    pieces.add(new FacilityManifest.Piece(templateId.toString(), new int[]{x, y, z},
                            new int[]{x, y, z, x + size.getX() - 1, y + size.getY() - 1, z + size.getZ() - 1}));
                    hashes.put(stage.relativize(path).toString().replace('\\', '/'), RegionInput.hash(path));
                }
            var manifest = new FacilityManifest(1, config.id(), config.origin(), config.rotation(), config.mirror(),
                    new int[]{input.offset().getX(), input.offset().getY(), input.offset().getZ()}, rights, pieces,
                    config.protectedPaths(), config.loot(), config.decay(), Map.of());
            manifest = new FacilityManifest(manifest.format(), manifest.id(), manifest.origin(), manifest.rotation(), manifest.mirror(),
                    manifest.sourceOffset(), rights, pieces, manifest.protectedPaths(), manifest.loot(), manifest.decay(), manifest.index());
            manifest.validate();
            var index = stage.resolve("data/ashen_frontier/structures/" + config.mapVersion() + "/index.json");
            Files.createDirectories(index.getParent());
            Files.writeString(index, JSON.toJson(List.of(manifest)) + "\n");
            try (var reader = Files.newBufferedReader(index)) {
                var restored = JSON.fromJson(reader, FacilityManifest[].class)[0];
                restored.validate();
                if (!JSON.toJson(restored).equals(JSON.toJson(manifest))) throw new IllegalStateException("manifestの再読込み照合に失敗しました");
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("format", config.format()); report.put("dimension", config.dimension()); report.put("range", config.range());
            report.put("inputs", sourceHashes); report.put("outputs", hashes); report.put("removedBlocks", removed);
            report.put("entities", "全削除"); report.put("inventoryAndComponents", "全削除"); report.put("rights", rights);
            Files.writeString(stage.resolve("conversion-report.json"), JSON.toJson(report) + "\n");
            Files.move(stage, output);
            published = true;
            return manifest;
        } finally {
            if (!published) try (var paths = Files.walk(stage)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static boolean inside(BlockPos p, BlockPos size) {
        return p.getX() >= 0 && p.getY() >= 0 && p.getZ() >= 0 && p.getX() < size.getX() && p.getY() < size.getY() && p.getZ() < size.getZ();
    }

    private static boolean same(List<StructureInput.Cell> a, List<StructureInput.Cell> b) {
        Map<BlockPos, StructureInput.Cell> left = new HashMap<>(), right = new HashMap<>();
        a.forEach(c -> left.put(c.pos(), c)); b.forEach(c -> right.put(c.pos(), c));
        return left.equals(right);
    }

    public static CompoundTag templateTag(BlockPos size, List<StructureInput.Cell> cells) {
        var tag = new CompoundTag();
        tag.putInt("DataVersion", StructureInput.DATA_VERSION);
        tag.put("size", StructureInput.ints(size.getX(), size.getY(), size.getZ()));
        var palette = new ListTag(); var blocks = new ListTag();
        List<BlockState> states = new ArrayList<>();
        for (var cell : cells) {
            int index = states.indexOf(cell.state());
            if (index < 0) { index = states.size(); states.add(cell.state()); palette.add(NbtUtils.writeBlockState(cell.state())); }
            var block = new CompoundTag();
            block.put("pos", StructureInput.ints(cell.pos().getX(), cell.pos().getY(), cell.pos().getZ()));
            block.putInt("state", index);
            if (cell.nbt() != null) block.put("nbt", cell.nbt().copy());
            blocks.add(block);
        }
        tag.put("palette", palette); tag.put("blocks", blocks); tag.put("entities", new ListTag());
        return tag;
    }

    private static BlockState map(BlockState source, Map<String, String> mapping) {
        String id = BuiltInRegistries.BLOCK.getKey(source.getBlock()).toString();
        String target = mapping.get(id);
        if (target == null) return source;
        var result = StructureInput.state(target);
        if (target.contains("[")) return result;
        for (var property : source.getProperties()) {
            var dest = result.getBlock().getStateDefinition().getProperty(property.getName());
            if (dest == null) throw new IllegalArgumentException("マッピングで状態が失われます。出力状態を明示してください: " + id + " " + property.getName());
            result = copyProperty(result, dest, value(source, property));
        }
        return result;
    }

    private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) { return property.getName(state.getValue(property)); }
    private static <T extends Comparable<T>> BlockState copyProperty(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow(() -> new IllegalArgumentException("状態値が非対応: " + value)));
    }

    private static BlockEntity entity(BlockState state, BlockPos pos) {
        return state.getBlock() instanceof EntityBlock block ? block.newBlockEntity(pos, state) : null;
    }

    private CompoundTag sanitize(BlockPos pos, BlockState state, CompoundTag input) {
        var be = entity(state, pos);
        if (be == null) return null;
        if (!(state.getBlock() instanceof ChestBlock || state.getBlock() instanceof SignBlock || state.getBlock() instanceof BedBlock
                || be instanceof RelicEquipmentEntity))
            throw new IllegalArgumentException("稼働BlockEntityは配置できません: " + state);
        var tag = new CompoundTag();
        tag.putString("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString());
        if (state.getBlock() instanceof SignBlock && input != null) for (String side : List.of("front_text", "back_text")) {
            var text = input.getCompound(side);
            var messages = text.getList("messages", Tag.TAG_STRING);
            if (messages.isEmpty()) continue;
            if (messages.size() != 4) throw new IllegalArgumentException("看板の行数は4です");
            var clean = new CompoundTag(); var lines = new ListTag();
            for (int i = 0; i < 4; i++) {
                var component = Component.Serializer.fromJson(messages.getString(i), registries);
                if (component == null) throw new IllegalArgumentException("看板のJSONテキストが不正です");
                lines.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(component.getString()), registries)));
            }
            clean.put("messages", lines);
            clean.putString("color", "black");
            tag.put(side, clean);
        }
        return tag;
    }

    private static void validatePairs(Map<BlockPos, StructureInput.Cell> cells) {
        for (var cell : cells.values()) {
            var state = cell.state(); var pos = cell.pos();
            BlockPos mate = null;
            if (state.getBlock() instanceof DoorBlock) mate = state.getValue(DoorBlock.HALF)
                    == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            else if (state.getBlock() instanceof BedBlock) mate = pos.relative(state.getValue(BedBlock.PART)
                    == BedPart.FOOT ? state.getValue(BedBlock.FACING) : state.getValue(BedBlock.FACING).getOpposite());
            else if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
                mate = pos.relative(ChestBlock.getConnectedDirection(state));
            if (mate != null) {
                var other = cells.get(mate);
                if (other == null || other.state().getBlock() != state.getBlock()) throw new IllegalArgumentException("複数ブロックの相方がありません: " + pos);
                var paired = other.state();
                if (state.getBlock() instanceof DoorBlock && (state.getValue(DoorBlock.HALF) == paired.getValue(DoorBlock.HALF)
                        || state.getValue(DoorBlock.FACING) != paired.getValue(DoorBlock.FACING) || state.getValue(DoorBlock.HINGE) != paired.getValue(DoorBlock.HINGE)))
                    throw new IllegalArgumentException("扉の状態が不一致です: " + pos);
                if (state.getBlock() instanceof BedBlock && (state.getValue(BedBlock.PART) == paired.getValue(BedBlock.PART)
                        || state.getValue(BedBlock.FACING) != paired.getValue(BedBlock.FACING))) throw new IllegalArgumentException("ベッドの状態が不一致です: " + pos);
                if (state.getBlock() instanceof ChestBlock && (paired.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                        || state.getValue(ChestBlock.FACING) != paired.getValue(ChestBlock.FACING)
                        || !mate.relative(ChestBlock.getConnectedDirection(paired)).equals(pos)))
                    throw new IllegalArgumentException("二重チェストの状態が不一致です: " + pos);
            }
        }
    }
}
