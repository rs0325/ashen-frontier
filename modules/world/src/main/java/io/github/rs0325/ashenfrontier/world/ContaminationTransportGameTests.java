package io.github.rs0325.ashenfrontier.world;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.SoilContamination;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.Map;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ContaminationTransportGameTests {
    private static final SoilContamination FIRST = new SoilContamination(.0002, .00002, .6, 4.8, 16000, 4e7);
    private static final SoilContamination SECOND = new SoilContamination(.001, .00004, 1.2, 3.8, 64000, 8e8);

    private static boolean recorded(ServerLevel level, BlockPos pos) {
        var chunk = level.getChunkAt(pos);
        return chunk.hasData(BlockContamination.CHANGES) && chunk.getData(BlockContamination.CHANGES).containsKey(pos.asLong());
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void explosionTransfersDropsAndRemovesEmptyRecord(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(3, 3, 3));
        level.setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
        BlockContamination.set(level, pos, FIRST);
        var drops = new ArrayList<ItemStack>();
        var explosion = new Explosion(level, null, pos.getX(), pos.getY(), pos.getZ(), 4, false, Explosion.BlockInteraction.DESTROY);
        level.getBlockState(pos).onExplosionHit(level, pos, explosion, (stack, origin) -> drops.add(stack));
        helper.assertTrue(level.getBlockState(pos).isAir() && drops.size() == 1, "爆発の実ドロップ経路が土を回収");
        helper.assertTrue(BlockContamination.readItem(drops.getFirst()).stages().equals(FIRST.stages()), "爆発回収でも全指標が一致");
        helper.assertTrue(!recorded(level, pos), "爆発後の空気に記録を残さない");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void numericM1ComponentsRemainIndependentOfSoilStages(GameTestHelper helper) {
        var food = new ItemStack(Items.APPLE);
        food.set(ItemProperties.CONTAMINATION, ItemProperties.Contamination.of(Map.of("lead", .0002)));
        food.set(ItemProperties.RADIOACTIVITY, new ItemProperties.Radioactivity("cs137", 8000, 0));
        var ops = helper.getLevel().registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        var saved = ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, food).getOrThrow()).getOrThrow();
        helper.assertTrue(ItemStack.isSameItemSameComponents(food, saved), "食品の数値汚染・核種成分は従来のまま保存");
        helper.assertTrue(saved.get(ItemProperties.CONTAMINATION).amounts().get("lead") == .0002
                && saved.get(ItemProperties.RADIOACTIVITY).becquerels() == 8000, "M1成分の値を量子化しない");
        var stack = new ItemStack(Items.DIRT);
        helper.assertTrue(BlockContamination.readItem(stack).equals(SoilContamination.CLEAN), "土の段階成分がなければ背景値");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void stickyPistonMovesDistinctValuesBothWays(GameTestHelper helper) {
        var level = helper.getLevel();
        var base = new BlockPos(11500, 120, 11500);
        var one = base.east(); var two = base.east(2); var three = base.east(3);
        level.setBlockAndUpdate(base, Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        for (var pos : new BlockPos[]{one,two,three}) level.setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
        BlockContamination.set(level, one, FIRST);
        BlockContamination.set(level, two, SECOND);
        BlockContamination.set(level, three, SoilContamination.CLEAN);
        level.removeBlock(three, false);
        level.setBlockAndUpdate(base.below(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.assertTrue(level.getBlockState(base).triggerEvent(level, base, 0, Direction.EAST.get3DDataValue()), "実際の伸長イベントが成功");
        finishPistons(level, one, two, three);
        helper.assertTrue(BlockContamination.get(level, two).orElseThrow().equals(FIRST), "押した列の先頭を転写");
        helper.assertTrue(BlockContamination.get(level, three).orElseThrow().equals(SECOND), "後方の異なる値を上書きせず転写");
        helper.assertTrue(!recorded(level, one), "元位置のピストンヘッドには記録なし");
        level.removeBlock(base.below(), false);
        helper.assertTrue(level.getBlockState(base).triggerEvent(level, base, 1, Direction.EAST.get3DDataValue()), "実際の収縮イベントが成功");
        finishPistons(level, base, one, two);
        helper.assertTrue(BlockContamination.get(level, one).orElseThrow().equals(FIRST), "粘着ピストンの引戻しも保持");
        helper.assertTrue(level.getBlockState(two).isAir() && !recorded(level, two), "引戻し元の空気に記録なし");
        helper.assertTrue(BlockContamination.get(level, three).orElseThrow().equals(SECOND), "動かなかった土は不変");
        level.removeBlock(base, false);
        helper.succeed();
    }

    private static void finishPistons(ServerLevel level, BlockPos... positions) {
        for (int tick = 0; tick < 4; tick++) for (var pos : positions)
            if (level.getBlockEntity(pos) instanceof PistonMovingBlockEntity moving)
                PistonMovingBlockEntity.tick(level, pos, level.getBlockState(pos), moving);
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void pistonFailureAndDestructionKeepRecordsConsistent(GameTestHelper helper) {
        var level = helper.getLevel();
        var base = new BlockPos(11600, 120, 11500);
        var pos = base.east();
        level.setBlockAndUpdate(base, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
        BlockContamination.set(level, pos, FIRST);
        level.setBlockAndUpdate(base.east(2), Blocks.OBSIDIAN.defaultBlockState());
        level.setBlockAndUpdate(base.below(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.assertTrue(!level.getBlockState(base).triggerEvent(level, base, 0, Direction.EAST.get3DDataValue()), "黒曜石が押せずに失敗する");
        helper.assertTrue(BlockContamination.get(level, pos).orElseThrow().equals(FIRST), "失敗した移動は値を変えない");
        level.removeBlock(base.east(2), false);
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos, Blocks.SUSPICIOUS_SAND.defaultBlockState());
        BlockContamination.set(level, pos, FIRST);
        var dropsRule = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS);
        boolean dropsEnabled = dropsRule.get();
        dropsRule.set(false, level.getServer());
        try {
            helper.assertTrue(level.getBlockState(base).triggerEvent(level, base, 0, Direction.EAST.get3DDataValue()), "押し壊しの伸長イベントが成功");
            finishPistons(level, pos);
            helper.assertTrue(!level.getBlockState(pos).is(Blocks.SUSPICIOUS_SAND), "実際の押し壊し反応で対象が消える");
            helper.assertTrue(!recorded(level, pos), "ドロップ無効でも押し壊した元位置に記録を残さない");
        } finally {
            dropsRule.set(dropsEnabled, level.getServer());
        }
        level.removeBlock(base.below(), false);
        level.removeBlock(base, false);
        helper.succeed();
    }

    private static Goal endermanGoal(EnderMan enderman, String name) throws ReflectiveOperationException {
        var constructor = Class.forName("net.minecraft.world.entity.monster.EnderMan$" + name).getDeclaredConstructor(EnderMan.class);
        constructor.setAccessible(true);
        return (Goal) constructor.newInstance(enderman);
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void endermanPickupSavePlaceAndDeathKeepValues(GameTestHelper helper) throws ReflectiveOperationException {
        var level = helper.getLevel();
        var center = helper.absolutePos(new BlockPos(3, 3, 3));
        for (int x = -2; x <= 2; x++) for (int y = 0; y < 3; y++) for (int z = -2; z <= 2; z++) {
            var pos = center.offset(x,y,z);
            level.setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
            BlockContamination.set(level, pos, FIRST);
        }
        var enderman = EntityType.ENDERMAN.create(level);
        enderman.setPos(center.getX() + .5, center.getY(), center.getZ() + .5);
        enderman.getRandom().setSeed(79);
        var take = endermanGoal(enderman, "EndermanTakeBlockGoal");
        for (int i = 0; i < 1000 && enderman.getCarriedBlock() == null; i++) take.tick();
        helper.assertTrue(enderman.getCarriedBlock() != null, "実際の運搬AIが土を持ち上げる");
        int removed = 0;
        for (int x = -2; x <= 2; x++) for (int y = 0; y < 3; y++) for (int z = -2; z <= 2; z++) {
            var pos = center.offset(x,y,z);
            if (level.getBlockState(pos).isAir()) {
                removed++;
                helper.assertTrue(!recorded(level, pos), "持上げ元の空気に記録なし");
            }
        }
        helper.assertTrue(removed == 1, "持ち上げた1ブロックだけが消える");
        var saved = new CompoundTag();
        enderman.saveWithoutId(saved);
        var loaded = EntityType.ENDERMAN.create(level);
        loaded.load(saved);
        var destination = helper.absolutePos(new BlockPos(10, 3, 10));
        loaded.setPos(destination.getX() + .5, destination.getY(), destination.getZ() + .5);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            level.setBlockAndUpdate(destination.offset(x,-1,z), Blocks.STONE.defaultBlockState());
        var place = endermanGoal(loaded, "EndermanLeaveBlockGoal");
        for (int i = 0; i < 1000 && loaded.getCarriedBlock() != null; i++) place.tick();
        helper.assertTrue(loaded.getCarriedBlock() == null, "保存後の実際の設置AIが土を置く");
        int placed = 0;
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            var pos = destination.offset(x,0,z);
            if (level.getBlockState(pos).is(Blocks.DIRT)) {
                placed++;
                helper.assertTrue(BlockContamination.get(level, pos).orElseThrow().equals(FIRST), "運搬中の保存と設置で値を保持");
            }
        }
        helper.assertTrue(placed == 1, "運んだ1ブロックだけを設置");
        var dying = EntityType.ENDERMAN.create(level);
        dying.load(saved);
        var death = helper.absolutePos(new BlockPos(7, 8, 7));
        dying.setPos(death.getX() + .5, death.getY(), death.getZ() + .5);
        dying.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(death).inflate(2), entity -> entity.getItem().is(Items.DIRT));
        helper.assertTrue(drops.size() == 1 && BlockContamination.readItem(drops.getFirst().getItem()).stages().equals(FIRST.stages()), "運搬状態を保存した個体の死亡回収でも保持");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void fluidDestructionAndDispenserEjectionKeepItemComponents(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(3, 3, 3));
        var layer = BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("silt_layer"));
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos, layer.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1));
        BlockContamination.set(level, pos, FIRST);
        level.setBlockAndUpdate(pos.above(), Blocks.WATER.defaultBlockState());
        Fluids.WATER.tick(level, pos.above(), level.getFluidState(pos.above()));
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2));
        helper.assertTrue(level.getBlockState(pos).getFluidState().isSource() || !level.getFluidState(pos).isEmpty(), "実際の液体更新が薄層を破壊");
        helper.assertTrue(drops.stream().anyMatch(entity -> entity.getItem().is(layer.asItem())
                && BlockContamination.readItem(entity.getItem()).stages().equals(FIRST.stages())), "液体破壊による回収を保持");
        helper.assertTrue(!recorded(level, pos), "液体への置換後に土の記録なし");
        var dispenser = helper.absolutePos(new BlockPos(10, 4, 10));
        level.setBlockAndUpdate(dispenser, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        var inventory = (DispenserBlockEntity) level.getBlockEntity(dispenser);
        var stack = new ItemStack(Items.DIRT);
        BlockContamination.writeItem(stack, SECOND);
        inventory.setItem(0, stack);
        level.getBlockState(dispenser).tick(level, dispenser, level.random);
        var ejected = level.getEntitiesOfClass(ItemEntity.class, new AABB(dispenser).inflate(2), entity -> entity.getItem().is(Items.DIRT));
        helper.assertTrue(ejected.size() == 1 && BlockContamination.readItem(ejected.getFirst().getItem()).stages().equals(SECOND.stages()), "バニラの土のディスペンサー排出も成分を保持");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void fallingSandBrokenOnLandingKeepsDrops(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(8, 8, 8));
        level.setBlockAndUpdate(origin.below(5), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(origin.below(4), Blocks.TORCH.defaultBlockState());
        level.setBlockAndUpdate(origin, Blocks.SAND.defaultBlockState());
        BlockContamination.set(level, origin, FIRST);
        var falling = FallingBlockEntity.fall(level, origin, level.getBlockState(origin));
        for (int i = 0; i < 80 && falling.isAlive(); i++) falling.tick();
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(origin.below(4)).inflate(2), entity -> entity.getItem().is(Items.SAND));
        helper.assertTrue(drops.size() == 1 && BlockContamination.readItem(drops.getFirst().getItem()).stages().equals(FIRST.stages()), "着地できず壊れた落下砂のドロップも保持");
        helper.succeed();
    }

    private static boolean unloaded(ServerLevel level, BlockPos pos) {
        var manager = level.getChunkSource().chunkMap;
        long key = net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        try {
            for (String name : new String[]{"updatingChunkMap", "visibleChunkMap", "pendingUnloads"}) {
                var field = manager.getClass().getDeclaredField(name);
                field.setAccessible(true);
                if (((it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>) field.get(manager)).containsKey(key)) return false;
            }
            return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("アンロード試験のチャンク管理状態を確認できません", exception);
        }
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 600)
    public static void realChunkUnloadReloadCleanAndDimensionIsolation(GameTestHelper helper) {
        var level = helper.getLevel();
        var nether = level.getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "独立した別ディメンションが存在する");
        var pos = new BlockPos(12005, 90, 12005);
        var original = level.getChunkAt(pos);
        var other = nether.getChunkAt(pos);
        level.setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
        nether.setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
        BlockContamination.set(level, pos, FIRST);
        BlockContamination.set(nether, pos, SECOND);
        level.getChunkSource().save(true);
        nether.getChunkSource().save(true);
        var reloaded = new net.minecraft.world.level.chunk.LevelChunk[1];
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(unloaded(level, pos), "通常チャンクが実際にアンロードされる");
            helper.assertTrue(unloaded(nether, pos), "別ディメンションも実際にアンロードされる");
        }).thenExecute(() -> {
            reloaded[0] = level.getChunkAt(pos);
            helper.assertTrue(reloaded[0] != original && nether.getChunkAt(pos) != other, "メモリ上の旧チャンクを再利用せずディスクから復元");
            helper.assertTrue(BlockContamination.get(level, pos).orElseThrow().equals(FIRST), "実チャンク再読み込み後も汚染値を保持");
            helper.assertTrue(BlockContamination.get(nether, pos).orElseThrow().equals(SECOND), "同じ座標でもディメンションごとに独立");
            BlockContamination.set(level, pos, SoilContamination.CLEAN);
            level.getChunkSource().save(true);
        }).thenWaitUntil(() -> {
            helper.assertTrue(unloaded(level, pos), "清浄化後も実際にアンロードされる");
        }).thenExecute(() -> {
            helper.assertTrue(level.getChunkAt(pos) != reloaded[0], "清浄化したチャンクもディスクから再読み込み");
            helper.assertTrue(recorded(level, pos) && BlockContamination.get(level, pos).orElseThrow().equals(SoilContamination.CLEAN), "清浄化の明示値が消えずに保存される");
            helper.assertTrue(BlockContamination.get(nether, pos).orElseThrow().equals(SECOND), "他ディメンションは清浄化されない");
        }).thenWaitUntil(() -> {
            helper.assertTrue(unloaded(level, pos), "最終確認後の通常チャンクもアンロードされる");
            helper.assertTrue(unloaded(nether, pos), "最終確認後の別ディメンションもアンロードされる");
        }).thenSucceed();
    }
}
