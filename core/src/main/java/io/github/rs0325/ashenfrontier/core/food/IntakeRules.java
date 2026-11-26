package io.github.rs0325.ashenfrontier.core.food;

import java.util.Map;
import java.util.Set;

/** 第1期の食品規則。濃度はkg/kg、放射能は可食部のBq/kg。土壌の段階値を入力しない。 */
public final class IntakeRules {
    public enum Status { ELIGIBLE, INELIGIBLE, UNKNOWN }
    public record Result(Status status, String reason, boolean warning) {
        public boolean accepted() { return status == Status.ELIGIBLE; }
    }
    public static final double CS137_HALF_LIFE = 30.05 * 365.25 * 86400;
    private static final Set<String> WATER = Set.of("clean_water", "pure_water", "ultrapure_water");
    private static final Set<String> UNSAFE_WATER = Set.of("raw_water", "turbid_water", "contaminated_water", "toxic_water", "polluted_seawater");
    private IntakeRules() {}

    public static double lifetime(String profile) {
        return switch (profile) {
            case "canned_food" -> 180 * 86400.0;
            case "preserved_rations" -> 300 * 86400.0;
            case "cooked_staple", "cooked_soy", "vegetable_soup" -> 20 * 86400.0;
            case "dried_vegetables", "dried_mushrooms" -> 200 * 86400.0;
            default -> 0;
        };
    }

    public static Result unknown(String reason) { return new Result(Status.UNKNOWN, reason, false); }
    public static Result denied(String reason) { return new Result(Status.INELIGIBLE, reason, false); }
    public static Result allowed(boolean warning) { return new Result(Status.ELIGIBLE, "accepted", warning); }

    /** knownZeroは検証済み由来が当該可食部の未汚染を保証するときだけtrue。丸めた0 Bqとは区別する。 */
    public static Result check(String profile, String waterQuality, double massKg, double relativeRot,
                               Set<String> vitamins, Map<String, Double> chemicals, String nuclide,
                               double activityBq, boolean knownZero, double elapsedGameSeconds) {
        return check(profile,waterQuality,massKg,relativeRot,vitamins,chemicals,nuclide,activityBq,knownZero,elapsedGameSeconds,.1);
    }
    public static Result check(String profile, String waterQuality, double massKg, double relativeRot,
                               Set<String> vitamins, Map<String, Double> chemicals, String nuclide,
                               double activityBq, boolean knownZero, double elapsedGameSeconds, double activityUncertainty) {
        boolean water = profile.equals("water");
        if (!Double.isFinite(massKg) || massKg <= 0 || !Double.isFinite(relativeRot) || relativeRot < 0
                || !Double.isFinite(elapsedGameSeconds) || elapsedGameSeconds < 0
                || !Double.isFinite(activityUncertainty) || activityUncertainty < 0)
            return unknown("invalid_amount");
        if (water) {
            if (UNSAFE_WATER.contains(waterQuality) || waterQuality.equals("concentrated_residue")) return denied("water_quality");
            if (!WATER.contains(waterQuality)) return unknown("water_quality");
        } else if (lifetime(profile) == 0) return unknown("food_profile");
        else if (relativeRot >= 1) return denied("rotten");
        String unconfirmed = Set.of("c", "fat_soluble").containsAll(vitamins) ? "" : "vitamin";
        boolean unsafe = false;
        for (var entry : chemicals.entrySet()) {
            double limit = switch (entry.getKey()) {
                case "lead" -> water ? 1e-8 : 2e-7;
                case "pcb" -> water ? 5e-10 : 2e-7;
                default -> -1;
            };
            if (limit < 0 || !Double.isFinite(entry.getValue()) || entry.getValue() < 0) unconfirmed = "chemical";
            else unsafe |= entry.getValue() > limit;
        }
        if (unsafe) return denied("chemical_limit");
        if (!knownZero || !nuclide.isEmpty()) {
            if (!nuclide.equals("ashen_frontier:cs137") || !Double.isFinite(activityBq) || activityBq < 0)
                return unknown("nuclide");
            double upper = (activityBq + activityUncertainty) * Math.pow(.5, elapsedGameSeconds / CS137_HALF_LIFE) / massKg;
            if (upper > (water ? 10 : 100)) return denied("radioactivity_limit");
        }
        if (!unconfirmed.isEmpty()) return unknown(unconfirmed);
        return allowed(!water && relativeRot >= .8);
    }

    /** 1原料系統の加工。出力の保存性が変わっても劣化率を若返らせない。 */
    public static double inheritedRot(double referenceSeconds, String source, String target) {
        double before = lifetime(source), after = lifetime(target);
        if (before <= 0 || after <= 0 || !Double.isFinite(referenceSeconds) || referenceSeconds < 0)
            throw new IllegalArgumentException("腐敗の継承元・継承先が未定義です");
        return referenceSeconds * after / before;
    }
}
