package io.github.rs0325.ashenfrontier.core.loot;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.catalog.RelicContent;
import io.github.rs0325.ashenfrontier.core.config.CoreConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierCore.MOD_ID)
@PrefixGameTestTemplate(false)
public class LifeSupportLootGameTests {
    private static final ResourceKey<LootTable> BUILDING_LOOT = ResourceKey.create(
            Registries.LOOT_TABLE, AshenFrontierCore.id("buildings/shelter/removed_item_check"));
    private static final ResourceKey<LootTable> GUARANTEED_LOOT = ResourceKey.create(
            Registries.LOOT_TABLE, AshenFrontierCore.id("buildings/guaranteed/first_aid"));

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 40)
    public static void deferredLootUsesOpeningMultiplierAndPersistsResolvedInventory(GameTestHelper helper) {
        double original = CoreConfig.lifeSupportLootMultiplier();
        var multiplier = multiplierConfig();
        try {
            multiplier.set(1.0);
            ChestBlockEntity unopenedBeforeChange = chest(helper, new BlockPos(0, 1, 0), BUILDING_LOOT, 101L);
            multiplier.set(2.0);
            int openedCount = count(unopenedBeforeChange, RelicContent.item("canned_food"));
            if (openedCount != 6 || unopenedBeforeChange.getLootTable() != null) {
                helper.fail("未開封lootが開封時倍率を使わない、またはloot table参照が残った: count="
                        + openedCount + ", table=" + unopenedBeforeChange.getLootTable());
                return;
            }

            multiplier.set(4.0);
            if (count(unopenedBeforeChange, RelicContent.item("canned_food")) != 6) {
                helper.fail("開け直しで倍率が再適用された");
                return;
            }
            var serialized = unopenedBeforeChange.saveWithFullMetadata(helper.getLevel().registryAccess());
            BlockEntity restored = BlockEntity.loadStatic(unopenedBeforeChange.getBlockPos(),
                    unopenedBeforeChange.getBlockState(), serialized, helper.getLevel().registryAccess());
            if (!(restored instanceof ChestBlockEntity reloaded)) {
                helper.fail("保存データからチェストBlockEntityを復元できない");
                return;
            }
            reloaded.setLevel(helper.getLevel());
            if (reloaded.getLootTable() != null || count(reloaded, RelicContent.item("canned_food")) != 6) {
                helper.fail("再読込した確定lootに倍率が再適用された");
                return;
            }
            for (int i = 0; i < reloaded.getContainerSize(); i++) {
                if (reloaded.getItem(i).is(Blocks.HOPPER.asItem())) {
                    helper.fail("削除済みlootが倍率処理後に残った");
                    return;
                }
            }

            multiplier.set(4.0);
            ChestBlockEntity unopenedBeforeZero = chest(helper, new BlockPos(3, 1, 0), BUILDING_LOOT, 202L);
            multiplier.set(0.0);
            if (count(unopenedBeforeZero, RelicContent.item("canned_food")) != 0) {
                helper.fail("未開封容器が開封時の0倍を使わない");
                return;
            }

            ChestBlockEntity guaranteed = chest(helper, new BlockPos(6, 1, 0), GUARANTEED_LOOT, 303L);
            if (count(guaranteed, RelicContent.item("canned_food")) != 1) {
                helper.fail("倍率0で必須保証lootが失われた");
                return;
            }
            AshenFrontierCore.LOGGER.info("生命維持遅延loot・保存・削除・保証GameTest: 4ケース成功");
            helper.succeed();
        } finally {
            multiplier.set(original);
        }
    }

    private static ModConfigSpec.DoubleValue multiplierConfig() {
        try {
            var field = CoreConfig.class.getDeclaredField("LIFE_SUPPORT_LOOT_MULTIPLIER");
            field.setAccessible(true);
            return (ModConfigSpec.DoubleValue) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("生命維持lootテスト設定が取得できない", e);
        }
    }

    private static ChestBlockEntity chest(GameTestHelper helper, BlockPos pos, ResourceKey<LootTable> table, long seed) {
        helper.setBlock(pos, Blocks.CHEST);
        var chest = (ChestBlockEntity) helper.getBlockEntity(pos);
        chest.setLootTable(table);
        chest.setLootTableSeed(seed);
        return chest;
    }

    private static int count(ChestBlockEntity chest, Item item) {
        int count = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }
}
