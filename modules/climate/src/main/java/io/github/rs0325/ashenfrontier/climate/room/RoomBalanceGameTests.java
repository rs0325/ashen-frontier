package io.github.rs0325.ashenfrontier.climate.room;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.github.rs0325.ashenfrontier.climate.AshenFrontierClimate;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.environment.CombustionEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** 固定seedの状態機械検査。入力を独立に数え、毎操作後に実残留量と交換量を照合する。 */
@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RoomBalanceGameTests {
    private static final long SEED = 0x137_2026_1125L;
    private static final int PROGRAMS = 512, STEPS = 24;
    private static final RoomPhysics.Air AIR = new RoomPhysics.Air(293.15, 101325, 0, .00042, .2095, 1.5);
    private static final RoomPhysics.Amount ZERO = new RoomPhysics.Amount(0, 0, 0, 0);
    private static final BlockPos[] CELLS = {new BlockPos(1, 1, 1), new BlockPos(2, 1, 1), new BlockPos(3, 1, 1), new BlockPos(2, 2, 1), new BlockPos(2, 3, 1)};
    private static final BlockPos HELPER = new BlockPos(10, 1, 1), VENT = new BlockPos(4, 1, 1);
    private static final ResourceLocation HEARTH = AshenFrontierCore.id("hearth");
    private enum Kind { INPUT, DELAY, REPLAY, SPLIT, MERGE, OPEN, CLOSE, ROOF_OPEN, ROOF_CLOSE, CHIMNEY_OPEN, CHIMNEY_CLOSE, BLOCK_ALL, RECOVER, TRIM, COMPACT, SAVE, MOVE, CLONE, REMOVE, PLACE, EXCHANGE, CANCEL, PREPARE, OUTDOOR, RETAIN, NOISE, WATCH }
    private record Step(Kind kind, long salt, int ticks) {
        Step(Kind kind, long salt) { this(kind, salt, 1); }
    }
    private record Failure(String kind, String detail, int step) {}
    private static final class Violation extends RuntimeException {
        final String kind;
        Violation(String kind, String detail) { super(detail); this.kind = kind; }
    }
    private static long lastExpanded;
    private static EnumMap<Kind, Integer> lastEffective = new EnumMap<>(Kind.class);
    private static java.util.Map<String, Integer> lastCombos = java.util.Map.of();

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID, batch = "room_balance_generated")
    public static void generatedOperationsPreserveExtensiveBalance(GameTestHelper h) throws Exception {
        long start = System.nanoTime(), expanded = 0;
        var coverage = new EnumMap<Kind, Integer>(Kind.class);
        var effective = new EnumMap<Kind, Integer>(Kind.class);
        var combos = new HashMap<String, Integer>();
        for (int index = 0; index<PROGRAMS; index++) {
            long seed = SEED+index;
            var program = generate(seed, index);
            var failure = run(h, seed, program, coverage);
            if (failure != null) {
                var minimal = shrink(h, seed, program, failure.kind);
                var reduced = run(h, seed, minimal, new EnumMap<>(Kind.class));
                var text = "seed="+seed+" (base=0x13720261125)\nfull="+program+"\nminimal="+minimal+"\nfailure="+reduced+"\n";
                Files.writeString(Path.of("room-balance-failure.txt"), text);
                throw new AssertionError("seed="+seed+" kind="+reduced.kind+" 詳細: room-balance-failure.txt");
            }
            expanded+=lastExpanded;
            lastEffective.forEach((kind, count) -> effective.merge(kind, count, Integer::sum));
            lastCombos.forEach((kind, count) -> combos.merge(kind, count, Integer::sum));
            if (index%16 == 15) LogUtils.getLogger().info("M3-137 generated programs={} seed_base={} expanded={}", index+1, SEED, expanded);
        }
        for (var kind : Kind.values()) if (!coverage.containsKey(kind)) throw new AssertionError("未実行の操作: "+kind);
        for (var key : List.of("recovery_delay", "split_save_merge", "closed_delay"))
            if (combos.getOrDefault(key, 0) < 64) throw new AssertionError("重要な組合せの実効回数不足: "+key+" "+combos);
        for (var key : List.of("prepare_other","prepare_overlap","prepare_save","prepare_cancel","prepare_delay","prepare_resume"))
            if (combos.getOrDefault(key,0)<32) throw new AssertionError("整理途中の割込みの実効回数不足: "+key+" "+combos);
        for (var key : List.of("outdoor_observed","outdoor_covered","outdoor_removed","outdoor_delay","outdoor_save","outdoor_past"))
            if (combos.getOrDefault(key,0)<64) throw new AssertionError("屋外期間の実効回数不足: "+key+" "+combos);
        for(var key:List.of("approximate_indoor","approximate_outdoor"))
            if(combos.getOrDefault(key,0)<16) throw new AssertionError("保持上限を越える実効回数不足: "+combos);
        for(var key:List.of("outdoor_interrupt_progress","outdoor_multi_watch"))
            if(combos.getOrDefault(key,0)<64) throw new AssertionError("屋外の割込み/複数監視の実効回数不足: "+combos);
        for (var kind : Kind.values()) if (effective.getOrDefault(kind, 0) < 16)
            throw new AssertionError("操作の実効回数不足: "+kind+" "+effective);
        var result = "programs="+PROGRAMS+" seed_base="+SEED+" steps=25_to_27"+" expanded="+expanded
                +" elapsed_ms="+(System.nanoTime()-start)/1e6+" attempts="+coverage+" effective="+effective+" combos="+combos;
        Files.writeString(Path.of("room-balance-generated.txt"), result);
        LogUtils.getLogger().info("M3-137 generated {}", result);
        h.succeed();
    }
    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID, batch = "room_balance_generated")
    public static void compactingObservedRoomDoesNotChangeItsState(GameTestHelper h) {
        var program = List.of(new Step(Kind.OPEN, 2418830165076316999L),
                new Step(Kind.INPUT, -5481026752459491148L),
                new Step(Kind.INPUT, -3954404282036742344L),
                new Step(Kind.COMPACT, -478921535402273170L));
        var failure = run(h, 1336274194802L, program, new EnumMap<>(Kind.class));
        if (failure != null) throw new AssertionError(failure);
        var recovered = List.of(new Step(Kind.BLOCK_ALL, 335152230678772530L),
                new Step(Kind.TRIM, 6707231296047009063L),
                new Step(Kind.RECOVER, 5337347021651283721L),
                new Step(Kind.DELAY, -2327815196020042847L),
                new Step(Kind.INPUT, -8220523346916891309L),
                new Step(Kind.INPUT, -757103794934811638L),
                new Step(Kind.COMPACT, 4736816740914282782L),
                new Step(Kind.CHIMNEY_OPEN, -7987326810822236929L));
        failure = run(h, 1336274194841L, recovered, new EnumMap<>(Kind.class));
        if (failure != null) throw new AssertionError(failure);
        var sameTick = List.of(new Step(Kind.INPUT, 6836793109278034732L, 0),
                new Step(Kind.REMOVE, -4644779042193837972L, 0));
        failure = run(h, 1336274194981L, sameTick, new EnumMap<>(Kind.class));
        if (failure != null) throw new AssertionError(failure);
        var replayBoundary = List.of(new Step(Kind.CLONE, -255649985982625321L, 1),
                new Step(Kind.INPUT, -3145745073367356695L, 1), new Step(Kind.MOVE, 8613339634357523389L, 1),
                new Step(Kind.INPUT, 3775929794413215483L, 1), new Step(Kind.REMOVE, 1796190368156628654L, 0),
                new Step(Kind.PLACE, -982711415437721185L, 1), new Step(Kind.CLONE, -1739435174663277587L, 1),
                new Step(Kind.CHIMNEY_OPEN, 187830512239897950L, 0), new Step(Kind.MERGE, 4944710178879640824L, 0),
                new Step(Kind.MOVE, -8740950883266898717L, 1), new Step(Kind.ROOF_OPEN, -6227416012955558322L, 0),
                new Step(Kind.ROOF_OPEN, 6069714379445177458L, 0), new Step(Kind.EXCHANGE, -4993692945550269065L, 1),
                new Step(Kind.DELAY, -6991935371794874027L, 1));
        failure = run(h, 1336274194992L, replayBoundary, new EnumMap<>(Kind.class));
        if (failure != null) throw new AssertionError(failure);
        h.succeed();
    }

    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID, batch = "room_balance_generated")
    public static void compactedHistoricalNotificationsKeepTheirPhysicalBaseline(GameTestHelper h) {
        var program = List.of(new Step(Kind.INPUT, 924223550420884916L, 1),
                new Step(Kind.OPEN, -4154762461691818838L, 1),
                new Step(Kind.EXCHANGE, 5064943563099113259L, 1),
                new Step(Kind.SAVE, -6331917604690846232L, 1),
                new Step(Kind.SPLIT, -4298337509204181970L, 1),
                new Step(Kind.DELAY, 9167919843143438355L, 1),
                new Step(Kind.REMOVE, -5096905465868770856L, 1),
                new Step(Kind.COMPACT, 3941095261059479124L, 1),
                new Step(Kind.RECOVER, 8622721406886787846L, 1),
                new Step(Kind.MERGE, -6462599583896684131L, 1),
                new Step(Kind.CLONE, 4396046502965980790L, 1),
                new Step(Kind.CLONE, 4034848807878140129L, 1),
                new Step(Kind.DELAY, 5928699166162511830L, 1));
        var failure = run(h, 1336274194762L, program, new EnumMap<>(Kind.class));
        if (failure != null) throw new AssertionError(failure);
        var zeroTimeMix = List.of(new Step(Kind.DELAY, -3472215532588803524L, 0),
                new Step(Kind.ROOF_OPEN, 7704296934722568503L, 1),
                new Step(Kind.RECOVER, -6972132894591752673L, 1), new Step(Kind.SPLIT, 1224392008551794322L, 0));
        failure = run(h, 1336274195011L, zeroTimeMix, new EnumMap<>(Kind.class));
        if (failure != null) throw new AssertionError(failure);
        var recoveredOpening = List.of(new Step(Kind.CHIMNEY_OPEN, 4277653085817536531L, 1),
                new Step(Kind.RECOVER, -5053586458487863419L, 1),
                new Step(Kind.ROOF_OPEN, 2031585095797317542L, 0),
                new Step(Kind.DELAY, -6644426319816401720L, 0));
        failure = run(h, 1336274195047L, recoveredOpening, new EnumMap<>(Kind.class));
        if (failure != null) throw new AssertionError(failure);
        var preparedSave = List.of(new Step(Kind.PREPARE,1336274194733L,0),
                new Step(Kind.DELAY,-9007156365797604424L,1));
        failure = run(h,1336274194733L,preparedSave,new EnumMap<>(Kind.class));
        if (failure != null) throw new AssertionError(failure);
        h.succeed();
    }

    private record Delivery(RoomPhysics.Amount amount, RoomPhysics.Amount exchange, List<RoomPhysics.Amount> rooms) {}
    @GameTest(template = "room", templateNamespace = AshenFrontierClimate.MOD_ID, batch = "room_delivery_orders")
    public static void delayedSetsAreIndependentOfDeliveryOrder(GameTestHelper h) throws Exception {
        int programs = 0;
        for (int index = 0; index < 32; index++) {
            long seed = SEED + 10000 + index;
            Delivery reference = null;
            for (int variant = 0; variant < 6; variant++) {
                try (var state = new State(h, seed)) {
                    long start = state.originalTime;
                    var position = state.absolute(CELLS[0]).below();
                    if ((index & 1) != 0) { state.apply(new Step(Kind.OPEN, seed, 0)); state.observe(); state.check("delivery original opening"); }
                    state.post(new CombustionEvent(h.getLevel(), position, start, start,
                            AshenFrontierCore.id("dry_wood"), .028/.1263, 0, 0, new UUID(seed, 90000), 1));
                    state.advance(1);
                    state.apply(new Step(Kind.BLOCK_ALL, seed, 0)); state.observe(); state.check("delivery block");
                    state.apply(new Step(Kind.TRIM, seed, 0)); state.observe(); state.check("delivery trim");
                    state.advance(20);
                    var random = new Random(seed);
                    var notices = new ArrayList<CombustionEvent>();
                    double[] sizes = {.1 + random.nextDouble()*.1, .1, .05};
                    for (int n = 0; n < sizes.length; n++) {
                        long from = n == 1 ? start+2 : start, to = n == 2 ? from : from+1;
                        double heat = n == 1 ? 1+random.nextInt(10) : 0;
                        notices.add(new CombustionEvent(h.getLevel(), position, from, to, AshenFrontierCore.id("dry_wood"),
                                sizes[n]*.028/.1263, 0, -heat, new UUID(seed, 90001+n), 1));
                    }
                    java.util.Collections.shuffle(notices, new Random(seed + variant));
                    if (variant == 0) for (var event : notices) state.post(event);
                    if (variant == 5) state.post(notices.getFirst());
                    state.apply(new Step(Kind.RECOVER, seed, 0)); state.observe(); state.check("delivery recovered");
                    if ((index & 2) != 0) { state.apply(new Step(Kind.SPLIT, seed, 0)); state.observe(); state.check("delivery split after recovery"); }
                    if (variant == 1) for (var event : notices) state.post(event);
                    if (variant == 3) state.post(notices.getFirst());
                    if (variant == 4) { state.apply(new Step(Kind.SAVE, seed, 0)); state.observe(); state.check("delivery saved"); }
                    state.apply(new Step(Kind.OPEN, seed, 0)); state.observe(); state.check("delivery opened");
                    if (variant >= 2) {
                        int first = variant == 3 || variant == 5 ? 1 : 0;
                        for (int n = first; n < notices.size(); n++) {
                            state.post(notices.get(n));
                            if (variant >= 4) { state.apply(new Step(Kind.SAVE, seed, 0)); state.observe(); }
                            state.check("delivery notice");
                        }
                    }
                    state.apply(new Step(Kind.SAVE, seed, 0)); state.observe();
                    state.check("delivery restored");
                    state.advance(5);
                    var actual = state.service.balance().orElseThrow();
                    var rooms = new ArrayList<RoomPhysics.Amount>();
                    for (var shape : state.oracle.geometry()) {
                        var sample = state.service.sample(BlockPos.of(shape.cells().getFirst()), h.getLevel().getGameTime()).state().orElseThrow();
                        double mol = shape.cells().size()*101325/(8.314462618*293.15);
                        double capacity = mol*.02897*1005+shape.walls()*20000;
                        rooms.add(new RoomPhysics.Amount((sample.temperatureK()-293.15)*capacity,
                                sample.co()*mol, sample.co2()*mol, sample.oxygen()*mol));
                    }
                    var current = new Delivery(actual.retained(), actual.exchange(), rooms);
                    if (reference == null) reference = current;
                    else {
                        equal("DELIVERY_AMOUNT", reference.amount, current.amount, "seed="+seed+" variant="+variant);
                        equal("DELIVERY_EXCHANGE", reference.exchange, current.exchange, "seed="+seed+" variant="+variant);
                        if (reference.rooms.size() != current.rooms.size()) throw new AssertionError("到着順で室数が変わる");
                        for (int n = 0; n < rooms.size(); n++)
                            equal("DELIVERY_ROOM", reference.rooms.get(n), rooms.get(n), "seed="+seed+" variant="+variant+" room="+n);
                    }
                    programs++;
                }
            }
        }
        var result = "programs="+programs+" seed_base="+(SEED+10000)+" sets=32 permutations=6";
        Files.writeString(Path.of("room-delivery-generated.txt"), result);
        LogUtils.getLogger().info("M3-137 delivery {}", result);
        h.succeed();
    }

    /** 前半は各操作前に1tick、後半は同tickも含める。時刻条件も縮小対象の操作へ固定する。 */
    private static List<Step> generate(long seed, int index) {
        var random = new Random(seed);
        Kind[][] prefixes = {
                {Kind.INPUT, Kind.BLOCK_ALL, Kind.TRIM, Kind.RECOVER, Kind.DELAY},
                {Kind.INPUT, Kind.OPEN, Kind.EXCHANGE, Kind.SAVE, Kind.SPLIT, Kind.DELAY},
                {Kind.INPUT, Kind.SPLIT, Kind.INPUT, Kind.SAVE, Kind.MERGE, Kind.COMPACT, Kind.DELAY},
                {Kind.CLONE, Kind.INPUT, Kind.MOVE, Kind.INPUT, Kind.REMOVE, Kind.PLACE, Kind.REPLAY}};
        var result = new ArrayList<Step>();
        for (var kind : prefixes[index%prefixes.length]) {
            long salt = random.nextLong();
            result.add(new Step(kind, salt, index < 256 ? 1 : (int) (salt & 1)));
        }
        while (result.size()<STEPS) {
            var kind = Kind.values()[random.nextInt(Kind.values().length-6)];
            if (kind == Kind.TRIM || kind == Kind.COMPACT && result.stream().anyMatch(s->s.kind == Kind.COMPACT)) continue;
            long salt = random.nextLong();
            result.add(new Step(kind, salt, index < 256 ? 1 : (int) (salt & 1)));
        }
        result.add(index % (STEPS+1), new Step(Kind.CANCEL, seed, 0));
        if (index % 4 == 0) result.add(1, new Step(Kind.PREPARE, seed, 0));
        if (index % 4 == 1) result.add(1, new Step(Kind.OUTDOOR, seed, 0));
        if (index % 16 == 1) result.add(2, new Step(Kind.RETAIN, seed, 0));
        if (index % 8 == 2) result.add(1,new Step(Kind.NOISE,seed,0));
        if (index % 8 == 3) result.add(2,new Step(Kind.WATCH,seed,0));
        return List.copyOf(result);
    }
    private static Failure run(GameTestHelper h, long seed, List<Step> program, EnumMap<Kind, Integer> coverage) {
        int step = -1;
        try (var state = new State(h, seed)) {
            for (int n = 0;n<program.size();n++) {
                step = n; state.advance(program.get(n).ticks);
                var before = state.service.balance().orElseThrow();
                state.apply(program.get(n));
                coverage.merge(program.get(n).kind, 1, Integer::sum);
                state.observe(); state.check("step="+n+" "+program.get(n));
                var after = state.service.balance().orElseThrow();
                var kind = program.get(n).kind;
                if (kind != Kind.INPUT && kind != Kind.DELAY && kind != Kind.EXCHANGE && kind != Kind.PREPARE && kind != Kind.OUTDOOR && kind != Kind.RETAIN) {
                    equal("ZERO_TIME_STATE", before.retained(), after.retained(),"時間0: "+program.get(n));
                    equal("ZERO_TIME_EXCHANGE", before.exchange(), after.exchange(),"時間0: "+program.get(n));
                }
            }
            lastExpanded = state.expanded; lastEffective = state.effective; lastCombos = state.combos; return null;
        } catch (Violation error) { return new Failure(error.kind, error.getMessage(), step); }
    }
    /** 最後は1操作を削除しても同じ種類の失敗が残らない列まで縮める。 */
    private static List<Step> shrink(GameTestHelper h, long seed, List<Step> original, String kind) {
        var current = new ArrayList<>(original); int partitions = 2;
        while (!current.isEmpty()) {
            int size = Math.max(1, (current.size()+partitions-1)/partitions); boolean reduced = false;
            for (int from = 0;from<current.size();from+=size) {
                var candidate = new ArrayList<>(current);
                candidate.subList(from, Math.min(from+size, candidate.size())).clear();
                var failure = run(h, seed, candidate, new EnumMap<>(Kind.class));
                if (failure != null && failure.kind.equals(kind)) {
                    current = candidate; partitions = Math.max(2, partitions-1); reduced = true; break;
                }
            }
            if (reduced) continue;
            if (partitions >= current.size()) break;
            partitions = Math.min(current.size(), partitions*2);
        }
        return List.copyOf(current);
    }

    private static final class State implements AutoCloseable {
        final GameTestHelper h;
        final long originalTime, seed;
        final RoomAir original;
        final FakePlayer player;
        RoomAir service;
        RoomOracle oracle;
        final BlockPos probe;
        final EnumMap<Kind, Integer> effective = new EnumMap<>(Kind.class);
        final HashMap<String, Integer> combos = new HashMap<>();
        final List<long[]> closedSpans = new ArrayList<>();
        long closedSince, lastRecovery = -1;
        long outdoorObserved = -1;
        int lateSequence, replayChecks;
        boolean splitSeen, splitSaved;
        RoomPhysics.Amount inputs = ZERO, initial;
        final List<CombustionEvent> sent = new ArrayList<>();
        final HashMap<UUID, Long> receipts = new HashMap<>();
        final HashMap<BlockPos, List<Long>> observed = new HashMap<>();
        int compactSequence;
        long expanded;
        boolean split, roof, chimney, open, blocked;
        State(GameTestHelper h, long seed) {
            this.h = h; this.seed = seed;
            probe = h.absolutePos(new BlockPos(6,1,1)).atY(h.getLevel().getMaxBuildHeight()-4);
            originalTime = h.getLevel().getGameTime(); original = RoomAir.get(h.getLevel());
            player = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(),"room-balance"));
            var tag = new CompoundTag(); tag.putInt("format", 10); tag.put("retention",RoomRetentionCodec.save(new RoomRetention()));
            service = RoomAir.load(h.getLevel(), tag); h.getLevel().getDataStorage().set(RoomAir.ID, service); bind();
            StructureUtils.removeBarriers(h.getBounds(), h.getLevel());
            for (int x = 0;x <= 4;x++) for (int y = 0;y <= 4;y++) for (int z = 0;z <= 2;z++) h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
            for (int x = 8;x <= 12;x++) for (int y = 0;y <= 2;y++) for (int z = 0;z <= 2;z++)
                h.setBlock(new BlockPos(x, y, z), y == 1 && z == 1 && x>8 && x<12 ? Blocks.AIR : Blocks.STONE);
            for (int y = 2;y <= 4;y++) h.setBlock(new BlockPos(4, y, 1), Blocks.AIR);
            h.setBlock(new BlockPos(9, 2, 0), Blocks.AIR);
            shape();
            var standing = absolute(CELLS[0]);
            player.setPos(standing.getX()+.5, standing.getY()+1, standing.getZ()+.5);
            player.getInventory().clearContent(); h.getLevel().addNewPlayer(player);
            place(0, new ItemStack(BuiltInRegistries.ITEM.get(HEARTH)), false);
            for (var direction : Direction.values()) h.getLevel().setBlockAndUpdate(probe.relative(direction),Blocks.AIR.defaultBlockState());
            h.getLevel().setBlockAndUpdate(probe,Blocks.AIR.defaultBlockState());
            var roots = new ArrayList<BlockPos>();
            for (var cell : CELLS) roots.add(absolute(cell)); roots.add(absolute(HELPER)); roots.add(probe);
            oracle = new RoomOracle(h, roots, originalTime); observe();
            var balance = service.balance().orElseThrow();
            double mol = 8*101325/(8.314462618*293.15);
            equal("INITIAL", new RoomPhysics.Amount(0, 0, mol*.00042, mol*.2095), balance.retained(),"既知の8セル");
            initial = plus(balance.retained(), balance.exchange());
            equal("INITIAL", ZERO, initial,"初期の背景気体は外気からの流入");
        }
        void bind() {
            service.outside((level, pos, from, to)->{
                var rows = new ArrayList<RoomAir.Boundary>();
                if (from == to) rows.add(new RoomAir.Boundary(from, to, AIR));
                for (long tick = from;tick<to;tick++) rows.add(new RoomAir.Boundary(tick, tick+1, AIR));
                return java.util.Optional.of(rows);
            });
        }
        BlockPos absolute(BlockPos relative) { return h.absolutePos(relative); }
        BlockPos floor(int slot) { return absolute(CELLS[slot == 0?0 : 2].below()); }
        RoomAir.Sample discover(BlockPos pos) {
            var sample = service.sample(pos, h.getLevel().getGameTime());
            for (int n = 0;n<256 && sample.status() == RoomAir.Status.SCANNING;n++) {
                service.work(); sample = service.sample(pos, h.getLevel().getGameTime());
            }
            return sample;
        }
        void observe() {
            for (var cell : CELLS) if (h.getBlockState(cell).isAir()) {
                var pos = absolute(cell);
                if (discover(pos).status() == RoomAir.Status.INDOOR)
                    observed.computeIfAbsent(pos, p->new ArrayList<>()).add(h.getLevel().getGameTime());
            }
            discover(absolute(HELPER));
            oracle.record(h.getLevel().getGameTime());
        }
        void shape() {
            for (int n = 0;n<CELLS.length;n++) h.setBlock(CELLS[n], blocked || split && n == 1 ? Blocks.STONE : Blocks.AIR);
            for (int y = 2;y <= 4;y++) h.setBlock(new BlockPos(1, y, 1), roof?Blocks.AIR : Blocks.STONE);
            h.setBlock(new BlockPos(2, 4, 1), chimney?Blocks.AIR : Blocks.STONE);
            h.setBlock(VENT, open?Blocks.AIR : Blocks.STONE);
        }
        void apply(Step step) {
            var random = new Random(step.salt);
            var geometry = oracle.geometry(); var devices = devices();
            int notices = oracle.inputs.size(), replayed = replayChecks;
            int oldRegion = service.save(new CompoundTag(), h.getLevel().registryAccess()).getInt("next_region");
            boolean wasBlocked = blocked;
            boolean compacted = false;
            long timeBefore = h.getLevel().getGameTime();
            switch (step.kind) {
                case INPUT -> emit(random, false);
                case DELAY -> emit(random, true);
                case REPLAY -> { if (!sent.isEmpty()) { post(sent.get(random.nextInt(sent.size()))); replayChecks++; } }
                case SPLIT -> { split = true; shape(); }
                case MERGE -> { split = false; shape(); }
                case OPEN -> { open = true; shape(); }
                case CLOSE -> { open = false; shape(); }
                case ROOF_OPEN -> { roof = true; shape(); }
                case ROOF_CLOSE -> { roof = false; shape(); }
                case CHIMNEY_OPEN -> { chimney = true; shape(); }
                case CHIMNEY_CLOSE -> { chimney = false; shape(); }
                case BLOCK_ALL -> {
                    if (!blocked) closedSince = h.getLevel().getGameTime();
                    blocked = true; shape();
                }
                case RECOVER -> {
                    if (blocked) closedSpans.add(new long[]{closedSince, h.getLevel().getGameTime()});
                    blocked = split = roof = chimney = open = false; shape();

                }
                case TRIM -> {
                    for (int n = 0;n<72;n++) {
                        h.setBlock(new BlockPos(9, 1, 0), n%2 == 0?Blocks.AIR : Blocks.STONE);
                        discover(absolute(HELPER)); check("trim="+n);
                    }
                }
                case COMPACT -> {
                    var id = new UUID(seed, ++compactSequence); long now = h.getLevel().getGameTime();
                    int accepted = 0;
                    for (int n = 1; n <= 1026; n++)
                        if (service.respiration(id, n, absolute(HELPER), now, now, 0, 0, 0) == SourceReceipts.Result.ACCEPTED) accepted++;
                    compacted = accepted == 1026;
                    var here = absolute(CELLS[0]);
                    if (discover(here).status() == RoomAir.Status.INDOOR)
                        service.respiration(new UUID(seed, -compactSequence), 1, here, now, now, 0, 0, 0);
                }
                case SAVE -> {
                    service = RoomAir.load(h.getLevel(), service.save(new CompoundTag(), h.getLevel().registryAccess()));
                    h.getLevel().getDataStorage().set(RoomAir.ID, service); bind();
                }
                case PLACE -> { if (actor(0) == null) place(0, new ItemStack(BuiltInRegistries.ITEM.get(HEARTH)), false); }
                case REMOVE -> { remove(0); remove(1); }
                case MOVE -> {
                    int source = actor(0) != null?0 : 1; var stack = remove(source);
                    if (!stack.isEmpty()) { remove(1-source); place(1-source, stack, false); }
                }
                case CLONE -> {
                    int source = actor(0) != null?0 : 1; var entity = actor(source);
                    if (entity == null) { place(0, new ItemStack(BuiltInRegistries.ITEM.get(HEARTH)), false); source = 0; entity = actor(0); }
                    var stack = new ItemStack(BuiltInRegistries.ITEM.get(HEARTH));
                    entity.saveToItem(stack, h.getLevel().registryAccess());
                    remove(1-source); place(1-source, stack, true);
                    var id = entity.saveWithoutMetadata(h.getLevel().registryAccess()).getUUID("source_id");
                    var copied = actor(1-source).saveWithoutMetadata(h.getLevel().registryAccess()).getUUID("source_id");
                    if (id.equals(copied)) throw new Violation("IDENTITY","有効な複製のIDが衝突");
                }
                case RETAIN -> {
                    boolean indoorFirst=(step.salt>>>4&1)==0;
                    var target=indoorFirst ? absolute(HELPER) : probe;
                    long old=indoorFirst ? originalTime : outdoorObserved;
                    if(old<0) return;
                    for(int n=0;n<65;n++) {
                        advance(1);
                        h.getLevel().setBlockAndUpdate(target.above(),Blocks.AIR.defaultBlockState());service.changed(target.above());
                        oracle.record(h.getLevel().getGameTime());
                        if(service.sample(target,h.getLevel().getGameTime()).status()!=RoomAir.Status.OUTDOOR)
                            throw new Violation("PRECISION","屋外への切替が成立しない");
                        check("retain outdoor="+n);
                        advance(1);
                        h.getLevel().setBlockAndUpdate(target.above(),Blocks.STONE.defaultBlockState());service.changed(target.above());
                        oracle.record(h.getLevel().getGameTime());
                        if(discover(target).status()!=RoomAir.Status.INDOOR) throw new Violation("PRECISION","覆いの復旧が成立しない");
                        check("retain indoor="+n);
                    }
                    var before=service.sample(target,old);
                    var expected=indoorFirst ? RoomAir.Status.INDOOR : RoomAir.Status.OUTDOOR;
                    if(before.status()!=expected || before.accuracy()!=RoomAir.Accuracy.APPROXIMATE || !before.confirmed())
                        throw new Violation("PRECISION","古い代表構成の印がない: "+before);
                    service=RoomAir.load(h.getLevel(),service.save(new CompoundTag(),h.getLevel().registryAccess()));
                    h.getLevel().getDataStorage().set(RoomAir.ID,service);bind();
                    var after=service.sample(target,old);
                    if(after.status()!=expected || after.accuracy()!=RoomAir.Accuracy.APPROXIMATE || !after.confirmed()
                            ||service.sample(target,h.getLevel().getGameTime()).accuracy()!=RoomAir.Accuracy.OBSERVED)
                        throw new Violation("PRECISION","保存復元または現在の精度が違う: "+after);
                    var saved=service.save(new CompoundTag(),h.getLevel().registryAccess());
                    for(var item:saved.getList("past_cells",10))
                        if(((CompoundTag)item).getIntArray("models").length>64) throw new Violation("PRECISION","構成数の上限超過");
                    combos.merge(indoorFirst ? "approximate_indoor" : "approximate_outdoor",1,Integer::sum);
                }
                case NOISE -> {
                    var origin=absolute(HELPER);
                    h.setBlock(HELPER,Blocks.STONE);service.changed(origin);
                    h.setBlock(HELPER,Blocks.AIR);service.changed(origin);
                    try {
                        var pending=RoomPublicationGameTests.partial(service,h,origin);
                        int[] count={0};
                        int works=RoomPublicationGameTests.finish(service,pending,origin,1,()->{
                            int n=count[0]++;
                            var outdoor=probe.offset(n%16,2,8+n/16);
                            h.getLevel().getChunkAt(outdoor);
                            h.getLevel().setBlockAndUpdate(outdoor,Blocks.AIR.defaultBlockState());
                            if(service.sample(outdoor,h.getLevel().getGameTime()).status()!=RoomAir.Status.OUTDOOR)
                                throw new Violation("OUTDOOR_PROGRESS","割込みの屋外観測が成立しない");
                        });
                        if(works>128 || count[0]!=works || service.sample(origin,h.getLevel().getGameTime()).status()!=RoomAir.Status.INDOOR)
                            throw new Violation("OUTDOOR_PROGRESS","毎更新の割込み中に登録が完了しない: "+works);
                        combos.merge("outdoor_interrupt_progress",1,Integer::sum);
                    } catch(ReflectiveOperationException error) {throw new IllegalStateException(error);}
                }
                case WATCH -> {
                    RoomOutdoorGameTests.assertWatchedPassages(h,service,absolute(new BlockPos(6,1,6)),false);
                    combos.merge("outdoor_multi_watch",1,Integer::sum);
                }
                case OUTDOOR -> {
                    for (var direction : Direction.values()) {
                        var pos=probe.relative(direction);
                        h.getLevel().setBlockAndUpdate(pos,(direction==Direction.UP?Blocks.AIR:Blocks.STONE).defaultBlockState());service.changed(pos);
                    }
                    long outsideTick=h.getLevel().getGameTime(); outdoorObserved=outsideTick;
                    if (service.sample(probe,outsideTick).status()!=RoomAir.Status.OUTDOOR) throw new Violation("OUTDOOR_HISTORY","露天の観測が成立しない");
                    combos.merge("outdoor_observed",1,Integer::sum);
                    oracle.record(outsideTick);advance(1);
                    if (service.sample(probe,outsideTick).status()!=RoomAir.Status.OUTDOOR) throw new Violation("OUTDOOR_HISTORY","屋外の過去が欠落");
                    long coveredTick=h.getLevel().getGameTime();
                    h.getLevel().setBlockAndUpdate(probe.above(),Blocks.STONE.defaultBlockState());service.changed(probe.above());
                    oracle.record(coveredTick);
                    if (discover(probe).status()!=RoomAir.Status.INDOOR) throw new Violation("OUTDOOR_HISTORY","覆いが成立しない");
                    combos.merge("outdoor_covered",1,Integer::sum);
                    post(new CombustionEvent(h.getLevel(),probe.below(),coveredTick,coveredTick,AshenFrontierCore.id("dry_wood"),
                            .0001*.028/.1263,0,0,new UUID(seed,999997),1));
                    advance(1);
                    long again=h.getLevel().getGameTime();
                    h.getLevel().setBlockAndUpdate(probe.above(),Blocks.AIR.defaultBlockState());service.changed(probe.above());
                    oracle.record(again);
                    if (service.sample(probe,again).status()!=RoomAir.Status.OUTDOOR) throw new Violation("OUTDOOR_HISTORY","屋外の復旧が成立しない");
                    combos.merge("outdoor_removed",1,Integer::sum);
                    post(new CombustionEvent(h.getLevel(),probe.below(),coveredTick,coveredTick+1,AshenFrontierCore.id("dry_wood"),
                            .0002*.028/.1263,0,0,new UUID(seed,999996),1));
                    combos.merge("outdoor_delay",1,Integer::sum);
                    service=RoomAir.load(h.getLevel(),service.save(new CompoundTag(),h.getLevel().registryAccess()));
                    h.getLevel().getDataStorage().set(RoomAir.ID,service);bind();combos.merge("outdoor_save",1,Integer::sum);
                    advance(1);
                    if (service.sample(probe,outsideTick).status()!=RoomAir.Status.OUTDOOR
                            ||service.sample(probe,coveredTick).status()!=RoomAir.Status.INDOOR
                            ||service.sample(probe,again).status()!=RoomAir.Status.OUTDOOR
                            ||service.sample(probe.offset(4,0,0),outsideTick).status()!=RoomAir.Status.HISTORY_MISSING)
                        throw new Violation("OUTDOOR_HISTORY","独立した変更時刻の履歴/未観測の区別が違う");
                    combos.merge("outdoor_past",1,Integer::sum);
                }
                case PREPARE -> {
                    var origin = absolute(HELPER);
                    try {
                        for (int n=0;n<66;n++) {
                            var vent = new BlockPos(9,1,0);
                            h.setBlock(vent,n%2==0 ? Blocks.AIR : Blocks.STONE); service.changed(absolute(vent)); discover(origin);
                        }
                        h.setBlock(HELPER,Blocks.STONE);service.changed(origin);
                        h.setBlock(HELPER,Blocks.AIR);service.changed(origin);
                        var before = RoomPublicationGameTests.past(service);
                        var registration = RoomPublicationGameTests.partial(service,h,origin);
                        if (!before.equals(RoomPublicationGameTests.past(service))) throw new Violation("PREPARE_PAST","整理途中の履歴が変わった");
                        int mode = (int)(step.salt >>> 2 & 3);
                        var event = new CombustionEvent(h.getLevel(),origin.below(),originalTime,Math.min(originalTime+1,h.getLevel().getGameTime()),
                                AshenFrontierCore.id("dry_wood"),.001*.028/.1263,0,0,new UUID(seed,999999),1);
                        if (mode<2) post(event);
                        var otherCell = (step.salt>>>4 & 1)==0 ? CELLS[0] : HELPER.offset(1,0,0);
                        if (otherCell.equals(CELLS[0])) {
                            h.setBlock(otherCell,Blocks.STONE);service.changed(absolute(otherCell));
                            h.setBlock(otherCell,Blocks.AIR);service.changed(absolute(otherCell));
                        }
                        var other = RoomPublicationGameTests.start(service,h,absolute(otherCell));
                        RoomPublicationGameTests.finish(service,other,absolute(otherCell));
                        combos.merge(otherCell.equals(CELLS[0]) ? "prepare_other" : "prepare_overlap",1,Integer::sum);
                        if (mode>=2) post(event);
                        if (mode==1 || mode==3) {
                            service.changed(origin);combos.merge("prepare_cancel",1,Integer::sum);
                        }
                        if (mode==0 || mode==3) {
                            service=RoomAir.load(h.getLevel(),service.save(new CompoundTag(),h.getLevel().registryAccess()));
                            h.getLevel().getDataStorage().set(RoomAir.ID,service);bind();
                            combos.merge("prepare_save",1,Integer::sum);
                        }
                        if (mode==2) {
                            RoomPublicationGameTests.finish(service,registration,origin);
                            combos.merge("prepare_resume",1,Integer::sum);
                        }
                        discover(origin);
                        combos.merge("prepare_delay",1,Integer::sum);
                    } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                }
                case CANCEL -> {
                    var origin = absolute(HELPER);
                    h.setBlock(HELPER, Blocks.STONE); service.changed(origin);
                    h.setBlock(HELPER, Blocks.AIR); service.changed(origin);
                    try {
                        var field = RoomAir.class.getDeclaredField("past"); field.setAccessible(true);
                        var past = (RoomPast) field.get(service);
                        var before = new CompoundTag(); past.save(before);
                        for (int n = 0; n < 65; n++) RoomGameTests.cancelUnpublished(service, origin, h.getLevel().getGameTime());
                        var after = new CompoundTag(); past.save(after);
                        if (!before.equals(after)) throw new Violation("CANCEL_PAST", "取消で確定した構成が変わった");
                    } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                }
                case EXCHANGE -> advance(1+random.nextInt(4));
            }
            var afterGeometry = oracle.geometry();
            int afterRegion = service.save(new CompoundTag(), h.getLevel().registryAccess()).getInt("next_region");
            boolean changed = switch (step.kind) {
                case INPUT, DELAY -> oracle.inputs.size() > notices;
                case REPLAY -> replayChecks > replayed;
                case SPLIT -> mainRooms(afterGeometry) > mainRooms(geometry);
                case MERGE -> mainRooms(afterGeometry) < mainRooms(geometry);
                case BLOCK_ALL -> !wasBlocked;
                case RECOVER -> (wasBlocked || !geometry.equals(afterGeometry)) && mainRooms(afterGeometry) > 0;
                case TRIM -> afterRegion > oldRegion;
                case COMPACT -> compacted;
                case SAVE, CANCEL, PREPARE, OUTDOOR, RETAIN, NOISE, WATCH -> true;
                case EXCHANGE -> h.getLevel().getGameTime() > timeBefore;
                case MOVE, CLONE, REMOVE, PLACE -> !devices.equals(devices());
                default -> !geometry.equals(afterGeometry);
            };
            if (changed) effective.merge(step.kind, 1, Integer::sum);
            if (step.kind == Kind.RECOVER && changed) lastRecovery = h.getLevel().getGameTime();
            if (step.kind == Kind.SPLIT && changed) { splitSeen = true; splitSaved = false; }
            if (step.kind == Kind.SAVE && splitSeen) splitSaved = true;
            if (step.kind == Kind.MERGE && changed && splitSaved) {
                combos.merge("split_save_merge", 1, Integer::sum); splitSeen = splitSaved = false;
            }
            oracle.record(h.getLevel().getGameTime());
        }
        BlockEntity actor(int slot) {
            var entity = h.getLevel().getBlockEntity(floor(slot));
            return entity != null && entity.getClass().getSimpleName().equals("ThermalStationEntity")?entity : null;
        }
        long sequence(BlockEntity entity) {
            try {
                var method = entity.getClass().getDeclaredMethod("nextSequence"); method.setAccessible(true);
                return (long)method.invoke(entity);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("実設備の通知番号を取得できません", error); }
        }
        String devices() {
            String result = "";
            for (int slot = 0; slot < 2; slot++) {
                var device = actor(slot);
                result += device == null ? "/empty" : "/" + device.saveWithoutMetadata(h.getLevel().registryAccess()).getUUID("source_id");
            }
            return result;
        }
        int mainRooms(List<RoomOracle.Shape> rooms) {
            int count = 0;
            for (var room : rooms) if (Arrays.stream(CELLS).map(this::absolute).anyMatch(p -> room.cells().contains(p.asLong()))) count++;
            return count;
        }
        boolean closedAt(long tick) {
            if (blocked && tick >= closedSince) return true;
            return closedSpans.stream().anyMatch(span -> span[0] <= tick && tick < span[1]);
        }
        void emit(Random random, boolean delayed) {
            int slot = actor(0) != null && discover(floor(0).above()).status() == RoomAir.Status.INDOOR ? 0 : 1;
            var entity = actor(slot);
            if (!delayed && (entity == null || discover(floor(slot).above()).status() != RoomAir.Status.INDOOR)) return;
            long now = h.getLevel().getGameTime(), from = now, to = now;
            BlockPos position = entity == null ? absolute(CELLS[0]).below() : floor(slot);
            UUID id; long number;
            if (delayed) {
                id = new UUID(~seed, ++lateSequence); number = 1;
                if (blocked || !closedSpans.isEmpty()) {
                    var span = blocked ? new long[]{closedSince, now} : closedSpans.get(random.nextInt(closedSpans.size()));
                    from = span[0] + (span[1] > span[0] ? random.nextInt((int) (span[1] - span[0])) : 0);
                    to = random.nextBoolean() ? from : Math.min(span[1], from + 1);
                    position = absolute(CELLS[0]).below();
                } else {
                    var times = observed.getOrDefault(position.above(), List.of(originalTime));
                    from = times.get(random.nextInt(times.size()));
                    to = random.nextBoolean() ? from : Math.min(now, from + 1);
                }
            } else {
                number = sequence(entity);
                id = entity.saveWithoutMetadata(h.getLevel().registryAccess()).getUUID("source_id");
            }
            double kg = (1+random.nextInt(5))*.00001*.028/.1263, heat = 1+random.nextInt(10);
            var event = new CombustionEvent(h.getLevel(), position, from, to, AshenFrontierCore.id("dry_wood"),
                    kg, kg*16000000, kg*16000000-heat, id, number);
            post(event); sent.add(event);
        }
        void post(CombustionEvent event) {
            NeoForge.EVENT_BUS.post(event); long last = receipts.getOrDefault(event.sourceId(), 0L);
            if (event.sequence()>last) {
                receipts.put(event.sourceId(), event.sequence());
                double co = event.burnedKg()*.1263/.028, co2 = event.burnedKg()*.5/.012-co;
                double oxygen = co2+co/2+event.burnedKg()*(.06/.001/4-.44/.016/2);
                var amount = new RoomPhysics.Amount(event.energyJoules()-event.retainedHeatJoules(), co, co2, -oxygen);
                inputs = plus(inputs, amount);
                oracle.input(event.startTick(), event.endTick(), event.position().above().asLong(), amount, h.getLevel().getGameTime());
                if (event.startTick() < h.getLevel().getGameTime() && closedAt(event.startTick()))
                    combos.merge("closed_delay", 1, Integer::sum);
                if (!blocked && lastRecovery >= 0 && event.startTick() < lastRecovery)
                    combos.merge("recovery_delay", 1, Integer::sum);
            }
        }
        void place(int slot, ItemStack stack, boolean creative) {
            var pos = floor(slot);
            h.getLevel().setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            h.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            player.gameMode.changeGameModeForPlayer(creative?GameType.CREATIVE : GameType.SURVIVAL);
            player.setPos(pos.getX()+.5, pos.getY()+1, pos.getZ()+.5); player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            player.gameMode.useItemOn(player, h.getLevel(), stack, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos.below()).add(0, .5, 0), Direction.UP, pos.below(), false));
            if (actor(slot) == null) throw new Violation("DEVICE","設備を配置できない: "+slot);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        ItemStack remove(int slot) {
            if (actor(slot) == null) return ItemStack.EMPTY;
            var pos = floor(slot); player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (!player.gameMode.destroyBlock(pos)) throw new Violation("DEVICE","設備を撤去できない");
            var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1));
            var stack = drops.stream().filter(e->e.getItem().is(BuiltInRegistries.ITEM.get(HEARTH)))
                    .findFirst().map(e->e.getItem().copy()).orElse(ItemStack.EMPTY);
            for (var item : drops) item.discard();
            h.getLevel().setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            if (stack.isEmpty()) throw new Violation("DEVICE","回収アイテムがない");
            return stack;
        }
        void advance(int ticks) {
            for (int n = 0;n<ticks;n++) {

                var clock = h.getLevel().getServer().getWorldData().overworldData(); clock.setGameTime(clock.getGameTime()+1);

                check("advance");
            }
        }
        void check(String operation) {
            expanded++; oracle.record(h.getLevel().getGameTime());
            var balance = service.balance().orElseThrow();
            var expected = oracle.evaluate(h.getLevel().getGameTime());
            equal("ORACLE_AMOUNT", expected.total().amount(), balance.retained(), operation);
            equal("ORACLE_EXCHANGE", expected.total().exchange(), balance.exchange(), operation);
            for (var shape : oracle.geometry()) {
                var state = service.sample(BlockPos.of(shape.cells().getFirst()), h.getLevel().getGameTime()).state().orElseThrow();
                double mol = shape.cells().size()*101325/(8.314462618*293.15);
                double capacity = mol*.02897*1005+shape.walls()*20000;
                equal("ORACLE_ROOM", expected.in(shape.cells()).amount(),
                        new RoomPhysics.Amount((state.temperatureK()-293.15)*capacity, state.co()*mol, state.co2()*mol, state.oxygen()*mol), operation);
            }
            equal("BALANCE", plus(initial, inputs), plus(balance.retained(), balance.exchange()), operation);
            var saved = service.save(new CompoundTag(), h.getLevel().registryAccess());
            for (var name : List.of("pending_recent","pending_cumulative")) for (var element : saved.getList(name, 10)) {
                var row = (CompoundTag)element;
                if (Math.abs(row.getDouble("heat"))>1e-8 || Math.abs(row.getDouble("co"))>1e-12)
                    throw new Violation("PENDING", operation+" 観測済み区間の入力が未反映: "+row);
            }
            for (var element : saved.getList("displaced", 10)) {
                var row = (CompoundTag) element;
                nonnegative(new RoomPhysics.Amount(row.getDouble("heat"), row.getDouble("co"),
                        row.getDouble("co2"), row.getDouble("oxygen")), operation+" 未配分");
            }
            for (var element : saved.getList("regions", 10)) {
                var row = (CompoundTag) element;
                for (var entry : row.getList("checkpoints", 10)) {
                    var state = (CompoundTag) entry;
                    nonnegative(new RoomPhysics.Amount(state.getDouble("temperature")-293.15,
                            state.getDouble("co"), state.getDouble("co2"), state.getDouble("oxygen")), operation+" 室checkpoint");
                }
            }
            var q = balance.retained();
            if (q.heatJ()< -1e-5 || q.coMol()< -1e-10 || q.co2Mol()< -1e-10 || q.oxygenMol()< -1e-10)
                throw new Violation("NEGATIVE", operation+" "+q);
        }
        @Override public void close() {
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            for (int slot = 0;slot<2;slot++) h.getLevel().setBlockAndUpdate(floor(slot), Blocks.STONE.defaultBlockState());
            for (var item : h.getLevel().getEntitiesOfClass(ItemEntity.class, h.getBounds().inflate(4))) item.discard();
            player.discard(); h.getLevel().getDataStorage().set(RoomAir.ID, original);
            h.getLevel().getServer().getWorldData().overworldData().setGameTime(originalTime);
        }
    }
    private static RoomPhysics.Amount plus(RoomPhysics.Amount a, RoomPhysics.Amount b) {
        return new RoomPhysics.Amount(a.heatJ()+b.heatJ(), a.coMol()+b.coMol(), a.co2Mol()+b.co2Mol(), a.oxygenMol()+b.oxygenMol());
    }
    private static RoomPhysics.Amount minus(RoomPhysics.Amount a, RoomPhysics.Amount b) {
        return new RoomPhysics.Amount(a.heatJ()-b.heatJ(), a.coMol()-b.coMol(), a.co2Mol()-b.co2Mol(), a.oxygenMol()-b.oxygenMol());
    }
    private static void nonnegative(RoomPhysics.Amount q, String operation) {
        if (q.heatJ() < -1e-5 || q.coMol() < -1e-10 || q.co2Mol() < -1e-10 || q.oxygenMol() < -1e-10)
            throw new Violation("NEGATIVE", operation+" "+q);
    }
    private static void equal(String kind, RoomPhysics.Amount expected, RoomPhysics.Amount actual, String operation) {
        if (!Double.isFinite(actual.heatJ()) || !Double.isFinite(actual.coMol()) || !Double.isFinite(actual.co2Mol()) || !Double.isFinite(actual.oxygenMol()) || Math.abs(expected.heatJ()-actual.heatJ())>2e-5 || Math.abs(expected.coMol()-actual.coMol())>1e-9 || Math.abs(expected.co2Mol()-actual.co2Mol())>1e-9 || Math.abs(expected.oxygenMol()-actual.oxygenMol())>1e-8)
            throw new Violation(kind, operation+" expected="+expected+" actual="+actual);
    }
}
