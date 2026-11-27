package io.github.rs0325.ashenfrontier.core;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/** 消費が確定した道具摩耗。係数の端数は生理側が所有し、再試行の事前見積もりでは発火しない。 */
public final class ToolWearEvent extends Event {
    private final LivingEntity user;
    private final double base;
    private double amount;
    private ToolWearEvent(LivingEntity user,double base) { this.user=user;this.base=base;amount=base; }
    public LivingEntity user() { return user; }
    public double base() { return base; }
    public double amount() { return amount; }
    public void add(double extra) {
        if(!Double.isFinite(extra)||extra<0)throw new IllegalArgumentException("摩耗の追加量が不正です");
        amount+=extra;
    }
    public static double committed(LivingEntity user,double base) {
        return NeoForge.EVENT_BUS.post(new ToolWearEvent(user,base)).amount;
    }
}
