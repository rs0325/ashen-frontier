package io.github.rs0325.ashenfrontier.core.water;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaterBudgetTest {
    @Test
    void dryReserveExhaustsExactlyAtBothApprovedFractions() {
        for (long step : new long[]{25_000, 100_000}) {
            var budget = new WaterBudget(144_000_000, 144_000_000, 0, null);
            for (int n = 0; n < 144_000_000 / step; n++) assertEquals(step, budget.take(step));
            assertEquals(0, budget.remaining());
            assertEquals(0, budget.take(step));
        }
    }

    @Test
    void largeSourceDoesNotPassThroughLocalDoubleLimit() {
        var budget = new WaterBudget(2_000_000_000_000_000L, 2_000_000_000_000_000L, 0, null);
        assertEquals(4_000_000, budget.take(4_000_000));
        assertEquals(1_999_999_996_000_000L, budget.remaining());
        var maximum = new WaterBudget(Long.MAX_VALUE, Long.MAX_VALUE - 1, 0, null);
        var receipt = maximum.recharge(0, 20, Long.MAX_VALUE).receipt();
        assertEquals(1, receipt.acceptedMg());
        assertEquals(Long.MAX_VALUE - 1, receipt.overflowMg());
        assertEquals(Long.MAX_VALUE, maximum.remaining());
    }

    @Test
    void rechargeCursorAndOverflowSurviveReconstructionWithoutRecredit() {
        var budget = new WaterBudget(100, 90, 10, null);
        var committed = budget.recharge(10, 20, 30);
        assertEquals(new WaterBudget.Receipt(10, 20, 30, 10, 20), committed.receipt());
        budget.take(100);
        var restored = new WaterBudget(budget.capacity, budget.remaining(), budget.through(), budget.last());
        assertFalse(restored.recharge(10, 20, 30).committedNow());
        assertEquals(0, restored.remaining());
        assertThrows(IllegalArgumentException.class, () -> restored.recharge(10, 20, 31));
        assertThrows(IllegalArgumentException.class, () -> restored.recharge(0, 10, 30));
        assertThrows(IllegalArgumentException.class, () -> restored.recharge(21, 30, 30));
        assertThrows(IllegalArgumentException.class, () -> restored.recharge(20, 30, -1));
        assertTrue(restored.recharge(20, 30, 0).committedNow());
        assertEquals(30, restored.through());
        assertEquals(0, restored.remaining());
    }
}
