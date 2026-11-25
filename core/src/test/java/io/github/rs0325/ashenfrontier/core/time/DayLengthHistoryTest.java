package io.github.rs0325.ashenfrontier.core.time;

import io.github.rs0325.ashenfrontier.core.recipe.TimeBasis;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DayLengthHistoryTest {
    @Test
    void changesOnlyAffectTheirOwnIntervalsAndSurviveRestoration() {
        var history = new DayLengthHistory(100, 24000);
        history.change(1100, 12000);
        var saved = history.changes();
        var restored = new DayLengthHistory(saved);
        assertEquals(10800, GameTime.gameSeconds(100, 2100, restored), 1e-9);
        assertEquals(100, TimeBasis.REAL.seconds(100, 2100, restored));
        assertEquals(0, TimeBasis.INSTANT.seconds(100, 2100, restored));
        assertEquals(3600, TimeBasis.GAME.seconds(100, 1100, restored), 1e-9);
        assertEquals(7200, TimeBasis.GAME.seconds(1100, 2100, restored), 1e-9);
        assertEquals(12000, restored.intervals(1100, 1100).getFirst().ticksPerDay());
        assertEquals(0, GameTime.gameSeconds(1100, 1100, restored));
        history.change(2100, 48000);
        assertEquals(2, restored.changes().size());
        assertThrows(UnsupportedOperationException.class, () -> saved.add(new DayLengthHistory.Change(3100, 24000)));
    }

    @Test
    void rejectsRetroactiveChangesAndUnknownHistory() {
        var history = new DayLengthHistory(100, 24000);
        history.change(200, 24000);
        assertThrows(IllegalArgumentException.class, () -> history.change(150, 12000));
        assertThrows(IllegalArgumentException.class, () -> history.change(200, 12000));
        assertThrows(IllegalArgumentException.class, () -> history.change(300, 0));
        assertThrows(IllegalArgumentException.class, () -> history.intervals(99, 300));
        assertThrows(IllegalArgumentException.class, () -> history.intervals(300, 200));
        assertThrows(IllegalArgumentException.class, () -> TimeBasis.REAL.seconds(300, 200, history));
        assertThrows(IllegalArgumentException.class, () -> new DayLengthHistory(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DayLengthHistory(List.of(
                new DayLengthHistory.Change(200, 24000), new DayLengthHistory.Change(100, 12000))));
    }
}
