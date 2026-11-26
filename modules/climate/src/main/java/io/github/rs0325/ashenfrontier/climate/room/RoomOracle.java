package io.github.rs0325.ashenfrontier.climate.room;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 検査用の独立再生。製品の探索結果・履歴・交換台帳・物理関数を期待値に使わない。 */
final class RoomOracle {
    private static final RoomPhysics.Amount ZERO = new RoomPhysics.Amount(0, 0, 0, 0);
    record Shape(List<Long> cells, int walls, int openings, double height) {}
    record Frame(long tick, long order, List<Shape> rooms) {}
    record Input(long from, long to, long cell, RoomPhysics.Amount amount, List<Long> instantCells, long order) {}
    record Value(RoomPhysics.Amount amount, RoomPhysics.Amount exchange) {}
    record Result(Map<Long, Value> cells) {
        Value total() { return sum(cells.values().stream().toList()); }
        Value in(List<Long> positions) {
            return sum(positions.stream().map(p -> cells.getOrDefault(p, new Value(ZERO, ZERO))).toList());
        }
    }
    final GameTestHelper helper;
    final List<BlockPos> roots;
    final long start;
    final List<Frame> frames = new ArrayList<>();
    private long order;
    final List<Input> inputs = new ArrayList<>();
    final java.util.Set<Long> initialCells = new HashSet<>();
    RoomOracle(GameTestHelper helper, List<BlockPos> roots, long start) {
        this.helper = helper; this.roots = roots; this.start = start;
    }
    List<Shape> geometry() {
        var result = new ArrayList<Shape>(); var assigned = new HashSet<Long>();
        for (var root : roots) {
            if (assigned.contains(root.asLong()) || !helper.getLevel().getBlockState(root).isAir() || sky(root)) continue;
            var visited = new HashSet<Long>(); var air = new HashSet<Long>();
            var walls = new HashSet<Long>(); var ports = new HashSet<Long>();
            var queue = new ArrayDeque<BlockPos>(); queue.add(root);
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            while (!queue.isEmpty()) {
                var p = queue.removeFirst(); if (!visited.add(p.asLong())) continue;
                if (!helper.getLevel().getBlockState(p).isAir()) { walls.add(p.asLong()); continue; }
                if (sky(p)) { ports.add(p.asLong()); high = Math.max(high, p.getY()); continue; }
                air.add(p.asLong()); low = Math.min(low, p.getY());
                if (air.size() > 128) throw new AssertionError("独立fixtureの空間上限を超えた");
                for (var direction : Direction.values()) queue.add(p.relative(direction));
            }
            assigned.addAll(air);
            result.add(new Shape(air.stream().sorted().toList(), walls.size(), ports.size(),
                    ports.isEmpty() ? 0 : Math.max(1, high - low)));
        }
        return List.copyOf(result);
    }
    private boolean sky(BlockPos pos) {
        return pos.getY() >= helper.getLevel().getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
    }
    void record(long tick) {
        var rooms = geometry();
        if (!frames.isEmpty() && frames.getLast().rooms.equals(rooms)) return;
        var frame = new Frame(tick, order++, rooms);
        if (frames.isEmpty()) for (var room : frame.rooms) initialCells.addAll(room.cells);
        frames.add(frame);
    }
    private List<Shape> at(long tick) {
        List<Shape> result = List.of();
        for (var frame : frames) { if (frame.tick > tick) break; result = frame.rooms; }
        return result;
    }
    void input(long from, long to, long cell, RoomPhysics.Amount amount, long arrival) {
        List<Long> instant = List.of(cell);
        if (from == to) for (var room : at(from == arrival ? arrival : from))
            if (room.cells.contains(cell)) { instant = room.cells; break; }
        inputs.add(new Input(from, to, cell, amount, instant, from == arrival ? order++ : Long.MAX_VALUE));
    }
    Result evaluate(long until) {
        var values = new HashMap<Long, Value>();
        for (long cell : initialCells) values.put(cell, new Value(ambient(1), scale(ambient(1), -1)));
        for (long tick = start; tick <= until; tick++) {
            long current = tick;
            var events = new ArrayList<Object>();
            events.addAll(frames.stream().filter(f -> f.tick == current).toList());
            events.addAll(inputs.stream().filter(i -> i.from == current && i.to == current).toList());
            events.sort(java.util.Comparator.comparingLong(event -> event instanceof Frame f ? f.order : ((Input) event).order));
            for (var event : events) {
                if (event instanceof Frame frame) mix(values, frame.rooms);
                else {
                    var input = (Input) event;
                    for (long cell : input.instantCells) {
                        var old = values.getOrDefault(cell, new Value(ZERO, ZERO));
                        values.put(cell, new Value(plus(old.amount, scale(input.amount, 1.0 / input.instantCells.size())), old.exchange));
                    }
                }
            }
            for (var room : at(tick)) {
                var before = sum(room.cells.stream().map(p -> values.getOrDefault(p, new Value(ZERO, ZERO))).toList());
                var rate = ZERO;
                if (tick < until) for (var input : inputs)
                    if (input.from <= tick && tick < input.to && room.cells.contains(input.cell))
                        rate = plus(rate, scale(input.amount, 20.0 / (input.to - input.from)));
                var value = tick == until ? before : advance(before, rate, room);
                for (long cell : room.cells) values.put(cell, new Value(scale(value.amount, 1.0 / room.cells.size()),
                        scale(value.exchange, 1.0 / room.cells.size())));
            }
            if (tick < until) for (var input : inputs) if (input.from <= tick && tick < input.to
                    && at(tick).stream().noneMatch(r -> r.cells.contains(input.cell))) {
                var old = values.getOrDefault(input.cell, new Value(ZERO, ZERO));
                values.put(input.cell, new Value(plus(old.amount, scale(input.amount, 1.0 / (input.to - input.from))), old.exchange));
            }
        }
        return new Result(Map.copyOf(values));
    }
    private static void mix(Map<Long, Value> values, List<Shape> rooms) {
        for (var room : rooms) {
            for (long cell : room.cells) if (!values.containsKey(cell)) {
                var background = ambient(1); values.put(cell, new Value(background, scale(background, -1)));
            }
            var total = sum(room.cells.stream().map(values::get).toList());
            var perCell = new Value(scale(total.amount, 1.0 / room.cells.size()), scale(total.exchange, 1.0 / room.cells.size()));
            for (long cell : room.cells) values.put(cell, perCell);
        }
    }

