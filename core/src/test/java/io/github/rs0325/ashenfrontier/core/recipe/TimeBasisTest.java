package io.github.rs0325.ashenfrontier.core.recipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimeBasisTest {
    @Test
    void changingDayLengthOnlyChangesLongProcesses() {
        assertEquals(1, TimeBasis.REAL.seconds(20, 24000));
        assertEquals(1, TimeBasis.REAL.seconds(20, 48000));
        assertEquals(72, TimeBasis.GAME.seconds(20, 24000));
        assertEquals(36, TimeBasis.GAME.seconds(20, 48000));
        assertEquals(0, TimeBasis.INSTANT.seconds(20, 24000));
        assertThrows(IllegalArgumentException.class, () -> TimeBasis.GAME.seconds(-1, 24000));
        assertThrows(IllegalArgumentException.class, () -> TimeBasis.GAME.seconds(20, 0));
    }
}
