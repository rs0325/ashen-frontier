package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 専用initスクリプトでだけ読み込む、実列の地図標本化。配布jarには含めない。 */
@GameTestHolder("ashen_frontier_map_render")
@PrefixGameTestTemplate(false)
public final class MapRenderGameTest {
    private static final class Sampling implements AutoCloseable {
        final String name;
        final int x0, z0, step, width, height;
        final DataOutputStream stream;
        final Map<Block, Integer> ids = new HashMap<>();
        final List<String> palette = new ArrayList<>();
        int row;

        Sampling(Path out, JsonObject job) throws IOException {
            name = job.get("name").getAsString();
            var bounds = job.getAsJsonArray("bounds");
            x0 = bounds.get(0).getAsInt();
            z0 = bounds.get(1).getAsInt();
            step = job.get("step").getAsInt();
            width = (bounds.get(2).getAsInt() - x0) / step;
            height = (bounds.get(3).getAsInt() - z0) / step;
            stream = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(out.resolve(name + ".bin")), 1 << 20));
            stream.writeInt(0x4d415031);
            stream.writeInt(x0);
            stream.writeInt(z0);
            stream.writeInt(step);
            stream.writeInt(width);
            stream.writeInt(height);
        }

        int id(Block block) {
            return ids.computeIfAbsent(block, b -> {
                int index = palette.size();
                palette.add(BuiltInRegistries.BLOCK.getKey(b).toString());
                return index;
            });
        }

        void sample(FixedTerrain terrain) throws IOException {
            int z = z0 + row * step + step / 2;
            for (int col = 0; col < width; col++) {
                int x = x0 + col * step + step / 2;
                var column = terrain.column(x, z);
                int ground = column.surfaceY(), top = column.topY();
                var surface = column.state(ground);
                var cap = column.state(top);
                boolean frozen = top > ground
                        && BuiltInRegistries.BLOCK.getKey(cap.getBlock()).getPath().endsWith("_ice");
                boolean water = top > ground && (!cap.getFluidState().isEmpty() || frozen);
                int flags = water ? (terrain.map().coastDistance(x, z) < 0 ? 1 : 2) : 0;
                if (surface.is(Blocks.SNOW_BLOCK) || surface.is(Blocks.POWDER_SNOW) || cap.is(Blocks.SNOW)) flags |= 4;
                if (surface.is(Blocks.PACKED_ICE) || surface.is(Blocks.BLUE_ICE)) flags |= 8;
                if (frozen) flags |= 16;
                stream.writeShort(ground);
                stream.writeShort(top);
                stream.writeShort(id(surface.getBlock()));
                stream.writeShort(id(cap.getBlock()));
                stream.writeByte(flags);
            }
            row++;
            if (row % 128 == 0 || row == height) {
                LogUtils.getLogger().info("FIXED_MAP_RENDER {} row={}/{}", name, row, height);
            }
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 120000)
    public static void sampleMap(GameTestHelper helper) throws Exception {
        var requestPath = Path.of(System.getProperty("ashen.fixedMapRenderRequest"));
        var request = JsonParser.parseString(Files.readString(requestPath, StandardCharsets.UTF_8)).getAsJsonObject();
        var out = requestPath.getParent();
        var key = ResourceKey.create(Registries.WORLD_PRESET, AshenFrontierCore.id("fixed_map"));
        var generator = (FixedMapChunkGenerator) helper.getLevel().registryAccess()
                .registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(key)
                .value().createWorldDimensions().overworld();
        var terrain = generator.terrain();
        var anchors = new StringBuilder("id,x,z,ground_y,top_y,surface_block,top_block\n");
        for (var value : request.getAsJsonObject("layout").getAsJsonArray("facilities")) {
            var facility = value.getAsJsonObject();
            var xz = facility.getAsJsonArray("xz");
            int x = xz.get(0).getAsInt(), z = xz.get(1).getAsInt();
            var column = terrain.column(x, z);
            anchors.append(facility.get("id").getAsString()).append(',').append(x).append(',').append(z)
                    .append(',').append(column.surfaceY()).append(',').append(column.topY()).append(',')
                    .append(BuiltInRegistries.BLOCK.getKey(column.state(column.surfaceY()).getBlock())).append(',')
                    .append(BuiltInRegistries.BLOCK.getKey(column.state(column.topY()).getBlock())).append('\n');
        }
        Files.writeString(out.resolve("anchors.csv"), anchors, StandardCharsets.UTF_8);
        var jobs = request.getAsJsonArray("jobs");
        Sampling[] current = {new Sampling(out, jobs.get(0).getAsJsonObject())};
        int[] index = {0};
        long start = System.nanoTime();
        helper.onEachTick(() -> {
            try {
                var job = current[0];
                for (int n = 0; n < 8 && job.row < job.height; n++) job.sample(terrain);
                if (job.row == job.height) {
                    job.close();
                    Files.write(out.resolve(job.name + "-palette.txt"), job.palette, StandardCharsets.UTF_8);
                    index[0]++;
                    if (index[0] == jobs.size()) {
                        var result = new JsonObject();
                        result.addProperty("token", request.get("token").getAsString());
                        result.addProperty("seconds", (System.nanoTime() - start) / 1e9);
                        Files.writeString(out.resolve("sampling-complete.json"), result.toString(), StandardCharsets.UTF_8);
                        helper.succeed();
                    } else {
                        current[0] = new Sampling(out, jobs.get(index[0]).getAsJsonObject());
                    }
                }
            } catch (IOException e) {
                try { current[0].close(); } catch (IOException close) { e.addSuppressed(close); }
                throw new UncheckedIOException(e);
            }
        });
    }
}