    private static Value advance(Value before, RoomPhysics.Amount rate, Shape room) {
        double volume = room.cells.size();
        double mol = volume * 101325 / (8.314462618 * 293.15);
        double capacity = mol * .02897 * 1005 + room.walls * 20000;
        double temperature = 293.15 + before.amount.heatJ() / capacity;
        double flow = .3 * room.openings * Math.sqrt(1.5 * 1.5
                + 2 * 9.80665 * room.height * Math.abs(temperature - 293.15) / 293.15);
        double heatLambda = (flow * mol * .02897 * 1005 / volume + room.walls) / capacity;
        double gasLambda = flow / volume;
        var background = ambient(volume);
        double[] initial = components(before.amount), rates = components(rate), target = components(background);
        double[] quantity = new double[4], exchanged = new double[4];
        for (int i = 0; i < 4; i++) {
            double lambda = i == 0 ? heatLambda : gasLambda;
            if (lambda == 0) { quantity[i] = initial[i] + rates[i] / 20; exchanged[i] = 0; }
            else {
                double integral = -Math.expm1(-lambda / 20) / lambda;
                quantity[i] = target[i] + (initial[i] - target[i]) * Math.exp(-lambda / 20) + rates[i] * integral;
                // 外気交換は、既知の形状と初期値から求める積分流束。製品の入力−残留ではない。
                exchanged[i] = (initial[i] - target[i]) * -Math.expm1(-lambda / 20)
                        + rates[i] * (1.0 / 20 - integral);
            }
        }
        return new Value(amount(quantity), plus(before.exchange, amount(exchanged)));
    }
    static RoomPhysics.Amount ambient(double volume) {
        double mol = volume * 101325 / (8.314462618 * 293.15);
        return new RoomPhysics.Amount(0, 0, mol * .00042, mol * .2095);
    }
    static Value sum(List<Value> values) {
        var amount = ZERO; var exchange = ZERO;
        for (var value : values) { amount = plus(amount, value.amount); exchange = plus(exchange, value.exchange); }
        return new Value(amount, exchange);
    }
    private static double[] components(RoomPhysics.Amount a) { return new double[]{a.heatJ(), a.coMol(), a.co2Mol(), a.oxygenMol()}; }
    private static RoomPhysics.Amount amount(double[] a) { return new RoomPhysics.Amount(a[0], a[1], a[2], a[3]); }
    private static RoomPhysics.Amount plus(RoomPhysics.Amount a, RoomPhysics.Amount b) {
        return new RoomPhysics.Amount(a.heatJ()+b.heatJ(), a.coMol()+b.coMol(), a.co2Mol()+b.co2Mol(), a.oxygenMol()+b.oxygenMol());
    }
    private static RoomPhysics.Amount scale(RoomPhysics.Amount a, double b) {
        return new RoomPhysics.Amount(a.heatJ()*b, a.coMol()*b, a.co2Mol()*b, a.oxygenMol()*b);
    }
}
