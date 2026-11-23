package io.github.rs0325.ashenfrontier.world;

import com.mojang.serialization.JsonOps;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.SoilContamination;
import io.github.rs0325.ashenfrontier.core.catalog.NatureTooltips;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SoilItemGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void equalStagesStackAndSurviveItemSerialization(GameTestHelper helper) {
        var first = new ItemStack(Items.DIRT);
        var second = new ItemStack(Items.DIRT);
        var different = new ItemStack(Items.DIRT);
        BlockContamination.writeItem(first, new SoilContamination(.00016, .000002, .25, 5.4, 150, 2e6));
        BlockContamination.writeItem(second, new SoilContamination(.0003, .000004, .3, 5.2, 250, 4e6));
        BlockContamination.writeItem(different, new SoilContamination(.0005, .000004, .3, 5.2, 250, 4e6));
        helper.assertTrue(ItemStack.isSameItemSameComponents(first, second), "元濃度が異なっても6段階が同じなら同じスタック");
        helper.assertTrue(!ItemStack.isSameItemSameComponents(first, different), "1種類でも段階が違えば別スタック");
        var inventory = helper.makeMockPlayer(GameType.SURVIVAL).getInventory();
        inventory.add(first.copy());
        inventory.add(second.copy());
        inventory.add(different.copy());
        helper.assertTrue(inventory.getItem(0).getCount() == 2 && inventory.getItem(1).getCount() == 1,
                "実インベントリでも同じ段階だけ合流する");
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var saved = ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, first).getOrThrow()).getOrThrow();
        helper.assertTrue(ItemStack.isSameItemSameComponents(saved, second), "保存後も同じ段階の土とスタック可能");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            ItemStack.STREAM_CODEC.encode(buffer, first);
            helper.assertTrue(ItemStack.isSameItemSameComponents(first, ItemStack.STREAM_CODEC.decode(buffer)), "同期Codecでも6段階を保持");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void cleanSoilHasNoComponentAndStacksWithVanilla(GameTestHelper helper) {
        var vanilla = new ItemStack(Items.DIRT);
        var collected = new ItemStack(Items.DIRT);
        BlockContamination.writeItem(collected, new SoilContamination(.00012, 0, .05, 6.5, 20, 0));
        BlockContamination.writeItem(collected, new SoilContamination(.00002, .0000001, .1, 6.1, 30, 50000));
        helper.assertTrue(collected.get(ItemProperties.SOIL_CONTAMINATION) == null, "全清浄は既存の段階成分も除去する");
        helper.assertTrue(ItemStack.isSameItemSameComponents(vanilla, collected), "背景値が違っても全清浄ならバニラ土と同一");
        var inventory = helper.makeMockPlayer(GameType.SURVIVAL).getInventory();
        inventory.add(vanilla.copy()); inventory.add(collected.copy());
        helper.assertTrue(inventory.getItem(0).getCount() == 2 && inventory.getItem(1).isEmpty(), "バニラ土と実際にスタックする");
        helper.assertTrue(BlockContamination.readItem(collected).equals(SoilContamination.CLEAN), "清浄土の設置値は自然背景へ戻る");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void loreShowsOnlyNoncleanKindsIncludingVanillaSoil(GameTestHelper helper) {
        var stack = new ItemStack(Items.DIRT);
        var lines = new ArrayList<Component>();
        NatureTooltips.appendContamination(stack, lines);
        helper.assertTrue(lines.isEmpty(), "清浄な土には汚染Loreを出さない");
        for (int stage = 1; stage <= 4; stage++) {
            lines.clear();
            stack.set(ItemProperties.SOIL_CONTAMINATION, new SoilContamination.Stages(stage, stage, stage, stage, stage, stage));
            NatureTooltips.appendContamination(stack, lines);
            helper.assertTrue(lines.size() == 6, "現在は全6種類・全段階を表示");
            int index = 0;
            for (var kind : SoilContamination.Kind.values()) {
                var expected = Component.translatable("tooltip.ashen_frontier.soil_contamination",
                        Component.translatable("contamination.ashen_frontier." + kind.key),
                        Component.translatable("contamination.ashen_frontier.stage." + stage)).withStyle(ChatFormatting.GRAY);
                helper.assertTrue(lines.get(index++).equals(expected), "種類: 段階の翻訳と属性同様の色");
            }
        }
        lines.clear();
        stack.set(ItemProperties.SOIL_CONTAMINATION, new SoilContamination.Stages(0, 0, 0, 0, 3, 0));
        NatureTooltips.appendContamination(stack, lines);
        helper.assertTrue(lines.size() == 1, "混在時も清浄の種類を省く");
        helper.succeed();
    }
}
