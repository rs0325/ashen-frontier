package io.github.rs0325.ashenfrontier.world;

import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapVersionTest {
    @Test
    void codecPreservesSelectedVersionAndManifestHash() {
        var version = new MapVersion("earth_001", 7, "0123456789abcdef");
        var encoded = MapVersion.CODEC.encodeStart(JsonOps.INSTANCE, version).getOrThrow();
        assertEquals(version, MapVersion.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        assertEquals("earth_001", encoded.getAsJsonObject().get("map_version").getAsString());
        assertEquals(7, encoded.getAsJsonObject().get("format_version").getAsInt());
    }
}
