package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.core.time.DayLengthHistory;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClimateModelTest {
    @Test void standardAtmosphereAndLayerBoundary() {
        assertEquals(101325, StandardAtmosphere.pressurePa(0), 0.01);
        assertEquals(79495, StandardAtmosphere.pressurePa(2000), 2);
        assertEquals(22632, StandardAtmosphere.pressurePa(11000), 1);
        assertEquals(StandardAtmosphere.pressurePa(11000), StandardAtmosphere.pressurePa(11000.001), 0.01);
        assertTrue(StandardAtmosphere.pressurePa(12800) < 22632);
        assertEquals(-71.5, StandardAtmosphere.temperatureOffsetK(12800), 1e-9);
        assertTrue(StandardAtmosphere.pressurePa(-4775) > 101325);
        assertThrows(IllegalArgumentException.class, () -> StandardAtmosphere.pressurePa(12826));
    }

    @Test void solarEquatorFortyAndPolarExtremes() {
        assertEquals(Math.PI / 2, SolarPosition.at(0, 79.0 / 365, 0.5).altitudeRad(), 0.01);
        assertTrue(SolarPosition.at(40, 172.0 / 365, 0.5).altitudeRad() > 1.2);
        assertTrue(SolarPosition.at(40, 355.0 / 365, 0.5).altitudeRad() < 0.5);
        assertEquals(1, SolarPosition.at(80, 172.0 / 365, 0).daylightFraction());
        assertEquals(0, SolarPosition.at(80, 355.0 / 365, 0.5).daylightFraction());
        assertEquals(0, SolarPosition.at(-80, 172.0 / 365, 0.5).daylightFraction());
        assertEquals(1, SolarPosition.at(-80, 355.0 / 365, 0).daylightFraction());
        assertEquals(0, SolarPosition.at(0, 0.5, 0).horizontalProjection());
        for (double lat : new double[]{-90, -80, 0, 40, 80, 90}) {
            assertTrue(Double.isFinite(SolarPosition.at(lat, 0.5, 0.5).altitudeRad()));
        }
    }

    @Test void temperatureSeasonReversesAndHeightIsAppliedOnce() {
        var profile = ClimateProfile.forRegion(255);
        double winter = ClimateModel.temperatureK(40, 0, 0, profile);
        double summer = ClimateModel.temperatureK(40, 0, 12 * 86400, profile);
        assertTrue(summer > winter);
        assertTrue(ClimateModel.temperatureK(-40, 0, 12 * 86400, profile)
                < ClimateModel.temperatureK(-40, 0, 0, profile));
        assertEquals(13, winter - ClimateModel.temperatureK(40, 2000, 0, profile), 1e-9);
        assertEquals(4, ClimateModel.annualSeaLevelK(0, profile) - 300.15, 1e-9);
        assertEquals(winter, ClimateModel.temperatureK(40, 0, 24 * 86400, profile), 1e-9);
    }

    @Test void dayLengthChangesPreserveElapsedPhaseAndIntervals() {
        var history = new DayLengthHistory(0, 24000);
        history.change(12000, 48000);
        assertEquals(86400, GameTime.gameSeconds(0, 36000, history), 1e-9);
        assertEquals(2, history.intervals(0, 36000).size());
        assertEquals(43200, GameTime.gameSeconds(0, 12000, history), 1e-9);
        assertEquals(0, ClimateModel.sun(0, GameTime.gameSeconds(0, 18000, history)).horizontalProjection());
    }

    @Test void initialRegionIsNotPermanentlyFrozenOrDryAndAllRegionsExist() {
        for (int id : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 255}) assertNotNull(ClimateProfile.forRegion(id));
        var initial = ClimateProfile.forRegion(1);
        for (int day = 0; day < 24; day++) {
            assertTrue(ClimateModel.temperatureK(40, 0, day * 86400, initial) > 278.15);
        }
        assertTrue(initial.annualPrecipitationKgM2() >= 400);
        assertThrows(IllegalArgumentException.class, () -> ClimateProfile.forRegion(42));
    }
    @Test void diurnalPeakMatchesSolarFifteenHours() {
        var profile = new ClimateProfile(0, 0, 5, 0.5, 650, 0.6);
        double mean = ClimateModel.annualSeaLevelK(0, profile);
        assertEquals(mean + 5, ClimateModel.temperatureK(0, 0, 0.375 * 86400, profile), 1e-9);
        assertEquals(mean - 5, ClimateModel.temperatureK(0, 0, 0.875 * 86400, profile), 1e-9);
    }
}
