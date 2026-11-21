package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonParser;
import io.github.rs0325.ashenfrontier.core.catalog.NatureCatalog;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class UndergroundLayoutTest {
    private UndergroundLayout layout() {
        return UndergroundLayout.read(new MapVersion("earth_001", 1, "unit-test"));
    }

    @Test
    void fixedAnchorsHaveReachableResourcesAndFacilityPortals() throws Exception {
        var layout = layout();
        try (var stream = getClass().getResourceAsStream("/data/ashen_frontier/fixed_map/earth_001/index.json")) {
            var index = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var ids = new HashSet<String>();
            for (var entry : layout.deposits()) assertTrue(ids.add(entry.id()), entry.id());
            for (var value : index.getAsJsonArray("deposits")) {
                var anchor = value.getAsJsonObject();
                var deposit = layout.deposits().stream().filter(d -> d.id().equals(anchor.get("id").getAsString())).findFirst().orElseThrow();
                assertTrue(deposit.required());
                var xz = anchor.getAsJsonArray("xz");
                assertTrue(Math.hypot(deposit.x() - xz.get(0).getAsInt(), deposit.z() - xz.get(1).getAsInt()) <= anchor.get("radius_blocks").getAsInt());
                if (Map.of("mining_iron", 1, "mining_copper", 1, "mining_coal", 1).containsKey(deposit.id())) {
                    assertTrue(Math.hypot(deposit.x() - Math.max(96, deposit.depth() + 24) + 10000, deposit.z() + 10650) < 1000);
                }
            }
            var facilities = new HashSet<String>();
            for (var value : index.getAsJsonArray("facilities")) facilities.add(value.getAsJsonObject().get("id").getAsString());
            for (var space : layout.spaces()) {
                assertTrue(facilities.contains(space.facility()));
                assertTrue(space.height() >= 3);
                assertTrue(space.depth() > 0);
            }
            assertEquals(4, layout.spaces().stream().map(UndergroundLayout.Space::kind).distinct().count());
        }
    }

    @Test
    void onlyRegisteredHostPairsAndBoundedShapesAreUsed() {
        var entries = NatureCatalog.entries().stream()
                .collect(Collectors.toMap(NatureCatalog.Entry::id, entry -> entry));
        int oils = 0;
        for (var deposit : layout().deposits()) {
            assertTrue(List.of("ellipsoid", "vein", "layer").contains(deposit.shape()));
            assertEquals(3, deposit.radii().size());
            assertTrue(deposit.radii().stream().allMatch(r -> r > 0 && r <= 32));
            assertTrue(deposit.x() < 0 && deposit.z() < 0);
            if (Boolean.TRUE.equals(deposit.vanilla())) continue;
            var entry = entries.get(deposit.block());
            assertNotNull(entry, deposit.id());
            if (entry.host() != null) {
                assertEquals(entry.host(), deposit.host().replace("minecraft:", ""));
            }
            if (entry.kind().equals("oil")) oils++;
        }
        assertEquals(4, oils);
    }
}
