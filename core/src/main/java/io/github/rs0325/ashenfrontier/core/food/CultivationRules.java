package io.github.rs0325.ashenfrontier.core.food;

import java.util.Set;

/** M2の確認済み由来と栽培入力の照合。育成速度や数値汚染の移行は扱わない。 */
public final class CultivationRules {
    private CultivationRules() {}
    public static IntakeRules.Result waterOrigin(String soilMap, String soilManifest, String quality,
                                                  String waterMap, String waterManifest, String profile, String state) {
        if(profile.equals("ashen_frontier:concentrated_residue"))return IntakeRules.denied("water_quality");
        if(state.equals("INVALIDATED"))return IntakeRules.denied("origin_invalidated");
        if(!state.equals("CONFIRMED")||!soilMap.equals(waterMap)||!soilManifest.equals(waterManifest)
                ||!Set.of("clean_water","pure_water","ultrapure_water").contains(path(profile))
                ||!path(quality).equals(path(profile)))return IntakeRules.unknown("origin_unconfirmed");
        return IntakeRules.allowed(false);
    }
    public static IntakeRules.Result check(String profile, String state, String species, String substrate,
                                            String water, boolean cleanStages, boolean confirmedWater) {
        profile=path(profile);species=path(species);substrate=path(substrate);water=path(water);
        if (state.equals("INVALIDATED")) return IntakeRules.denied("origin_invalidated");
        if (Set.of("contaminated_water", "toxic_water", "polluted_seawater").contains(water))
            return IntakeRules.denied("water_quality");
        if (!state.equals("CONFIRMED") || !confirmedWater) return IntakeRules.unknown("origin_unconfirmed");
        Set<String> speciesSet = switch (profile) {
            case "shelter_food_soil", "expansion_food_soil" -> Set.of("barley", "soybean", "leafy_greens");
            case "heated_oyster_substrate" -> Set.of("oyster_mushroom");
            case "nonfood_cultivation" -> Set.of("flax", "coppice_willow");
            case "certified_crop_residue", "certified_plant_compost" -> Set.of();
            default -> null;
        };
        if (speciesSet == null) return IntakeRules.unknown("origin_profile");
        if (!speciesSet.contains(species)) return IntakeRules.denied("crop_species");
        if (profile.equals("nonfood_cultivation")) return IntakeRules.denied("nonfood");
        if (!cleanStages) return IntakeRules.denied("soil_contamination");
        if (!(profile.equals("heated_oyster_substrate") ? substrate.equals("heated_oyster_substrate") : substrate.equals("fertile_soil")))
            return IntakeRules.denied("substrate");
        if (!Set.of("clean_water", "pure_water", "ultrapure_water").contains(water)) return IntakeRules.denied("water_quality");
        return IntakeRules.allowed(false);
    }
    private static String path(String id) { return id.startsWith("ashen_frontier:") ? id.substring("ashen_frontier:".length()) : id; }
}
