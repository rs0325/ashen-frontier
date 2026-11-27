package io.github.rs0325.ashenfrontier.climate.room;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RoomHistoryTest {
    @Test void indexedAppendDoesNotCopyTheGlobalHistory() throws Exception {
        var history=new RoomHistory();
        history.restore(20,java.util.List.of(new RoomHistory.Entry(1,0,20,new RoomPhysics.Amount(1,0,0,0))),java.util.List.of());
        history.matching(p->p==1);var totals=history.cumulative();
        var entries=RoomHistory.class.getDeclaredField("entries");entries.setAccessible(true);
        for(int n=0;n<100;n++) {
            var entry=new RoomHistory.Entry(2,20+n,21+n,new RoomPhysics.Amount(n,0,0,0));history.append(entry);
            assertEquals(java.util.List.of(entry),history.matching(p->p==2).subList(n,n+1));
            assertNull(entries.get(history),"位置別の照会に全履歴の複製を作らない");
            assertSame(totals,history.cumulative(),"通常追記は累積一覧を変更しない");
        }
    }
    @Test void normalAppendKeepsStructuralGenerationButCompactionAndTransferChangeIt() {
        var history=new RoomHistory();long version=history.structureRevision();
        for(int n=0;n<RoomHistory.LIMIT;n++)history.append(new RoomHistory.Entry(n%4,n,n+1,new RoomPhysics.Amount(1,0,0,0)));
        assertEquals(version,history.structureRevision());
        history.append(new RoomHistory.Entry(1,2048,2049,new RoomPhysics.Amount(2,0,0,0)));
        assertTrue(history.compactIfNeeded());assertTrue(history.structureRevision()>version);
        version=history.structureRevision();history.append(new RoomHistory.Entry(1,0,1,new RoomPhysics.Amount(3,0,0,0)));
        assertTrue(history.structureRevision()>version);
        version=history.structureRevision();assertTrue(history.transfer(1,Map.of(9L,1.0)));assertTrue(history.structureRevision()>version);
        var restored=new RoomHistory();restored.restore(history.checkpoint(),history.cumulative(),history.recent());
        assertEquals(history.entries(),restored.entries());
    }
    @Test @SuppressWarnings("unchecked") void cachedListsMatchCanonicalOrderAcrossAllMutations() throws Exception {
        var tailField=RoomHistory.class.getDeclaredField("recent");tailField.setAccessible(true);
        var totalsField=RoomHistory.class.getDeclaredField("cumulative");totalsField.setAccessible(true);
        var random=new java.util.Random(12520261127L);var history=new RoomHistory();int restores=0,transfers=0,retro=0,compactions=0;
        for(int n=0;n<6144;n++) {
            long previous=history.checkpoint();
            if(n>2048&&n%37==0) {
                if(history.transfer(random.nextInt(16),Map.of(20L,.4,21L,.6)))transfers++;
            } else if(n>2048&&n%53==0) {
                var copy=new RoomHistory();copy.restore(history.checkpoint(),history.cumulative(),history.recent());history=copy;restores++;
            } else {
                long from=n>2048&&random.nextBoolean()?0:n*20L;
                if(from<history.checkpoint())retro++;
                history.add(new RoomHistory.Entry(random.nextInt(16),from,n*20L+20,new RoomPhysics.Amount(n,.01,.02,-.03)));
            }
            if(history.checkpoint()>previous)compactions++;
            var expected=new java.util.ArrayList<>(((Map<?,RoomHistory.Entry>)totalsField.get(history)).values());
            var totals=java.util.List.copyOf(expected);expected.addAll((java.util.List<RoomHistory.Entry>)tailField.get(history));
            assertEquals(expected,history.entries());assertEquals(totals,history.cumulative());
            int group=n%5;
            assertEquals(expected.stream().filter(e->e.position()%5==group).toList(),history.matching(position->position%5==group));
            assertSame(history.entries(),history.entries());assertSame(history.cumulative(),history.cumulative());
        }
        assertTrue(restores>=70&&transfers>=70&&retro>=1000&&compactions>=2);
    }
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
