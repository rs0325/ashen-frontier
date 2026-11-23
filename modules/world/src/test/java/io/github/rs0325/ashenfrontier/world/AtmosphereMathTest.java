package io.github.rs0325.ashenfrontier.world;

import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtmosphereMathTest {
    @Test
    void edgeFogFadesAcrossTheConfigured1024Blocks() {
        assertEquals(0.0, AtmosphereMath.edgeDensity(1024));
        assertEquals(0.5, AtmosphereMath.edgeDensity(512));
        assertEquals(1.0, AtmosphereMath.edgeDensity(0));
        assertEquals(1.0, AtmosphereMath.edgeDensity(-32));
    }

    @Test
    void biomeFogScalesBothPlanes() {
        var fog = AtmosphereMath.fogRange(230.4, 256, 0.82, 2048);
        assertEquals(230.4 * 0.4424, fog.start(), 1e-9);
        assertEquals(256 * 0.4424, fog.end(), 1e-9);
    }

    @Test
    void edgeFogStaysOrderedAndHidesEverySideOfTheBorder() {
        for (double density : new double[]{0, 0.18, 0.82, 1}) {
            double previous = Double.POSITIVE_INFINITY;
            for (double distance : new double[]{2048, 1024, 1023.999, 768, 512, 256, 128, 16, 1, 0, -32}) {
                for (double far : new double[]{32, 256, 1024}) {
                    var fog = AtmosphereMath.fogRange(far * 0.9, far, density, distance);
                    assertTrue(fog.start() < fog.end());
                    assertTrue(fog.end() < distance || distance <= 0);
                }
                var fog = AtmosphereMath.fogRange(230.4, 256, density, distance);
                assertTrue(fog.end() <= previous);
                previous = fog.end();
            }
        }
        assertEquals(AtmosphereMath.fogRange(921.6, 1024, 0, 1024).end(),
                AtmosphereMath.fogRange(921.6, 1024, 0, 1023.999).end(), 0.001);
    }

    @Test
    void gaussianInterpolationPreservesUniformValuesAndBlendsBoundaries() {
        var uniform = new Vec3(0.82, 0.4, 0.7);
        var sampled = AtmosphereSampler.sample(new Vec3(-12.3, 100, 4.7), (x, y, z) -> uniform);
        assertEquals(uniform.x, sampled.x, 1e-9);
        assertEquals(uniform.y, sampled.y, 1e-9);
        assertEquals(uniform.z, sampled.z, 1e-9);
        var left = AtmosphereSampler.sample(new Vec3(-20, 100, 0),
                (x, y, z) -> x < 0 ? Vec3.ZERO : new Vec3(1, 0.5, 0.25));
        var middle = AtmosphereSampler.sample(new Vec3(0, 100, 0),
                (x, y, z) -> x < 0 ? Vec3.ZERO : new Vec3(1, 0.5, 0.25));
        var right = AtmosphereSampler.sample(new Vec3(20, 100, 0),
                (x, y, z) -> x < 0 ? Vec3.ZERO : new Vec3(1, 0.5, 0.25));
        assertEquals(0, left.x, 1e-9);
        assertEquals(0.5, middle.x, 1e-9);
        assertEquals(0.25, middle.y, 1e-9);
        assertEquals(0.125, middle.z, 1e-9);
        assertEquals(1, right.x, 1e-9);
        var before = AtmosphereSampler.sample(new Vec3(-0.001, 100, 0),
                (x, y, z) -> x < 0 ? Vec3.ZERO : new Vec3(1, 1, 1));
        var after = AtmosphereSampler.sample(new Vec3(0.001, 100, 0),
                (x, y, z) -> x < 0 ? Vec3.ZERO : new Vec3(1, 1, 1));
        assertTrue(before.x < after.x && after.x - before.x < 0.001);
    }
}
