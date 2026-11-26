package io.github.rs0325.ashenfrontier.core.food;

import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.ItemPropertyMath;
import io.github.rs0325.ashenfrontier.core.MaterialData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** 単一の食品原料系統だけを数量換算する。別原料の混合・殺菌・検査を代行しない。 */
public final class FoodInheritance {
    private FoodInheritance() {}
    public static void apply(ItemStack source, int consumed, ItemStack target, String recipe) {
        var evidence=source.get(FoodProperties.EVIDENCE);
        var nutrition=NutritionData.get(source);
        if(evidence==null&&nutrition.isEmpty()) return;
        if(consumed<=0||target.isEmpty()) throw new IllegalArgumentException("食品継承の個数が不正です");
        double ratio=consumed/(double)target.getCount();
        nutrition.ifPresent(n->target.set(FoodProperties.NUTRITION,new NutritionData(n.energyJoules()*ratio,n.proteinKg()*ratio,
                n.fatKg()*ratio,n.waterKg()*ratio,n.vitaminGroups())));
        if(evidence==null)return;
        if(source.has(FoodProperties.ORIGIN))target.set(FoodProperties.ORIGIN,source.get(FoodProperties.ORIGIN));
        var chemicals=source.get(ItemProperties.CONTAMINATION);
        if(chemicals!=null) {
            double sourceMass=MaterialData.get(source.getItem()).orElseThrow().massKg();
            double targetMass=MaterialData.get(target.getItem()).orElseThrow().massKg();
            if(sourceMass<=0||targetMass<=0)throw new IllegalArgumentException("食品継承の可食質量が不正です");
            var values=new java.util.HashMap<String,Double>();
            chemicals.amounts().forEach((key,value)->values.put(key,value*sourceMass*ratio/targetMass));
            target.set(ItemProperties.CONTAMINATION,new ItemProperties.Contamination(values));
        }
        var radio=source.get(ItemProperties.RADIOACTIVITY);
        if(radio!=null) {
            double value=radio.becquerels()*ratio;
            double stored=ItemPropertyMath.roundDown(value,ItemProperties.ACTIVITY_STEP_BQ);
            double uncertainty=source.getOrDefault(FoodProperties.ACTIVITY_UNCERTAINTY,ItemProperties.ACTIVITY_STEP_BQ)*ratio+value-stored;
            target.set(ItemProperties.RADIOACTIVITY,new ItemProperties.Radioactivity(radio.nuclide(),stored,radio.gameTime()));
            target.set(FoodProperties.ACTIVITY_UNCERTAINTY,uncertainty);
        }
        String profile=target.getOrDefault(FoodProperties.PROFILE,BuiltInRegistries.ITEM.getKey(target.getItem()).getPath());
        var rot=source.get(ItemProperties.ROT);
        target.remove(FoodProperties.EVIDENCE);
        if(evidence==null||rot==null||IntakeRules.lifetime(evidence.profile())<=0||IntakeRules.lifetime(profile)<=0)return;
        double reference=IntakeRules.inheritedRot(rot.referenceSeconds(),evidence.profile(),profile);
        target.set(ItemProperties.ROT,new ItemProperties.Rot(rot.createdAt(),rot.lastCalculatedAt(),Math.ceil(reference/600)*600));
        target.set(FoodProperties.EVIDENCE,new FoodEvidence(evidence.origin(),BuiltInRegistries.ITEM.getKey(target.getItem()).toString(),
                profile,evidence.cleanBaseline(),recipe,evidence.revision()));
    }
}
