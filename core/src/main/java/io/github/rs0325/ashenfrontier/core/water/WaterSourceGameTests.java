package io.github.rs0325.ashenfrontier.core.water;

import net.minecraft.core.component.DataComponents;

import com.google.gson.JsonParser;

import com.mojang.serialization.JsonOps;

import net.minecraft.resources.RegistryOps;

import net.minecraft.world.item.AdventureModePredicate;

import java.nio.file.StandardCopyOption;

import net.neoforged.neoforge.common.IOUtilities;

import net.neoforged.neoforge.common.CommonHooks;

import net.minecraft.world.level.Level;

import net.minecraft.world.level.GameType;

import com.mojang.authlib.GameProfile;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.catalog.NatureCatalog;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import io.github.rs0325.ashenfrontier.core.catalog.RelicContent;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import io.github.rs0325.ashenfrontier.core.recipe.WaterMass;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.util.datafix.DataFixers;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.util.*;

@GameTestHolder(AshenFrontierCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WaterSourceGameTests {
    private static final WaterSourceKey.MapRef MAP = new WaterSourceKey.MapRef("fixture", 1, "a".repeat(64));
    private static final Map<BlockPos, WaterSourceAccessProvider.Access> ACCESS = new HashMap<>();
    private static final List<String> QUALITIES = NatureCatalog.entries().stream().filter(e -> e.kind().equals("water"))
            .map(NatureCatalog.Entry::id).toList();

    private static WaterSourceKey key(String name) { return new WaterSourceKey(MAP, WaterSourceKey.Kind.SURFACE, name); }
    private static List<WaterSourceDefinition> catalogue() {
        var result = new ArrayList<WaterSourceDefinition>();
        for (String name : QUALITIES) result.add(new WaterSourceDefinition(key(name), AshenFrontierCore.id(name),
                Optional.of(new WaterSourceDefinition.Stock(10_000_000, 5_000_000))));
        for (String name : List.of("reload", "large")) result.add(new WaterSourceDefinition(key(name), AshenFrontierCore.id("raw_water"),
                Optional.of(new WaterSourceDefinition.Stock(2_000_000_000_000_000L, name.equals("reload") ? 4_000_000 : 2_000_000_000_000_000L))));
        result.add(new WaterSourceDefinition(key("ocean"), AshenFrontierCore.id("polluted_seawater"), Optional.empty()));
        return result;
    }
    private static void connectFixture(ServerLevel level, WaterSourceAccessProvider provider) {
        // 新しい試験worldだけにfixtureを作る。本番worldの判定に転用しない。
        if (WaterSources.get(level) == null) WaterSources.initializeNewWorld(level, MAP, catalogue(), provider);
        else WaterSources.bindDefinitions(level, MAP, catalogue(), provider);
    }

    @BeforeBatch(batch = "water_sources")
    public static void prepareWaterSources(ServerLevel level) { bind(level); }

    private static boolean prepared;
    private static void bind(ServerLevel level) {
        connectFixture(level, (l, p, k) -> Optional.ofNullable(ACCESS.get(p)));
        if (!prepared) {
            // GameTest再実行時のfixture予算は実涵養APIで準備する。本番へ提供しない。
            var data = WaterSources.get(level);
            long tick = level.getGameTime();
            for (var definition : catalogue()) if (definition.stock().isPresent())
                tick = Math.max(tick, data.budget(definition.key()).through() + 1);
            level.getServer().getWorldData().overworldData().setGameTime(tick);
            for (var definition : catalogue()) if (definition.stock().isPresent()) {
                var budget = data.budget(definition.key());
                long target = definition.stock().orElseThrow().initialMg();
                if (budget.remaining() > target) budget.take(budget.remaining() - target);
                WaterSources.applyRecharge(level, definition.key(), definition.quality(),
                        budget.through(), tick, target - budget.remaining());
            }
            prepared = true;
        }
    }
    private static void source(ServerLevel level, BlockPos pos, String key, String quality, boolean ocean) {
        level.setBlock(pos, NatureContent.block(quality).defaultBlockState(), Block.UPDATE_CLIENTS);
        ACCESS.put(pos, new WaterSourceAccessProvider.Access(key(key), ocean));
    }
    private static ItemStack bowl(double kg, String quality) {
        var stack = new ItemStack(Items.BOWL);
        stack.set(RelicProperties.CONTENT_MASS, kg);
        stack.set(NatureProperties.QUALITY, AshenFrontierCore.id(quality));
        return stack;
    }

    @GameTest(batch = "water_sources", template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void allQualitiesTransferPartiallyAndSerializeWithoutDuplication(GameTestHelper h) {
        var level = h.getLevel();
        bind(level);
        var pos = h.absolutePos(new BlockPos(1, 2, 1));
        var first = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "water-first"));
        var second = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "water-second"));
        first.setPos(pos.getX(), pos.getY(), pos.getZ());
        second.setPos(pos.getX(), pos.getY(), pos.getZ());
        for (String q : QUALITIES) {
            source(level, pos, q, q, false);
            first.setItemInHand(InteractionHand.MAIN_HAND, bowl(0, "clean_water"));
            var transfer = WaterTransfers.sourceToContainer(first, pos, InteractionHand.MAIN_HAND, 4);
            h.assertTrue(transfer.movedMg() == 4_000_000 && first.getMainHandItem().get(NatureProperties.QUALITY).equals(AshenFrontierCore.id(q)), "4kgと品質を移送");
            second.setItemInHand(InteractionHand.MAIN_HAND, bowl(0, "clean_water"));
            h.assertTrue(WaterTransfers.sourceToContainer(second, pos, InteractionHand.MAIN_HAND, 4).movedMg() == 1_000_000, "2人目は残量だけ受取");
            h.assertTrue(WaterTransfers.sourceToContainer(second, pos, InteractionHand.MAIN_HAND, 1).status() == WaterTransfers.Status.DEPLETED, "0の水源は再利用不可");
            var saved = ItemStack.parse(level.registryAccess(), first.getMainHandItem().save(level.registryAccess())).orElseThrow();
            h.assertTrue(WaterMass.fromKg(saved.get(RelicProperties.CONTENT_MASS)) == 4_000_000, "容器保存で量を維持");
            bind(level);
            h.assertTrue(WaterTransfers.inspect(level, pos, WaterSourceKey.Kind.SURFACE).remainingMg().orElseThrow() == 0, "再接続で復活しない");
        }
        source(level, pos, "large", "raw_water", false);
        first.setItemInHand(InteractionHand.MAIN_HAND, bowl(0, "raw_water"));
        WaterTransfers.sourceToContainer(first, pos, InteractionHand.MAIN_HAND, 4);
        h.assertTrue(WaterTransfers.inspect(level, pos, WaterSourceKey.Kind.SURFACE).remainingMg().orElseThrow() == 1_999_999_996_000_000L, "大水域から4kgを正確に引く");
        var times = new long[1000];
        long transferred = 0;
        for (int n = 0; n < 1100; n++) {
            first.setItemInHand(InteractionHand.MAIN_HAND, bowl(0, "raw_water"));
            long start = System.nanoTime();
            var transferResult = WaterTransfers.sourceToContainer(first, pos, InteractionHand.MAIN_HAND, .025);
            long elapsed = System.nanoTime() - start;
            h.assertTrue(transferResult.movedMg() == 25_000, "実移送ベンチマーク");
            transferred += transferResult.movedMg();
            if (n >= 100) times[n - 100] = elapsed;
        }
        Arrays.sort(times);
        AshenFrontierCore.LOGGER.info("WATER_TRANSFER_BENCH count=1000 warmup=100 p50_us={} p95_us={} max_us={}",
                times[500] / 1e3, times[950] / 1e3, times[999] / 1e3);
        long from = WaterSources.rechargeThroughTick(level, key("large"));
        long to = Math.max(level.getGameTime(), from + 1);
        level.getServer().getWorldData().overworldData().setGameTime(to);
        WaterSources.applyRecharge(level, key("large"), AshenFrontierCore.id("raw_water"), from, to, transferred);

        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        h.succeed();
    }

    @GameTest(batch = "water_sources", template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void oceanRequiresDesignatedSourceAndContainersStayFinite(GameTestHelper h) {
        var level = h.getLevel(); bind(level);
        var pos = h.absolutePos(new BlockPos(1, 2, 1));
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "water-ocean"));
        player.setPos(pos.getX(), pos.getY(), pos.getZ());
        source(level, pos, "ocean", "polluted_seawater", false);
        player.setItemInHand(InteractionHand.MAIN_HAND, bowl(0, "raw_water"));
        h.assertTrue(WaterTransfers.sourceToContainer(player, pos, InteractionHand.MAIN_HAND, 4).status() == WaterTransfers.Status.NO_SOURCE, "内陸の海水を無限化しない");
        source(level, pos, "ocean", "polluted_seawater", true);
        var stack = bowl(0, "raw_water"); stack.setCount(2); player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        h.assertTrue(WaterTransfers.sourceToContainer(player, pos, InteractionHand.MAIN_HAND, 1).status() == WaterTransfers.Status.INVALID_CONTAINER, "スタック容器は拒否");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(RelicContent.item("sealed_water_container")));
        h.assertTrue(WaterTransfers.sourceToContainer(player, pos, InteractionHand.MAIN_HAND, 1).status() == WaterTransfers.Status.INVALID_CONTAINER, "密封容器を再充填しない");
        player.setItemInHand(InteractionHand.MAIN_HAND, bowl(1, "clean_water"));
        h.assertTrue(WaterTransfers.sourceToContainer(player, pos, InteractionHand.MAIN_HAND, 1).status() == WaterTransfers.Status.QUALITY_MISMATCH, "混合は拒否");
        player.setItemInHand(InteractionHand.MAIN_HAND, bowl(0, "clean_water"));
        h.assertTrue(WaterTransfers.sourceToContainer(player, pos, InteractionHand.MAIN_HAND, 4).movedMg() == 4_000_000, "真正海からも携帯量は4kgだけ");
        h.assertTrue(WaterTransfers.sourceToContainer(player, pos, InteractionHand.MAIN_HAND, 1).status() == WaterTransfers.Status.FULL, "海でも容器を越えない");
        level.setBlock(pos, NatureContent.block("polluted_seawater").defaultBlockState().setValue(LiquidBlock.LEVEL, 3), Block.UPDATE_CLIENTS);
        h.assertTrue(WaterTransfers.inspect(level, pos, WaterSourceKey.Kind.SURFACE).status() == WaterTransfers.Status.NO_SOURCE, "flowingは源ではない");
        level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
        h.assertTrue(WaterTransfers.inspect(level, pos, WaterSourceKey.Kind.SURFACE).status() == WaterTransfers.Status.NO_SOURCE, "バニラ水は源ではない");
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        h.succeed();
    }

    @GameTest(batch = "water_sources", template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
    public static void savedLedgerRestoresZeroAndRechargeReceiptFromDisk(GameTestHelper h) {
        var level = h.getLevel(); bind(level);
        var pos = h.absolutePos(new BlockPos(1, 2, 1));
        source(level, pos, "reload", "raw_water", false);
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "water-save"));
        player.setPos(pos.getX(), pos.getY(), pos.getZ());
        player.setItemInHand(InteractionHand.MAIN_HAND, bowl(0, "raw_water"));
        long from = WaterSources.get(level).budget(key("reload")).through();
        h.runAfterDelay(2, () -> {
            long to = level.getGameTime();
            var receipt = WaterSources.applyRecharge(level, key("reload"), AshenFrontierCore.id("raw_water"), from, to, 1_000_000);
            h.assertTrue(receipt.acceptedMg() == 1_000_000, "涵養を一度受取");
            h.assertTrue(WaterTransfers.sourceToContainer(player, pos, InteractionHand.MAIN_HAND, 4).movedMg() == 4_000_000, "保存前の4kg採水");
            player.setItemInHand(InteractionHand.MAIN_HAND, bowl(0, "raw_water"));
            h.assertTrue(WaterTransfers.sourceToContainer(player, pos, InteractionHand.MAIN_HAND, 4).movedMg() == 1_000_000, "保存前の残量1kg採水");
            level.getDataStorage().save();
            IOUtilities.waitUntilIOWorkerComplete();
            try {
                var folder = WaterSources.dataFile(level).getParent().toFile();
                var fresh = new DimensionDataStorage(folder, DataFixers.getDataFixer(), level.registryAccess());
                WaterSources restored = fresh.get(new net.minecraft.world.level.saveddata.SavedData.Factory<>(
                        () -> { throw new AssertionError("復元は初期化しない"); }, (tag, lookup) -> WaterSources.decode(tag), null), WaterSources.ID);
                h.assertTrue(restored != null && restored.budget(key("reload")).remaining() == 0, "実ファイルから0を復元");
                level.getDataStorage().set(WaterSources.ID, restored);
                bind(level);
                h.assertTrue(!WaterSources.applyRecharge(level, key("reload"), AshenFrontierCore.id("raw_water"), from, to, 1_000_000).committedNow(), "保存後の再送を二重計上しない");
                h.assertTrue(WaterTransfers.inspect(level, pos, WaterSourceKey.Kind.SURFACE).status() == WaterTransfers.Status.DEPLETED, "再送で0は回復しない");
                var tag = restored.save(new CompoundTag(), level.registryAccess());
                tag.putInt("water_schema_version", 2);
                boolean rejected = false;
                try { WaterSources.decode(tag); } catch (RuntimeException expected) { rejected = true; }
                h.assertTrue(rejected, "未知形式を満水初期化しない");
                var wrong = new WaterSourceKey.MapRef("other", 1, "a".repeat(64));
                rejected = false;
                try { WaterSources.bindDefinitions(level, wrong, catalogue(), (l, p, k) -> Optional.empty()); }
                catch (RuntimeException expected) { rejected = true; }
                h.assertTrue(rejected, "版の取り違えを拒否");
            } catch (Exception e) { throw new RuntimeException(e); }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            h.succeed();
        });
    }
    @GameTest(batch = "water_sources", template = "nature_buckets", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void bottlesDispensersWaterloggingAndSpongesDoNotConvertNaturalWater(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(1, 2, 2));
        var dispenserPos = pos.west();
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "water-bypass"));
        player.setPos(pos.getX() + .5, pos.getY() + .5 - player.getEyeHeight(), pos.getZ() - 1.5);
        player.setYRot(0); player.setXRot(0);
        for (String q : QUALITIES) {
            var state = NatureContent.block(q).defaultBlockState();
            level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(pos, state, Block.UPDATE_CLIENTS);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS_BOTTLE));
            var eye = player.getEyePosition();
            var hit = level.clip(new net.minecraft.world.level.ClipContext(eye,
                    eye.add(player.getViewVector(1).scale(4)), net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, player));
            h.assertTrue(hit.getBlockPos().equals(pos), "瓶が試験の水面を狙っている");
            player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND);
            h.assertTrue(player.getMainHandItem().is(Items.GLASS_BOTTLE) && level.getBlockState(pos) == state, "瓶で品質を消さない");
            level.setBlock(dispenserPos, Blocks.DISPENSER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.DispenserBlock.FACING, net.minecraft.core.Direction.EAST), Block.UPDATE_CLIENTS);
            var dispenser = (net.minecraft.world.level.block.entity.DispenserBlockEntity) level.getBlockEntity(dispenserPos);
            for (var item : List.of(Items.GLASS_BOTTLE, Items.BUCKET)) {
                var result = net.minecraft.world.level.block.DispenserBlock.DISPENSER_REGISTRY.get(item).dispense(
                        new net.minecraft.core.dispenser.BlockSource(level, dispenserPos, level.getBlockState(dispenserPos), dispenser),
                        new ItemStack(item));
                h.assertTrue(result.isEmpty() || result.is(item), "ディスペンサーで水入り品を生成しない");
                h.assertTrue(level.getBlockState(pos) == state && dispenser.isEmpty(), "機械採取でも源を消さず容器へ変換しない");
            }
            level.setBlock(pos.above(), Blocks.SPONGE.defaultBlockState(), Block.UPDATE_ALL);
            h.assertTrue(level.getBlockState(pos) == state && level.getBlockState(pos.above()).is(Blocks.SPONGE), "スポンジが品質なしの水へ変えない");
            var dryStair = Blocks.OAK_STAIRS.defaultBlockState();
            level.setBlock(pos.above(), dryStair, Block.UPDATE_CLIENTS);
            var waterlogged = (net.minecraft.world.level.block.SimpleWaterloggedBlock) Blocks.OAK_STAIRS;
            h.assertTrue(!waterlogged.placeLiquid(level, pos.above(), dryStair, state.getFluidState()), "独自水をwaterlogged booleanにしない");
            level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(dispenserPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        h.succeed();
    }

    @GameTest(batch = "water_sources", template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 200)
    public static void tenThousandSourceSaveHasNoPerTickWork(GameTestHelper h) throws Exception {
        System.gc();
        long heapBase = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        var definitions = new ArrayList<WaterSourceDefinition>();
        for (int n = 0; n < 10_000; n++) definitions.add(new WaterSourceDefinition(key("bench-" + n),
                AshenFrontierCore.id("raw_water"), Optional.of(new WaterSourceDefinition.Stock(Long.MAX_VALUE, 144_000_000))));
        long heapBefore = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long start = System.nanoTime();
        var ledger = new WaterSources(MAP, definitions, 0);
        long createNs = System.nanoTime() - start;
        System.gc();
        long ledgerHeap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() - heapBase;
        start = System.nanoTime();
        for (int n = 0; n < 10_000; n++) ledger.budget(key("bench-" + n)).take(25_000);
        long operationNs = System.nanoTime() - start;
        start = System.nanoTime();
        var tag = ledger.save(new CompoundTag(), h.getLevel().registryAccess());
        var bytes = new java.io.ByteArrayOutputStream();
        NbtIo.write(tag, new java.io.DataOutputStream(bytes));
        long saveNs = System.nanoTime() - start;
        h.assertTrue(WaterSources.decode(tag).budget(key("bench-9999")).remaining() == 143_975_000, "1万源を保存復元");
        long heapAfter = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        AshenFrontierCore.LOGGER.info("WATER_BENCH sources=10000 create_ms={} operations_ms={} save_ms={} nbt_bytes={} observed_heap_delta={} ledger_heap_after_gc={} (参考差分)",
                createNs / 1e6, operationNs / 1e6, saveNs / 1e6, bytes.size(), heapAfter - heapBefore, ledgerHeap);
        h.succeed();
    }

    @GameTest(batch = "water_sources", template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void invalidSavedFilesCannotFallBackToFullInitialBalances(GameTestHelper h) throws Exception {
        var directory = Files.createTempDirectory("ashen-water-corrupt-");
        var file = directory.resolve(WaterSources.ID + ".dat");
        try {
            var proposed = new WaterSources(MAP, catalogue(), 0);
            for (int fault = 0; fault < 4; fault++) {
                var tag = proposed.save(new CompoundTag(), h.getLevel().registryAccess());
                if (fault == 0) tag.putInt("water_schema_version", 99);
                if (fault == 1) tag.getList("sources", 10).remove(0);
                if (fault == 2) tag.getList("sources", 10).getCompound(0).getCompound("balance").putLong("remaining_mg", -1);
                if (fault == 3) tag.getCompound("map").putString("manifest_hash", "c".repeat(64));
                var root = new CompoundTag(); root.put("data", tag);
                NbtIo.writeCompressed(root, file);
                var before = Files.readAllBytes(file);
                var storage = new DimensionDataStorage(directory.toFile(), DataFixers.getDataFixer(), h.getLevel().registryAccess());
                boolean rejected = false;
                try { storage.computeIfAbsent(WaterSources.factory(proposed, file), WaterSources.ID); }
                catch (RuntimeException expected) { rejected = true; }
                h.assertTrue(rejected && Arrays.equals(before, Files.readAllBytes(file)),
                        "実SavedData読込失敗は満水初期化せず元ファイルを保持: " + fault);
            }
        } finally {
            IOUtilities.waitUntilIOWorkerComplete();
            Files.deleteIfExists(file); Files.deleteIfExists(directory);
        }
        h.succeed();
    }

    @GameTest(batch = "water_sources", template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void dimensionsWithTheSameSourceKeyDoNotShareBalances(GameTestHelper h) {
        var level = h.getLevel(); bind(level);
        var other = level.getServer().getLevel(Level.NETHER);
        connectFixture(other, (l, p, k) -> Optional.empty());
        var first = WaterSources.get(level).budget(key("large"));
        var second = WaterSources.get(other).budget(key("large"));
        long before = second.remaining();
        first.take(1);
        h.assertTrue(second.remaining() == before && first != second, "同じ版・キーでもdimensionごとに残高が独立");
        long from = WaterSources.rechargeThroughTick(level, key("large"));
        long to = Math.max(level.getGameTime(), from + 1);
        level.getServer().getWorldData().overworldData().setGameTime(to);
        WaterSources.applyRecharge(level, key("large"), AshenFrontierCore.id("raw_water"), from, to, 1);
        h.succeed();
    }

    @GameTest(batch = "water_sources", template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void missingLedgerFileCannotInitializeOnReconnect(GameTestHelper h) throws Exception {
        var level = h.getLevel().getServer().getLevel(Level.NETHER);
        connectFixture(level, (l, p, k) -> Optional.empty());
        var original = WaterSources.get(level);
        original.budget(key("large")).take(4_000_000);
        original.setDirty();
        level.getDataStorage().save();
        IOUtilities.waitUntilIOWorkerComplete();
        var file = WaterSources.dataFile(level);
        var backup = Files.createTempFile("ashen-water-missing-", ".dat");
        Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        level.getDataStorage().set(WaterSources.ID, null);
        boolean rejected = false;
        try {
            WaterSources.bindDefinitions(level, MAP, catalogue(), (l, p, k) -> Optional.empty());
        } catch (IllegalStateException expected) {
            rejected = true;
        } finally {
            level.getDataStorage().set(WaterSources.ID, original);
            Files.move(backup, file, StandardCopyOption.REPLACE_EXISTING);
        }
        h.assertTrue(rejected, "再接続でdat全体が欠けても初期量・涵養epochを作り直さない");
        h.succeed();
    }

    @GameTest(batch = "water_sources", template = "nature_buckets", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void oneClickConsumesOnlyOneHandThroughTheUseHooks(GameTestHelper h) {
        var level = h.getLevel(); bind(level);
        var pos = h.absolutePos(new BlockPos(1, 2, 2));
        source(level, pos, "ocean", "polluted_seawater", true);
        var client = h.makeMockPlayer(GameType.SURVIVAL);
        var server = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "water-click"));
        for (var player : List.of(client, server)) {
            player.setPos(pos.getX() + .5, pos.getY() + .5 - player.getEyeHeight(), pos.getZ() - 1.5);
            player.setYRot(0); player.setXRot(0);
        }
        for (boolean shift : new boolean[]{false, true}) {
            client.setShiftKeyDown(shift); server.setShiftKeyDown(shift);
            for (var hand : InteractionHand.values()) {
                client.setItemInHand(hand, bowl(0, "raw_water"));
                server.setItemInHand(hand, bowl(0, "raw_water"));
            }
            int packets = 0;
            // MultiPlayerGameMode.useItemと同じフック→Item.use→consumesActionの順。
            for (var hand : InteractionHand.values()) {
                var result = CommonHooks.onItemRightClick(client, hand);
                if (result == null) result = client.getItemInHand(hand).use(level, client, hand).getResult();
                packets++;
                server.gameMode.useItem(server, level, server.getItemInHand(hand), hand);
                if (result.consumesAction()) break;
            }
            h.assertTrue(packets == 1, "クライアント予測が操作を消費しオフハンド要求を送らない");
            h.assertTrue(server.getMainHandItem().get(RelicProperties.CONTENT_MASS) == (shift ? 4 : 1)
                    && server.getOffhandItem().get(RelicProperties.CONTENT_MASS) == 0, "1クリックの受取は1kg/Shift4kgだけ");
            h.assertTrue(client.getMainHandItem().get(RelicProperties.CONTENT_MASS) == 0, "クライアント予測では水量を変更しない");
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        h.succeed();
    }

    @GameTest(batch = "water_sources", template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void offhandPermissionUsesTheOperatedContainer(GameTestHelper h) {
        var level = h.getLevel(); bind(level);
        var pos = h.absolutePos(new BlockPos(1, 2, 1));
        source(level, pos, "ocean", "polluted_seawater", true);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "water-permission"));
        player.setPos(pos.getX(), pos.getY(), pos.getZ());
        player.gameMode.changeGameModeForPlayer(GameType.ADVENTURE);
        var allowed = bowl(0, "raw_water");
        var predicate = AdventureModePredicate.CODEC.parse(
                RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()),
                JsonParser.parseString("{\"blocks\":\"minecraft:stone\"}")).getOrThrow();
        allowed.set(DataComponents.CAN_PLACE_ON, predicate);
        player.setItemInHand(InteractionHand.MAIN_HAND, bowl(0, "raw_water"));
        player.setItemInHand(InteractionHand.OFF_HAND, allowed);
        h.assertTrue(WaterTransfers.sourceToContainer(player, pos, InteractionHand.OFF_HAND, 1).movedMg() == 1_000_000, "オフハンドの許可を使う");
        player.setItemInHand(InteractionHand.MAIN_HAND, allowed.copy());
        player.setItemInHand(InteractionHand.OFF_HAND, bowl(0, "raw_water"));
        h.assertTrue(WaterTransfers.sourceToContainer(player, pos, InteractionHand.OFF_HAND, 1).status() == WaterTransfers.Status.ACCESS_DENIED,
                "メインハンドの許可をオフハンドへ借用しない");
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        h.succeed();
    }

}
