package io.github.rs0325.ashenfrontier.survival;

import java.util.Arrays;

/** Minecraft非依存の個人生理。単位と係数の正本は設計14の#124表。 */
public final class Body {
    public double core=309.95, skin=306.85, water=42, energy=20_000_000, protein=.12, fat=.08;
    public double stamina=100, fatigue, sleepDebt, mental=80, injury, wet;
    public double vitaminC, vitaminFat, emptyEnergy, emptyProtein, emptyFat;
    public double coldFeeling, coldStrong, coldDanger, warmFeeling, damage, wearFraction;
    public boolean exhausted;
    final int[] levels=new int[13];
    public Body copy() {
        var b=new Body();b.values(values());b.exhausted=exhausted;
        System.arraycopy(levels,0,b.levels,0,levels.length);return b;
    }
    /** 保存codecと独立検査用の固定順。並び替えは形式変更。 */
    public double[] values() {
        return new double[]{core,skin,water,energy,protein,fat,stamina,fatigue,sleepDebt,mental,injury,wet,
                vitaminC,vitaminFat,emptyEnergy,emptyProtein,emptyFat,coldFeeling,coldStrong,coldDanger,warmFeeling,damage,wearFraction};
    }
    void values(double[] v) {
        if(v.length!=23||Arrays.stream(v).anyMatch(x->!Double.isFinite(x)))
            throw new IllegalArgumentException("生理状態が不正です");
        core=v[0];skin=v[1];water=v[2];energy=v[3];protein=v[4];fat=v[5];stamina=v[6];fatigue=v[7];
        sleepDebt=v[8];mental=v[9];injury=v[10];wet=v[11];vitaminC=v[12];vitaminFat=v[13];
        emptyEnergy=v[14];emptyProtein=v[15];emptyFat=v[16];coldFeeling=v[17];coldStrong=v[18];coldDanger=v[19];
        warmFeeling=v[20];damage=v[21];wearFraction=v[22];
        if(core<=0||skin<=0||water<0||water>42||energy<0||energy>20_000_000||protein<0||protein>.18
                ||fat<0||fat>.12||stamina<0||stamina>100||fatigue<0||fatigue>100||sleepDebt<0||sleepDebt>86400
                ||mental<0||mental>100||injury<0||injury>100||wet<0||wet>1
                ||Arrays.stream(v,12,v.length).anyMatch(x->x<0)||wearFraction>=1)
            throw new IllegalArgumentException("生理状態の範囲が不正です");
    }
    public int level(int index) { return levels[index]; }
    public int severity() { return Arrays.stream(levels).max().orElse(0); }
    public double workFactor() { return new double[]{1,.9,.75,.5}[severity()]; }
    public double wearFactor() { return 1+.25*Math.clamp((40-mental)/40,0,1); }
}
