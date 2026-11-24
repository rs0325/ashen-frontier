package io.github.rs0325.ashenfrontier.core.recipe;

import io.github.rs0325.ashenfrontier.core.time.GameTime;

/** 加工型ごとに固定する時計。ゲーム秒は日長設定を反映する。 */
public enum TimeBasis {
    INSTANT, REAL, GAME;

    public double seconds(long elapsedTicks, long ticksPerDay) {
        if (elapsedTicks < 0 || ticksPerDay <= 0) throw new IllegalArgumentException("経過時間または日長が不正です");
        return switch (this) {
            case INSTANT -> 0;
            case REAL -> GameTime.realSeconds(elapsedTicks);
            case GAME -> GameTime.gameSeconds(elapsedTicks, ticksPerDay);
        };
    }
}
