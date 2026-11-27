package io.github.rs0325.ashenfrontier.core.food;

import java.util.Map;

/** 完了した1回分の実摂取量。係数や症状は所有せず、受領側が同じ栄養操作に適用する。 */
public record IntakeExposure(double massKg,Map<String,Double> substancesKg,String nuclide,
                             double activityLowerBq,double activityUpperBq,long tick) {
    public IntakeExposure {
        substancesKg=Map.copyOf(substancesKg);
        if(!Double.isFinite(massKg+activityLowerBq+activityUpperBq)||massKg<0||activityLowerBq<0
                ||activityUpperBq<activityLowerBq||tick<0||nuclide==null)
            throw new IllegalArgumentException("確定摂取の曝露量が不正です");
        for(var entry:substancesKg.entrySet())if(entry.getKey().isBlank()||!Double.isFinite(entry.getValue())
                ||entry.getValue()<0||entry.getValue()>massKg)throw new IllegalArgumentException("摂取した物質量が不正です");
        if(nuclide.isEmpty()&&activityUpperBq!=0)throw new IllegalArgumentException("核種不明のBqを無核種として保存できません");
    }
}
