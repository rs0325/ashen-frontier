package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.climate.ClimateQuery;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.MaterialData;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import io.github.rs0325.ashenfrontier.core.food.*;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 可食判定・実消費・生理受付のサーバー側境界。操作終了は取り消し可能な1つの使用から1回だけ確定する。 */
public final class IntakeService {
    private static final class Use {
        final InteractionHand hand;final ItemStack expected;final long operation;long target=-1;
        Use(InteractionHand hand,ItemStack expected,long operation){this.hand=hand;this.expected=expected;this.operation=operation;}
    }
    private static final Map<ServerPlayer, Use> USES = new IdentityHashMap<>();
    private IntakeService() {}
    static void clear(ServerPlayer player) {
        var use=USES.remove(player);
        if(use!=null&&use.target>=0&&use.expected.has(ItemProperties.RADIOACTIVITY))ExposureSources.changedSource(player);
    }
    static void clear() { USES.clear(); }
    static boolean pending(ServerPlayer player,long operation) {
        var use=USES.get(player);return use!=null&&use.operation==operation&&use.target>=0;
    }
    static void retry() {
        for(var entry:java.util.List.copyOf(USES.entrySet()))if(entry.getValue().target>=0)
            FoodUse.request(entry.getKey(),entry.getValue().hand,entry.getValue().operation,FoodUse.Action.FINISH);
    }

