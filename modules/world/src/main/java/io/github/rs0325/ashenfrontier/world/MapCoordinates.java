package io.github.rs0325.ashenfrontier.world;

/** 固定マップのブロック座標と気候用座標。水平範囲は両軸とも[-16384, 16384)。 */
public final class MapCoordinates {
    public static final int MIN = -16384;
    public static final int MAX = 16384;
    public static final int SEA_LEVEL = 63;

    private MapCoordinates() {}

    public static boolean contains(int x, int z) {
        return x >= MIN && x < MAX && z >= MIN && z < MAX;
    }

    /** 連続ブロックZ座標から北を正とする緯度（度）を返す。境界値の換算も可能。 */
    public static double latitude(double z) {
        return -80.0 * z / MAX;
    }

    /** 緯度（度、北が正）から連続ブロックZ座標へ戻す。 */
    public static double zFromLatitude(double latitude) {
        return -latitude * MAX / 80.0;
    }

    /** ブロックY座標から海面基準の仮想標高（m）を返す。水平縮尺は適用しない。 */
    public static double elevation(double y) {
        return (y - SEA_LEVEL) * 25.0;
    }
}
