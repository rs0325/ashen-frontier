package io.github.rs0325.ashenfrontier.climate.room;

import com.mojang.logging.LogUtils;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.environment.CombustionEvent;
import java.util.UUID;
import io.github.rs0325.ashenfrontier.climate.AshenFrontierClimate;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RoomPublicationGameTests {
    private static final RoomPhysics.Air AIR = new RoomPhysics.Air(293.15, 101325, 0, .00042, .2095, 0);
    private static final Method STEP;
    private static final Constructor<?> CONSTRUCTOR;
    static {
        try {
            var type = Arrays.stream(RoomAir.class.getDeclaredClasses()).filter(c -> c.getSimpleName().equals("Registration")).findFirst().orElseThrow();
            STEP = type.getDeclaredMethod("step", int.class); STEP.setAccessible(true);
            CONSTRUCTOR = type.getDeclaredConstructor(RoomAir.class, RoomSearch.class); CONSTRUCTOR.setAccessible(true);
        } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
    private static RoomAir fixture(GameTestHelper h) {
        var tag = new CompoundTag(); tag.putInt("format", 8);
        var service = RoomAir.load(h.getLevel(), tag);
        service.outside((l, p, from, to) -> Optional.of(List.of(new RoomAir.Boundary(from, to, AIR))));
        return service;
    }
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        var f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    @SuppressWarnings("unchecked")
    private static Map<Long, Object> map(RoomAir service, String name) throws ReflectiveOperationException {
        return (Map<Long, Object>) field(service, name);
    }
    static Object start(RoomAir service, GameTestHelper h, BlockPos pos) throws ReflectiveOperationException {
        service.sample(pos, h.getLevel().getGameTime());
        var registrations = map(service, "registrations");
        if (registrations.containsKey(pos.asLong())) return registrations.get(pos.asLong());
        var job = (RoomSearch) map(service, "jobs").get(pos.asLong());
        if (job == null) throw new AssertionError("登録候補の探索がない");
        for (int n = 0; n < 1024 && job.status == RoomSearch.Status.SCANNING; n++) job.work(h.getLevel(), 256, 4096);
        if (job.status != RoomSearch.Status.INDOOR) throw new AssertionError("登録候補が室内ではない: "+job.status);
        var registration = CONSTRUCTOR.newInstance(service, job);
        registrations.put(pos.asLong(), registration); return registration;
    }
    static Object partial(RoomAir service, GameTestHelper h, BlockPos pos) throws ReflectiveOperationException {
        var registration = start(service, h, pos);
        for (int n = 0; n < 32768 && (int) field(registration, "pastCursor") == 0; n++)
            if ((boolean) STEP.invoke(registration, 1)) throw new AssertionError("整理途中に止まる前に公開された");
        int prepared = (int) field(registration, "pastCursor");
        if (prepared <= 0 || prepared >= ((long[]) field(registration, "cells")).length)
            throw new AssertionError("セル履歴の整理途中ではない");
        return registration;
    }
    static void finish(RoomAir service, Object registration, BlockPos pos) throws ReflectiveOperationException {
        for (int n = 0; n < 2048; n++) if ((boolean) STEP.invoke(registration, 256)) {
            map(service, "registrations").remove(pos.asLong()); map(service, "jobs").remove(pos.asLong());
            service.setDirty(); return;
        }
        throw new AssertionError("登録が完了しない");
    }
    static CompoundTag past(RoomAir service) throws ReflectiveOperationException {
        var tag = new CompoundTag(); ((RoomPast) field(service, "past")).save(tag); return tag;
    }
    static RoomAir restore(GameTestHelper h, CompoundTag tag) {
        var service = RoomAir.load(h.getLevel(), tag);
        service.outside((l, p, from, to) -> Optional.of(List.of(new RoomAir.Boundary(from, to, AIR))));
        return service;
    }
    private static long[] cells(BlockPos base, int side) {
        var result = new long[side*side*side]; int n = 0;
        for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) for (int z = 0; z < side; z++)
            result[n++] = base.offset(x, y, z).asLong();
        Arrays.sort(result); return result;
    }
    private static CompoundTag history(long[] cells, long start) {
        var models = new ListTag(); var locations = new ListTag(); int[] ids = new int[64];
        for (int n = 0; n < 64; n++) {
            ids[n] = n; var row = new CompoundTag();
            row.putInt("id", n+1000); row.putLong("from", start+n*2); row.putLong("until", start+n*2+1);
            row.putLongArray("cells", cells); row.putInt("walls", 1536); row.putInt("openings", 0);
            row.putDouble("height", 0); row.putDouble("temperature", 293.15); models.add(row);
        }
        for (long cell : cells) { var row = new CompoundTag(); row.putLong("cell", cell); row.putIntArray("models", ids); locations.add(row); }
        var tag = new CompoundTag(); tag.put("past_models", models); tag.put("past_cells", locations); return tag;
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID, batch = "room_publication")
    public static void partialPreparationIsInvisibleAndCancellationKeepsHistory(GameTestHelper h) throws Exception {
        long[] points = cells(h.absolutePos(new BlockPos(1, 1, 1)), 2);
        var past = RoomPast.load(history(points, 0)); var before = new CompoundTag(); past.save(before);
        var candidate = new RoomPast.Model(2000, 200, points, 24, 1, 1);
        past.bind(points[0], candidate);
        var during = new CompoundTag(); past.save(during);
        h.assertTrue(before.equals(during), "整理途中の保存は未公開の候補を含まない");
        h.assertTrue(!past.known(points[0], 200) && !past.known(points[1], 200), "部分的な構成を公開しない");
        var input = new RoomPhysics.Amount(10, .2, .3, -.1);
        RoomPast.Boundaries boundary = (p, a, b) -> Optional.of(List.of(new RoomAir.Boundary(a, b, AIR)));
        var expected = RoomPast.load(before).response(points[0], 40, 41, 200, input, boundary);
        h.assertTrue(expected.equals(past.response(points[0], 40, 41, 200, input, boundary)), "準備中の遅延応答は確定した構成だけを使う");
        var restored = RoomPast.load(during);
        h.assertTrue(expected.equals(restored.response(points[0], 40, 41, 200, input, boundary)), "途中保存の復元も同じ応答");
        past.discard(candidate); var cancelled = new CompoundTag(); past.save(cancelled);
        h.assertTrue(before.equals(cancelled), "取消で元の履歴を変更しない");
        h.succeed();
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID, batch = "room_publication", timeoutTicks = 400)
    public static void publicationAt4096CellsAnd64ModelsStaysWithinBudget(GameTestHelper h) throws Exception {
        long originalTime = h.getLevel().getGameTime();
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        clock.setGameTime(originalTime+200);
        try {
            var points = cells(h.absolutePos(new BlockPos(1, 1, 1)), 16);
            long now = h.getLevel().getGameTime();
            var template = history(points, Math.max(0, now-200));
            var values = new ArrayList<Long>(); var gcFree = new ArrayList<Long>(); var publication = new ArrayList<Long>();
            var collectors = ManagementFactory.getGarbageCollectorMXBeans();
            try (var profile = new RoomSearchProfile("publication")) {
                for (int run = 0; run < 32; run++) {
                    var service = fixture(h);
                    var f = RoomAir.class.getDeclaredField("past"); f.setAccessible(true); f.set(service, RoomPast.load(template));
                    var job = new RoomSearch(BlockPos.of(points[0]), now);
                    for (long point : points) { job.cells.add(point); job.visited.add(point); }
                    job.status = RoomSearch.Status.INDOOR; job.walls = 1536;
                    var registration = CONSTRUCTOR.newInstance(service, job);
                    boolean done = false;
                    for (int work = 0; work < 2048 && !done; work++) {
                        long gc = collectors.stream().mapToLong(c -> Math.max(0, c.getCollectionCount())).sum();
                        boolean[] result = {false};
                        Runnable action = () -> {
                            try { result[0] = (boolean) STEP.invoke(registration, 256); }
                            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                        };
                        long elapsed;
                        if (run >= 8) elapsed = profile.measure(action);
                        else { action.run(); elapsed = 0; }
                        done = result[0];
                        if (run >= 8) {
                            values.add(elapsed);
                            if (gc == collectors.stream().mapToLong(c -> Math.max(0, c.getCollectionCount())).sum()) gcFree.add(elapsed);
                            if (done) publication.add(elapsed);
                        }
                    }
                    h.assertTrue(done, "256操作ずつの登録が完了する");
                }
            }
            values.sort(Long::compare); gcFree.sort(Long::compare); publication.sort(Long::compare);
            double p95 = gcFree.get((int)Math.ceil(gcFree.size()*.95)-1)/1e6;
            var summary = "warmup=8 runs=24 cells=4096 models_per_cell=64 works="+values.size()
                    +" wall_p95_ms="+values.get((int)Math.ceil(values.size()*.95)-1)/1e6+" wall_max_ms="+values.getLast()/1e6
                    +" publish_median_ms="+publication.get(publication.size()/2)/1e6
                    +" publish_p95_ms="+publication.get((int)Math.ceil(publication.size()*.95)-1)/1e6
                    +" gc_free_p95_ms="+p95+" gc_free_max_ms="+gcFree.getLast()/1e6+" publish_max_ms="+publication.getLast()/1e6;
            Files.writeString(Path.of("room-publication-budget.txt"), summary);
            LogUtils.getLogger().info("M3-137 publication budget {}", summary);
            h.assertTrue(publication.get((int)Math.ceil(publication.size()*.95)-1) < 5_000_000, "公開の最終作業p95を5 ms未満へ: "+summary);
            h.succeed();
        } finally { clock.setGameTime(originalTime); }
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID, batch = "room_publication")
    public static void interruptionsDuringPreparationPreservePhysicalState(GameTestHelper h) throws Exception {
        long original = h.getLevel().getGameTime();
        var clock = h.getLevel().getServer().getWorldData().overworldData();
        clock.setGameTime(original+200);
        try {
            for (int offset : new int[]{0, 8}) for (int x = 0; x <= 4; x++)
                for (int y = 0; y <= 2; y++) for (int z = 0; z <= 2; z++)
                    h.setBlock(new BlockPos(x+offset,y,z), x>0 && x<4 && y==1 && z==1 ? Blocks.AIR : Blocks.STONE);
            var origin = h.absolutePos(new BlockPos(2,1,1));
            var other = h.absolutePos(new BlockPos(10,1,1));
            var service = fixture(h);
            finish(service, start(service,h,origin),origin); finish(service, start(service,h,other),other);
            long from = clock.getGameTime();
            var source = UUID.randomUUID();
            var fuel = AshenFrontierCore.id("dry_wood");
            service.receive(new CombustionEvent(h.getLevel(),origin.below(),
                    from,from,fuel,.028/.1263,0,0,source,1));
            clock.setGameTime(from+1);
            for (int n=0;n<66;n++) {
                h.setBlock(new BlockPos(2,1,1),Blocks.STONE);service.changed(origin);
                h.setBlock(new BlockPos(2,1,1),Blocks.AIR);service.changed(origin);
                finish(service,start(service,h,origin),origin);
            }
            h.setBlock(new BlockPos(2,1,1),Blocks.STONE);service.changed(origin);
            h.setBlock(new BlockPos(2,1,1),Blocks.AIR);service.changed(origin);
            var before = past(service);
            var pending = partial(service,h,origin);
            h.assertTrue(before.equals(past(service)), "1セル整理しても確定履歴は同じ");
            var saved = service.save(new CompoundTag(),h.getLevel().registryAccess());
            var reference = restore(h,saved);finish(reference,start(reference,h,origin),origin);
            var delayed = new CombustionEvent(h.getLevel(),origin.below(),
                    from,from+1,fuel,.2*.028/.1263,0,0,source,2);
            reference.receive(delayed);
            service.receive(delayed);
            h.setBlock(new BlockPos(10,1,1),Blocks.STONE);service.changed(other);
            h.setBlock(new BlockPos(10,1,1),Blocks.AIR);service.changed(other);
            finish(service,start(service,h,other),other);
            finish(service,pending,origin);
            var restored = restore(h,saved);
            var cancelled = restore(h,saved);
            restored.receive(delayed);finish(restored,start(restored,h,origin),origin);
            partial(cancelled,h,origin);cancelled.changed(origin);
            cancelled.receive(delayed);finish(cancelled,start(cancelled,h,origin),origin);
            var overlap = restore(h,saved);
            var left = partial(overlap,h,origin);
            var secondOrigin = h.absolutePos(new BlockPos(3,1,1));
            overlap.receive(delayed);finish(overlap,start(overlap,h,secondOrigin),secondOrigin);
            finish(overlap,left,origin);
            var expected = reference.quantity().orElseThrow();
            for (var result : List.of(service,restored,cancelled,overlap)) {
                result=restore(h,result.save(new CompoundTag(),h.getLevel().registryAccess()));
                var value=result.quantity().orElseThrow();
                h.assertTrue(Math.abs(value.coMol()-expected.coMol())<1e-9
                        && Math.abs(value.co2Mol()-expected.co2Mol())<1e-9
                        && Math.abs(value.oxygenMol()-expected.oxygenMol())<1e-9
                        && Math.abs(value.heatJ()-expected.heatJ())<1e-6,
                        "保存復元/別登録/取消/遅延の順でも全量が同じ: "+value+" expected="+expected);
                var sample=result.sample(origin,clock.getGameTime());
                double mol=3*101325/(8.314462618*293.15);
                h.assertTrue(sample.confirmed()&&Math.abs(sample.state().orElseThrow().co()*mol-1.2)<1e-9,
                        "整理途中の割込み後も室CO1.2 mol: "+sample);
            }
            h.succeed();
        } finally { clock.setGameTime(original); }
    }
}
