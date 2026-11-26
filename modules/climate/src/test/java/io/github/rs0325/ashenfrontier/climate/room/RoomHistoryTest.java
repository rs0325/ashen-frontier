package io.github.rs0325.ashenfrontier.climate.room;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RoomHistoryTest {
    @Test void boundedTailAndCumulativePreserveAllFourSignedQuantities() {
        var history = new RoomHistory();
        var amount = new RoomPhysics.Amount(1234, .001, .002, -.003);
        for (int n = 0; n < 400000; n++) {
            history.add(new RoomHistory.Entry(n % 40, n / 40 * 20L, (n / 40 + 1) * 20L, amount));
            assertTrue(history.retainedCount() <= RoomHistory.LIMIT);
        }
        assertEquals(40, history.cumulativeCount());
        var sum = total(history);
        assertEquals(400000 * 1234.0, sum.heatJ(), 1e-6);
        assertEquals(400, sum.coMol(), 1e-7);
        assertEquals(800, sum.co2Mol(), 1e-7);
        assertEquals(-1200, sum.oxygenMol(), 1e-7);
        var restored = new RoomHistory();
        restored.restore(history.checkpoint(), history.cumulative(), history.recent());
        restored.add(new RoomHistory.Entry(0, 0, 20, amount));
        var after = total(restored);
        assertEquals(sum.heatJ() + 1234, after.heatJ(), 1e-6);
        assertEquals(sum.oxygenMol() - .003, after.oxygenMol(), 1e-7);
        restored.add(new RoomHistory.Entry(0, 0, 200020, amount));
        var crossing = total(restored);
        assertEquals(sum.heatJ() + 2468, crossing.heatJ(), 1e-6);
        assertEquals(sum.oxygenMol() - .006, crossing.oxygenMol(), 1e-7);
        assertTrue(restored.recent().size() <= RoomHistory.LIMIT);
        assertTrue(restored.cumulative().stream().filter(p -> p.position() == 0).findFirst().orElseThrow().to() < 200000);
    }

    private static RoomPhysics.Amount total(RoomHistory history) {
        var sum = new RoomPhysics.Amount(0, 0, 0, 0);
        for (var e : history.entries()) sum = RoomHistory.plus(sum, e.amount());
        return sum;
    }
    @Test void retiredGenerationDistributesOnceAndPreservesInputBalance() {
        var history = new RoomHistory();
        long parent = Long.MIN_VALUE, left = parent + 1, right = parent + 2;
        history.add(new RoomHistory.Entry(parent, 0, 20, new RoomPhysics.Amount(1000, 1, 2, -3)));
        history.transfer(parent, Map.of(left, .4, right, .4));
        var retained = total(history);
        var closed = RoomHistory.plus(retained, history.archivedInput());
        assertEquals(1000, closed.heatJ(), 1e-10);
        assertEquals(1, closed.coMol(), 1e-12);
        assertEquals(2, closed.co2Mol(), 1e-12);
        assertEquals(-3, closed.oxygenMol(), 1e-12);
        assertTrue(history.entries().stream().noneMatch(e -> e.position() == parent));
        history.add(new RoomHistory.Entry(left, 21, 21, new RoomPhysics.Amount(100, 1, 0, 0)));
        double rightCo = history.entries().stream().filter(e -> e.position() == right).mapToDouble(e -> e.amount().coMol()).sum();
        assertEquals(.4, rightCo, 1e-12);
    }

    @Test void materializedResidualCannotBecomeAnInputAgain() {
        var history = new RoomHistory();
        var original = new RoomPhysics.Amount(1000, 1, 2, -3);
        history.add(new RoomHistory.Entry(1, 0, 20, original));
        history.transfer(1, Map.of());
        for (int n = 1; n <= 2052; n++)
            history.add(new RoomHistory.Entry(2, n + 20, n + 21, new RoomPhysics.Amount(0, 0, 0, 0)));
        assertEquals(original, history.archivedInput());
        assertTrue(history.entries().stream().noneMatch(e -> e.position() == 1));
        var restored = new RoomHistory();
        restored.restore(history.checkpoint(), history.cumulative(), history.recent());
        restored.restoreArchivedInput(history.archivedInput());
        assertEquals(original, restored.archivedInput());
        assertTrue(restored.entries().stream().noneMatch(e -> e.position() == 1));
    }

    @Test void otherPositionsDoNotExtendTheActualInputInterval() {
        var history = new RoomHistory();
        history.add(new RoomHistory.Entry(1, 0, 20, new RoomPhysics.Amount(1000, 1, 2, -3)));
        for (int n = 1; n <= 2052; n++)
            history.add(new RoomHistory.Entry(2, n + 20, n + 21, new RoomPhysics.Amount(0, 0, 0, 0)));
        assertEquals(20, history.cumulativeAt(1).to());
        history.add(new RoomHistory.Entry(1, 0, 10, new RoomPhysics.Amount(0, 0, 0, 0)));
        assertEquals(20, history.cumulativeAt(1).to());
    }
    @Test void instantaneousOwnersSurviveCompactionWithABoundedNumberOfContexts() {
        var history = new RoomHistory();
        history.add(new RoomHistory.Entry(1, 0, 0, new RoomPhysics.Amount(0, .27, 0, 0), 1));
        history.add(new RoomHistory.Entry(1, 0, 0, new RoomPhysics.Amount(0, .09, 0, 0), 2));
        for (int n = 0; n < 1026; n++) history.add(new RoomHistory.Entry(2, n+1, n+2, new RoomPhysics.Amount(0, 0, 0, 0)));
        assertEquals(.27, history.cumulative().stream().filter(e -> e.context() == 1).findFirst().orElseThrow().amount().coMol(), 1e-12);
        assertEquals(.09, history.cumulative().stream().filter(e -> e.context() == 2).findFirst().orElseThrow().amount().coMol(), 1e-12);
        var restored = new RoomHistory();
        restored.restore(history.checkpoint(), history.cumulative(), history.recent());
        assertEquals(history.cumulativeCount(), restored.cumulativeCount());
        for (int n = 3; n <= 100; n++) restored.add(new RoomHistory.Entry(1, 0, 0, new RoomPhysics.Amount(0, 1, 0, 0), n));
        assertEquals(65, restored.cumulative().stream().filter(e -> e.position() == 1).count());
        assertEquals(98.36, total(restored).coMol(), 1e-10);
    }
}
