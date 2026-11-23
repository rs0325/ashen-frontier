package io.github.rs0325.ashenfrontier.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

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

        /** 清浄0〜有毒4。境界値は高段階へ含め、pHは低いほど高段階とする。 */
        public int stage(double value) {
            int stage = 0;
            while (stage < 4 && (this == ACIDITY ? value <= thresholds[stage] : value >= thresholds[stage])) stage++;
            return stage;
        }

        public double boundary(int stage) { return thresholds[stage - 1]; }

        /** 清浄0〜有毒4の設置用SI代表値。有限区間は幾何平均（pHは算術平均）、有毒は境界の2倍（pHは-0.5）。 */
        public double representative(int stage) {
            if (stage == 0) return CLEAN.value(this);
            double lower = boundary(stage);
            if (stage == 4) return this == ACIDITY ? lower - .5 : lower * 2;
            double upper = boundary(stage + 1);
            return this == ACIDITY ? (lower + upper) / 2 : Math.sqrt(lower * upper);
        }
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

    public Stages stages() {
        return new Stages(stage(Kind.METALS), stage(Kind.ORGANICS), stage(Kind.SALTS),
                stage(Kind.ACIDITY), stage(Kind.RADIOACTIVE), stage(Kind.BIOLOGICAL));
    }

    /** 土アイテムの6種類の段階。0は清浄で、保存時に省略する。位置の連続値とは分離する。 */
    public record Stages(int lead, int pcb, int conductivity, int ph, int caesium137, int fecalColiform) {
        public static final Stages CLEAN = new Stages(0, 0, 0, 0, 0, 0);
        private static final Codec<Integer> STAGE = Codec.intRange(0, 4);
        public static final Codec<Stages> CODEC = RecordCodecBuilder.create(i -> i.group(
                STAGE.optionalFieldOf("lead", 0).forGetter(Stages::lead),
                STAGE.optionalFieldOf("pcb", 0).forGetter(Stages::pcb),
                STAGE.optionalFieldOf("conductivity", 0).forGetter(Stages::conductivity),
                STAGE.optionalFieldOf("ph", 0).forGetter(Stages::ph),
                STAGE.optionalFieldOf("caesium137", 0).forGetter(Stages::caesium137),
                STAGE.optionalFieldOf("fecal_coliform", 0).forGetter(Stages::fecalColiform)
        ).apply(i, Stages::new));

        public int stage(Kind kind) {
            return switch (kind) {
                case METALS -> lead;
                case ORGANICS -> pcb;
                case SALTS -> conductivity;
                case ACIDITY -> ph;
                case RADIOACTIVE -> caesium137;
                case BIOLOGICAL -> fecalColiform;
            };
        }

        /** アイテム設置用の代表濃度を返す。元の連続値は復元しない。 */
        public SoilContamination representative() {
            return new SoilContamination(Kind.METALS.representative(lead), Kind.ORGANICS.representative(pcb),
                    Kind.SALTS.representative(conductivity), Kind.ACIDITY.representative(ph),
                    Kind.RADIOACTIVE.representative(caesium137), Kind.BIOLOGICAL.representative(fecalColiform));
        }
    }
}
