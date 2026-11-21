package io.github.rs0325.ashenfrontier.world;

import net.minecraft.util.CubicSampler;
import net.minecraft.world.phys.Vec3;

/** 標準の霧色と同じ4ブロック格子・ガウス重みで大気の値を補間する。 */
final class AtmosphereSampler {
    private AtmosphereSampler() {}

    static Vec3 sample(Vec3 position, CubicSampler.Vec3Fetcher fetcher) {
        return CubicSampler.gaussianSampleVec3(position.subtract(2, 2, 2).scale(0.25), fetcher);
    }
}
