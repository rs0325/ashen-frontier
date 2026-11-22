package io.github.rs0325.ashenfrontier.world;

import com.mojang.authlib.GameProfile;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MapGameTests {
    private MapGameTests() {}

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, skyAccess = true)
    public static void actualBlocksHeightAndWaterDepth(GameTestHelper helper) {
        var level = helper.getLevel();
        helper.setBlock(5, 8, 5, Blocks.GRANITE);
        helper.setBlock(6, 9, 5, Blocks.LAPIS_BLOCK);
        helper.setBlock(5, 5, 6, Blocks.STONE);
        for (int y = 6; y <= 8; y++) helper.setBlock(5, y, 6, Blocks.WATER);
        var tiles = new HashMap<MapTileKey, MapTileData>();
        for (var relative : new BlockPos[]{new BlockPos(5, 8, 5), new BlockPos(6, 9, 5), new BlockPos(5, 8, 6)}) {
            var pos = helper.absolutePos(relative);
            var key = MapTileKey.at(0, pos.getX(), pos.getZ());
            var data = tiles.computeIfAbsent(key, ignored -> new MapTileData());
            MapChunkSampler.sample(level, level.getChunkAt(pos), data);
        }
        for (var relative : new BlockPos[]{new BlockPos(5, 8, 5), new BlockPos(6, 9, 5), new BlockPos(5, 8, 6)}) {
            var pos = helper.absolutePos(relative);
            var data = tiles.get(MapTileKey.at(0, pos.getX(), pos.getZ()));
            int at = MapTileKey.pixel(0, pos.getZ()) * MapTileKey.SIZE + MapTileKey.pixel(0, pos.getX());
            double slope = (level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX() - 1, pos.getZ()) - 1
                    + level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ() - 1) - 1) / 2.0 - pos.getY();
            int depth = relative.getZ() == 6 ? 3 : 0;
            int color = level.getBlockState(pos).getMapColor(level, pos).col;
            helper.assertTrue(data.heights[at] == pos.getY(), "元画素の高さは実ブロック位置と一致");
            helper.assertTrue(data.blocks[at] == (0xFF000000 | MapViewMath.blockColor(color, depth, slope)),
                    "MapColor・実水深・隣接列の陰影を使う");
            helper.assertTrue(data.relief[at] == (0xFF000000 | MapViewMath.color(pos.getY(), depth, slope)),
                    "高度モードも同じ実地表と水深から作る");
        }
        var pos = helper.absolutePos(new BlockPos(5, 8, 5));
        var data = tiles.get(MapTileKey.at(0, pos.getX(), pos.getZ()));
        int at = MapTileKey.pixel(0, pos.getZ()) * MapTileKey.SIZE + MapTileKey.pixel(0, pos.getX());
        int previous = data.blocks[at];
        helper.setBlock(5, 8, 5, Blocks.LAPIS_BLOCK);
        MapChunkSampler.sample(level, level.getChunkAt(pos), data);
        helper.assertTrue(previous != data.blocks[at], "実ブロックの変更を再計算へ反映");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void teleportPermissionsAndGameModes(GameTestHelper helper) {
        var level = helper.getLevel();
        var preset = level.registryAccess().registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(
                ResourceKey.create(Registries.WORLD_PRESET, AshenFrontierCore.id("fixed_map")));
        helper.assertTrue(preset.value().createWorldDimensions().overworld() instanceof FixedMapChunkGenerator,
                "検査対象に固定マップ生成器が登録されている");
        for (int permission : new int[]{0, 1, 2, 4}) {
            for (var mode : GameType.values()) {
                var player = new ServerPlayer(level.getServer(), level,
                        new GameProfile(UUID.randomUUID(), "map-test"), ClientInformation.createDefault()) {
                    @Override protected int getPermissionLevel() { return permission; }
                };
                new ServerGamePacketListenerImpl(level.getServer(),
                        new Connection(PacketFlow.SERVERBOUND), player,
                        CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
                    @Override public void send(Packet<?> packet) {}
                };
                player.setGameMode(mode);
                boolean allowed = permission >= 2 && mode == GameType.CREATIVE;
                helper.assertTrue(MapTeleportRequest.isAllowed(player) == allowed,
                        "レベル2以上かつクリエイティブだけを許可: " + permission + " / " + mode);
                var before = player.position();
                if (!allowed) helper.assertTrue(!new MapTeleportRequest(0, 0, 100, true).teleport(player),
                        "権限なし・サバイバル・アドベンチャー・観戦の要求を拒否");
                helper.assertTrue(!new MapTeleportRequest(MapCoordinates.MAX, 0, 100, false).teleport(player),
                        "境界外の要求は拒否");
                helper.assertTrue(!new MapTeleportRequest(0, 0, Integer.MAX_VALUE, false).teleport(player),
                        "Y範囲外の要求は拒否");
                helper.assertTrue(before.equals(player.position()), "拒否された移動は現在地を変えない");
            }
        }
        helper.assertTrue(new MapTeleportRequest(0, 0, level.getMinBuildHeight(), false).withinBounds(level),
                "下限Yは有効");
        helper.assertTrue(new MapTeleportRequest(0, 0, level.getMaxBuildHeight() - 2, false).withinBounds(level),
                "頭部まで入る上限Yは有効");
        helper.assertTrue(!new MapTeleportRequest(0, 0, level.getMinBuildHeight() - 1, false).withinBounds(level),
                "下限未満Yは無効");
        helper.assertTrue(!new MapTeleportRequest(0, 0, level.getMaxBuildHeight() - 1, false).withinBounds(level),
                "頭部が上限を越えるYは無効");
        for (int edge : new int[]{MapCoordinates.MIN - 1, MapCoordinates.MAX}) {
            helper.assertTrue(!new MapTeleportRequest(edge, 0, 100, true).withinBounds(level), "X境界外");
            helper.assertTrue(!new MapTeleportRequest(0, edge, 100, true).withinBounds(level), "Z境界外");
        }
        for (int edge : new int[]{MapCoordinates.MIN, MapCoordinates.MAX - 1}) {
            helper.assertTrue(new MapTeleportRequest(edge, 0, 100, true).withinBounds(level), "X境界内");
            helper.assertTrue(new MapTeleportRequest(0, edge, 100, true).withinBounds(level), "Z境界内");
        }
        helper.succeed();
    }
}