    static void use(FoodUse event) {
        var player = event.player();
        switch (event.action()) {
            case CANCEL -> {
                var use=USES.get(player);
                if(use!=null&&(event.operation()==0||event.operation()==use.operation))clear(player);
            }
            case START -> {
                var ledger=player.getData(AshenFrontierSurvival.INTAKE);
                if(ledger.failure!=null){message(player,"unavailable");return;}
                if(event.operation()>0&&event.operation()<=ledger.issued) {
                    var use=USES.get(player);var current=player.getItemInHand(event.hand());
                    if(use!=null&&use.operation==event.operation()&&use.hand==event.hand()&&current.getCount()==use.expected.getCount()
                            &&ItemStack.isSameItemSameComponents(current,use.expected))event.accept();
                    return;
                }
                if(event.operation()>0&&event.operation()!=ledger.issued+1)return;
                clear(player);
                var runtime = AshenFrontierSurvival.ACTIVE.get(player);
                if (runtime == null || runtime.data.blocked()) { message(player, "unavailable"); return; }
                var stack = player.getItemInHand(event.hand());
                var result = check(player, stack, false);
                if (!result.accepted()) { message(player, result.reason()); return; }
                if (available(runtime, stack) <= 0) { message(player, "full"); return; }
                var trace=stack.get(ItemProperties.CONTAMINATION);
                if(trace!=null&&trace.amounts().values().stream().anyMatch(value->value>0))message(player,"trace_contamination");
                if(!water(stack)) {
                    FoodStorageService.remember(player);
                    FoodStorage.observe(player.serverLevel(),player.getUUID(),player.blockPosition(),stack);
                }
                event.assign(++ledger.issued);
                USES.put(player, new Use(event.hand(), stack.copy(),event.operation())); event.accept();
            }
            case FINISH -> {
                var use = USES.get(player);
                if (use == null || use.hand != event.hand() || event.operation()!=0&&event.operation()!=use.operation) return;
                var ledger=player.getData(AshenFrontierSurvival.INTAKE);
                if(ledger.failure!=null||ledger.issued!=use.operation){clear(player);message(player,"unavailable");return;}
                var current = player.getItemInHand(event.hand());
                if (current.getCount() != use.expected.getCount() || !ItemStack.isSameItemSameComponents(current, use.expected)){clear(player);return;}
                var runtime = AshenFrontierSurvival.ACTIVE.get(player);
                if (runtime == null || runtime.data.blocked()){clear(player);return;}
                if(use.target<0){use.target=player.serverLevel().getGameTime();runtime.reserveIntake(use.operation);
                    if(current.has(ItemProperties.RADIOACTIVITY))ExposureSources.changedSource(player);
                }
                var evaluated = current;
                if (!water(current)) {
                    var rot = current.get(ItemProperties.ROT);
                    if (rot == null) { clear(player);message(player, "rot_history"); return; }
                    if (rot.lastCalculatedAt() != use.target||current.has(FoodProperties.STORAGE)) {
                        var request = FoodHistoryQuery.request(player, current, use.target);
                        if (request.value().isEmpty()) {
                            if(request.reason().isPresent()||!current.has(FoodProperties.STORAGE))clear(player);
                            message(player,request.reason().orElse("rot_history"));return;
                        }
                        evaluated = request.value().get();
                    }
                }
                var result = check(player, evaluated, false);
                if (!result.accepted()) { clear(player);message(player, result.reason()); return; }
                double quantity = available(runtime, evaluated);
                if (quantity <= 0) { clear(player);message(player, "full"); return; }
                var nutrition = water(evaluated) ? new NutritionData(0,0,0,quantity,Set.of()) : NutritionData.get(evaluated).orElseThrow();
                var remainder = evaluated.copy();
                ItemStack returned = ItemStack.EMPTY;
                if (water(evaluated)) {
                    remainder.setCount(1);
                    remainder.set(RelicProperties.CONTENT_MASS, Math.max(0, evaluated.get(RelicProperties.CONTENT_MASS)-quantity));
                    var radiation=evaluated.get(ItemProperties.RADIOACTIVITY);
                    if(radiation!=null) {
                        double ratio=remainder.get(RelicProperties.CONTENT_MASS)/evaluated.get(RelicProperties.CONTENT_MASS);
                        double exact=radiation.becquerels()*ratio;
                        double stored=io.github.rs0325.ashenfrontier.core.ItemPropertyMath.roundDown(exact,ItemProperties.ACTIVITY_STEP_BQ);
                        remainder.set(ItemProperties.RADIOACTIVITY,new ItemProperties.Radioactivity(radiation.nuclide(),stored,radiation.gameTime()));
                        remainder.set(FoodProperties.ACTIVITY_UNCERTAINTY,evaluated.getOrDefault(FoodProperties.ACTIVITY_UNCERTAINTY,ItemProperties.ACTIVITY_STEP_BQ)*ratio+exact-stored);
                    }
                    if (evaluated.getCount() > 1) {
                        returned = remainder; remainder = evaluated.copy(); remainder.shrink(1);
                        if (!roomFor(player, returned)) { clear(player);message(player, "return_full"); return; }
                    }
                } else remainder.shrink(1);
                // 以後は同一サーバー呼出し内で完了する。capture失敗時には在庫を変更しない。
                runtime.completeIntake(use.operation,nutrition.waterKg(),nutrition.energyJoules(),nutrition.proteinKg(),nutrition.fatKg(),
                        nutrition.vitaminGroups().contains("c"),nutrition.vitaminGroups().contains("fat_soluble"),exposure(player,evaluated,quantity,use.target));
                if(!water(evaluated))FoodStorage.consume(player.serverLevel(),evaluated,1);
                clear(player);
                player.setItemInHand(event.hand(),remainder);
                if (!returned.isEmpty() && !player.getInventory().add(returned)) throw new IllegalStateException("確認済みの返却先が変わりました");
                player.getInventory().setChanged();
                if(evaluated.has(ItemProperties.RADIOACTIVITY))ExposureSources.changedSource(player);
                event.accept();
                if (result.warning()) message(player,"rot_warning");
            }
        }
    }
    private static io.github.rs0325.ashenfrontier.core.food.IntakeExposure exposure(ServerPlayer player,ItemStack stack,double quantity,long tick) {
        double mass=water(stack)?quantity:MaterialData.get(stack.getItem()).orElseThrow().massKg();
        var substances=new java.util.LinkedHashMap<String,Double>();
        var contamination=stack.get(ItemProperties.CONTAMINATION);
        if(contamination!=null)contamination.amounts().forEach((id,fraction)->substances.put(id,fraction*mass));
        var radiation=stack.get(ItemProperties.RADIOACTIVITY);double lower=0,upper=0;String nuclide="";
        if(radiation!=null) {
            nuclide=radiation.nuclide();var definition=Nuclides.get(nuclide).orElseThrow();
            var history=ClimateQuery.dayLengthHistory(player.serverLevel()).orElseThrow();
            double factor=Math.exp(-Math.log(2)*GameTime.gameSeconds(radiation.gameTime(),tick,history)/definition.halfLifeGameSeconds());
            double portion=water(stack)?mass/stack.get(RelicProperties.CONTENT_MASS):1;
            lower=radiation.becquerels()*factor*portion;
            upper=(radiation.becquerels()+stack.getOrDefault(FoodProperties.ACTIVITY_UNCERTAINTY,ItemProperties.ACTIVITY_STEP_BQ))*factor*portion;
        }
        return new io.github.rs0325.ashenfrontier.core.food.IntakeExposure(mass,substances,nuclide,lower,upper,tick);
    }
    static boolean unresolvedSource(ServerPlayer owner,ItemStack stack) {
        var use=USES.get(owner);
        return use!=null&&use.target>=0&&owner.getItemInHand(use.hand)==stack;
    }
    private static boolean roomFor(ServerPlayer player, ItemStack returned) {
        for (var stack : player.getInventory().items)
            if (stack.isEmpty() || ItemStack.isSameItemSameComponents(stack, returned) && stack.getCount() < stack.getMaxStackSize()) return true;
        return false;
    }
    private static void message(ServerPlayer player, String reason) { player.displayClientMessage(Component.translatable("intake.ashen_frontier."+reason),true); }
    private static boolean water(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("ashen_frontier:sealed_water_container"); }
    private static double available(SurvivalRuntime runtime, ItemStack stack) {
        double pendingWater=0,pendingEnergy=0;
        for (var frame : runtime.data.frames) if (frame.kind == PhysiologyData.Kind.INTAKE) { pendingWater+=frame.a; pendingEnergy+=frame.b; }
        return water(stack) ? Math.max(0,Math.min(.25,Math.min(stack.getOrDefault(RelicProperties.CONTENT_MASS,0.0),42-runtime.data.body.water-pendingWater)))
                : runtime.data.body.energy+pendingEnergy < 20e6 ? 1 : 0;
    }

