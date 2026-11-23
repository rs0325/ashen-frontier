package io.github.rs0325.ashenfrontier.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SoilContaminationTest {
    @Test
    void codecPreservesAdjacentBoundaryValuesWithoutQuantization() {
        for (double lead : new double[]{Math.nextDown(5e-5), 5e-5, Math.nextUp(5e-5)}) {
            var value = new SoilContamination(lead, 1e-6, .2, 5.6, 100, 1e6);
            var ops = com.mojang.serialization.JsonOps.INSTANCE;
            for (int i = 0; i < 5; i++) {
                var saved = SoilContamination.CODEC.encodeStart(ops, value).getOrThrow();
                value = SoilContamination.CODEC.parse(ops, saved).getOrThrow();
            }
            assertEquals(lead, value.lead());
        }
    }

    @Test
    void allStageBoundariesUseThePublishedIntervals() {
        for (var kind : SoilContamination.Kind.values()) {
            assertEquals(0, SoilContamination.CLEAN.stage(kind));
            for (int stage = 1; stage <= 4; stage++) {
                double boundary = kind.boundary(stage);
                if (kind == SoilContamination.Kind.ACIDITY) {
                    assertEquals(stage - 1, kind.stage(boundary));
                    assertEquals(stage, kind.stage(Math.nextDown(boundary)));
                    assertEquals(stage - 1, kind.stage(Math.nextUp(boundary)));
                } else {
                    assertEquals(stage, kind.stage(boundary));
                    assertEquals(stage - 1, kind.stage(Math.nextDown(boundary)));
                    assertEquals(stage, kind.stage(Math.nextUp(boundary)));
                }
            }
        }
    }

    @Test
    void depthAttenuatesIncrementsAndAcidityUsesActivity() {
        var top = new SoilContamination(.003, .0001, 3.2, 2.9, 200000, 4e9);
        var one = top.atDepth(1);
        assertEquals(1e-5 + (.003 - 1e-5) / 4, one.lead());
        assertEquals(.00005, one.pcb());
        assertEquals(.05 + (3.2 - .05) * .75, one.conductivity());
        assertEquals((Math.pow(10, -2.9) + Math.pow(10, -6.5)) / 2, Math.pow(10, -one.ph()), 1e-15);
        assertEquals(20 + (200000 - 20) / 4.0, one.caesium137());
        assertEquals(1e9, one.fecalColiform());
        assertEquals(SoilContamination.CLEAN, top.atDepth(4));
        assertEquals(SoilContamination.CLEAN.lead(), top.atDepth(3).lead());
        assertTrue(top.atDepth(3).conductivity() > SoilContamination.CLEAN.conductivity());
    }
}
