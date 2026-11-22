package io.github.rs0325.ashenfrontier.world;

/** 北を上とする地図の座標変換と実地表の表示色。縮尺は画面ピクセル/ブロック。 */
final class MapViewMath {
    private MapViewMath() {}

    static double screen(double world, double center, double origin, double scale) {
        return origin + (world - center) * scale;
    }

    static double world(double screen, double center, double origin, double scale) {
        return center + (screen - origin) / scale;
    }

    static int color(double height, int waterDepth, double slope) {
        int color;
        if (waterDepth > 0) {
            double depth = Math.clamp(waterDepth / 127.0, 0, 1);
            color = mix(0x65BBD0, 0x102C65, depth);
        } else if (height < 160) {
            color = mix(0x568C49, 0xA79462, Math.clamp((height - 63) / 97, 0, 1));
        } else if (height < 280) {
            color = mix(0xA79462, 0x82766B, (height - 160) / 120);
        } else {
            color = mix(0x82766B, 0xF0F1EC, Math.clamp((height - 280) / 230, 0, 1));
        }
        return shade(color, slope);
    }

    static int blockColor(int color, int waterDepth, double slope) {
        if (waterDepth > 0) color = mix(color, 0x0A2048, Math.clamp(waterDepth / 127.0, 0, 1));
        return shade(color, slope);
    }

    private static int shade(int color, double slope) {
        return mix(0, color, Math.clamp(0.88 + slope * 0.04, 0.55, 1));
    }

    private static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int blue = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | blue;
    }

    static int abgr(int rgb) {
        return 0xFF000000 | (rgb & 255) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 255);
    }
}
