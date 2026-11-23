package io.github.rs0325.ashenfrontier.core.catalog;

import com.mojang.authlib.GameProfile;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.MaterialData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@GameTestHolder(AshenFrontierCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NatureGameTests {
    private NatureGameTests() {}

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void creativeTabContainsEveryCoreItemOnce(GameTestHelper helper) {
        var tab = CoreCreativeTab.CONTENTS.get();
        tab.buildContents(new net.minecraft.world.item.CreativeModeTab.ItemDisplayParameters(
                net.minecraft.world.flag.FeatureFlags.VANILLA_SET, true, helper.getLevel().registryAccess()));
        var displayed = tab.getDisplayItems().stream().map(ItemStack::getItem).toList();
        var expected = BuiltInRegistries.ITEM.stream()
                .filter(item -> BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(AshenFrontierCore.NAMESPACE)).toList();
        helper.assertTrue(displayed.size() == expected.size() && new java.util.HashSet<>(displayed).containsAll(expected),
                "coreの登録済みアイテムを重複・欠落なく専用タブへ表示");
        helper.assertTrue(displayed.stream().noneMatch(item -> item.getDefaultInstance().is(
                net.minecraft.tags.ItemTags.create(AshenFrontierCore.id("removed_items")))), "削除対象のバニラ要素を追加しない");
        AshenFrontierCore.LOGGER.info("CREATIVE_CONTENTS count={} ids={}", displayed.size(),
                displayed.stream().map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).toList());
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void mudCollisionMatchesItsDepositionalType(GameTestHelper helper) {
        for (String id : new String[]{"tidal_mud", "organic_mud", "peat", "cracked_mud"}) {
            var block = NatureContent.block(id);
            var shape = block.defaultBlockState().getCollisionShape(helper.getLevel(), BlockPos.ZERO);
            double expected = id.equals("tidal_mud") || id.equals("organic_mud") ? 14 / 16.0 : 1;
            helper.assertTrue(shape.max(Direction.Axis.Y) == expected, "泥の沈み込み: " + id);
            helper.assertTrue(!(block instanceof FallingBlock), "泥は落下しない: " + id);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void sedimentLayersStackAndRecoverEveryLayer(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(0, 2, 0));
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "sediment-test"));
        int count = 0;
        for (var entry : NatureCatalog.entries()) {
            if (!entry.sediment()) continue;
            var block = NatureContent.block(entry.id());
            helper.assertTrue((block instanceof FallingBlock) == entry.kind().equals("falling_sediment"), "堆積物の落下特性: " + entry.id());
            if (!entry.layer()) continue;
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            for (int layers = 1; layers <= 8; layers++) {
                var state = block.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
                level.setBlock(pos, state, Block.UPDATE_CLIENTS);
                helper.assertTrue(state.canSurvive(level, pos), "堆積層の支持");
                helper.assertTrue(state.getShape(level, pos).max(Direction.Axis.Y) == layers / 8.0, "堆積層の形状");
                var drops = Block.getDrops(state, level, pos, null, player, new ItemStack(Items.IRON_SHOVEL));
                helper.assertTrue(drops.size() == 1 && drops.getFirst().is(block.asItem()) && drops.getFirst().getCount() == layers,
                        "堆積層の回収数: " + entry.id() + " layers=" + layers);
                var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(block),
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                if (layers < 8) helper.assertTrue(block.getStateForPlacement(context).getValue(SnowLayerBlock.LAYERS) == layers + 1, "堆積層の重ね置き");
                level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                helper.assertTrue(!state.canSurvive(level, pos), "空中に堆積層を支持しない");
                level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                count++;
            }
        }
        AshenFrontierCore.LOGGER.info("堆積層の形状・積層・支持・回収: {}状態成功", count);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void itemsRetainMaterialAndQuality(GameTestHelper helper) {
        var lookup = helper.getLevel().registryAccess();
        int count = 0;
        for (var entry : NatureCatalog.entries()) {
            if (entry.fluid()) continue;
            var stack = new ItemStack(NatureContent.item(entry.id()), 3);
            stack.set(ItemProperties.COMPOSITION, ItemProperties.Composition.of(Map.of(entry.material(), .83)));
            var quality = stack.get(NatureProperties.QUALITY);
            for (int i = 0; i < 3; i++) {
                stack = ItemStack.parse(lookup, stack.save(lookup)).orElseThrow();
            }
            helper.assertTrue(stack.is(NatureContent.item(entry.id())) && stack.getCount() == 3,
                    "Item保存往復でID・個数が変化: " + entry.id());
            helper.assertTrue(Objects.equals(quality, stack.get(NatureProperties.QUALITY))
                    && stack.get(ItemProperties.COMPOSITION).equals(ItemProperties.Composition.of(Map.of(entry.material(), .83))),
                    "品質が変化: " + entry.id());
            helper.assertTrue(MaterialData.get(stack.getItem()).orElseThrow().massKg() == entry.massKg(),
                    "材料データの欠落: " + entry.id());
            if (entry.fuel() != null) helper.assertTrue(stack.getItem().builtInRegistryHolder().getData(FuelData.ITEM)
                    .equals(FuelData.from(entry)), "燃料特性が不一致: " + entry.id());
            count++;
        }
        AshenFrontierCore.LOGGER.info("自然物Item保存往復: {}件成功", count);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void fluidsRetainTypeAmountAndQuality(GameTestHelper helper) {
        var lookup = helper.getLevel().registryAccess();
        int count = 0;
        for (var entry : NatureCatalog.entries()) {
            if (!entry.fluid()) continue;
            var fluids = NatureContent.fluids().get(entry.id());
            for (var fluid : new Fluid[]{fluids.source().get(), fluids.flowing().get()}) {
                var stack = new FluidStack(fluid, 750);
                stack.set(NatureProperties.QUALITY, AshenFrontierCore.id(entry.id()));
                stack.set(ItemProperties.COMPOSITION, ItemProperties.Composition.of(Map.of(entry.material(), .91)));
                for (int i = 0; i < 3; i++) stack = FluidStack.parse(lookup, stack.save(lookup)).orElseThrow();
                helper.assertTrue(stack.getFluid() == fluid && stack.getAmount() == 750
                        && stack.get(NatureProperties.QUALITY).equals(AshenFrontierCore.id(entry.id()))
                        && stack.get(ItemProperties.COMPOSITION).equals(ItemProperties.Composition.of(Map.of(entry.material(), .91))),
                        "Fluid保存往復で品質・量・IDが変化: " + entry.id());
                helper.assertTrue(fluid.getFluidType() == fluids.type().get()
                        && !fluid.getFluidType().canConvertToSource(fluid.defaultFluidState(), helper.getLevel(), helper.absolutePos(BlockPos.ZERO)),
                        "FluidTypeまたは有限性が不一致: " + entry.id());
                if (entry.fuel() != null) helper.assertTrue(fluid.builtInRegistryHolder().getData(FuelData.FLUID)
                        .equals(FuelData.from(entry)), "流体燃料特性が不一致: " + entry.id());
                count++;
            }
        }
        AshenFrontierCore.LOGGER.info("自然物Fluid保存往復: {}件成功", count);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void blockStatesRetainIdentityAndFluidLevel(GameTestHelper helper) {
        var ops = RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess());
        int count = 0;
        for (var entry : NatureCatalog.entries()) {
            if (!entry.block()) continue;
            var block = NatureContent.block(entry.id());
            for (var state : block.getStateDefinition().getPossibleStates()) {
                var loaded = BlockState.CODEC.parse(ops, BlockState.CODEC.encodeStart(ops, state).getOrThrow()).getOrThrow();
                helper.assertTrue(loaded == state, "BlockStateの保存往復で変化: " + entry.id());
                if (entry.fluid()) helper.assertTrue(loaded.getFluidState().getType().getFluidType()
                        == NatureContent.fluids().get(entry.id()).type().get(), "水質・油品質の変化: " + entry.id());
                count++;
            }
        }
        AshenFrontierCore.LOGGER.info("自然物BlockState保存往復: {}状態成功", count);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void recoveryLootMatchesCatalog(GameTestHelper helper) {
        int count = 0;
        for (var entry : NatureCatalog.entries()) {
            if (!entry.blockItem()) continue;
            var state = NatureContent.block(entry.id()).defaultBlockState();
            var drops = Block.getDrops(state, helper.getLevel(),
                    helper.absolutePos(BlockPos.ZERO), null, null,
                    new ItemStack(Items.DIAMOND_PICKAXE));
            var expected = BuiltInRegistries.ITEM.get(
                    ResourceLocation.parse(entry.recovery().contains(":")
                            ? entry.recovery() : "ashen_frontier:" + entry.recovery()));
            helper.assertTrue(drops.size() == 1 && drops.getFirst().is(expected) && drops.getFirst().getCount() == 1,
                    "回収先または回収数が不一致: " + entry.id());
            count++;
        }
        AshenFrontierCore.LOGGER.info("自然物回収loot: {}件成功", count);
        helper.succeed();
    }

    @GameTest(template = "nature_buckets", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void emptyBucketLeavesAllFiniteSources(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(1, 2, 2));
        var player = bucketPlayer(helper, pos);
        int count = 0;
        for (var entry : NatureCatalog.entries()) {
            if (!entry.fluid()) continue;
            var source = NatureContent.block(entry.id()).defaultBlockState();
            level.setBlock(pos, source, Block.UPDATE_ALL);
            assertBucketTarget(helper, player, pos, ClipContext.Fluid.SOURCE_ONLY);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
            var result = player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND);
            helper.assertTrue(result == InteractionResult.FAIL && level.getBlockState(pos) == source,
                    "空バケツが有限源を変更: " + entry.id());
            helper.assertTrue(player.getMainHandItem().is(Items.BUCKET) && player.getMainHandItem().getCount() == 1,
                    "拒否した空バケツが変化: " + entry.id());
            count++;
        }
        level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        var result = player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND);
        helper.assertTrue(result.consumesAction() && level.getBlockState(pos).isAir()
                && player.getMainHandItem().is(Items.WATER_BUCKET), "対照のバニラ水源を回収できない");
        AshenFrontierCore.LOGGER.info("空バケツによる有限源の保護: {}品質成功（バニラ回収の対照も成功）", count);
        helper.succeed();
    }

    @GameTest(template = "nature_buckets", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void waterBucketCannotOverwriteNaturalFluids(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(1, 2, 2));
        var player = bucketPlayer(helper, pos);
        int count = 0;
        for (var entry : NatureCatalog.entries()) {
            if (!entry.fluid()) continue;
            for (int fluidLevel : new int[]{0, 3}) {
                var state = NatureContent.block(entry.id()).defaultBlockState().setValue(LiquidBlock.LEVEL, fluidLevel);
                level.setBlock(pos, state, Block.UPDATE_ALL);
                assertBucketTarget(helper, player, pos.south(), ClipContext.Fluid.NONE);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
                var result = player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND);
                helper.assertTrue(result == InteractionResult.FAIL && level.getBlockState(pos) == state,
                        "固体面を狙った水バケツが隣の流体を変更: " + entry.id() + " level=" + fluidLevel);
                helper.assertTrue(player.getMainHandItem().is(Items.WATER_BUCKET) && player.getMainHandItem().getCount() == 1,
                        "拒否した水入りバケツが変化: " + entry.id());
                count++;
            }
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        var result = player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND);
        helper.assertTrue(result.consumesAction() && level.getBlockState(pos).is(Blocks.WATER)
                && player.getMainHandItem().is(Items.BUCKET), "対照の空き位置へ水を置けない");
        AshenFrontierCore.LOGGER.info("水バケツによる品質上書きの拒否: {}状態成功（空き位置への配置の対照も成功）", count);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void naturalIceDoesNotMeltOrReleaseWater(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(0, 2, 0));
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "ice-test"));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        for (var entry : NatureCatalog.entries()) {
            if (!entry.kind().equals("ice")) continue;
            var block = NatureContent.block(entry.id());
            var state = block.defaultBlockState();
            helper.assertTrue(!state.isRandomlyTicking(), "光による融解tickを登録しない: " + entry.id());
            helper.assertTrue(block.getFriction() == Blocks.ICE.getFriction(), "氷と同じ滑りやすさ");
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos, state, Block.UPDATE_ALL);
            state.randomTick(level, pos, level.random);
            helper.assertTrue(level.getBlockState(pos) == state, "randomTickでも氷が融解しない");
            var drops = Block.getDrops(state, level, pos, null, player, new ItemStack(Items.DIAMOND_PICKAXE));
            helper.assertTrue(drops.size() == 1 && drops.getFirst().is(block.asItem()), "シルクタッチなしで自己回収");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
            helper.assertTrue(player.gameMode.destroyBlock(pos), "ツルハシで氷を破壊");
            helper.assertTrue(level.getBlockState(pos).isAir() && level.getFluidState(pos).isEmpty(), "破壊後に水を出さない");
        }
        helper.succeed();
    }

    private static FakePlayer bucketPlayer(GameTestHelper helper, BlockPos pos) {
        helper.getLevel().setBlock(pos.south(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "nature-bucket-test"));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.setPos(pos.getX() + .5, pos.getY() + .5 - player.getEyeHeight(), pos.getZ() - 1.5);
        player.setYRot(0);
        player.setYHeadRot(0);
        player.setXRot(0);
        return player;
    }

    private static void assertBucketTarget(GameTestHelper helper, FakePlayer player, BlockPos expected, ClipContext.Fluid fluid) {
        var eye = player.getEyePosition();
        var end = eye.add(player.calculateViewVector(player.getXRot(), player.getYRot()).scale(player.blockInteractionRange()));
        var hit = helper.getLevel().clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, fluid, player));
        helper.assertTrue(hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(expected)
                && hit.getDirection() == Direction.NORTH, "バケツ操作が対象の北面へ到達していない: "
                + hit.getType() + " " + hit.getBlockPos() + " " + hit.getDirection());
    }
}
