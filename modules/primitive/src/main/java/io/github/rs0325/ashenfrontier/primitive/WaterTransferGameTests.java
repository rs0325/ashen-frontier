package io.github.rs0325.ashenfrontier.primitive;

import com.mojang.authlib.GameProfile;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.catalog.NatureCatalog;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import io.github.rs0325.ashenfrontier.core.catalog.RelicContent;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import io.github.rs0325.ashenfrontier.core.recipe.WaterMass;
import io.github.rs0325.ashenfrontier.core.water.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.*;

@GameTestHolder(AshenFrontierPrimitive.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WaterTransferGameTests {
    private static final WaterSourceKey.MapRef MAP = new WaterSourceKey.MapRef("primitive_fixture", 1, "b".repeat(64));
    private static final Map<BlockPos, WaterSourceKey> ACCESS = new HashMap<>();
    private static final List<String> QUALITIES = NatureCatalog.entries().stream().filter(e -> e.kind().equals("water"))
            .map(NatureCatalog.Entry::id).toList();
    private static WaterSourceKey key(String name) { return new WaterSourceKey(MAP, WaterSourceKey.Kind.SURFACE, name); }
    private static boolean fixtureConnected;
    private static void bind(ServerLevel level) {
        var definitions = new ArrayList<WaterSourceDefinition>();
        for (String q : QUALITIES) definitions.add(new WaterSourceDefinition(key(q), AshenFrontierCore.id(q),
                Optional.of(new WaterSourceDefinition.Stock(20_000_000, 10_000_000))));
        definitions.add(new WaterSourceDefinition(key("disk"), AshenFrontierCore.id("raw_water"),
                Optional.of(new WaterSourceDefinition.Stock(4_000_000, 4_000_000))));
        definitions.add(new WaterSourceDefinition(key("restart"), AshenFrontierCore.id("raw_water"),
                Optional.of(new WaterSourceDefinition.Stock(8_000_000, 8_000_000))));
        WaterSourceAccessProvider provider = (l, p, k) ->
                Optional.ofNullable(ACCESS.get(p)).map(a -> new WaterSourceAccessProvider.Access(a, false));
        var file = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),
                level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT))
                .resolve("data").resolve(WaterSources.ID + ".dat");
        if (!fixtureConnected && java.nio.file.Files.notExists(file))
            WaterSources.initializeNewWorld(level, MAP, definitions, provider);
        else WaterSources.bindDefinitions(level, MAP, definitions, provider);
        fixtureConnected = true;
    }

    private static void prepareFixture(net.minecraft.server.level.ServerPlayer player, BlockPos inlet, String name, long target) {
        var level = player.serverLevel();
        long current = WaterTransfers.inspect(level, inlet, WaterSourceKey.Kind.SURFACE).remainingMg().orElseThrow();
        while (current > target) {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PrimitiveContent.item("ceramic_pot")));
            var result = WaterTransfers.sourceToContainer(player, inlet, InteractionHand.MAIN_HAND,
                    WaterMass.toKg(Math.min(current - target, 4_000_000)));
            if (result.movedMg() == 0) throw new AssertionError("fixture採水失敗");
            current -= result.movedMg();
        }
        if (current < target) {
            long from = WaterSources.rechargeThroughTick(level, key(name));
            long to = Math.max(level.getGameTime(), from + 1);
            level.getServer().getWorldData().overworldData().setGameTime(to);
            WaterSources.applyRecharge(level, key(name), AshenFrontierCore.id(name.equals("disk") ? "raw_water" : name),
                    from, to, target - current);
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    /** Dも同じ承認済み有限水源fixtureを使い、本番の配置を先取りしない。 */
    static BlockPos sourceFixture(GameTestHelper h,
                                  ServerPlayer player, String quality, long massMg) {
        bind(h.getLevel());
        var inlet = h.absolutePos(new BlockPos(1, 2, 1));
        ACCESS.put(inlet, key(quality));
        h.getLevel().setBlockAndUpdate(inlet, NatureContent.block(quality).defaultBlockState());
        player.setPos(inlet.getX() + 0.5, inlet.getY() + 1, inlet.getZ() + 0.5);
        prepareFixture(player, inlet, quality, massMg);
        return inlet;
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void sourcePotAndOwnedTankKeepAllEightQualities(GameTestHelper h) {
        var level = h.getLevel(); bind(level);
        var inlet = h.absolutePos(new BlockPos(1, 2, 1));
        var machine = h.absolutePos(new BlockPos(2, 2, 1));
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "water-machine"));
        var other = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "water-other"));
        player.setPos(machine.getX(), machine.getY(), machine.getZ());
        other.setPos(machine.getX(), machine.getY(), machine.getZ());
        for (String q : QUALITIES) {
            level.setBlock(machine, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(machine, PrimitiveContent.SURFACE.get().defaultBlockState(), Block.UPDATE_CLIENTS);
            var owner = (KnappingSurfaceEntity) level.getBlockEntity(machine);
            level.setBlock(inlet, NatureContent.block(q).defaultBlockState(), Block.UPDATE_CLIENTS);
            ACCESS.put(inlet, key(q));
            prepareFixture(player, inlet, q, 10_000_000);
            h.assertTrue(WaterTransfers.sourceToTank(player, inlet, WaterSourceKey.Kind.SURFACE, InteractionHand.MAIN_HAND, owner, 3).movedMg() == 3_000_000, "設備へ3kg");
            h.assertTrue(WaterTransfers.sourceToTank(other, inlet, WaterSourceKey.Kind.SURFACE, InteractionHand.MAIN_HAND, owner, 3).movedMg() == 1_000_000, "他プレイヤーも同じ空きを共有");
            h.assertTrue(WaterTransfers.sourceToTank(other, inlet, WaterSourceKey.Kind.SURFACE, InteractionHand.MAIN_HAND, owner, 1).status() == WaterTransfers.Status.FULL, "満杯で源を引かない");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PrimitiveContent.item("ceramic_pot")));
            h.assertTrue(WaterTransfers.tankToContainer(player, InteractionHand.MAIN_HAND, owner, 4).movedMg() == 4_000_000, "4kgの登録容量を使う");
            var pot = player.getMainHandItem();
            h.assertTrue(pot.get(NatureProperties.QUALITY).equals(AshenFrontierCore.id(q)), "槽から土鍋へ品質を維持");
            h.assertTrue(WaterTransfers.containerToTank(player, InteractionHand.MAIN_HAND, owner, 1).movedMg() == 1_000_000, "部分払出し");
            h.assertTrue(WaterMass.fromKg(pot.get(RelicProperties.CONTENT_MASS)) == 3_000_000, "同ID容器に残り3kg");
            var incompatible = new ItemStack(RelicContent.item("sealed_water_container"));
            if (!q.equals("clean_water")) {
                player.setItemInHand(InteractionHand.MAIN_HAND, incompatible);
                h.assertTrue(WaterTransfers.containerToTank(player, InteractionHand.MAIN_HAND, owner, 1).status() == WaterTransfers.Status.QUALITY_MISMATCH, "異品質は混ぜない");
                h.assertTrue(incompatible.get(RelicProperties.CONTENT_MASS) == 1, "拒否時は容器不変");
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, pot);
            WaterTransfers.containerToTank(player, InteractionHand.MAIN_HAND, owner, 4);
            h.assertTrue(owner.waterTank().contents().massKg() == 4 && pot.get(RelicProperties.CONTENT_MASS) == 0, "往復の総量は4kg");
            var saved = owner.saveWithFullMetadata(level.registryAccess());
            level.removeBlockEntity(machine);
            h.assertTrue(WaterTransfers.sourceToTank(player, inlet, WaterSourceKey.Kind.SURFACE, InteractionHand.MAIN_HAND, owner, 1).status() == WaterTransfers.Status.ACCESS_DENIED, "取り外した設備へ移さない");
            var loaded = (KnappingSurfaceEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                    machine, PrimitiveContent.SURFACE.get().defaultBlockState(), saved, level.registryAccess());
            level.setBlockEntity(loaded);
            h.assertTrue(loaded.waterTank().contents().massKg() == 4, "プレイヤーなしのBE復元でも槽を保持");
            long remaining = WaterTransfers.inspect(level, inlet, WaterSourceKey.Kind.SURFACE).remainingMg().orElseThrow();
            h.assertTrue(remaining + WaterMass.fromKg(loaded.waterTank().contents().massKg()) == 10_000_000, "水源と受取先の総量を維持");
            // 各品質の試験所有物は回収して次のfixtureへ移る。
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PrimitiveContent.item("ceramic_pot")));
            WaterTransfers.tankToContainer(player, InteractionHand.MAIN_HAND, loaded, 4);
            level.setBlock(machine, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(inlet, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        h.succeed();
    }

    // gameTimeを進めるfixtureから、非同期の実チャンク再読込みを分離する。
    @GameTest(template = "empty", templateNamespace = AshenFrontierPrimitive.MOD_ID, timeoutTicks = 220, batch = "water_transfer_disk")
    public static void actualChunkUnloadAndDiskLoadKeepTheTankAndZeroSource(GameTestHelper h) {
        var level = h.getLevel(); bind(level);
        var far = h.absolutePos(new BlockPos(1025, 2, 1));
        var inlet = far.above(16);
        int cx = far.getX() >> 4, cz = far.getZ() >> 4;
        h.assertTrue(!level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(cx, cz)), "試験用の専用チャンク");
        level.setChunkForced(cx, cz, true);
        level.getChunkAt(far);
        level.setBlock(far, PrimitiveContent.SURFACE.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(inlet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL)
            level.setBlock(inlet.relative(direction), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(inlet, NatureContent.block("raw_water").defaultBlockState(), Block.UPDATE_CLIENTS);
        ACCESS.put(inlet, key("disk"));
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "water-unload"));
        player.setPos(far.getX(), far.getY(), far.getZ());
        prepareFixture(player, inlet, "disk", 4_000_000);
        var original = (KnappingSurfaceEntity) level.getBlockEntity(far);
        WaterTransfers.sourceToTank(player, inlet, WaterSourceKey.Kind.SURFACE, InteractionHand.MAIN_HAND, original, 4);
        player.discard();
        level.getDataStorage().save();
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        level.getChunkSource().save(true);
        level.setChunkForced(cx, cz, false);
        ChunkReloadFixture.afterUnload(h, original, () -> {
            try {
                h.assertTrue(level.getChunkSource().getChunkNow(cx, cz) == null, "実際にアンロードされた");
                level.getChunkAt(far);
                var restored = (KnappingSurfaceEntity) level.getBlockEntity(far);
                h.assertTrue(restored != original && restored.waterTank().contents().massKg() == 4, "ディスクから別BEに4kgを復元");
                bind(level);
                h.assertTrue(WaterTransfers.inspect(level, inlet, WaterSourceKey.Kind.SURFACE).remainingMg().orElseThrow() == 0, "読み直しても源は0");
                h.succeed();
            } finally {
                level.setBlock(far, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                level.setBlock(inlet, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                level.setChunkForced(cx, cz, false);
            }
        });
    }
    @GameTest(template = "empty", templateNamespace = AshenFrontierPrimitive.MOD_ID, timeoutTicks = 220)
    public static void normalRestartKeepsSourceContainerAndTankTotal(GameTestHelper h) {
        var level = h.getLevel(); bind(level);
        var pos = new BlockPos(32776, -58, 32776);
        var inlet = pos.east();
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        level.setChunkForced(cx, cz, true);
        level.getChunkAt(pos);
        ACCESS.put(inlet, key("restart"));
        var player = FakePlayerFactory.get(level, new GameProfile(
                UUID.fromString("d5caef5b-64d0-4b10-b8dc-1907932430a2"), "water-restart"));
        boolean restored = level.getServer().getPlayerList().load(player).isPresent();
        player.setPos(pos.getX(), pos.getY(), pos.getZ());
        if (!restored) {
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(pos, PrimitiveContent.SURFACE.get().defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(inlet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                if (inlet.relative(direction).equals(pos)) continue;
                level.setBlock(inlet.relative(direction), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            level.setBlock(inlet, NatureContent.block("raw_water").defaultBlockState(), Block.UPDATE_CLIENTS);
            var owner = (KnappingSurfaceEntity) level.getBlockEntity(pos);
            h.assertTrue(WaterTransfers.sourceToTank(player, inlet, WaterSourceKey.Kind.SURFACE, InteractionHand.MAIN_HAND, owner, 4).movedMg() == 4_000_000, "初回に槽へ4kg");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PrimitiveContent.item("ceramic_pot")));
            h.assertTrue(WaterTransfers.sourceToContainer(player, inlet, InteractionHand.MAIN_HAND, 2).movedMg() == 2_000_000, "初回に携帯2kg");
        }
        var owner = (KnappingSurfaceEntity) level.getBlockEntity(pos);
        long source = WaterTransfers.inspect(level, inlet, WaterSourceKey.Kind.SURFACE).remainingMg().orElseThrow();
        h.assertTrue(owner != null && source == 2_000_000
                && WaterMass.fromKg(owner.waterTank().contents().massKg()) == 4_000_000
                && WaterMass.fromKg(player.getMainHandItem().get(RelicProperties.CONTENT_MASS)) == 2_000_000,
                "水源2kg・実BE槽4kg・プレイヤー容器2kgを維持");
        h.assertTrue(player.getMainHandItem().get(NatureProperties.QUALITY).equals(AshenFrontierCore.id("raw_water"))
                && owner.waterTank().contents().quality().orElseThrow().equals(AshenFrontierCore.id("raw_water")), "再起動後も水質を維持");
        level.getServer().getPlayerList().remove(player);
        h.assertTrue(java.nio.file.Files.exists(level.getServer().getWorldPath(
                net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).resolve(player.getStringUUID() + ".dat")),
                "標準プレイヤーファイルを実保存");
        level.getDataStorage().save();
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        level.getChunkSource().save(true);
        player.discard();
        level.setChunkForced(cx, cz, false);
        ChunkReloadFixture.afterUnload(h, owner, () -> {
            h.assertTrue(level.getChunkSource().getChunkNow(cx, cz) == null, "再起動fixtureを実アンロードして残す");
            AshenFrontierCore.LOGGER.info("WATER_RESTART_PROBE restored_from_previous_jvm={} source_mg=2000000 tank_mg=4000000 container_mg=2000000 total_mg=8000000", restored);
            h.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void tankRefusalDistinguishesQualityFromCapacity(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(1, 2, 1));
        level.setBlock(pos, PrimitiveContent.SURFACE.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        var owner = (KnappingSurfaceEntity) level.getBlockEntity(pos);
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "water-refusal"));
        player.setPos(pos.getX(), pos.getY(), pos.getZ());
        owner.waterTank().fill(player, AshenFrontierCore.id("clean_water"), 4);
        var pot = new ItemStack(PrimitiveContent.item("ceramic_pot"));
        pot.set(RelicProperties.CONTENT_MASS, 1.0);
        pot.set(NatureProperties.QUALITY, AshenFrontierCore.id("raw_water"));
        player.setItemInHand(InteractionHand.OFF_HAND, pot);
        var mismatch = WaterTransfers.tankToContainer(player, InteractionHand.OFF_HAND, owner, 1);
        h.assertTrue(mismatch.status() == WaterTransfers.Status.QUALITY_MISMATCH && mismatch.movedMg() == 0, "空きがある異品質はFULLではない");
        var message = (net.minecraft.network.chat.contents.TranslatableContents) mismatch.message().getContents();
        h.assertTrue(message.getKey().equals("water.ashen_frontier.quality_mismatch"), "異品質の日英案内を選ぶ");
        h.assertTrue(owner.waterTank().contents().massKg() == 4 && pot.get(RelicProperties.CONTENT_MASS) == 1, "拒否時に双方不変");
        pot.set(NatureProperties.QUALITY, AshenFrontierCore.id("clean_water"));
        pot.set(RelicProperties.CONTENT_MASS, 4.0);
        h.assertTrue(WaterTransfers.tankToContainer(player, InteractionHand.OFF_HAND, owner, 1).status() == WaterTransfers.Status.FULL, "同品質の容量不足はFULL");
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierPrimitive.MOD_ID, timeoutTicks = 220)
    public static void pendingUnloadDoesNotCountAsADiskReload(GameTestHelper h) {
        var level = h.getLevel();
        var far = h.absolutePos(new BlockPos(2049, 2, 1));
        var cache = level.getChunkSource();
        int cx = far.getX() >> 4, cz = far.getZ() >> 4;
        level.setChunkForced(cx, cz, true);
        var chunk = level.getChunkAt(far);
        level.setBlock(far, PrimitiveContent.SURFACE.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        var original = level.getBlockEntity(far);
        cache.save(true);
        level.setChunkForced(cx, cz, false);
        // unloadQueueの実行予算だけを使い切り、CIで観測した不可視・未破棄の状態を作る。
        for (int n = 0; n < 4; n++) cache.tick(() -> false, false);
        h.assertTrue(cache.getChunkNow(cx, cz) == null && !original.isRemoved(), "不可視でも元BEはpendingUnloadsに残る");
        h.assertTrue(level.getChunkAt(far) == chunk && level.getBlockEntity(far) == original,
                "この段階の再要求はディスクでなく元のチャンクを復帰させる");
        for (int n = 0; n < 4; n++) cache.tick(() -> false, false);
        h.assertTrue(cache.getChunkNow(cx, cz) == null && !original.isRemoved(), "再び実アンロードを保留");
        AshenFrontierCore.LOGGER.info("PENDING_UNLOAD_REPRO visible=false original_removed={} early_reload_same=true", original.isRemoved());
        ChunkReloadFixture.afterUnload(h, original, () -> {
            try {
                var reloaded = level.getChunkAt(far);
                h.assertTrue(reloaded != chunk && level.getBlockEntity(far) != original,
                        "待機後はpendingUnloadsを復帰させずディスクから新しいチャンク/BEを読む");
                h.succeed();
            } finally {
                level.setBlock(far, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                level.setChunkForced(cx, cz, false);
            }
        });
    }

}
