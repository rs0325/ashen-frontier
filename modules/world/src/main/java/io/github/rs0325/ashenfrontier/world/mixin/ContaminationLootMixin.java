package io.github.rs0325.ashenfrontier.world.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.rs0325.ashenfrontier.world.BlockContamination;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import org.spongepowered.asm.mixin.Mixin;
import java.util.List;
import java.util.function.BiConsumer;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class ContaminationLootMixin {
    @WrapMethod(method = "getDrops")
    private List<ItemStack> drops(LootParams.Builder params, Operation<List<ItemStack>> original) {
        var drops = original.call(params);
        BlockContamination.loot((BlockState) (Object) this, params, drops);
        return drops;
    }

    @WrapMethod(method = "onExplosionHit")
    private void explosion(Level level, BlockPos pos, Explosion explosion, BiConsumer<ItemStack, BlockPos> consumer, Operation<Void> original) {
        original.call(level, pos, explosion, consumer);
        if (level instanceof ServerLevel server && BlockContamination.isTarget((BlockState) (Object) this)
                && !level.getBlockState(pos).is(((BlockState) (Object) this).getBlock())) BlockContamination.clear(server, pos);
    }
}
