package io.github.rs0325.ashenfrontier.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class MapTileTest {
    @TempDir Path directory;

    @Test void blockTileAndPixelCoordinates() {
        for (int level = 0; level <= MapTileKey.MAX_LEVEL; level++) {
            for (int x : new int[]{-16385, -16384, -16257, -16256, -1, 0, 127, 128, 16383}) {
                var tile = MapTileKey.at(level, x, x);
                int pixel = MapTileKey.pixel(level, x);
                assertTrue(pixel >= 0 && pixel < MapTileKey.SIZE);
                assertTrue(tile.minX() + (pixel << level) <= x);
                assertTrue(tile.minX() + ((pixel + 1) << level) > x);
                assertEquals(tile.minX(), tile.minZ());
            }
        }
        assertEquals(127, MapTileKey.pixel(0, -1));
        assertEquals(0, MapTileKey.pixel(0, 0));
        assertEquals(new MapTileKey(8, 0, 0), MapTileKey.at(8, 16383, -16384));
    }

    @Test void compressedFormatRoundTripAndDamage() throws IOException {
        var tile = new MapTileData();
        tile.set(0, 0, 0x123456, 0x654321, -128);
        tile.set(127, 127, 0xABCDEF, 0xDEADBE, 575);
        var snapshot = tile.copy();
        tile.set(0, 0, 1, 2, 3);
        var bytes = new ByteArrayOutputStream();
        snapshot.write(bytes);
        byte[] encoded = bytes.toByteArray();
        var decoded = MapTileData.read(new ByteArrayInputStream(encoded));
        assertArrayEquals(snapshot.blocks, decoded.blocks);
        assertArrayEquals(snapshot.relief, decoded.relief);
        assertArrayEquals(snapshot.heights, decoded.heights);
        assertEquals(MapTileData.NO_HEIGHT, decoded.heights[1]);
        assertEquals(0, decoded.blocks[1]);
        assertEquals(0xFF123456, decoded.blocks[0]);
        assertThrows(IOException.class, () -> MapTileData.read(new ByteArrayInputStream(Arrays.copyOf(encoded, encoded.length - 4))));
        assertThrows(IOException.class, () -> MapTileData.read(new ByteArrayInputStream(new byte[]{1, 2, 3, 4})));
    }

    @Test void reducedTilesPreserveUnknownAndOtherQuadrants() {
        var child = new MapTileData();
        child.set(0, 0, 0xFF0000, 0x00FF00, 10);
        child.set(1, 0, 0x0000FF, 0x00FF00, 20);
        var parent = new MapTileData();
        parent.set(0, 0, 0xFFFFFF, 0xFFFFFF, 100);
        child.reduceInto(parent, 1, 1);
        int at = 64 * MapTileKey.SIZE + 64;
        assertEquals(0xFF7F007F, parent.blocks[at]);
        assertEquals(15, parent.heights[at]);
        assertEquals(0, parent.blocks[at + 1]);
        assertEquals(MapTileData.NO_HEIGHT, parent.heights[at + 1]);
        assertEquals(0xFFFFFFFF, parent.blocks[0]);
    }

    @Test void storageReopensAndSeparatesServers() {
        var first = new MapTileStorage(directory, "server:first", null);
        var second = new MapTileStorage(directory, "server:second", null);
        var key = new MapTileKey(0, 0, 0);
        var tile = new MapTileData();
        tile.set(0, 0, 0x112233, 0x445566, 80);
        assertEquals(9, first.save(key, tile.copy()).join().size());
        var reopened = new MapTileStorage(directory, "server:first", null);
        assertArrayEquals(tile.blocks, reopened.load(key).join().blocks);
        assertEquals(0, second.load(key).join().blocks[0]);
        assertEquals(0xFF112233, reopened.load(new MapTileKey(8, 0, 0)).join().blocks[0]);

        var adjacent = new MapTileData();
        adjacent.set(0, 0, 0x778899, 0x112233, 90);
        first.save(new MapTileKey(0, 1, 0), adjacent).join();
        var parent = reopened.load(new MapTileKey(1, 0, 0)).join();
        assertEquals(0xFF112233, parent.blocks[0]);
        assertEquals(0xFF778899, parent.blocks[64]);
    }

    @Test void cacheEvictsOldTilesAndSavesPendingEditsOnExit() {
        var storage = new MapTileStorage(directory, "cache", null);
        var cache = new MapTileCache(storage);
        for (int x = 0; x <= MapTileCache.LIMIT; x++) {
            var key = new MapTileKey(0, x, 0);
            assertNull(cache.get(key));
            storage.flush().join();
            cache.tick(false);
            assertNotNull(cache.get(key));
        }
        assertNull(cache.get(new MapTileKey(0, 0, 0)), "上限を越えた古いタイルは再読込になる");
        var last = new MapTileKey(0, MapTileCache.LIMIT, 0);
        cache.get(last).set(1, 2, 0x123456, 0x654321, 42);
        cache.changed(last);
        cache.close();
        storage.flush().join();
        assertEquals(42, new MapTileStorage(directory, "cache", null).load(last).join().heights[2 * MapTileKey.SIZE + 1]);
    }
}
