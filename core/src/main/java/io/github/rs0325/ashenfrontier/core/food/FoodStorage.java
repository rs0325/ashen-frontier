package io.github.rs0325.ashenfrontier.core.food;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import java.util.UUID;

/** 食品の保管所有者の観測と実消費。移送先は挿入時にobserveし、加工は確定した実数だけconsumeする。 */
public final class FoodStorage extends Event {
    public final ServerLevel level;
    public final UUID holder;
    public final BlockPos position;
    public final ItemStack stack;
    public final int consumed;
    private FoodStorage(ServerLevel level,UUID holder,BlockPos position,ItemStack stack,int consumed) {
        this.level=level;this.holder=holder;this.position=position.immutable();this.stack=stack;this.consumed=consumed;
    }
    /** サーバースレッドで、実際に保管するスタックを渡す。コピーの事前評価では発行しない。 */
    public static void observe(ServerLevel level,UUID holder,BlockPos position,ItemStack stack) {
        NeoForge.EVENT_BUS.post(new FoodStorage(level,holder,position,stack,0));
    }
    /** 確定した実消費の元スタックと正の個数を渡す。台帳の残数と一致しなければ例外とする。 */
    public static void consume(ServerLevel level,ItemStack stack,int count) {
        if(count<=0)throw new IllegalArgumentException("食品の実消費数が不正です");
        NeoForge.EVENT_BUS.post(new FoodStorage(level,new UUID(0,0),BlockPos.ZERO,stack,count));
    }
}
