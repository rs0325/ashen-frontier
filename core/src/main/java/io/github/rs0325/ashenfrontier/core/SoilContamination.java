package io.github.rs0325.ashenfrontier.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;

/** 乾燥固形物の代表指標。質量分率kg/kg、導電率S/m、無次元pH、比放射能Bq/kg、菌数推定MPN/kg。 */
public record SoilContamination(double lead, double pcb, double conductivity, double ph,
                                double caesium137, double fecalColiform) {
    public static final SoilContamination CLEAN = new SoilContamination(1e-5, 0, .05, 6.5, 20, 0);
    public static final Codec<SoilContamination> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("lead").forGetter(SoilContamination::lead),
            Codec.DOUBLE.fieldOf("pcb").forGetter(SoilContamination::pcb),
            Codec.DOUBLE.fieldOf("conductivity").forGetter(SoilContamination::conductivity),
            Codec.DOUBLE.fieldOf("ph").forGetter(SoilContamination::ph),
            Codec.DOUBLE.fieldOf("caesium137").forGetter(SoilContamination::caesium137),
            Codec.DOUBLE.fieldOf("fecal_coliform").forGetter(SoilContamination::fecalColiform)
    ).apply(i, SoilContamination::new));

    public enum Kind {
        METALS("lead", "mg/kg", 1e6, new double[]{5e-5, 1.5e-4, 5e-4, .0015}),
        ORGANICS("pcb", "mg/kg", 1e6, new double[]{1e-6, 1e-5, 2.5e-5, 5e-5}),
        SALTS("conductivity", "dS/m", 10, new double[]{.2, .4, .8, 1.6}),
        ACIDITY("ph", "pH", 1, new double[]{5.6, 5.1, 4.5, 3.5}),
        RADIOACTIVE("caesium137", "Bq/kg", 1, new double[]{100, 400, 8000, 100000}),
        BIOLOGICAL("fecal_coliform", "MPN/g", .001, new double[]{1e6, 1e7, 1e8, 2e9});

        public final String key, unit;
        public final double displayFactor;
        private final double[] thresholds;

        Kind(String key, String unit, double displayFactor, double[] thresholds) {
            this.key = key;
            this.unit = unit;
            this.displayFactor = displayFactor;
            this.thresholds = thresholds;
        }

        /** 清浄0〜有毒4。pHは境界値を清浄側へ、それ以外は高段階へ含める。 */
        public int stage(double value) {
            int stage = 0;
            while (stage < 4 && (this == ACIDITY ? value < thresholds[stage] : value >= thresholds[stage])) stage++;
            return stage;
        }

        public double boundary(int stage) { return thresholds[stage - 1]; }
    }

    public double value(Kind kind) {
        return switch (kind) {
            case METALS -> lead;
            case ORGANICS -> pcb;
            case SALTS -> conductivity;
            case ACIDITY -> ph;
            case RADIOACTIVE -> caesium137;
            case BIOLOGICAL -> fecalColiform;
        };
    }

    public int stage(Kind kind) { return kind.stage(value(kind)); }

    public boolean severe() {
        for (var kind : Kind.values()) if (stage(kind) >= 3) return true;
        return false;
    }

    /** 地表からの増分を減衰させる。酸性は水素イオン活量で補間する。 */
    public SoilContamination atDepth(int depth) {
        int d = Math.max(0, depth);
        double fast = d >= 3 ? 0 : Math.pow(.25, d), slow = d >= 3 ? 0 : Math.pow(.5, d);
        double salt = d >= 4 ? 0 : 1 - d * .25;
        return new SoilContamination(blend(CLEAN.lead, lead, fast), blend(0, pcb, slow),
                blend(CLEAN.conductivity, conductivity, salt),
                -Math.log10(blend(Math.pow(10, -CLEAN.ph), Math.pow(10, -ph), slow)),
                blend(CLEAN.caesium137, caesium137, fast), fecalColiform * fast);
    }

    private static double blend(double a, double b, double t) { return t == 0 ? a : t == 1 ? b : a + (b - a) * t; }

    /** 放射能は既存の核種付き総Bq成分へ分離する。他の5指標のキーと単位は本recordと共通。 */
    public ItemProperties.Contamination itemContamination() {
        return ItemProperties.Contamination.of(Map.of("lead", lead, "pcb", pcb, "conductivity", conductivity,
                "ph", ph, "fecal_coliform", fecalColiform));
    }

    /** Cs-137比放射能Bq/kgと成分から復元する。欠損キーは清浄値、単位はitemContaminationと共通。 */
    public static SoilContamination fromItem(ItemProperties.Contamination chemical, double specificActivity) {
        var values = chemical.amounts();
        return new SoilContamination(values.getOrDefault("lead", CLEAN.lead), values.getOrDefault("pcb", CLEAN.pcb),
                values.getOrDefault("conductivity", CLEAN.conductivity), values.getOrDefault("ph", CLEAN.ph),
                specificActivity, values.getOrDefault("fecal_coliform", CLEAN.fecalColiform));
    }
}
