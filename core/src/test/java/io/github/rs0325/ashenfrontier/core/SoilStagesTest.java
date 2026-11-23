package io.github.rs0325.ashenfrontier.core;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SoilStagesTest {
    @Test
    void everyCombinationReturnsToTheSameStagesAfterPlacement() {
        for (int code = 0; code < 15625; code++) {
            int n = code;
            int[] stages = new int[6];
            for (int i = 0; i < stages.length; i++) { stages[i] = n % 5; n /= 5; }
            var value = new SoilContamination.Stages(stages[0], stages[1], stages[2], stages[3], stages[4], stages[5]);
            assertEquals(value, value.representative().stages());
        }
    }

    @Test
    void equalStagesHaveEqualComponentValuesDespiteDifferentConcentrations() {
        var first = new SoilContamination(.00016, .000002, .25, 5.4, 150, 2e6);
        var second = new SoilContamination(.0003, .000004, .3, 5.2, 250, 4e6);
        assertNotEquals(first, second);
        assertEquals(first.stages(), second.stages());
        assertNotEquals(first.stages(), new SoilContamination(.0005, .000004, .3, 5.2, 250, 4e6).stages());
        assertEquals(SoilContamination.Stages.CLEAN, SoilContamination.CLEAN.stages());
    }

    @Test
    void codecDefaultsCleanKindsAndRejectsInvalidStages() {
        var ops = JsonOps.INSTANCE;
        var partial = SoilContamination.Stages.CODEC.parse(ops, JsonParser.parseString("{\"lead\":2}")).getOrThrow();
        assertEquals(new SoilContamination.Stages(2, 0, 0, 0, 0, 0), partial);
        assertEquals("{\"lead\":2}", SoilContamination.Stages.CODEC.encodeStart(ops, partial).getOrThrow().toString());
        for (String invalid : new String[]{"{\"lead\":5}", "{\"ph\":-1}"})
            assertTrue(SoilContamination.Stages.CODEC.parse(ops, JsonParser.parseString(invalid)).error().isPresent());
    }
}
