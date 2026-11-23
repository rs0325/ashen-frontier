package io.github.rs0325.ashenfrontier.world;

/** level=0は1ブロック/画素。上位段は受信済みの下位タイルだけを縮小したもの。 */
record MapTileKey(int level, int x, int z) {
    static final int SIZE = 128;
    static final int MAX_LEVEL = 8;

    static MapTileKey at(int level, int x, int z) {
        int span = SIZE << level;
        return new MapTileKey(level, Math.floorDiv(x - MapCoordinates.MIN, span),
                Math.floorDiv(z - MapCoordinates.MIN, span));
    }

    static int pixel(int level, int coordinate) {
        return Math.floorMod(coordinate - MapCoordinates.MIN, SIZE << level) >> level;
    }

    int minX() { return MapCoordinates.MIN + x * (SIZE << level); }
    int minZ() { return MapCoordinates.MIN + z * (SIZE << level); }
    boolean overlaps(MapTileKey other) {
        return minX() < other.minX() + (SIZE << other.level) && other.minX() < minX() + (SIZE << level)
                && minZ() < other.minZ() + (SIZE << other.level) && other.minZ() < minZ() + (SIZE << level);
    }
    MapTileKey parent() { return new MapTileKey(level + 1, x >> 1, z >> 1); }
}
