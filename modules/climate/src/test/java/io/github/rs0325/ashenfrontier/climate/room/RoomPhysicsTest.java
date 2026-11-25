package io.github.rs0325.ashenfrontier.climate.room;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RoomPhysicsTest {
    private static final RoomPhysics.Air AIR = new RoomPhysics.Air(293.15, 101325, 0, .00042, .2095, 2);

    @Test void sealedRoomConservesGasAndSignedHeat() {
        var initial = RoomPhysics.State.ambient(AIR);
        var rate = new RoomPhysics.Rate(100, .001, .002, -.003);
        var state = RoomPhysics.advance(initial, AIR, 30, 0, 0, rate, 100);
        double moles = AIR.pressurePa() * 30 / (RoomPhysics.R * AIR.temperatureK());
        assertEquals(.1, state.co() * moles, 1e-12);
        assertEquals(.2, (state.co2() - initial.co2()) * moles, 1e-12);
        assertEquals(.3, (initial.oxygen() - state.oxygen()) * moles, 1e-12);
        assertEquals(10000, (state.temperatureK() - initial.temperatureK()) * moles * .02897 * 1005, 1e-8);
        var released = RoomPhysics.inject(initial, AIR, 30, 0, new RoomPhysics.Amount(5000, 0, 0, 0));
        assertTrue(released.temperatureK() > initial.temperatureK());
    }

    @Test void constantCoefficientsComposeAndVentilationRecovers() {
        var initial = new RoomPhysics.State(310, .001, .002, .19);
        var rate = new RoomPhysics.Rate(150, .001, .002, -.003);
        var full = RoomPhysics.advance(initial, AIR, 30, 40, .5, rate, 100);
        var split = RoomPhysics.advance(RoomPhysics.advance(initial, AIR, 30, 40, .5, rate, 37),
                AIR, 30, 40, .5, rate, 63);
        assertEquals(full.temperatureK(), split.temperatureK(), 1e-12);
        assertEquals(full.co(), split.co(), 1e-15);
        assertEquals(full.oxygen(), split.oxygen(), 1e-15);
        var recovered = RoomPhysics.advance(full, AIR, 30, 40, .5, RoomPhysics.Rate.ZERO, 10000);
        assertEquals(AIR.co(), recovered.co(), 1e-12);
        assertEquals(AIR.oxygen(), recovered.oxygen(), 1e-12);
        assertTrue(recovered.temperatureK() < full.temperatureK());
        assertTrue(RoomPhysics.flow(1, 4, 320, AIR) > RoomPhysics.flow(1, 1, 320, AIR));
        assertEquals(0, RoomPhysics.flow(0, 0, 320, AIR));
    }

    @Test void boundaryChangesDoNotRescaleStoredMolesOrCapacity() {
        var changed = new RoomPhysics.Air(310, 90000, 0, .00042, .2095, 0);
        var rate = new RoomPhysics.Rate(100, 1, 2, -.1);
        var first = RoomPhysics.advance(RoomPhysics.State.ambient(AIR), AIR, 30, 0, 0, rate, 1);
        var second = RoomPhysics.advance(first, changed, 30, 0, 0, rate, 1);
        var quantity = RoomPhysics.amount(second, 30, 0);
        assertEquals(2, quantity.coMol(), 1e-12);
        assertEquals(200, quantity.heatJ(), 1e-7);
        assertEquals(RoomPhysics.amount(RoomPhysics.State.ambient(AIR), 30, 0).oxygenMol() - .2,
                quantity.oxygenMol(), 1e-12);
    }

    @Test void finalReceiptPerSourceRejectsReplaysAndConflicts() {
        var receipts = new SourceReceipts();
        var id = UUID.randomUUID();
        assertEquals(SourceReceipts.Result.ACCEPTED, receipts.accept(id, 1, "same"));
        assertEquals(SourceReceipts.Result.DUPLICATE, receipts.accept(id, 1, "same"));
        assertEquals(SourceReceipts.Result.CONFLICT, receipts.accept(id, 1, "different"));
        for (int n = 2; n <= 10000; n++)
            assertEquals(SourceReceipts.Result.ACCEPTED, receipts.accept(id, n, "same"));
        assertEquals(SourceReceipts.Result.OLD, receipts.accept(id, 1, "different"));
        assertEquals(1, receipts.entries().size());
        var restored = new SourceReceipts();
        receipts.entries().forEach(restored::restore);
        assertEquals(SourceReceipts.Result.DUPLICATE, restored.accept(id, 10000, "same"));
    }
}
