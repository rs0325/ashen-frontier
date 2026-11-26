package io.github.rs0325.ashenfrontier.core.food;

import io.github.rs0325.ashenfrontier.core.SoilContamination;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/** M2が発行した由来をagricultureが照会する。未接続・確認資料欠落は未確認。配置の変更は行わない。 */
public final class CultivationQuery extends Event {
    public final FoodOrigin origin;
    public final String species, substrate, water;
    public final SoilContamination.Stages stages;
    public final FoodOrigin waterOrigin;
    private IntakeRules.Result result=IntakeRules.unknown("unavailable");
    private CultivationQuery(FoodOrigin origin, String species, String substrate, String water,
                             SoilContamination.Stages stages, FoodOrigin waterOrigin) {
        this.origin=origin;this.species=species;this.substrate=substrate;this.water=water;this.stages=stages;this.waterOrigin=waterOrigin;
    }
    public void provide(IntakeRules.Result result) { this.result=result; }
    public static IntakeRules.Result query(FoodOrigin origin, String species, String substrate, String water,
                                           SoilContamination.Stages stages, FoodOrigin waterOrigin) {
        return NeoForge.EVENT_BUS.post(new CultivationQuery(origin,species,substrate,water,stages,waterOrigin)).result;
    }
}
