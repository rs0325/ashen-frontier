package io.github.rs0325.ashenfrontier.core;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/** ブロックと道具の適性をcoreタグで判定する。 */
public final class MiningRules {
    public static final TagKey<Item> ROCK_TOOLS = itemTag("rock_tools");
    public static final TagKey<Item> WOOD_TOOLS = itemTag("wood_tools");
    public static final TagKey<Item> SOIL_TOOLS = itemTag("soil_tools");
    public static final TagKey<Item> SIMPLE_TOOLS = itemTag("simple_tools");
    public static final TagKey<Block> HAND_SOFT = blockTag("soft_for_hand");
    public static final TagKey<Block> NEEDS_SIMPLE_TOOL = blockTag("needs_simple_tool");

    private MiningRules() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(MiningRules::onBreakSpeed);
        NeoForge.EVENT_BUS.addListener(MiningRules::onLeftClick);
        NeoForge.EVENT_BUS.addListener(MiningRules::onBreak);
    }

    private static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (!canMine(event.getState(), event.getEntity().getMainHandItem())) event.setNewSpeed(0);
    }

    private static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.ABORT
                && !event.getEntity().isCreative()
                && !canMine(event.getLevel().getBlockState(event.getPos()), event.getItemStack())) {
            event.setCanceled(true);
        }
    }

    private static void onBreak(BlockEvent.BreakEvent event) {
        if (!event.getPlayer().isCreative() && !canMine(event.getState(), event.getPlayer().getMainHandItem())) {
            event.setCanceled(true);
        }
    }

    /**
     * タグによる道具種別の適性だけを返す。素手対象は持ち物を問わず許可する。
     * 硬さ、ゲームモード、回収に必要な道具の等級とドロップ判定は通常の採掘経路へ委ねる。
     */
    public static boolean canMine(BlockState state, ItemStack tool) {
        if (state.is(HAND_SOFT)) return true;
        return state.is(NEEDS_SIMPLE_TOOL) ? tool.is(SIMPLE_TOOLS)
                : state.is(BlockTags.MINEABLE_WITH_PICKAXE) ? tool.is(ROCK_TOOLS)
                : state.is(BlockTags.MINEABLE_WITH_AXE) ? tool.is(WOOD_TOOLS)
                : state.is(BlockTags.MINEABLE_WITH_SHOVEL) ? tool.is(SOIL_TOOLS)
                : state.is(BlockTags.MINEABLE_WITH_HOE) ? tool.is(SOIL_TOOLS)
                : true;
    }

    private static TagKey<Item> itemTag(String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(AshenFrontierCore.NAMESPACE, path));
    }

    private static TagKey<Block> blockTag(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(AshenFrontierCore.NAMESPACE, path));
    }
}
