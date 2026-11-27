package io.github.rs0325.ashenfrontier.core.catalog;

import com.mojang.authlib.GameProfile;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.MiningRules;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.shapes.VoxelShape;

@GameTestHolder(AshenFrontierCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinGameTests {
    private RuinGameTests() {}

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void statesShapesAndPlacementSurviveSerialization(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "ruin-builder"));
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        int states = 0;
        for (var entry : RelicCatalog.entries()) {
            if (entry.shape() == null) continue;
            var block = RelicContent.block(entry.id());
            helper.assertTrue(!(block instanceof EntityBlock), "新建材にBEがある: " + entry.id());
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(block));
            var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, player.getMainHandItem(),
                    new BlockHitResult(Vec3.atCenterOf(pos.below()), Direction.UP, pos.below(), false));
            var placed = block.getStateForPlacement(context);
            helper.assertTrue(placed != null && placed.is(block), "BlockItemの再設置状態が作れない: " + entry.id());
            for (var state : block.getStateDefinition().getPossibleStates()) {
                var saved = BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
                var loaded = BlockState.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
                helper.assertTrue(state == loaded, "保存往復で向き/形状が変化: " + state);
                level.setBlock(pos, loaded, Block.UPDATE_CLIENTS);
                var selected = loaded.getShape(level, pos);
                var collision = loaded.getCollisionShape(level, pos);
                helper.assertTrue(!selected.isEmpty() && !Shapes.joinIsNotEmpty(selected, collision, BooleanOp.NOT_SAME),
                        "選択と衝突が不一致: " + state);
                helper.assertTrue(!loaded.isRandomlyTicking() && level.getBlockEntity(pos) == null,
                        "新建材に時間進行がある: " + entry.id());
                var rotated = state;
                for (int i = 0; i < 4; i++) rotated = rotated.rotate(Rotation.CLOCKWISE_90);
                helper.assertTrue(rotated == state, "4回転後に形状が戻らない: " + state);
                String model = null;
                if (block instanceof RuinBlock) {
                    var direction = state.getValue(HorizontalDirectionalBlock.FACING);
                    model = entry.id() + (direction == Direction.NORTH ? "" : "_" + direction.getName());
                } else if (block instanceof RuinBeamBlock) {
                    var axis = state.getValue(RotatedPillarBlock.AXIS);
                    model = entry.id() + (axis == Direction.Axis.Y ? "" : "_" + axis.getName());
                }
                if (model != null) {
                    var resource = RuinGameTests.class.getResourceAsStream("/assets/ashen_frontier/models/block/" + model + ".json");
                    helper.assertTrue(resource != null, "生成モデルがない: " + model);
                    VoxelShape rendered = Shapes.empty();
                    try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
                        var elements = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("elements");
                        helper.assertTrue(elements.size() <= 8, "モデルboxが制作予算を超えた: " + model);
                        for (var element : elements) {
                            var from = element.getAsJsonObject().getAsJsonArray("from");
                            var to = element.getAsJsonObject().getAsJsonArray("to");
                            rendered = Shapes.or(rendered, Block.box(from.get(0).getAsDouble(), from.get(1).getAsDouble(),
                                    from.get(2).getAsDouble(), to.get(0).getAsDouble(), to.get(1).getAsDouble(), to.get(2).getAsDouble()));
                        }
                    }
                    helper.assertTrue(!Shapes.joinIsNotEmpty(rendered, collision, BooleanOp.NOT_SAME),
                            "生成モデルと実衝突が不一致: " + state);
                }
                states++;
            }
            var good = new ItemStack(entry.material().equals("wood") ? Items.IRON_AXE : Items.IRON_PICKAXE);
            var bad = new ItemStack(entry.material().equals("wood") ? Items.IRON_PICKAXE : Items.IRON_AXE);
            helper.assertTrue(MiningRules.canMine(placed, good) && !MiningRules.canMine(placed, bad)
                    && !MiningRules.canMine(placed, ItemStack.EMPTY), "採掘適性が不正: " + entry.id());
            helper.assertTrue(Block.getDrops(placed, level, pos, null, null, bad).isEmpty()
                    && Block.getDrops(placed, level, pos, null, null, ItemStack.EMPTY).isEmpty(),
                    "不適正工具のlootがある: " + entry.id());
            var drops = Block.getDrops(placed, level, pos, null, null, good);
            helper.assertTrue(drops.size() == 1 && drops.getFirst().is(block.asItem()) && drops.getFirst().getCount() == 1,
                    "自身を1回回収できない: " + entry.id());
            level.removeBlock(pos, false);
            helper.assertTrue(level.getBlockState(pos).isAir(), "除去後の状態が残る: " + entry.id());
            player.setItemInHand(InteractionHand.MAIN_HAND, drops.getFirst().copy());
            var recoveredContext = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, player.getMainHandItem(),
                    new BlockHitResult(Vec3.atCenterOf(pos.below()), Direction.UP, pos.below(), false));
            player.getMainHandItem().useOn(recoveredContext);
            helper.assertTrue(level.getBlockState(pos).is(block), "回収Itemの実再設置に失敗: " + entry.id());
            level.removeBlock(pos, false);
        }
        AshenFrontierCore.LOGGER.info("R1新建材: Codec・形状{}状態、工具・一回回収・実再設置64 ID", states);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 1200,
            batch = "ruin_disk_storage")
    public static void placedAndRecoveredMaterialsSurviveStandardChunkStorage(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var entries = RelicCatalog.entries().stream().filter(entry -> entry.shape() != null).toList();
        var states = new ArrayList<BlockState>();
        for (var entry : entries) states.addAll(RelicContent.block(entry.id()).getStateDefinition().getPossibleStates());
        var chunk = level.getChunkAt(helper.absolutePos(BlockPos.ZERO));
        int x = chunk.getPos().getMinBlockX(), z = chunk.getPos().getMinBlockZ();
        int y = level.getMaxBuildHeight() - 16;
        var positions = new ArrayList<BlockPos>();
        var previous = new ArrayList<BlockState>();
        var recovered = new ArrayList<BlockPos>();
        var harvestedChunk = level.getChunk(chunk.getPos().x + 2, chunk.getPos().z);
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "ruin-storage"));
        var directory = Path.of("build", "ruin-save-test");
        Files.createDirectories(directory);
        try {
            for (int i = 0; i < states.size(); i++) {
                var pos = new BlockPos(x + i % 16, y + i / 256, z + i / 16 % 16);
                positions.add(pos);
                previous.add(chunk.getBlockState(pos));
                chunk.setBlockState(pos, states.get(i), false);
            }
            var file = directory.resolve("placed.nbt");
            NbtIo.writeCompressed(ChunkSerializer.write(level, chunk), file);
            var loaded = ChunkSerializer.read(level, level.getPoiManager(),
                    new RegionStorageInfo("ruin_test", level.dimension(), "chunk"), chunk.getPos(),
                    NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
            for (int i = 0; i < states.size(); i++)
                helper.assertTrue(loaded.getBlockState(positions.get(i)) == states.get(i), "標準チャンク保存で状態が変化: " + states.get(i));
            for (int i = 0; i < entries.size(); i++) {
                var pos = new BlockPos(harvestedChunk.getPos().getMinBlockX() + i % 8 * 2, y,
                        harvestedChunk.getPos().getMinBlockZ() + i / 8 * 2);
                helper.assertTrue(harvestedChunk.getBlockState(pos).isAir(), "回収試験の位置が空でない");
                recovered.add(pos);
                harvestedChunk.setBlockState(pos, RelicContent.block(entries.get(i).id()).defaultBlockState(), false);
            }
            file = directory.resolve("before-recovery.nbt");
            NbtIo.writeCompressed(ChunkSerializer.write(level, harvestedChunk), file);
            var before = ChunkSerializer.read(level, level.getPoiManager(),
                    new RegionStorageInfo("ruin_test", level.dimension(), "chunk"), harvestedChunk.getPos(),
                    NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                var pos = recovered.get(i);
                helper.assertTrue(before.getBlockState(pos).is(RelicContent.block(entry.id())), "回収前の保存に建材がない");
                player.setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(entry.material().equals("wood") ? Items.IRON_AXE : Items.IRON_PICKAXE));
                helper.assertTrue(player.gameMode.destroyBlock(pos), "保存済み建材を採掘できない: " + entry.id());
                var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(.5));
                helper.assertTrue(drops.size() == 1 && drops.getFirst().getItem().is(RelicContent.item(entry.id()))
                        && drops.getFirst().getItem().getCount() == 1, "保存済み建材の一回回収が不正: " + entry.id());
                drops.forEach(ItemEntity::discard);
            }
            file = directory.resolve("after-recovery.nbt");
            NbtIo.writeCompressed(ChunkSerializer.write(level, harvestedChunk), file);
            var after = ChunkSerializer.read(level, level.getPoiManager(),
                    new RegionStorageInfo("ruin_test", level.dimension(), "chunk"), harvestedChunk.getPos(),
                    NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
            for (var pos : recovered) helper.assertTrue(after.getBlockState(pos).isAir(), "回収位置が標準チャンク再読込で復活: " + pos);
            AshenFrontierCore.LOGGER.info("R1_CHUNK_STORAGE retained_states={} recovered_ids={} standard_disk_files=3 server_restart=false",
                    states.size(), recovered.size());
        } finally {
            for (int i = 0; i < positions.size(); i++) chunk.setBlockState(positions.get(i), previous.get(i), false);
            for (var pos : recovered) {
                harvestedChunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1)).forEach(ItemEntity::discard);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void missingMaterialIsAlsoMissingFromCollision(GameTestHelper helper) {
        var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        for (String id : new String[]{"exposed_rebar", "broken_glass_window", "broken_hollow_concrete", "perforated_steel_sheet"}) {
            var state = RelicContent.block(id).defaultBlockState();
            var probe = switch (id) {
                case "exposed_rebar" -> new AABB(.45,.45,.45,.55,.55,.55);
                case "broken_hollow_concrete" -> new AABB(.27,.45,.45,.36,.55,.55);
                default -> new AABB(.55,.60,.46,.65,.70,.54);
            };
            helper.assertTrue(!Shapes.joinIsNotEmpty(state.getCollisionShape(helper.getLevel(), pos),
                    Shapes.create(probe), BooleanOp.AND), "欠落部に衝突が残っている: " + id);
        }
        helper.succeed();
    }
}
