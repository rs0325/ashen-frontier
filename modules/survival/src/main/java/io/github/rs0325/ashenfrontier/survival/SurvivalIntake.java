package io.github.rs0325.ashenfrontier.survival;

import net.minecraft.server.level.ServerPlayer;

/** 確定した摂取の生理側受け口。食品の可食判定/消費transaction/投与IDの重複排除は#125の責務。 */
public final class SurvivalIntake {
    private SurvivalIntake() {}
    /** 確定したオンラインの摂取だけをサーバースレッドで呼ぶ。量はkg/J、ビタミンは定性有無。 */
    public static void accepted(ServerPlayer player,double waterKg,double energyJ,double proteinKg,double fatKg,
                                boolean vitaminC,boolean fatSoluble) {
        if(!Double.isFinite(waterKg+energyJ+proteinKg+fatKg)||waterKg<0||energyJ<0||proteinKg<0||fatKg<0)
            throw new IllegalArgumentException("摂取の単位が不正です");
        var runtime=AshenFrontierSurvival.ACTIVE.get(player);
        if(runtime==null)throw new IllegalStateException("オンラインの生理状態がありません");
        runtime.intake(waterKg,energyJ,proteinKg,fatKg,vitaminC,fatSoluble);
    }
}
