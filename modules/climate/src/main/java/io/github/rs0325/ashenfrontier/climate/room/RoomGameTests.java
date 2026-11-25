package io.github.rs0325.ashenfrontier.climate.room;

import com.mojang.logging.LogUtils;
import io.github.rs0325.ashenfrontier.climate.AshenFrontierClimate;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.environment.CombustionEvent;
import io.github.rs0325.ashenfrontier.core.environment.CombustionSourceQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.Arrays;
import java.util.TreeMap;
import java.util.Map;
import java.util.Iterator;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.NbtIo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RoomGameTests {
    private static final RoomPhysics.Air AIR = new RoomPhysics.Air(293.15, 101325, 0, .00042, .2095, 2);

    private static RoomAir fixture(GameTestHelper h) {
        var tag = new CompoundTag(); tag.putInt("format", 8);
        var service = RoomAir.load(h.getLevel(), tag);
        service.outside((level, pos, from, to) -> Optional.of(List.of(new RoomAir.Boundary(from, to, AIR))));
        return service;
    }
    private static void box(GameTestHelper h, int size) {
        StructureUtils.removeBarriers(h.getBounds(), h.getLevel());
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) for (int z = 0; z < size; z++)
            h.setBlock(new BlockPos(x, y, z), x == 0 || y == 0 || z == 0 || x == size - 1 || y == size - 1 || z == size - 1
                    ? Blocks.STONE : Blocks.AIR);
    }
    private static RoomAir.Sample discover(RoomAir service, BlockPos pos, long tick) {
        var sample = service.sample(pos, tick);
        for (int n = 0; n < 128 && sample.status() == RoomAir.Status.SCANNING; n++) {
            service.work(); sample = service.sample(pos, tick);
        }
        return sample;
    }
    private static CombustionEvent event(GameTestHelper h, BlockPos air, UUID id, long sequence, long from, long to, double mass, double retained) {
        return new CombustionEvent(h.getLevel(), air.below(), from, to, AshenFrontierCore.id("dry_wood"), mass, mass * 16000000, retained, id, sequence);
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void replaySaveRespirationAndMultipleSources(GameTestHelper h) {
        box(h, 5);
        var service = RoomAir.get(h.getLevel());
        service.outside((level, pos, from, to) -> Optional.of(List.of(new RoomAir.Boundary(from, to, AIR))));
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        long start = h.getLevel().getGameTime();
        var discovered = discover(service, pos, start);
        h.assertTrue(discovered.status() == RoomAir.Status.INDOOR, "密閉室を確定: " + discovered + " air=" + h.getLevel().getBlockState(pos) + " roof=" + h.getLevel().getBlockState(pos.above(2)) + " height=" + h.getLevel().getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()));
        h.runAfterDelay(20, () -> {
            long end = h.getLevel().getGameTime();
            var source = UUID.randomUUID();
            var first = event(h, pos, source, 1, start, end, .001, 10000);
            NeoForge.EVENT_BUS.post(first);
            var once = service.sample(pos, end).state().orElseThrow();
            h.assertTrue(once.co() > 0 && once.oxygen() < AIR.oxygen() && once.temperatureK() > AIR.temperatureK(), "燃焼の熱とガス");
            h.assertTrue(service.receive(first) == SourceReceipts.Result.DUPLICATE, "同一通知は再注入しない");
            h.assertTrue(service.sample(pos, end).state().orElseThrow().equals(once), "再送で状態不変");
            h.assertTrue(service.receive(event(h, pos, source, 1, start, end, .002, 10000)) == SourceReceipts.Result.CONFLICT, "同番号の異内容は拒否");
            service.receive(event(h, pos, source, 2, start, end, .001, 10000));
            service.receive(event(h, pos.east(), UUID.randomUUID(), 1, start, end, .001, 10000));
            service.respiration(UUID.randomUUID(), 1, pos, start, end, .01, .008, 20);
            var combined = service.sample(pos, end).state().orElseThrow();
            h.assertTrue(Math.abs(combined.co() - once.co() * 3) < 1e-12 && combined.co2() > once.co2(), "複数炉と別番号・呼吸を合計");
            var saved = service.save(new CompoundTag(), h.getLevel().registryAccess());
            var restored = RoomAir.load(h.getLevel(), saved);
            restored.outside((l, p, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, AIR))));
            h.assertTrue(restored.sample(pos, end).state().orElseThrow().equals(combined), "保存復元で履歴を再構成");
            h.assertTrue(restored.receive(first) == SourceReceipts.Result.OLD, "復元後に過去通知を再注入しない");
            h.assertTrue(restored.receive(event(h, pos, source, 2, start, end, .001, 10000)) == SourceReceipts.Result.DUPLICATE, "最終通知も復元");
            var before = restored.sample(pos, end).state().orElseThrow();
            restored.receive(event(h, pos, source, 3, end, end, 0, -5000));
            h.assertTrue(restored.sample(pos, end).state().orElseThrow().temperatureK() > before.temperatureK(), "未使用熱の負留保を放熱として受ける");
            h.succeed();
        });
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void openingChangeAndOutdoorBoundary(GameTestHelper h) {
        box(h, 5);
        var service = RoomAir.get(h.getLevel());
        service.outside((l, p, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, AIR))));
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        long start = h.getLevel().getGameTime();
        var closed = discover(service, pos, start);
        h.assertTrue(closed.covered() && !closed.ventilated(), "密閉は覆いあり・換気なし: " + closed);
        h.runAfterDelay(1, () -> {
            h.setBlock(new BlockPos(0, 2, 2), Blocks.AIR);
            var opened = discover(service, pos, h.getLevel().getGameTime());
            h.assertTrue(opened.covered() && opened.ventilated() && opened.flowM3S() > 0, "壁の開口をイベントで再探索: " + opened);
            h.assertTrue(service.sample(pos, start).state().isPresent(), "開口前の履歴を維持");
            h.assertTrue(discover(service, h.absolutePos(new BlockPos(8, 2, 8)), h.getLevel().getGameTime()).status() == RoomAir.Status.OUTDOOR, "露天は室内ではない");
            h.setBlock(new BlockPos(0, 2, 2), Blocks.OAK_DOOR.defaultBlockState());
            h.assertTrue(!discover(service, pos, h.getLevel().getGameTime()).ventilated(), "閉じた扉で換気経路を閉じる");
            h.setBlock(new BlockPos(0, 2, 2), Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(BlockStateProperties.OPEN, true));
            h.assertTrue(discover(service, pos, h.getLevel().getGameTime()).ventilated(), "扉を開けると換気経路が戻る");
            h.setBlock(new BlockPos(0, 2, 2), Blocks.STONE);
            for (int y = 4; y <= 8; y++) {
                h.setBlock(new BlockPos(1, y, 2), Blocks.STONE); h.setBlock(new BlockPos(3, y, 2), Blocks.STONE);
                h.setBlock(new BlockPos(2, y, 1), Blocks.STONE); h.setBlock(new BlockPos(2, y, 3), Blocks.STONE);
                h.setBlock(new BlockPos(2, y, 2), Blocks.AIR);
            }
            var chimney = discover(service, h.absolutePos(new BlockPos(1, 2, 1)), h.getLevel().getGameTime());
            h.assertTrue(chimney.covered() && chimney.ventilated(), "煙突で覆いと排気経路を両立");
            service.outside(null);
            h.assertTrue(service.sample(h.absolutePos(new BlockPos(1, 2, 1)), h.getLevel().getGameTime()).status() == RoomAir.Status.MISSING_BOUNDARY, "未接続に清浄外気を補わない");
            service.outside((l, p, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, AIR))));
            h.succeed();
        });
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void boundedSearchAndUnloadedBoundary(GameTestHelper h) {
        box(h, 20);
        var service = fixture(h);
        var pos = h.absolutePos(new BlockPos(10, 10, 10));
        var large = discover(service, pos, h.getLevel().getGameTime());
        h.assertTrue(large.status() == RoomAir.Status.LIMIT, "4096空気セル超は未確認: " + large);
        int before = h.getLevel().getChunkSource().getLoadedChunksCount();
        var far = new BlockPos(2000000, 100, 2000000);
        var unknown = discover(service, far, h.getLevel().getGameTime());
        h.assertTrue(unknown.status() == RoomAir.Status.UNLOADED && !unknown.confirmed() && !unknown.ventilated(), "未読込みを換気済み扱いしない");
        h.assertTrue(before == h.getLevel().getChunkSource().getLoadedChunksCount(), "探索でチャンクをロードしない");
        long[] times = new long[2000];
        int visits = 0;
        try (var profile = new RoomSearchProfile("limit")) {
            for (int n = -5; n < times.length; n++) {
                var search = new RoomSearch(pos, h.getLevel().getGameTime());
                while (search.status == RoomSearch.Status.SCANNING) {
                    int old = search.visits;
                    long elapsed = n < 0 ? warmSearch(search, h)
                            : profile.measure(() -> search.work(h.getLevel(), 256, 4096));
                    h.assertTrue(search.visits - old <= 256, "一作業256訪問");
                    if (n >= 0) times[n] = Math.max(times[n], elapsed);
                }
                visits = search.visits;
            }
        }
        Arrays.sort(times);
        LogUtils.getLogger().info("M3-137 giant room visits={} max-work p95={} ms worst={} ms; 256 visits/work",
                visits, times[1899]/1e6, times[1999]/1e6);
        box(h, 18);
        var traversal = new RoomSearch(pos, h.getLevel().getGameTime());
        while (traversal.status == RoomSearch.Status.SCANNING) traversal.work(h.getLevel(), 256, 4096);
        int minimumTraversalWorks = (traversal.visits + 255) / 256;
        long[] completion = new long[30];
        try (var profile = new RoomSearchProfile("registration")) {
            for (int n = -5; n < completion.length; n++) {
                var measured = fixture(h);
                var result = measured.sample(pos, h.getLevel().getGameTime());
                long slowest = 0;
                int calls = 0;
                while (result.status() == RoomAir.Status.SCANNING) {
                    long elapsed;
                    if (n < 0) { long begun = System.nanoTime(); measured.work(); elapsed = System.nanoTime() - begun; }
                    else elapsed = profile.measure(measured::work);
                    slowest = Math.max(slowest, elapsed);
                    result = measured.sample(pos, h.getLevel().getGameTime());
                    calls++;
                    if (calls == minimumTraversalWorks) h.assertTrue(result.status() == RoomAir.Status.SCANNING,
                            "探索末尾の1作業で4096セルの登録まで一括完了しない");
                }
                h.assertTrue(calls >= minimumTraversalWorks + 16, "登録段階も予算256で分割する");
                h.assertTrue(result.volumeM3() == 4096, "上限内の室を登録する");
                if (n >= 0) completion[n] = slowest;
            }
        }
        Arrays.sort(completion);
        LogUtils.getLogger().info("M3-137 accepted 4096-cell room work including registration p95={} ms worst={} ms", completion[28]/1e6, completion[29]/1e6);
        h.succeed();
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void delayedHeatAndVentilationMatchFreshReplay(GameTestHelper h) {
        box(h, 5);
        h.setBlock(new BlockPos(0, 2, 2), Blocks.AIR);
        var service = fixture(h);
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        long start = h.getLevel().getGameTime();
        discover(service, pos, start);
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        try {
            clock.setGameTime(start + 200);
            service.receive(event(h, pos, UUID.randomUUID(), 1, start, start + 100, .001, 10000));
            var polluted = service.sample(pos, start + 100).state().orElseThrow();
            var cooled = service.sample(pos, start + 200).state().orElseThrow();
            h.assertTrue(cooled.co() < polluted.co() && cooled.oxygen() > polluted.oxygen(), "発生停止と外気交換で回復する");
            service.receive(event(h, pos, UUID.randomUUID(), 1, start + 20, start + 80, .001, 10000));
            var delayed = service.sample(pos, start + 200).state().orElseThrow();
            var restored = RoomAir.load(h.getLevel(), service.save(new CompoundTag(), h.getLevel().registryAccess()));
            restored.outside((l, p, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, AIR))));
            var replay = restored.sample(pos, start + 200).state().orElseThrow();
            h.assertTrue(Math.abs(delayed.co() - replay.co()) < 1e-14
                    && Math.abs(delayed.temperatureK() - replay.temperatureK()) < 1e-10, "遅延通知でキャッシュを無効化し、保存後の完全再評価と一致");
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    private static long warmSearch(RoomSearch search, GameTestHelper h) {
        long start = System.nanoTime();
        search.work(h.getLevel(), 256, 4096);
        return System.nanoTime() - start;
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void closedChimneyInvalidatesAndLimitedRoomCanBeDivided(GameTestHelper h) {
        box(h, 5);
        for (int y = 4; y <= 8; y++) {
            h.setBlock(new BlockPos(1, y, 2), Blocks.STONE); h.setBlock(new BlockPos(3, y, 2), Blocks.STONE);
            h.setBlock(new BlockPos(2, y, 1), Blocks.STONE); h.setBlock(new BlockPos(2, y, 3), Blocks.STONE);
            h.setBlock(new BlockPos(2, y, 2), Blocks.AIR);
        }
        var service = RoomAir.get(h.getLevel());
        service.outside((l, p, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, AIR))));
        var pos = h.absolutePos(new BlockPos(1, 2, 1));
        h.assertTrue(discover(service, pos, h.getLevel().getGameTime()).ventilated(), "煙突を確認");
        h.setBlock(new BlockPos(2, 8, 2), Blocks.STONE);
        h.assertTrue(!discover(service, pos, h.getLevel().getGameTime()).ventilated(), "外気境界を塞ぐと換気を停止");
        box(h, 20);
        service.changed(pos);
        h.assertTrue(discover(service, pos, h.getLevel().getGameTime()).status() == RoomAir.Status.LIMIT, "大室は上限");
        for (int y = 1; y < 19; y++) for (int z = 1; z < 19; z++) h.setBlock(new BlockPos(10, y, z), Blocks.STONE);
        h.assertTrue(discover(service, pos, h.getLevel().getGameTime()).confirmed(), "上限到達後も間仕切りで再探索");
        h.succeed();
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void restartBetweenGeometryChangeAndDiscoveryPreservesAir(GameTestHelper h) {
        box(h, 5);
        var service = fixture(h);
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        long start = h.getLevel().getGameTime();
        discover(service, pos, start);
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        try {
            clock.setGameTime(start + 20);
            service.receive(event(h, pos, UUID.randomUUID(), 1, start, start + 20, .001, 10000));
            var polluted = service.sample(pos, start + 20).state().orElseThrow();
            h.assertTrue(polluted.co() > 0, "保存前に汚染した室を用意");
            h.setBlock(new BlockPos(0, 2, 2), Blocks.AIR); service.changed(h.absolutePos(new BlockPos(0, 2, 2)));
            var saved = service.save(new CompoundTag(), h.getLevel().registryAccess());
            h.assertTrue(saved.getList("regions", 10).getCompound(0).getLong("to") == start + 20, "新室の探索前に旧室を終了");
            var restored = RoomAir.load(h.getLevel(), saved);
            restored.outside((l, p, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, AIR))));
            var after = discover(restored, pos, start + 20).state().orElseThrow();
            h.assertTrue(after.co() > polluted.co() * .9 && after.temperatureK() > AIR.temperatureK(), "変更途中の正常保存で旧室の熱とガスを保持");
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID, timeoutTicks = 400)
    public static void longRunningSourcesAndDecadeReturnsStayBounded(GameTestHelper h) throws Exception {
        box(h, 5);
        var service = fixture(h);
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        long start = h.getLevel().getGameTime();
        discover(service, pos, start);
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        var ids = new UUID[40];
        for (int n = 0; n < ids.length; n++) ids[n] = UUID.randomUUID();
        try {
            // 40発生源×1実日。固定値は試験内だけ。小さい量で濃度モデルの飽和域を避ける。
            long begun = System.nanoTime();
            for (int second = 1; second <= 86400; second++) {
                long end = start + second * 20L;
                clock.setGameTime(end);
                for (int source = 0; source < ids.length; source++)
                    service.respiration(ids[source], second, pos, end - 20, end, .000000003, .000000002, .001);
            }
            long inputNs = System.nanoTime() - begun;
            long saveStart = System.nanoTime();
            var saved = service.save(new CompoundTag(), h.getLevel().registryAccess());
            var bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(saved, bytes);
            var uncompressed = new ByteArrayOutputStream();
            NbtIo.write(saved, new DataOutputStream(uncompressed));
            long saveNs = System.nanoTime() - saveStart;
            var sizes = new TreeMap<String, Integer>();
            for (var key : saved.getAllKeys()) {
                var field = new CompoundTag(); field.put(key, saved.get(key));
                var data = new ByteArrayOutputStream();
                NbtIo.write(field, new DataOutputStream(data));
                sizes.put(key, data.size()-4);
            }
            h.assertTrue(sizes.values().stream().mapToInt(Integer::intValue).sum()+4 == uncompressed.size(),
                    "保存量の各フィールドの内訳が全体と一致");
            Files.writeString(Path.of("room-history-size.txt"), sizes.toString());
            Files.write(Path.of("room-history-size.nbt"), uncompressed.toByteArray());
            int retained = saved.getList("emissions", 12).size();
            h.assertTrue(retained <= 1024 && saved.getList("cumulative", 12).size() == 1, "345万通知を1024区間と室の累積値へ制限");
            double heat = saved.getDouble("archived_heat"), oxygen = saved.getDouble("archived_oxygen");
            for (String name : List.of("emissions", "cumulative"))
                for (var row : RoomAir.loadEntries(saved.getList(name, 12))) {
                    heat += row.amount().heatJ(); oxygen += row.amount().oxygenMol();
                }
            h.assertTrue(Math.abs(heat - 3456) < 1e-5 && Math.abs(oxygen + .010368) < 1e-9, "集約後も熱と消費酸素の総量が一致");
            long loadStart = System.nanoTime();
            var restored = RoomAir.load(h.getLevel(), saved);
            restored.outside((l, p, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, AIR))));
            long loadNs = System.nanoTime() - loadStart;
            long evaluateStart = System.nanoTime();
            h.assertTrue(restored.sample(pos, clock.getGameTime()).confirmed(), "長期入力の保存復元後も評価できる");
            long evaluateNs = System.nanoTime() - evaluateStart;
            for (int years : new int[]{1, 10}) {
                long end = start + years * 24L * 24000;
                // 1年の時刻は既存入力の末尾より古いので、現在時刻自体は巻き戻さない。
                clock.setGameTime(Math.max(clock.getGameTime(), end));
                var source = UUID.randomUUID();
                var delayed = event(h, pos, source, 1, start, end, 0, -5000);
                long replayStart = System.nanoTime();
                h.assertTrue(restored.receive(delayed) == SourceReceipts.Result.ACCEPTED, "年単位で遅れた炉の放熱を受領");
                var state = restored.sample(pos, clock.getGameTime());
                h.assertTrue(state.confirmed() && Double.isFinite(state.state().orElseThrow().temperatureK()), "1年・10年復帰を再構成");
                h.assertTrue(restored.receive(delayed) == SourceReceipts.Result.DUPLICATE, "長期遅延の再送も排除");
                LogUtils.getLogger().info("M3-137 retained furnace years={} replay_ms={}", years, (System.nanoTime()-replayStart)/1e6);
            }
            var finalSave = restored.save(new CompoundTag(), h.getLevel().registryAccess());
            h.assertTrue(finalSave.getList("emissions", 12).size() <= 1024, "遅延通知でも保持上限を超えない");
            var intervals = restored.reconstruction(pos);
            h.assertTrue(!intervals.isEmpty() && intervals.getFirst().endTick() < clock.getGameTime(), "現在時刻ではなくcheckpoint区間を明示");
            LogUtils.getLogger().info("M3-137 history sources=40 seconds=86400 inputs=3456000 retained={} maximum_retained={} cumulative={} raw_bytes={} compressed_bytes={} input_ms={} save_ms={} load_ms={} evaluate_ms={}",
                    retained, service.retention().maximumRecent(), service.retention().cumulative(), uncompressed.size(), bytes.size(), inputNs/1e6, saveNs/1e6, loadNs/1e6, evaluateNs/1e6);
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void geometryHistoryIsBoundedAndPastQueriesReconstruct(GameTestHelper h) {
        box(h, 5);
        var service = fixture(h);
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        long start = h.getLevel().getGameTime();
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        discover(service, pos, start);
        try {
            for (int n = 1; n <= 100; n++) {
                clock.setGameTime(start + n);
                var wall = new BlockPos(0, 2, 2);
                h.setBlock(wall, n % 2 == 0 ? Blocks.STONE : Blocks.AIR);
                service.changed(h.absolutePos(wall));
                h.assertTrue(discover(service, pos, start + n).confirmed(), "構成を再確認");
            }
            var saved = service.save(new CompoundTag(), h.getLevel().registryAccess());
            h.assertTrue(saved.getList("regions", 10).size() <= 65, "現在室と旧64世代以内");
            var restored = RoomAir.load(h.getLevel(), saved);
            restored.outside((l, p, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, AIR))));
            h.assertTrue(restored.sample(pos, start).confirmed(), "破棄世代の照会を代表checkpoint構成で再構成");
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void checkpointApproximationReportsItsDifferenceAndConservesInput(GameTestHelper h) {
        box(h, 5); h.setBlock(new BlockPos(0, 2, 2), Blocks.AIR);
        // 開口セルは露天境界とし、開閉で室の空気容積を増減させない。
        h.setBlock(new BlockPos(1, 2, 0), Blocks.AIR);
        var service = fixture(h);
        var calm = new RoomPhysics.Air(293.15, 101325, 0, .00042, .2095, 0);
        service.outside((l, p, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, calm))));
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        long start = h.getLevel().getGameTime();
        var room = discover(service, pos, start);
        var shape = service.save(new CompoundTag(), h.getLevel().registryAccess()).getList("geometries", 10).getCompound(0);
        double walls = shape.getInt("walls"), openings = shape.getInt("openings"), height = shape.getDouble("height");
        var reference = new RoomPhysics.State[12001];
        reference[0] = RoomPhysics.State.ambient(calm);
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        var source = UUID.randomUUID();
        try {
            for (int second = 1; second <= 12000; second++) {
                clock.setGameTime(start + second * 20L);
                double heat = second <= 6000 ? 6000 : 0;
                double co2 = second <= 6000 ? .0001 : 0;
                service.respiration(source, second, pos, clock.getGameTime() - 20, clock.getGameTime(), co2 * 1.5, co2, heat);
                var before = reference[second - 1];
                reference[second] = RoomPhysics.advance(before, calm, room.volumeM3(), walls,
                        RoomPhysics.flow(openings, height, before.temperatureK(), calm),
                        new RoomPhysics.Rate(heat, 0, co2, -co2 * 1.5), 1);
            }
            var saved = service.save(new CompoundTag(), h.getLevel().registryAccess());
            int cut = (int) ((saved.getLong("checkpoint_tick") - start) / 20);
            var approximate = service.sample(pos, start + cut * 20L).state().orElseThrow();
            double errorK = Math.abs(approximate.temperatureK() - reference[cut].temperatureK());
            double errorCO2 = Math.abs(approximate.co2() - reference[cut].co2());
            double moles = calm.pressurePa() * room.volumeM3() / (RoomPhysics.R * calm.temperatureK());
            double energyBound = 36000000 / (moles * .02897 * 1005 + walls * 20000);
            h.assertTrue(errorK <= energyBound + 1e-8 && errorCO2 <= .6 / moles + 1e-10,
                    "固定外気・非負発生源の誤差は断熱/密閉の全量応答以下");
            double heatTotal = 0, gasTotal = 0;
            for (String name : List.of("emissions", "cumulative")) for (var row : RoomAir.loadEntries(saved.getList(name, 12))) {
                heatTotal += row.amount().heatJ(); gasTotal += row.amount().co2Mol();
            }
            h.assertTrue(Math.abs(heatTotal - 36000000) < .0001 && Math.abs(gasTotal - .6) < 1e-9,
                    "時間分布を近似しても熱とガスの入力総量を保存");
            var restored = RoomAir.load(h.getLevel(), service.save(new CompoundTag(), h.getLevel().registryAccess()));
            restored.outside((l, p, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, calm))));
            var replay = restored.sample(pos, start + cut * 20L).state().orElseThrow();
            h.assertTrue(Math.abs(replay.temperatureK() - approximate.temperatureK()) < 1e-9, "近似checkpointも保存復元で一致");
            LogUtils.getLogger().info("M3-137 approximation checkpoint_real_seconds={} temperature_error_K={} co2_error_ppm={} adiabatic_bound_K={} sealed_bound_ppm={}",
                    cut, errorK, errorCO2 * 1e6, energyBound, .6 / moles * 1e6);
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void splitRoomDoesNotDuplicateNewGasOrHeat(GameTestHelper h) {
        box(h, 7);
        var service = fixture(h);
        var left = h.absolutePos(new BlockPos(1, 2, 2));
        var right = h.absolutePos(new BlockPos(5, 2, 2));
        long start = h.getLevel().getGameTime();
        discover(service, left, start);
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        try {
            clock.setGameTime(start + 1);
            for (int y = 1; y < 6; y++) for (int z = 1; z < 6; z++) {
                var wall = new BlockPos(3, y, z);
                h.setBlock(wall, Blocks.STONE); service.changed(h.absolutePos(wall));
            }
            var a = discover(service, left, start + 1);
            var b = discover(service, right, start + 1);
            double kg = .028 / .1263;
            service.receive(new CombustionEvent(h.getLevel(), left.below(), start + 1, start + 1,
                    AshenFrontierCore.id("dry_wood"), kg, 10000, 0, UUID.randomUUID(), 1));
            var afterA = service.sample(left, start + 1);
            var afterB = service.sample(right, start + 1);
            double ma = AIR.pressurePa() * a.volumeM3() / (RoomPhysics.R * AIR.temperatureK());
            double mb = AIR.pressurePa() * b.volumeM3() / (RoomPhysics.R * AIR.temperatureK());
            double mol = afterA.state().orElseThrow().co() * ma + afterB.state().orElseThrow().co() * mb;
            h.assertTrue(Math.abs(mol - 1) < 1e-10, "分割後の左右のCO総量が1 mol（2 molにしない）");
            var tag = service.save(new CompoundTag(), h.getLevel().registryAccess());
            var shapes = tag.getList("geometries", 10);
            double energy = 0;
            for (var element : tag.getList("regions", 10)) {
                var row = (CompoundTag) element;
                if (row.getLong("to") != Long.MAX_VALUE) continue;
                var g = shapes.getCompound(row.getInt("geometry"));
                double volume = g.getLongArray("cells").length;
                double moles = AIR.pressurePa() * volume / (RoomPhysics.R * AIR.temperatureK());
                boolean isLeft = Arrays.stream(g.getLongArray("cells")).anyMatch(p -> p == left.asLong());
                double temperature = (isLeft ? afterA : afterB).state().orElseThrow().temperatureK();
                energy += (temperature - AIR.temperatureK()) * (moles * .02897 * 1005 + g.getInt("walls") * 20000);
            }
            h.assertTrue(Math.abs(energy - 10000) < 1e-6, "左右に保存された熱の合計も10000 J");
            var zero = UUID.randomUUID();
            for (int n = 1; n <= 1200; n++) service.respiration(zero, n, left, start + 1, start + 1, 0, 0, 0);
            var restored = RoomAir.load(h.getLevel(), service.save(new CompoundTag(), h.getLevel().registryAccess()));
            restored.outside((l, p, from, to) -> Optional.of(List.of(new RoomAir.Boundary(from, to, AIR))));
            double replay = restored.sample(left, start + 1).state().orElseThrow().co() * ma
                    + restored.sample(right, start + 1).state().orElseThrow().co() * mb;
            h.assertTrue(Math.abs(replay - 1) < 1e-10, "集約と保存復元の後も左右合計1 mol");
            clock.setGameTime(start + 2);
            for (int y = 1; y < 6; y++) for (int z = 1; z < 6; z++) {
                var wall = new BlockPos(3, y, z);
                h.setBlock(wall, Blocks.AIR); restored.changed(h.absolutePos(wall));
            }
            var joined = discover(restored, left, start + 2);
            restored.receive(new CombustionEvent(h.getLevel(), left.below(), start + 2, start + 2,
                    AshenFrontierCore.id("dry_wood"), kg, 0, 0, UUID.randomUUID(), 1));
            double joinedMoles = AIR.pressurePa() * joined.volumeM3() / (RoomPhysics.R * AIR.temperatureK());
            double joinedCO = restored.sample(left, start + 2).state().orElseThrow().co() * joinedMoles;
            h.assertTrue(Math.abs(joinedCO - 2) < 1e-9, "再結合後の新規1 molを祖先へ逆流させない");
            for (int step = 1; step <= 70; step++) {
                clock.setGameTime(start + 2 + step);
                boolean split = step % 2 == 1;
                for (int y = 1; y < 6; y++) for (int z = 1; z < 6; z++) {
                    var wall = new BlockPos(3, y, z);
                    h.setBlock(wall, split ? Blocks.STONE : Blocks.AIR); restored.changed(h.absolutePos(wall));
                }
                var first = discover(restored, left, clock.getGameTime());
                var second = split ? discover(restored, right, clock.getGameTime()) : null;
                double factor = AIR.pressurePa() / (RoomPhysics.R * AIR.temperatureK());
                double beforeMol = first.state().orElseThrow().co() * first.volumeM3() * factor
                        + (split ? second.state().orElseThrow().co() * second.volumeM3() * factor : 0);
                restored.receive(new CombustionEvent(h.getLevel(), left.below(), clock.getGameTime(), clock.getGameTime(),
                        AshenFrontierCore.id("dry_wood"), kg, 0, 0, UUID.randomUUID(), 1));
                var current = restored.sample(left, clock.getGameTime());
                double afterMol = current.state().orElseThrow().co() * current.volumeM3() * factor;
                if (split) {
                    var other = restored.sample(right, clock.getGameTime());
                    afterMol += other.state().orElseThrow().co() * other.volumeM3() * factor;
                }
                h.assertTrue(Math.abs(afterMol - beforeMol - 1) < 1e-8, "旧64世代を越えた後も新規入力は合計1 molだけ");
            }
            var aged = restored.save(new CompoundTag(), h.getLevel().registryAccess());
            double input = aged.getDouble("archived_co");
            for (String name : List.of("emissions", "cumulative")) for (var row : RoomAir.loadEntries(aged.getList(name, 12)))
                input += row.amount().coMol();
            h.assertTrue(Math.abs(input - 72) < 1e-8, "世代を破棄しても入力の台帳合計72 molを保存");
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void outdoorProbeIsInvalidatedByRoofAboveIt(GameTestHelper h) {
        StructureUtils.removeBarriers(h.getBounds(), h.getLevel());
        var service = RoomAir.get(h.getLevel());
        service.outside((l, p, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, AIR))));
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        h.assertTrue(discover(service, pos, h.getLevel().getGameTime()).status() == RoomAir.Status.OUTDOOR, "最初は露天");
        box(h, 5);
        var closed = discover(service, pos, h.getLevel().getGameTime());
        h.assertTrue(closed.status() == RoomAir.Status.INDOOR && closed.covered() && !closed.ventilated(),
                "上方を屋根で閉じたら露天キャッシュを使い続けない");
        h.succeed();
    }
    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void sourceQueryReadsPersistedReceiptAcrossDimensions(GameTestHelper h) {
        var source = UUID.randomUUID();
        var other = h.getLevel().getServer().getLevel(Level.NETHER);
        long now = other.getGameTime();
        var event = new CombustionEvent(other, BlockPos.ZERO, now, now,
                AshenFrontierCore.id("dry_wood"), 0, 0, -10, source, 7);
        NeoForge.EVENT_BUS.post(event);
        h.assertTrue(CombustionSourceQuery.lastSequence(h.getLevel(), source) == 7, "別次元の受領済み番号も実イベント照会で読む");
        var saved = RoomAir.get(other).save(new CompoundTag(), other.registryAccess());
        var restored = RoomAir.load(other, saved);
        h.assertTrue(restored.receive(event) == SourceReceipts.Result.DUPLICATE, "設備の有効登録なしでも受領番号は保存復元される");
        h.assertTrue(CombustionSourceQuery.lastSequence(h.getLevel(), source) == 7, "照会では番号を進めない");
        h.succeed();
    }


    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void existingHeatSurvivesPartitionAndReunion(GameTestHelper h) {
        box(h, 7);
        var service = fixture(h);
        var left = h.absolutePos(new BlockPos(1, 2, 2));
        var right = h.absolutePos(new BlockPos(5, 2, 2));
        long now = h.getLevel().getGameTime();
        var original = discover(service, left, now);
        service.receive(new CombustionEvent(h.getLevel(), left.below(), now, now,
                AshenFrontierCore.id("dry_wood"), 0, 10000, 0, UUID.randomUUID(), 1));
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        long start = now;
        try {
            clock.setGameTime(++now);
            double initial = storedHeat(h, service, left, now);
            var initialQuantity = service.quantity().orElseThrow();
            h.assertTrue(initial > 9999 && initial <= 10000, "構成変更の前に10000 J");
            for (int y = 1; y < 6; y++) for (int z = 1; z < 6; z++) {
                var wall = new BlockPos(3, y, z); h.setBlock(wall, Blocks.STONE); service.changed(h.absolutePos(wall));
            }
            discover(service, left, now); discover(service, right, now);
            double split = storedHeat(h, service, left, now) + storedHeat(h, service, right, now);
            h.assertTrue(Math.abs(split + initial * 25 / original.volumeM3() - initial) < 1e-6,
                    "分割後の両室と仕切り位置の未配分の熱量が元の10000 J");
            var restored = RoomAir.load(h.getLevel(), service.save(new CompoundTag(), h.getLevel().registryAccess()));
            restored.outside((l, p0, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, AIR))));
            for (int y = 1; y < 6; y++) for (int z = 1; z < 6; z++) {
                var wall = new BlockPos(3, y, z); h.setBlock(wall, Blocks.AIR); restored.changed(h.absolutePos(wall));
            }
            discover(restored, left, now);
            h.assertTrue(Math.abs(storedHeat(h, restored, left, now) - initial) < 1e-6,
                    "保存復元して再結合しても既存熱量を増減させない");
            for (int step = 1; step <= 70; step++) {
                boolean splitAgain = step % 2 == 1;
                for (int y = 1; y < 6; y++) for (int z = 1; z < 6; z++) {
                    var wall = new BlockPos(3, y, z);
                    h.setBlock(wall, splitAgain ? Blocks.STONE : Blocks.AIR); restored.changed(h.absolutePos(wall));
                }
                discover(restored, left, now);
                if (splitAgain) discover(restored, right, now);
                else h.assertTrue(Math.abs(storedHeat(h, restored, left, now) - initial) < 1e-5,
                        "交換時間0の繰返しと旧世代整理でも既存10000 Jを保存: " + step);
                var actual = restored.quantity().orElseThrow();
                h.assertTrue(Math.abs(actual.heatJ() - initialQuantity.heatJ()) < 1e-5
                        && Math.abs(actual.coMol() - initialQuantity.coMol()) < 1e-10
                        && Math.abs(actual.co2Mol() - initialQuantity.co2Mol()) < 1e-10
                        && Math.abs(actual.oxygenMol() - initialQuantity.oxygenMol()) < 1e-8,
                        "各分割/統合で全室+未配分の実J/molを保存: " + step);
            }
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    private static double storedHeat(GameTestHelper h, RoomAir service, BlockPos pos, long tick) {
        var sample = service.sample(pos, tick);
        var saved = service.save(new CompoundTag(), h.getLevel().registryAccess());
        var shapes = saved.getList("geometries", 10);
        for (var item : saved.getList("regions", 10)) {
            var row = (CompoundTag) item;
            var shape = shapes.getCompound(row.getInt("geometry"));
            if (row.getLong("to") != Long.MAX_VALUE || Arrays.stream(shape.getLongArray("cells")).noneMatch(v -> v == pos.asLong())) continue;
            double mol = 101325 * sample.volumeM3() / (RoomPhysics.R * 293.15);
            return (sample.state().orElseThrow().temperatureK() - 293.15) * (mol * .02897 * 1005 + shape.getInt("walls") * 20000);
        }
        throw new AssertionError("評価した室の幾何がない");
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void displacedGasReturnsAfterSeventyGeometryChanges(GameTestHelper h) {
        StructureUtils.removeBarriers(h.getBounds(), h.getLevel());
        for (int x = 0; x < 5; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++)
            h.setBlock(new BlockPos(x, y, z), x == 0 || x == 4 || y != 1 || z != 1 ? Blocks.STONE : Blocks.AIR);
        // 開口セルは露天境界とし、開閉で室の空気容積を増減させない。
        h.setBlock(new BlockPos(1, 2, 0), Blocks.AIR);
        var service = fixture(h);
        var calm = new RoomPhysics.Air(293.15, 101325, 0, .00042, .2095, 0);
        service.outside((l, p0, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, calm))));
        var left = h.absolutePos(new BlockPos(1, 1, 1));
        var right = h.absolutePos(new BlockPos(3, 1, 1));
        var middle = new BlockPos(2, 1, 1);
        var opening = new BlockPos(1, 1, 0);
        long start = h.getLevel().getGameTime();
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        try {
            discover(service, left, start);
            double kg = .028 / .1263;
            service.receive(new CombustionEvent(h.getLevel(), left.below(), start, start,
                    AshenFrontierCore.id("dry_wood"), kg, 0, 0, UUID.randomUUID(), 1));
            var originalQuantity = RoomPhysics.amount(service.sample(left, start).state().orElseThrow(), 3, 0);
            // 熱は入力しない。浮力/風による流量は全期間0。
            clock.setGameTime(start + 1);
            h.setBlock(middle, Blocks.STONE); service.changed(h.absolutePos(middle));
            discover(service, left, start + 1); discover(service, right, start + 1);
            for (int step = 1; step <= 70; step++) {
                clock.setGameTime(start + step + 1);
                h.setBlock(opening, step % 2 == 0 ? Blocks.STONE : Blocks.AIR);
                service.changed(h.absolutePos(opening));
                h.assertTrue(discover(service, left, clock.getGameTime()).flowM3S() == 0, "無風・内外同温で交換0");
                if (step == 60 || step == 66 || step == 70) {
                    var saved = service.save(new CompoundTag(), h.getLevel().registryAccess());
                    var probe = RoomAir.load(h.getLevel(), saved);
                    probe.outside((l, p0, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, calm))));
                    h.setBlock(middle, Blocks.AIR); probe.changed(h.absolutePos(middle));
                    var joined = discover(probe, left, clock.getGameTime());
                    double mol = joined.state().orElseThrow().co() * 101325 * joined.volumeM3() / (RoomPhysics.R * 293.15);
                    h.assertTrue(Math.abs(mol - 1) < 1e-10, step + "回変更/保存復元後の再結合でもCO総量1 mol: " + mol);
                    var quantity = RoomPhysics.amount(joined.state().orElseThrow(), joined.volumeM3(), 0);
                    h.assertTrue(Math.abs(quantity.co2Mol() - originalQuantity.co2Mol()) < 1e-10
                            && Math.abs(quantity.oxygenMol() - originalQuantity.oxygenMol()) < 1e-10
                            && quantity.heatJ() == 0, "CO2/O2/熱も追加入力・交換なしでは保存");
                    var all = service.quantity().orElseThrow();
                    h.assertTrue(Math.abs(all.coMol() - originalQuantity.coMol()) < 1e-10
                            && Math.abs(all.co2Mol() - originalQuantity.co2Mol()) < 1e-10
                            && Math.abs(all.oxygenMol() - originalQuantity.oxygenMol()) < 1e-10
                            && all.heatJ() == 0, "再結合前の全室+未配分にも同じJ/molが残る");
                    // 検査用の再結合で本体の世代を更新せず、66回まで同じ中央セルを未配分に保つ。
                    h.setBlock(middle, Blocks.STONE);
                }
            }
            service.receive(new CombustionEvent(h.getLevel(), h.absolutePos(middle).below(),
                    clock.getGameTime(), clock.getGameTime(), AshenFrontierCore.id("dry_wood"),
                    kg * .3, 0, 0, UUID.randomUUID(), 1));
            var last = service.save(new CompoundTag(), h.getLevel().registryAccess());
            service = RoomAir.load(h.getLevel(), last);
            service.outside((l, p0, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, calm))));
            h.setBlock(middle, Blocks.AIR); service.changed(h.absolutePos(middle));
            var finalRoom = discover(service, left, clock.getGameTime());
            double finalMol = finalRoom.state().orElseThrow().co() * RoomPhysics.moles(finalRoom.volumeM3());
            h.assertTrue(Math.abs(finalMol - 1.3) < 1e-10, "未配分位置への遅延通知も保存復元・再結合で一度だけ戻す");
            service.receive(new CombustionEvent(h.getLevel(), h.absolutePos(middle).below(), start + 2, start + 3,
                    AshenFrontierCore.id("dry_wood"), kg * .2, 0, 0, UUID.randomUUID(), 1));
            var afterLate = service.sample(left, clock.getGameTime());
            double afterLateMol = afterLate.state().orElseThrow().co() * RoomPhysics.moles(afterLate.volumeM3());
            h.assertTrue(Math.abs(afterLateMol - 1.5) < 1e-10, "回収後に届く閉塞期間の通知も過去の回収境界から再評価する");
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void notificationDuringRediscoveryPreservesMoles(GameTestHelper h) {
        box(h, 5);
        var service = fixture(h);
        var calm = new RoomPhysics.Air(293.15, 101325, 0, .00042, .2095, 0);
        service.outside((l, p0, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, calm))));
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        long start = h.getLevel().getGameTime();
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        try {
            discover(service, pos, start);
            double kg = .028 / .1263;
            service.receive(new CombustionEvent(h.getLevel(), pos.below(), start, start,
                    AshenFrontierCore.id("dry_wood"), kg, 0, 0, UUID.randomUUID(), 1));
            clock.setGameTime(start + 1);
            var wall = new BlockPos(1, 1, 1);
            h.setBlock(wall, Blocks.STONE); service.changed(h.absolutePos(wall));
            clock.setGameTime(start + 2);
            service.receive(new CombustionEvent(h.getLevel(), pos.below(), start + 1, start + 2,
                    AshenFrontierCore.id("dry_wood"), kg, 0, 0, UUID.randomUUID(), 1));
            clock.setGameTime(start + 3);
            var restored = RoomAir.load(h.getLevel(), service.save(new CompoundTag(), h.getLevel().registryAccess()));
            restored.outside((l, p0, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, calm))));
            discover(restored, pos, start + 3);
            h.assertTrue(Math.abs(restored.quantity().orElseThrow().coMol() - 2) < 1e-10,
                    "再探索前に受領・保存した通知も全室+未配分へ一度だけ残る");
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void recoveredQuantityIsNotReplayedByAnotherRoomsCompaction(GameTestHelper h) {
        long start = h.getLevel().getGameTime();
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        var calm = new RoomPhysics.Air(293.15, 101325, 0, .00042, .2095, 0);
        try {
            for (int order = 0; order < 3; order++) {
                clock.setGameTime(start);
                StructureUtils.removeBarriers(h.getBounds(), h.getLevel());
                for (int offset : new int[]{0, 8})
                    for (int x = 0; x < 5; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++)
                        h.setBlock(new BlockPos(offset + x, y, z),
                                x == 0 || x == 4 || y != 1 || z != 1 ? Blocks.STONE : Blocks.AIR);
                h.setBlock(new BlockPos(9, 2, 0), Blocks.AIR);
                var service = fixture(h);
                service.outside((l, p0, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, calm))));
                var main = h.absolutePos(new BlockPos(2, 1, 1));
                var other = h.absolutePos(new BlockPos(10, 1, 1));
                var opening = new BlockPos(9, 1, 0);
                discover(service, main, start); discover(service, other, start);
                var initial = service.quantity().orElseThrow();
                service.receive(new CombustionEvent(h.getLevel(), main.below(), start, start,
                        AshenFrontierCore.id("dry_wood"), .028 / .1263, 0, 0, UUID.randomUUID(), 1));
                var expected = service.quantity().orElseThrow();
                clock.setGameTime(start + 1);
                for (int x = 1; x <= 3; x++) {
                    var cell = new BlockPos(x, 1, 1); h.setBlock(cell, Blocks.STONE); service.changed(h.absolutePos(cell));
                }
                for (int n = 0; n < 72; n++) {
                    clock.setGameTime(start + n + 2);
                    h.setBlock(opening, n % 2 == 0 ? Blocks.AIR : Blocks.STONE); service.changed(h.absolutePos(opening));
                    discover(service, other, clock.getGameTime());
                }
                checkQuantity(h, service, expected, "旧世代整理後");
                if (order == 1) compactOtherRoom(h, service, other);
                if (order == 2) {
                    service = RoomAir.load(h.getLevel(), service.save(new CompoundTag(), h.getLevel().registryAccess()));
                    service.outside((l, p0, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, calm))));
                }
                for (int x = 1; x <= 3; x++) {
                    var cell = new BlockPos(x, 1, 1); h.setBlock(cell, Blocks.AIR); service.changed(h.absolutePos(cell));
                }
                discover(service, main, clock.getGameTime());
                checkQuantity(h, service, expected, "回収後/順序" + order);
                service.receive(new CombustionEvent(h.getLevel(), main.below(), start, start + 1,
                        AshenFrontierCore.id("dry_wood"), .2 * .028 / .1263, 0, 0, UUID.randomUUID(), 1));
                expected = RoomHistory.plus(expected, RoomHistory.scale(
                        RoomHistory.plus(expected, RoomHistory.scale(initial, -1)), .2));
                checkQuantity(h, service, expected, "全セル回収後の観測済み区間の遅延入力1.2 mol");
                for (int pass = 0; pass < 2; pass++) {
                    compactOtherRoom(h, service, other);
                    checkQuantity(h, service, expected, "回収後の再集約/順序" + order + "/回" + pass);
                    service = RoomAir.load(h.getLevel(), service.save(new CompoundTag(), h.getLevel().registryAccess()));
                    service.outside((l, p0, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, calm))));
                    checkQuantity(h, service, expected, "再集約後の保存復元");
                }
            }
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void observedEndpointsIgnoreRoomsAfterFixtureTimeRewind(GameTestHelper h) {
        box(h, 5);
        var service = fixture(h);
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        long start = clock.getGameTime();
        var future = h.absolutePos(new BlockPos(2, 2, 2));
        try {
            clock.setGameTime(start + 20);
            h.assertTrue(discover(service, future, clock.getGameTime()).confirmed(), "時刻fixtureの先の室");
            clock.setGameTime(start);
            for (int x = 8; x <= 12; x++) for (int y = 0; y <= 2; y++) for (int z = 0; z <= 2; z++)
                h.setBlock(new BlockPos(x, y, z), y == 1 && z == 1 && x > 8 && x < 12 ? Blocks.AIR : Blocks.STONE);
            h.setBlock(new BlockPos(9, 2, 0), Blocks.AIR);
            var other = h.absolutePos(new BlockPos(10, 1, 1));
            var opening = new BlockPos(9, 1, 0);
            for (int n = 0; n < 72; n++) {
                h.setBlock(opening, n % 2 == 0 ? Blocks.AIR : Blocks.STONE);
                service.changed(h.absolutePos(opening));
                h.assertTrue(discover(service, other, clock.getGameTime()).confirmed(), "有効な室の整理を継続: " + n);
            }
            clock.setGameTime(start + 20);
            h.assertTrue(service.sample(future, clock.getGameTime()).confirmed(), "先の室も有効時刻には再評価可能");
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID, batch = "room_historical")
    public static void historicalClosedInputDoesNotUseRecoveredOpening(GameTestHelper h) {
        long start = h.getLevel().getGameTime();
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        try {
            for (boolean restore : new boolean[]{false, true}) for (int order = 0; order < 2; order++) {
                clock.setGameTime(start);
                StructureUtils.removeBarriers(h.getBounds(), h.getLevel());
                for (int offset : new int[]{0, 8}) for (int x = 0; x <= 4; x++)
                    for (int y = 0; y <= 2; y++) for (int z = 0; z <= 2; z++)
                        h.setBlock(new BlockPos(offset+x, y, z), y == 1 && z == 1 && x > 0 && x < 4 ? Blocks.AIR : Blocks.STONE);
                h.setBlock(new BlockPos(0, 2, 1), Blocks.AIR);
                h.setBlock(new BlockPos(9, 2, 0), Blocks.AIR);
                var service = fixture(h);
                var main = h.absolutePos(new BlockPos(2, 1, 1));
                var other = h.absolutePos(new BlockPos(10, 1, 1));
                discover(service, main, start); discover(service, other, start);
                service.receive(new CombustionEvent(h.getLevel(), main.below(), start, start,
                        AshenFrontierCore.id("dry_wood"), .028/.1263, 0, 0, UUID.randomUUID(), 1));
                clock.setGameTime(start+1);
                for (int x = 1; x <= 3; x++) {
                    var cell = new BlockPos(x, 1, 1); h.setBlock(cell, Blocks.STONE); service.changed(h.absolutePos(cell));
                }
                for (int n = 0; n < 72; n++) {
                    clock.setGameTime(start+n+2);
                    var opening = new BlockPos(9, 1, 0); h.setBlock(opening, n%2 == 0 ? Blocks.AIR : Blocks.STONE);
                    service.changed(h.absolutePos(opening)); discover(service, other, clock.getGameTime());
                }
                clock.setGameTime(start+1000);
                var delayed = new CombustionEvent(h.getLevel(), main.below(), start, start+1,
                        AshenFrontierCore.id("dry_wood"), .2*.028/.1263, 0, 0, UUID.randomUUID(), 1);
                if (order == 0) service.receive(delayed);
                if (restore) { service = RoomAir.load(h.getLevel(), service.save(new CompoundTag(), h.getLevel().registryAccess()));
                    service.outside((l, p0, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, AIR)))); }
                for (int x = 1; x <= 3; x++) { var cell = new BlockPos(x, 1, 1);
                    h.setBlock(cell, Blocks.AIR); service.changed(h.absolutePos(cell)); }
                discover(service, main, clock.getGameTime());
                var opening = new BlockPos(0, 1, 1); h.setBlock(opening, Blocks.AIR); service.changed(h.absolutePos(opening));
                h.assertTrue(discover(service, main, clock.getGameTime()).ventilated(), "回収後に開口が成立");
                if (restore) { service = RoomAir.load(h.getLevel(), service.save(new CompoundTag(), h.getLevel().registryAccess()));
                    service.outside((l, p0, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, AIR)))); }
                if (order == 1) service.receive(delayed);
                var pastState = service.sample(main, start);
                h.assertTrue(pastState.confirmed() && Math.abs(pastState.state().orElseThrow().co()*3*101325/(8.314462618*293.15)-1) < 1e-10,
                        "回収前の観測済み密閉期間も元の構成と元の入力から再構成");
                var actual = service.balance().orElseThrow();
                h.assertTrue(Math.abs(actual.retained().coMol()-1.2) < 1e-10
                        && Math.abs(actual.exchange().coMol()) < 1e-10,
                        "密閉の過去入力は到着順/復元によらず残留1.2・外気0: order="+order+" restore="+restore+" "+actual);
            }
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID, batch = "room_historical")
    public static void lateInputDuringRegistrationIsRecoveredOnce(GameTestHelper h) throws Exception {
        box(h, 5);
        var service = fixture(h);
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        long start = clock.getGameTime();
        try {
            discover(service, pos, start);
            service.receive(new CombustionEvent(h.getLevel(), pos.below(), start, start,
                    AshenFrontierCore.id("dry_wood"), .028/.1263, 0, 0, UUID.randomUUID(), 1));
            clock.setGameTime(start+1);
            box(h, 16); service.changed(h.absolutePos(new BlockPos(2, 4, 2)));
            service.sample(pos, clock.getGameTime());
            var registrations = RoomAir.class.getDeclaredField("registrations"); registrations.setAccessible(true);
            boolean copied = false;
            for (int n = 0; n < 512 && !copied; n++) {
                service.work();
                var pending = (Map<?, ?>) registrations.get(service);
                for (var registration : pending.values()) {
                    var room = registration.getClass().getDeclaredField("room"); room.setAccessible(true);
                    if (room.get(registration) != null) copied = true;
                }
            }
            h.assertTrue(copied, "親セルの照合後、登録完了前の窓を通す");
            service.receive(new CombustionEvent(h.getLevel(), pos.below(), start, start+1,
                    AshenFrontierCore.id("dry_wood"), .2*.028/.1263, 0, 0, UUID.randomUUID(), 1));
            for (int n = 0; n < 512 && service.sample(pos, clock.getGameTime()).status() == RoomAir.Status.SCANNING; n++) service.work();
            var state = service.sample(pos, clock.getGameTime()).state().orElseThrow();
            double mol = 14*14*14*101325/(8.314462618*293.15);
            h.assertTrue(Math.abs(state.co()*mol-1.2) < 1e-9 && Math.abs(service.quantity().orElseThrow().coMol()-1.2) < 1e-9,
                    "登録途中の遅延量を全室と未配分へ一度だけ回収: room="+state.co()*mol+" total="+service.quantity());
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID, batch = "room_historical")
    public static void historicalInstantUsesItsParentOrChildContext(GameTestHelper h) {
        long start = h.getLevel().getGameTime();
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        try {
            box(h, 5); var service = fixture(h);
            var left = h.absolutePos(new BlockPos(1, 2, 2)); var right = h.absolutePos(new BlockPos(3, 2, 2));
            discover(service, left, start);
            service.receive(new CombustionEvent(h.getLevel(), left.below(), start, start,
                    AshenFrontierCore.id("dry_wood"), .27*.028/.1263, 0, 0, UUID.randomUUID(), 1));
            for (int y = 1; y <= 3; y++) for (int z = 1; z <= 3; z++) {
                var wall = new BlockPos(2, y, z); h.setBlock(wall, Blocks.STONE); service.changed(h.absolutePos(wall)); }
            discover(service, left, start); discover(service, right, start);
            service.receive(new CombustionEvent(h.getLevel(), left.below(), start, start,
                    AshenFrontierCore.id("dry_wood"), .09*.028/.1263, 0, 0, UUID.randomUUID(), 1));
            clock.setGameTime(start+1);
            for (int y = 1; y <= 3; y++) for (int z = 1; z <= 3; z++) {
                var wall = new BlockPos(2, y, z); h.setBlock(wall, Blocks.AIR); service.changed(h.absolutePos(wall)); }
            discover(service, left, clock.getGameTime());
            for (int x = 8; x <= 12; x++) for (int y = 0; y <= 2; y++) for (int z = 0; z <= 2; z++)
                h.setBlock(new BlockPos(x, y, z), y == 1 && z == 1 && x > 8 && x < 12 ? Blocks.AIR : Blocks.STONE);
            h.setBlock(new BlockPos(9, 2, 0), Blocks.AIR);
            var other = h.absolutePos(new BlockPos(10, 1, 1));
            discover(service, other, clock.getGameTime());
            for (int n = 0; n < 72; n++) {
                clock.setGameTime(start+n+2); var opening = new BlockPos(9, 1, 0);
                h.setBlock(opening, n%2 == 0 ? Blocks.AIR : Blocks.STONE); service.changed(h.absolutePos(opening));
                discover(service, other, clock.getGameTime());
            }
            var restored = RoomAir.load(h.getLevel(), service.save(new CompoundTag(), h.getLevel().registryAccess()));
            restored.outside((l, p0, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, AIR))));
            var historical = restored.sample(left, start);
            double mol = 9*101325/(8.314462618*293.15);
            h.assertTrue(historical.confirmed() && historical.volumeM3() == 9
                    && Math.abs(historical.state().orElseThrow().co()*mol-.18) < 1e-10,
                    "旧世代整理後も親の0.27を1/3と子の0.09を重ねた旧左室0.18 mol: "+historical);
            for (int n = 1; n <= 1026; n++) restored.respiration(new UUID(137, 12345), n, other,
                    clock.getGameTime(), clock.getGameTime(), 0, 0, 0);
            restored = RoomAir.load(h.getLevel(), restored.save(new CompoundTag(), h.getLevel().registryAccess()));
            restored.outside((l, p0, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, AIR))));
            var compacted = restored.sample(left, start);
            h.assertTrue(compacted.confirmed() && Math.abs(compacted.state().orElseThrow().co()*mol-.18) < 1e-10,
                    "監査の集約・復元後も同tick親子の所有室を保持: "+compacted);
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    static Object stageUnpublished(RoomAir service, BlockPos origin, long tick) throws ReflectiveOperationException {
        service.sample(origin, tick);
        var field = RoomAir.class.getDeclaredField("registrations"); field.setAccessible(true);
        Object registration = null;
        for (int n = 0; n < 512 && registration == null; n++) {
            var pending = (Map<?, ?>) field.get(service);
            registration = pending.get(origin.asLong());
            if (registration == null) service.work();
        }
        if (registration == null) throw new AssertionError("取消対象の登録が作られていない");
        var type = registration.getClass();
        var roomField = type.getDeclaredField("room"); roomField.setAccessible(true);
        var cursor = type.getDeclaredField("pastCursor"); cursor.setAccessible(true);
        var cellsField = type.getDeclaredField("cells"); cellsField.setAccessible(true);
        var indexing = type.getDeclaredField("indexing"); indexing.setAccessible(true);
        var portals = type.getDeclaredField("portalCursor"); portals.setAccessible(true);
        var step = type.getDeclaredMethod("step", int.class); step.setAccessible(true);
        boolean staged = false;
        for (int n = 0; n < 32768; n++) {
            var index = (Iterator<?>) indexing.get(registration);
            if (roomField.get(registration) != null && cursor.getInt(registration) == ((long[]) cellsField.get(registration)).length
                    && index != null && !index.hasNext() && portals.getInt(registration) == 0) { staged = true; break; }
            if ((boolean) step.invoke(registration, 1)) throw new AssertionError("取消前に公開されてしまった");
        }
        if (!staged) throw new AssertionError("公開直前まで登録を進められない");
        return registration;
    }

    static void cancelUnpublished(RoomAir service, BlockPos origin, long tick) throws ReflectiveOperationException {
        stageUnpublished(service, origin, tick);
        var field = RoomAir.class.getDeclaredField("registrations"); field.setAccessible(true);
        service.changed(origin);
        if (((Map<?, ?>) field.get(service)).containsKey(origin.asLong()))
            throw new AssertionError("変更イベントで登録が取り消されていない");
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID, batch = "room_historical")
    public static void cancelledRegistrationCannotExtendCommittedPast(GameTestHelper h) throws Exception {
        long start = h.getLevel().getGameTime();
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        try {
            box(h, 5); var service = fixture(h); var pos = h.absolutePos(new BlockPos(2, 2, 2));
            discover(service, pos, start);
            clock.setGameTime(start+5); h.setBlock(new BlockPos(2, 2, 2), Blocks.STONE); service.changed(pos);
            clock.setGameTime(start+7); h.setBlock(new BlockPos(2, 2, 2), Blocks.AIR); service.changed(pos);
            var field = RoomAir.class.getDeclaredField("past"); field.setAccessible(true);
            var past = (RoomPast) field.get(service);
            h.assertTrue(!past.known(pos.asLong(), start+6), "終了後の期間は室内ではない");
            var before = new CompoundTag(); past.save(before);
            for (int n = 0; n < 65; n++) cancelUnpublished(service, pos, clock.getGameTime());
            var after = new CompoundTag(); past.save(after);
            h.assertTrue(!past.known(pos.asLong(), start+6) && before.equals(after), "取消65回でも確定した過去を変更しない");
            var restored = RoomAir.load(h.getLevel(), service.save(new CompoundTag(), h.getLevel().registryAccess()));
            h.assertTrue(!((RoomPast) field.get(restored)).known(pos.asLong(), start+6), "復元しても閉塞期間を延長しない");
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    private static void compactOtherRoom(GameTestHelper h, RoomAir service, BlockPos pos) {
        var source = UUID.randomUUID();
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        for (int n = 1; n <= 1026; n++) {
            long from = clock.getGameTime();
            clock.setGameTime(from + 1);
            service.respiration(source, n, pos, from, from + 1, 0, 0, 0);
        }
    }

    private static void checkQuantity(GameTestHelper h, RoomAir service, RoomPhysics.Amount expected, String operation) {
        var actual = service.quantity().orElseThrow();
        h.assertTrue(Math.abs(actual.heatJ() - expected.heatJ()) < 1e-5
                && Math.abs(actual.coMol() - expected.coMol()) < 1e-10
                && Math.abs(actual.co2Mol() - expected.co2Mol()) < 1e-9
                && Math.abs(actual.oxygenMol() - expected.oxygenMol()) < 1e-9,
                operation + "で全室+未配分のJ/molが不変: CO=" + actual.coMol());
    }
}
