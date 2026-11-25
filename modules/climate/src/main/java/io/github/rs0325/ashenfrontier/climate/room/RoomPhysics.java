package io.github.rs0325.ashenfrontier.climate.room;

/** 十分混合の一室近似。濃度はモル分率、発生量はmol、熱はJ、時間はREAL秒。 */
public final class RoomPhysics {
    public static final double R = 8.314462618;
    public static final double REFERENCE_K = 293.15;
    private static final double MOL_PER_M3 = 101325 / (R * REFERENCE_K);
    private RoomPhysics() {}

    public record Air(double temperatureK, double pressurePa, double co, double co2, double oxygen, double windMS) {
        public Air {
            if (!Double.isFinite(temperatureK) || temperatureK <= 0 || !Double.isFinite(pressurePa) || pressurePa <= 0
                    || !fraction(co) || !fraction(co2) || !fraction(oxygen) || !Double.isFinite(windMS) || windMS < 0)
                throw new IllegalArgumentException("室内境界のSI値が不正です");
        }
        private static boolean fraction(double v) { return Double.isFinite(v) && v >= 0 && v <= 1; }
    }
    public record State(double temperatureK, double co, double co2, double oxygen) {
        public static State ambient(Air air) { return new State(air.temperatureK, air.co, air.co2, air.oxygen); }
    }
    public record Amount(double heatJ, double coMol, double co2Mol, double oxygenMol) {
        public Rate rate(double perSecond) { return new Rate(heatJ * perSecond, coMol * perSecond, co2Mol * perSecond, oxygenMol * perSecond); }
    }
    public record Rate(double heatW, double coMolS, double co2MolS, double oxygenMolS) {
        public static final Rate ZERO = new Rate(0, 0, 0, 0);
        public Rate plus(Rate other) { return new Rate(heatW + other.heatW, coMolS + other.coMolS, co2MolS + other.co2MolS, oxygenMolS + other.oxygenMolS); }
        public Rate scale(double factor) { return new Rate(heatW * factor, coMolS * factor, co2MolS * factor, oxygenMolS * factor); }
    }

    /** 風と浮力を二乗和で合成する。単一開口の双方向流を1/2で集約するゲーム近似。 */
    public static double flow(double areaM2, double heightM, double indoorK, Air outside) {
        return 0.5 * 0.6 * areaM2 * Math.sqrt(outside.windMS * outside.windMS
                + 2 * 9.80665 * heightM * Math.abs(indoorK - outside.temperatureK) / outside.temperatureK);
    }

    /** qと境界はこの小区間で一定。壁熱容量と貫流率は代表的なゲーム設定。 */
    public static State advance(State state, Air out, double volumeM3, double wallM2, double qM3S, Rate source, double seconds) {
        double moles = moles(volumeM3);
        double airCapacity = moles * 0.02897 * 1005;
        double capacity = capacity(volumeM3, wallM2);
        double conductance = qM3S * airCapacity / volumeM3 + wallM2;
        return new State(evolve(state.temperatureK, out.temperatureK, source.heatW / capacity, conductance / capacity, seconds),
                Math.max(0, evolve(state.co, out.co, source.coMolS / moles, qM3S / volumeM3, seconds)),
                Math.max(0, evolve(state.co2, out.co2, source.co2MolS / moles, qM3S / volumeM3, seconds)),
                Math.max(0, evolve(state.oxygen, out.oxygen, source.oxygenMolS / moles, qM3S / volumeM3, seconds)));
    }

    public static State inject(State state, Air out, double volumeM3, double wallM2, Amount amount) {
        double moles = moles(volumeM3);
        double capacity = capacity(volumeM3, wallM2);
        return new State(state.temperatureK + amount.heatJ / capacity,
                Math.max(0, state.co + amount.coMol / moles), Math.max(0, state.co2 + amount.co2Mol / moles),
                Math.max(0, state.oxygen + amount.oxygenMol / moles));
    }

    /** 固定密度の十分混合近似。外気の温度/気圧が変わっても保有molを作り直さない。 */
    public static double moles(double volumeM3) { return MOL_PER_M3 * volumeM3; }
    public static double capacity(double volumeM3, double wallM2) { return moles(volumeM3) * .02897 * 1005 + wallM2 * 20000; }

    /** 293.15 Kを熱量0とし、空気と壁を合わせた顕熱Jと各ガスmolへ変換する。 */
    public static Amount amount(State state, double volumeM3, double wallM2) {
        double mol = moles(volumeM3);
        return new Amount((state.temperatureK - REFERENCE_K) * capacity(volumeM3, wallM2),
                state.co * mol, state.co2 * mol, state.oxygen * mol);
    }

    /** 構成変更で配分した示量変数から、新しい熱容量/容積で示強変数を求める。 */
    public static State state(Amount amount, double volumeM3, double wallM2) {
        double mol = moles(volumeM3);
        return new State(REFERENCE_K + amount.heatJ / capacity(volumeM3, wallM2),
                amount.coMol / mol, amount.co2Mol / mol, amount.oxygenMol / mol);
    }

    static double evolve(double current, double outside, double source, double lambda, double seconds) {
        if (lambda == 0) return current + source * seconds;
        double response = -Math.expm1(-lambda * seconds);
        return current + (outside - current) * response + source * response / lambda;
    }
}
