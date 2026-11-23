package io.github.rs0325.ashenfrontier.world.mixin;

import io.github.rs0325.ashenfrontier.world.BlockContamination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;

/** NeoForgeの設置取消し・アイテム消費を終えた後に位置の記録を確定する。 */
@Mixin(CommonHooks.class)
public abstract class ContaminationPlacementMixin {
    @WrapMethod(method = "onPlaceItemIntoWorld")
    private static InteractionResult placement(UseOnContext context, Operation<InteractionResult> original) {
        if (!(context.getLevel() instanceof ServerLevel level)
                || !(context.getItemInHand().getItem() instanceof BlockItem item)
                || !BlockContamination.isTarget(item.getBlock().defaultBlockState())) return original.call(context);
        var stack = context.getItemInHand().copy();
        var pos = new BlockPlaceContext(context).getClickedPos().immutable();
        var result = original.call(context);
        if (result.consumesAction() && level.getBlockState(pos).is(item.getBlock()))
            BlockContamination.placed(level, pos, stack);
        return result;
    }
}
