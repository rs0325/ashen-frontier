package io.github.rs0325.ashenfrontier.core.catalog;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 後続機能Modが同じブロックIDへ機能を追加するためのcore所有の非tick型設備。 */
public final class RelicEquipmentEntity extends BlockEntity {
    public RelicEquipmentEntity(BlockPos pos, BlockState state) { super(RelicContent.EQUIPMENT.get(), pos, state); }
}