    /** 可食部を照合する。checkHistory=falseは予備判定、または別途固定末尾まで履歴を確定したコピーに使う。 */
    public static IntakeRules.Result check(ServerPlayer player, ItemStack stack, boolean checkHistory) {
        if (stack.isEmpty()) return IntakeRules.unknown("food_profile");
        if(stack.has(ItemProperties.SOIL_CONTAMINATION))return IntakeRules.unknown("contamination_unconfirmed");
        var evidence=stack.get(FoodProperties.EVIDENCE);
        if (evidence==null) return IntakeRules.unknown("origin_unconfirmed");
        var origin=evidence.origin();
        if(stack.has(FoodProperties.ORIGIN)&&!origin.equals(stack.get(FoodProperties.ORIGIN)))return IntakeRules.unknown("origin_scope");
        if (origin.state()==FoodOrigin.State.INVALIDATED) return IntakeRules.denied("origin_invalidated");
        if (origin.state()!=FoodOrigin.State.CONFIRMED || evidence.revision()!=1) return IntakeRules.unknown("origin_unconfirmed");
        String originProfile=origin.profile().startsWith("ashen_frontier:")?origin.profile().substring("ashen_frontier:".length()):origin.profile();
        if(originProfile.equals("nonfood_cultivation"))return IntakeRules.denied("nonfood");
        if(!Set.of("shelter_food_soil","expansion_food_soil","heated_oyster_substrate","canned_food","preserved_rations",
                "clean_water","pure_water","ultrapure_water").contains(originProfile))return IntakeRules.unknown("origin_profile");
        boolean water=water(stack);
        if(water&&!evidence.profile().equals("water"))return evidence.profile().equals("concentrated_residue")
                ?IntakeRules.denied("water_quality"):IntakeRules.unknown("food_profile");
        var qualityId=stack.getOrDefault(NatureProperties.QUALITY, net.minecraft.resources.ResourceLocation.withDefaultNamespace("unknown"));
        String quality=qualityId.getNamespace().equals("ashen_frontier")?qualityId.getPath():"unknown";
        String scope=water ? "ashen_frontier:"+quality : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!evidence.scope().equals(scope)) return IntakeRules.unknown("origin_scope");
        var nutrition=water ? Optional.of(new NutritionData(0,0,0,0,Set.of())) : NutritionData.get(stack);
        if (nutrition.isEmpty()) return IntakeRules.unknown("nutrition");
        double mass=water ? stack.getOrDefault(RelicProperties.CONTENT_MASS,0.0) : MaterialData.get(stack.getItem()).map(MaterialData::massKg).orElse(0.0);
        double relative=0;long now=player.serverLevel().getGameTime();
        if (!water) {
            var rot=stack.get(ItemProperties.ROT);
            if (rot==null || rot.createdAt()<0 || rot.lastCalculatedAt()<rot.createdAt() || rot.lastCalculatedAt()>now
                    || checkHistory && rot.lastCalculatedAt()!=now) return IntakeRules.unknown("rot_history");
            relative=rot.referenceSeconds()/IntakeRules.lifetime(evidence.profile());
        }
        var chemicals=stack.get(ItemProperties.CONTAMINATION);
        var radiation=stack.get(ItemProperties.RADIOACTIVITY);
        if (chemicals==null && !evidence.cleanBaseline() || radiation==null && !evidence.cleanBaseline()) return IntakeRules.unknown("contamination_unconfirmed");
        double elapsed=0;
        if (radiation!=null) {
            if(Nuclides.get(radiation.nuclide()).isEmpty())return IntakeRules.unknown("nuclide");
            var history=ClimateQuery.dayLengthHistory(player.serverLevel());
            if (history.isEmpty() || radiation.gameTime()<0 || radiation.gameTime()>now) return IntakeRules.unknown("clock");
            elapsed=GameTime.gameSeconds(radiation.gameTime(),now,history.get());
        }
        return IntakeRules.check(water?"water":evidence.profile(),quality,mass,relative,nutrition.get().vitaminGroups(),
                chemicals==null?Map.of():chemicals.amounts(),radiation==null?"":radiation.nuclide(),radiation==null?0:radiation.becquerels(),
                evidence.cleanBaseline()&&radiation==null,elapsed,stack.getOrDefault(FoodProperties.ACTIVITY_UNCERTAINTY,ItemProperties.ACTIVITY_STEP_BQ));
    }
}
