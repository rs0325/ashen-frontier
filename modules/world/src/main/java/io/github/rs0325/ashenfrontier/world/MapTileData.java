package io.github.rs0325.ashenfrontier.world;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** 色はARGB。透明画素と高さのNO_HEIGHTは未探索を表す。保存形式は固定寸法の圧縮バイナリ。 */
final class MapTileData {
    static final short NO_HEIGHT = Short.MIN_VALUE;
    private static final int MAGIC = 0x41464D50;
    private static final int VERSION = 1;
    private static final int[] QUAD = {0, 1, MapTileKey.SIZE, MapTileKey.SIZE + 1};
    final int[] blocks;
    final int[] relief;
    final short[] heights;

    MapTileData() {
        blocks = new int[MapTileKey.SIZE * MapTileKey.SIZE];
        relief = new int[blocks.length];
        heights = new short[blocks.length];
        Arrays.fill(heights, NO_HEIGHT);
    }

    private MapTileData(MapTileData source) {
        blocks = source.blocks.clone();
        relief = source.relief.clone();
        heights = source.heights.clone();
    }

    MapTileData copy() { return new MapTileData(this); }

    void set(int x, int z, int blockColor, int reliefColor, int height) {
        int at = z * MapTileKey.SIZE + x;
        blocks[at] = 0xFF000000 | blockColor;
        relief[at] = 0xFF000000 | reliefColor;
        heights[at] = (short) height;
    }

    void reduceInto(MapTileData parent, int quadrantX, int quadrantZ) {
        int half = MapTileKey.SIZE / 2;
        for (int z = 0; z < half; z++) for (int x = 0; x < half; x++) {
            int from = z * 2 * MapTileKey.SIZE + x * 2;
            int to = (quadrantZ * half + z) * MapTileKey.SIZE + quadrantX * half + x;
            parent.blocks[to] = average(blocks, from);
            parent.relief[to] = average(relief, from);
            int height = 0, count = 0;
            for (int offset : QUAD) {
                if (heights[from + offset] == NO_HEIGHT) continue;
                height += heights[from + offset];
                count++;
            }
            parent.heights[to] = count == 0 ? NO_HEIGHT : (short) (height / count);
        }
    }

    private static int average(int[] pixels, int from) {
        int red = 0, green = 0, blue = 0, count = 0;
        for (int offset : QUAD) {
            int color = pixels[from + offset];
            if ((color >>> 24) == 0) continue;
            red += color >> 16 & 255;
            green += color >> 8 & 255;
            blue += color & 255;
            count++;
        }
        return count == 0 ? 0 : 0xFF000000 | (red / count) << 16 | (green / count) << 8 | blue / count;
    }

    void write(OutputStream stream) throws IOException {
        try (var output = new DataOutputStream(new DeflaterOutputStream(stream))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            for (int i = 0; i < blocks.length; i++) {
                output.writeInt(blocks[i]);
                output.writeInt(relief[i]);
                output.writeShort(heights[i]);
            }
        }
    }

    static MapTileData read(InputStream stream) throws IOException {
        try (var input = new DataInputStream(new InflaterInputStream(stream))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("地図タイルの形式が不正です");
            var tile = new MapTileData();
            for (int i = 0; i < tile.blocks.length; i++) {
                tile.blocks[i] = input.readInt();
                tile.relief[i] = input.readInt();
                tile.heights[i] = input.readShort();
            }
            if (input.read() != -1) throw new IOException("地図タイルに余分なデータがあります");
            return tile;
        }
    }
}
