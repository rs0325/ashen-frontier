package io.github.rs0325.ashenfrontier.primitive;

import io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentRetention;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import io.github.rs0325.ashenfrontier.core.recipe.WaterTank;
import io.github.rs0325.ashenfrontier.core.recipe.WaterMass;
import io.github.rs0325.ashenfrontier.core.water.WaterTankOwner;
import io.github.rs0325.ashenfrontier.core.water.WaterTransfers;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.MaterialData;
import io.github.rs0325.ashenfrontier.core.catalog.FuelData;
import io.github.rs0325.ashenfrontier.core.environment.CombustionEvent;
import io.github.rs0325.ashenfrontier.core.environment.CombustionSourceQuery;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentSample;
import io.github.rs0325.ashenfrontier.core.recipe.CommonRecipe;
import io.github.rs0325.ashenfrontier.core.recipe.ProcessingStation;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Comparator;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.Map;
import java.util.HashMap;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import net.minecraft.server.MinecraftServer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/** 加工の所有は共通基盤、着火操作と燃料・余熱だけを炉が保持する。M3入力なしでは停止する。 */
public final class ThermalStationEntity extends BlockEntity implements WaterTankOwner {
    private static final Map<MinecraftServer, Map<UUID, WeakReference<ThermalStationEntity>>> SOURCES = new WeakHashMap<>();
    private static final Map<ServerLevel, WorkQueue> WORK = new WeakHashMap<>();
    private static final class WorkRequest {
        final WeakReference<ThermalStationEntity> station;
        long through,order;
        boolean queued;
        WorkRequest(ThermalStationEntity station) { this.station=new WeakReference<>(station); }
    }
    private static final class WorkQueue {
        final NavigableSet<WorkRequest> ready=new TreeSet<>(
                Comparator.comparingLong((WorkRequest r)->r.through).thenComparingLong(r->r.order));
        final NavigableSet<WorkRequest> waiting=new TreeSet<>(Comparator.comparingLong(r->r.order));
        // 未処理末尾と待機順を交互に選び、細分化された仕事と新しい待機の両方を進める。
        boolean oldestFrontier=true;
        long order;
        void offer(ThermalStationEntity station) {
            if(station.workRequest==null)station.workRequest=new WorkRequest(station);
            var request=station.workRequest;
            if(request.queued)return;
            request.through=station.frontier();request.order=++order;request.queued=true;ready.add(request);waiting.add(request);
        }
        void remove(ThermalStationEntity station) {
            var request=station.workRequest;
            if(request!=null&&request.queued)removeRequest(request);
        }
        ThermalStationEntity next(ServerLevel level,long deadline) {
            while(!ready.isEmpty()&&System.nanoTime()<deadline) {
                var request=(oldestFrontier?ready:waiting).first();
                var station=request.station.get();
                if(station==null||station.isRemoved()||station.level!=level||!level.hasChunkAt(station.worldPosition)){removeRequest(request);continue;}
                if(request.through!=station.frontier()){removeRequest(request);offer(station);continue;}
                return station;
            }
            return null;
        }
        private void removeRequest(WorkRequest request) { ready.remove(request);waiting.remove(request);request.queued=false; }
    }
    private ProcessingStation station;
    private final HearthHeat heat = new HearthHeat();
    private final BoilingHeat boilingHeat = new BoilingHeat();
    private ItemStack fuel = ItemStack.EMPTY;
    private ItemStack tinder = ItemStack.EMPTY;
    private ItemStack ignitionTool = ItemStack.EMPTY;
    private ResourceLocation selected;
    private ResourceLocation fuelProfile;
    private UUID sourceId = UUID.randomUUID();
    private long notificationSequence;
    private UUID worker;
    private long workTick;
    private long lastTick = -1;
    private long coolingTarget=-1,workTarget=-1;
    private WorkRequest workRequest;
    private long leaseGeneration,leaseFrom,handoffTick=-1;
    private BlockPos heatReference;
    private double frictionSeconds;
    private boolean igniting;
    private boolean lit;
    private boolean restored,discardClosed;
    private long evaporatedMg;

    public ThermalStationEntity(BlockPos pos, BlockState state) {
        super(PrimitiveContent.THERMAL_ENTITY.get(), pos, state);
        station = new ProcessingStation(1, 2, state.is(PrimitiveContent.HEARTH.get()) ? 4 : 0.000001);
        selected = AshenFrontierCore.id(state.is(PrimitiveContent.HEARTH.get()) ? "primitive/fire_pot" : "primitive/dry_pot");
    }

    ProcessingStation station() { return station; }
    HearthHeat heat() { return heat; }
    @Override
    public WaterTank waterTank() { return station.water(); }
    private boolean boiling() { return selected.getPath().startsWith("primitive/boil_water"); }
    private boolean highAltitude() { return (worldPosition.getY() - 63) * 25 > 2000; }
    boolean lit() { return lit; }
    private boolean hearth() { return getBlockState().is(PrimitiveContent.HEARTH.get()); }
    private ResourceLocation facility() { return AshenFrontierCore.id(hearth() ? "hearth" : "drying_rack"); }
    private int interval() { return hearth() ? 20 : 200; }
    private static boolean dry(ItemStack stack) { return stack.getOrDefault(NatureProperties.MOISTURE.get(), 0.0) == 0; }

    public void interact(ServerPlayer player, InteractionHand hand, Direction face) {
        if (worker != null && !worker.equals(player.getUUID()) && (station.active() || igniting)) {
            player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.busy"));
            return;
        }
        workBudgeted();
        var held = player.getItemInHand(hand);
        if(hearth()&&lastTick<level.getGameTime()) {
            if(held.isEmpty()&&!player.isShiftKeyDown()&&(!station.output(0).isEmpty()||!station.output(1).isEmpty())) {
                collect(player);setChanged();return;
            }
            player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.environment_missing"));
            return;
        }
        var storedKg = held.get(RelicProperties.CONTENT_MASS.get());
        if (storedKg != null && storedKg >= 0 && storedKg <= WaterMass.MAX_KG)
            held.set(RelicProperties.CONTENT_MASS, WaterMass.toKg(WaterMass.fromKg(storedKg)));
        if (boiling() && selected.getPath().endsWith("_high") != highAltitude()) {
            cancelProcessing(player);
            selected = AshenFrontierCore.id(highAltitude() ? "primitive/boil_water_high" : "primitive/boil_water");
        }
        if (hearth() && held.has(RelicProperties.CONTENT_MASS.get())
                && (!held.is(PrimitiveContent.item("ceramic_pot"))
                || held.getOrDefault(RelicProperties.CONTENT_MASS.get(), 0.0) > 0 || player.isShiftKeyDown())) {
            var result = player.isShiftKeyDown()
                    ? WaterTransfers.tankToContainer(player, hand, this, 4)
                    : WaterTransfers.containerToTank(player, hand, this, 4);
            if (result.movedMg() > 0) {
                station.cancel(player);
                releaseHeat(boilingHeat.reset());
            }
            player.sendSystemMessage(result.message());
        } else if (held.isEmpty() && player.isShiftKeyDown()) {
            if (face == Direction.DOWN) {
                cancelProcessing(player);
                igniting = false;
                frictionSeconds = 0;
                setLit(false);
            } else if (station.active() || igniting) {
                cancelProcessing(player);
                igniting = false;
                frictionSeconds = 0;
            } else if (face == Direction.UP) {
                var choices = recipes();
                int current = -1;
                for (int i = 0; i < choices.size(); i++) if (choices.get(i).id().equals(selected)) current = i;
                if (!choices.isEmpty()) selected = choices.get((current + 1) % choices.size()).id();
            } else {
                give(player, station.input(0));
                station.setInput(player, 0, ItemStack.EMPTY);
                give(player, fuel); fuel = ItemStack.EMPTY;
                give(player, tinder); tinder = ItemStack.EMPTY;
                collect(player);
            }
        } else if (hearth() && held.is(PrimitiveContent.item("fire_drill")) && hand == InteractionHand.MAIN_HAND && !lit) {
            var environment = EnvironmentQuery.sample(player.serverLevel(), worldPosition, level.getGameTime(), level.getGameTime());
            if (!tinder.isEmpty() && dry(tinder) && dry(held) && PrimitiveToolItem.usable(held)
                    && (!fuel.isEmpty() || heat.fuelJoules() > 0) && environment.filter(e -> e.covered() && e.ventilated()).isPresent()) {
                igniting = true;
                ignitionTool = held.copy();
                resume(player,false);
            } else {
                player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.fire_requirements"));
            }
        } else if (!held.isEmpty() && !igniting) {
            if (hearth() && held.is(PrimitiveContent.item("tinder"))) {
                tinder = insert(player, held, tinder);
            } else if (hearth() && fuelData(held).isPresent()) {
                if (dry(held)) fuel = insert(player, held, fuel);
                else player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.fire_requirements"));
            } else if (!station.active()) {
                station.setInput(player, 0, insert(player, held, station.input(0)));
            }
        } else if (held.isEmpty()) {
            if (!station.output(0).isEmpty() || !station.output(1).isEmpty()) collect(player);
            else {
                long now = level.getGameTime();
                var environment = EnvironmentQuery.sample((ServerLevel) level, worldPosition, now, now);
                boolean continuing=station.active();
                if (continuing || suitable(environment) && (station.start(player, selected, facility(), environment, temperature()) || lit))
                    resume(player,continuing);
                else player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.environment_missing"));
            }
        }
        describe(player);
        setChanged();
    }

    private void cancelProcessing(ServerPlayer player) {
        station.cancel(player);
        releaseHeat(boilingHeat.releaseVapor());
    }

    private void claimSource() {
        if (level == null || level.isClientSide || !level.getServer().isSameThread()) return;
        var sources = SOURCES.computeIfAbsent(level.getServer(), key -> new HashMap<>());
        var reference = sources.get(sourceId);
        var other = reference == null ? null : reference.get();
        long received = CombustionSourceQuery.lastSequence((ServerLevel) level, sourceId);
        if (received > notificationSequence || other != null && other != this && !other.isRemoved() && sourceId.equals(other.sourceId)) {
            sourceId = UUID.randomUUID();notificationSequence=0;
            leaseGeneration=0;handoffTick=-1;coolingTarget=-1;
            heatReference=worldPosition.immutable();lastTick=level.getGameTime();
            setChanged();
        }
        sources.put(sourceId, new WeakReference<>(this));
    }

    private void releaseSource() {
        if (level != null && !level.isClientSide && level.getServer().isSameThread()) {
            var queue=WORK.get((ServerLevel)level);if(queue!=null)queue.remove(this);
            var sources = SOURCES.get(level.getServer());
            if (sources != null) {
                var reference = sources.get(sourceId);
                if (reference != null && reference.get() == this) sources.remove(sourceId);
            }
        }
    }

    @Override
    public void setRemoved() {
        releaseSource();
        super.setRemoved();
    }

    private long nextSequence() {
        claimSource();
        notificationSequence = Math.incrementExact(notificationSequence);
        setChanged();
        return notificationSequence;
    }

    private void releaseHeat(double retainedJ) {
        if (retainedJ != 0 && fuelProfile != null) {
            long now = level.getGameTime();
            long sequence = nextSequence();
            NeoForge.EVENT_BUS.post(new CombustionEvent((ServerLevel) level, worldPosition, now, now,
                    fuelProfile, 0, 0, retainedJ, sourceId, sequence));
        }
    }

    private void resume(ServerPlayer player,boolean continuing) {
        if(!continuing||worker==null||!worker.equals(player.getUUID())) {
            worker=player.getUUID();
            workTick=level.getGameTime();workTarget=-1;
            station.advance(player,facility(),Optional.empty(),0,temperature(),0);
        }
        level.scheduleTick(worldPosition, getBlockState().getBlock(), interval());
    }

    static boolean sourceInUse(ServerLevel level,UUID id) {
        var sources=SOURCES.get(level.getServer());
        var reference=sources==null?null:sources.get(id);
        var entity=reference==null?null:reference.get();
        return entity!=null&&!entity.isRemoved();
    }

    boolean settleDiscarded(ServerLevel server,long until) {
        if(!coolTo(server,until))return false;
        var environment=hearth()?EnvironmentQuery.sample(server,reference(),until,until):Optional.<EnvironmentSample>empty();
        if(hearth()&&environment.isEmpty())return false;
        double ambient=hearth()?environment.orElseThrow().temperatureK():Double.NaN;
        double retained=Double.isFinite(heat.temperatureK())?500*(ambient-heat.temperatureK()):0;
        retained+=boilingHeat.reset();
        if(retained!=0&&fuelProfile!=null) {
            NeoForge.EVENT_BUS.post(new CombustionEvent(server,reference(),until,until,fuelProfile,
                    0,0,retained,sourceId,nextSequence()));
        }
        if(hearth())heat.restore(0,0,ambient);
        EnvironmentRetention.request(server,sourceId,leaseGeneration,
                reference().above(),leaseFrom,until,notificationSequence,
                EnvironmentRetention.Action.END);
        commitEnvironment(server,until);discardClosed=true;setChanged();
        return EnvironmentRetention.request(server,sourceId,leaseGeneration,
                reference().above(),leaseFrom,until,notificationSequence,
                EnvironmentRetention.Action.RELEASE).orElse(false);
    }

    private BlockPos reference() { return heatReference==null?worldPosition:heatReference; }

    private void retainEnvironment(ServerLevel server) {
        long now=server.getGameTime();
        if(leaseGeneration==0) {
            leaseGeneration=1;leaseFrom=now;heatReference=worldPosition.immutable();
            setChanged();
        }
        var accepted=EnvironmentRetention.request(server,sourceId,leaseGeneration,
                reference().above(),leaseFrom,now,notificationSequence,
                EnvironmentRetention.Action.RETAIN);
        if(accepted.isPresent()&&!accepted.get()) {
            releaseSource();sourceId=UUID.randomUUID();notificationSequence=0;
            leaseGeneration=1;leaseFrom=now;heatReference=worldPosition.immutable();lastTick=now;discardClosed=false;
            handoffTick=-1;coolingTarget=-1;claimSource();setChanged();
            EnvironmentRetention.request(server,sourceId,leaseGeneration,
                    reference().above(),leaseFrom,now,0,
                    EnvironmentRetention.Action.RETAIN);
        }
        if(!reference().equals(worldPosition)&&handoffTick<0) {
            handoffTick=now;setChanged();
            EnvironmentRetention.request(server,sourceId,leaseGeneration+1,
                    worldPosition.above(),handoffTick,now,notificationSequence,
                    EnvironmentRetention.Action.RETAIN);
        }
        if(!hearth()&&handoffTick>=0) { lastTick=handoffTick;handoff(server); }
    }

    private void commitEnvironment(ServerLevel server,long through) {
        EnvironmentRetention.request(server,sourceId,leaseGeneration,
                reference().above(),leaseFrom,through,notificationSequence,
                EnvironmentRetention.Action.COMMIT);
    }

    private void handoff(ServerLevel server) {
        var type=EnvironmentRetention.Action.END;
        EnvironmentRetention.request(server,sourceId,leaseGeneration,
                reference().above(),leaseFrom,handoffTick,notificationSequence,type);
        commitEnvironment(server,handoffTick);
        EnvironmentRetention.request(server,sourceId,leaseGeneration,
                reference().above(),leaseFrom,handoffTick,notificationSequence,
                EnvironmentRetention.Action.RELEASE);
        leaseGeneration++;leaseFrom=handoffTick;heatReference=worldPosition.immutable();handoffTick=-1;setChanged();
    }

    private static ItemStack insert(ServerPlayer player, ItemStack held, ItemStack stored) {
        if (!stored.isEmpty() && !ItemStack.isSameItemSameComponents(held, stored)) return stored;
        int moved = Math.min(player.isShiftKeyDown() ? held.getCount() : 1, held.getMaxStackSize() - stored.getCount());
        if (moved <= 0) return stored;
        if (stored.isEmpty()) stored = held.copyWithCount(moved); else stored.grow(moved);
        if (!player.isCreative()) held.shrink(moved);
        return stored;
    }

    private static Optional<FuelData> fuelData(ItemStack stack) {
        return Optional.ofNullable(stack.getItem().builtInRegistryHolder().getData(FuelData.ITEM))
                .filter(f -> f.profile().equals(AshenFrontierCore.id("dry_wood")))
                .filter(f -> f.heatingValueJKg().filter(v -> Double.isFinite(v) && v > 0).isPresent())
                .filter(f -> MaterialData.get(stack.getItem()).filter(m -> m.massKg() > 0).isPresent());
    }

    private OptionalDouble temperature() {
        return hearth() && Double.isFinite(heat.temperatureK()) ? OptionalDouble.of(heat.temperatureK()) : OptionalDouble.empty();
    }

    private boolean suitable(Optional<EnvironmentSample> environment) {
        return environment.filter(e -> e.covered() && e.ventilated()
                && (hearth() || e.temperatureK() > 273.15 && e.relativeHumidity() <= 0.8)).isPresent();
    }

    public void work() { work(System.nanoTime()+300_000); }
    private long frontier() { return worker==null?lastTick:hearth()?Math.min(lastTick,workTick):workTick; }
    void scheduledWork() {
        if(!(level instanceof ServerLevel server))return;
        long available=EnvironmentBudget.remaining(server);
        if(available==Long.MAX_VALUE){workBudgeted();return;}
        if(available<=0&&workRequest!=null&&workRequest.queued) {
            if(frontier()<level.getGameTime())level.scheduleTick(worldPosition,getBlockState().getBlock(),1);
            return;
        }
        long start=System.nanoTime(),now=level.getGameTime();
        var queue=WORK.computeIfAbsent(server,l->new WorkQueue());
        queue.offer(this);
        long remaining=EnvironmentBudget.remaining(server);
        var next=remaining>0?queue.next(server,start+remaining):null;
        EnvironmentBudget.charge(server,System.nanoTime()-start);
        long before=next==null?now:next.frontier();
        if(next!=null&&next.workBudgeted()) {
            start=System.nanoTime();queue.oldestFrontier=!queue.oldestFrontier;
            if(!next.isRemoved()&&next.frontier()>before&&next.frontier()<now)queue.offer(next);
            EnvironmentBudget.charge(server,System.nanoTime()-start);
        }
        if(next!=this&&frontier()<now)level.scheduleTick(worldPosition,getBlockState().getBlock(),1);
    }
    private boolean workBudgeted() {
        if(!(level instanceof ServerLevel server))return false;
        long start=System.nanoTime(),remaining=EnvironmentBudget.remaining(server);
        if(remaining<=0) { level.scheduleTick(worldPosition,getBlockState().getBlock(),1);return false; }
        var queue=WORK.get(server);if(queue!=null)queue.remove(this);
        try { work(start+Math.min(300_000,remaining));return true; }
        finally { EnvironmentBudget.charge(server,System.nanoTime()-start); }
    }
    private void work(long deadline) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        retainEnvironment(serverLevel);
        if (worker == null) {
            boolean complete=false;
            for(int count=0;count<128;count++) {
                long before=lastTick;
                complete=coolTo(serverLevel,level.getGameTime());
                if(complete||before==lastTick||System.nanoTime()>=deadline)break;
            }
            if(!complete)level.scheduleTick(worldPosition,getBlockState().getBlock(),1);
            return;
        }
        var player = serverLevel.getPlayerByUUID(worker);
        if (!(player instanceof ServerPlayer serverPlayer) || !player.isAlive()) { worker=null;work(deadline);return; }
        long now = level.getGameTime();
        long from = workTick;
        if(workTarget<0)workTarget=now;
        var request=coolTo(serverLevel,from)?EnvironmentQuery.queryFirst(serverLevel,worldPosition,from,workTarget):null;
        var environment=request==null?Optional.<List<EnvironmentSample>>empty():request.values();
        if (environment.isEmpty()) {
            serverPlayer.sendSystemMessage(Component.translatable(request==null?"primitive.ashen_frontier.environment_missing":
                    request.reason().orElse("primitive.ashen_frontier.environment_missing")));
            level.scheduleTick(worldPosition,getBlockState().getBlock(),interval());
            setChanged();return;
        }
        var sample=environment.get().getFirst();
        workInterval(serverLevel,serverPlayer,sample);
        workTick=sample.endTick();
        if(workTick==workTarget)workTarget=-1;
        if (station.active() || igniting || lit)
            level.scheduleTick(worldPosition,getBlockState().getBlock(),workTick<now?1:interval());
        else worker = null;
        setChanged();
    }

    private void workInterval(ServerLevel serverLevel, ServerPlayer serverPlayer, EnvironmentSample sample) {
        long from = sample.startTick();
        long now = sample.endTick();
        double seconds = GameTime.realSeconds(now - from);
        var environment = Optional.of(sample);
        boolean newlyLit = false;
        if (igniting) {
            var held = serverPlayer.getMainHandItem();
            if (serverPlayer.distanceToSqr(worldPosition.getCenter()) > 16 || !suitable(environment)
                    || !ItemStack.isSameItemSameComponents(held, ignitionTool) || !dry(held)
                    || !dry(tinder) || tinder.isEmpty()) {
                igniting = false;
            } else {
                frictionSeconds += seconds;
                if (frictionSeconds >= 30) {
                    tinder.shrink(1);
                    var wear = held.get(ItemProperties.WEAR);
                    held.set(ItemProperties.WEAR, ItemProperties.Wear.of(wear.value() + io.github.rs0325.ashenfrontier.core.ToolWearEvent.committed(serverPlayer,1), wear.maximum()));
                    frictionSeconds = 0;
                    igniting = false;
                    setLit(true);
                    newlyLit = true;
                    ResearchManager.report(serverPlayer, AshenFrontierCore.id("primitive_event"), "friction_ignition");
                }
            }
        }
        double processJ = 0;
        double activeSeconds = 0;
        double suppliedProcessJ = 0;
        HearthHeat.Step evaluatedHeat = null;
        if (hearth()) {
            while (!newlyLit && lit && suitable(environment) && heat.fuelJoules() < HearthHeat.POWER_W * seconds && !fuel.isEmpty()) {
                var properties = fuelData(fuel);
                if (properties.isEmpty() || heat.fuelJoules() > 0
                        && heat.heatingValueJKg() != properties.get().heatingValueJKg().orElseThrow()) break;
                fuelProfile = properties.get().profile();
                heat.addFuel(MaterialData.get(fuel.getItem()).orElseThrow().massKg(), properties.get().heatingValueJKg().orElseThrow());
                fuel.shrink(1);
            }
            var step = heat.advance(newlyLit ? 0 : seconds, GameTime.gameSeconds(Math.max(0, now - from), sample.ticksPerDay()),
                    sample.temperatureK(), !newlyLit && lit && suitable(environment), station.active());
            lastTick = now;
            evaluatedHeat = step;
            processJ = step.processJoules();
            activeSeconds = step.processSeconds();
            if (lit && heat.fuelJoules() == 0 && fuel.isEmpty()) setLit(false);
        }
        double gameSeconds = GameTime.gameSeconds(now - from, sample.ticksPerDay());
        boolean heatingWater = station.active() && boiling() && suitable(environment) && activeSeconds > 0;
        double poweredGameSeconds = heatingWater ? gameSeconds * (activeSeconds / seconds) : 0;
        double waterCoolingJ = 0;
        if (!station.active() || !boiling()) {
            waterCoolingJ = coolWater(serverLevel, sample, gameSeconds);
        }
        if (station.active()) {
            if (suitable(environment) && (!hearth() || activeSeconds > 0)) {
                OptionalDouble processTemperature = temperature();
                double processSeconds = hearth() ? activeSeconds : Double.MAX_VALUE;
                double recipeJ = processJ;
                double retainedJ = processJ;
                if (boiling()) {
                    var step = boilingHeat.advance(activeSeconds, poweredGameSeconds,
                            sample.temperatureK(), sample.pressurePa(), processJ);
                    if (step.interrupted()) station.resetProgress(serverLevel);
                    processSeconds = step.hotSeconds();
                    recipeJ = step.vaporJoules();
                    retainedJ = step.absorbedJoules();
                    processTemperature = OptionalDouble.of(step.temperatureK());
                }
                boolean advanced = station.advanceInterval(serverPlayer, facility(), sample, recipeJ, processTemperature, processSeconds);
                if (boiling() || advanced) suppliedProcessJ = retainedJ;
                if (station.finish(serverPlayer, facility(), environment, processTemperature)) {
                    if (boiling()) {
                        evaporatedMg += 200_000;
                        ResearchManager.report(serverPlayer, AshenFrontierCore.id("water_event"), "boiled_water");
                        serverPlayer.sendSystemMessage(Component.translatable("primitive.ashen_frontier.boiled_storage"));
                        boilingHeat.complete();
                    }
                    if (station.output(0).is(PrimitiveContent.item("ceramic_pot"))) {
                        ResearchManager.report(serverPlayer, AshenFrontierCore.id("primitive_event"), "ceramic_pot");
                    }
                    serverPlayer.sendSystemMessage(Component.translatable("primitive.ashen_frontier.finished"));
                }
            } else {
                if (boiling()) waterCoolingJ = coolWater(serverLevel, sample, gameSeconds);
                station.advanceInterval(serverPlayer, facility(), sample, 0, temperature(), 0);
            }
        }
        if (heatingWater) waterCoolingJ += coolWater(serverLevel, sample, gameSeconds - poweredGameSeconds);
        if (!station.active()) waterCoolingJ += boilingHeat.releaseVapor();
        if (evaluatedHeat != null)notifyHeat(serverLevel,from,now,evaluatedHeat,suppliedProcessJ+waterCoolingJ);
        if(!hearth())lastTick=now;
        commitEnvironment(serverLevel,now);
    }

    /** 作業・燃焼を進めず、未ロード区間も含む余熱だけを遅延評価する。未接続では時刻を進めない。 */
    private boolean coolTo(ServerLevel serverLevel, long now) {
        retainEnvironment(serverLevel);
        if (!hearth()) return true;
        if(handoffTick>=0&&lastTick>=handoffTick)handoff(serverLevel);
        if (lastTick < 0 || !Double.isFinite(heat.temperatureK())) { lastTick = now; return true; }
        if (now <= lastTick) return true;
        long end=handoffTick>=0?Math.min(now,handoffTick):now;
        if(coolingTarget<0)coolingTarget=Math.min(end,lastTick+600);
        var environment=EnvironmentQuery.queryFirst(serverLevel,reference(),lastTick,coolingTarget).values();
        if(environment.isEmpty())return false;
        // 放熱の通知を受けた室の次区間は、通知後に再照会する。
        for (var sample : environment.get().subList(0,1)) {
            var step = heat.advance(0, GameTime.gameSeconds(sample.endTick() - sample.startTick(), sample.ticksPerDay()),
                    sample.temperatureK(), false, false);
            double waterJ = coolWater(serverLevel, sample,
                    GameTime.gameSeconds(sample.endTick() - sample.startTick(), sample.ticksPerDay()));
            if (!station.active()) waterJ += boilingHeat.releaseVapor();
            notifyHeat(serverLevel, sample.startTick(), sample.endTick(), step, waterJ);
            lastTick = sample.endTick();
        }
        commitEnvironment(serverLevel,lastTick);
        coolingTarget=-1;
        if(handoffTick>=0&&lastTick>=handoffTick)handoff(serverLevel);
        setChanged();
        return lastTick>=now;
    }

    private double coolWater(ServerLevel level, EnvironmentSample sample, double gameSeconds) {
        double before = boilingHeat.temperatureK();
        double retainedJ = boilingHeat.cool(gameSeconds, sample.temperatureK());
        double boilingK = BoilingHeat.boilingK(sample.pressurePa());
        if (boiling() && (before < boilingK || boilingHeat.temperatureK() < boilingK)) {
            station.resetProgress(level);
            retainedJ += boilingHeat.releaseVapor();
        }
        return retainedJ;
    }

    private void notifyHeat(ServerLevel serverLevel, long from, long to, HearthHeat.Step step, double suppliedProcessJ) {
        if (fuelProfile != null && (step.chemicalJoules() > 0 || step.storedJoules() != 0 || suppliedProcessJ != 0)) {
            long sequence = nextSequence();
            NeoForge.EVENT_BUS.post(new CombustionEvent(serverLevel,reference(),from,to,fuelProfile,
                    step.burnedKg(), step.chemicalJoules(), step.storedJoules() + suppliedProcessJ, sourceId, sequence));
        }
    }

    private void setLit(boolean value) {
        lit = value;
        if (level != null && getBlockState().getValue(BlockStateProperties.LIT) != value) {
            level.setBlock(worldPosition, getBlockState().setValue(BlockStateProperties.LIT, value), 3);
        }
    }

    private List<RecipeHolder<CommonRecipe>> recipes() {
        return level.getRecipeManager().getRecipes().stream()
                .filter(h -> h.value() instanceof CommonRecipe r && r.conditions().facility().filter(facility()::equals).isPresent())
                .filter(h -> !h.id().getPath().startsWith("primitive/boil_water")
                        || h.id().getPath().endsWith("_high") == highAltitude())
                .map(h -> new RecipeHolder<>(h.id(), (CommonRecipe) h.value()))
                .sorted(Comparator.comparing(h -> h.id().toString())).toList();
    }

    private void describe(ServerPlayer player) {
        player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.thermal_controls"));
        var water = station.water().contents();
        player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.stored_water", water.massKg(),
                water.quality().<Component>map(q -> Component.translatable("fluid_type." + q.getNamespace() + "." + q.getPath()))
                        .orElseGet(() -> Component.literal("-"))));
        if (hearth()) {
            player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.boiling_controls"));
            player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.evaporated_water", WaterMass.toKg(evaporatedMg)));
        }
        var environment = EnvironmentQuery.sample((ServerLevel) level, worldPosition, level.getGameTime(), level.getGameTime());
        if (environment.isEmpty()) player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.environment_missing"));
        else if (!suitable(environment)) player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.unsuitable_environment"));
        if (hearth()) {
            String state = !Double.isFinite(heat.temperatureK()) ? "unknown"
                    : heat.temperatureK() < 973 ? "cold" : heat.temperatureK() > 1123 ? "hot" : "suitable";
            player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.heat_" + state));
        } else player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.drying_conditions"));

        recipes().stream().filter(h -> h.id().equals(selected)).findFirst().ifPresent(h -> {
            var output = h.value().outputs().getFirst();
            player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.selected", output.getHoverName()));
            if (output.has(PotteryProperties.FORM)) player.sendSystemMessage(Component.translatable("tooltip.ashen_frontier.pottery_form",
                    Component.translatable("pottery_form.ashen_frontier." + output.get(PotteryProperties.FORM))));
            h.value().inputs().forEach(input -> player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.input", input.getItems()[0].getHoverName())));
            h.value().serverDisplayLines(player).forEach(player::sendSystemMessage);
        });
        player.sendSystemMessage(Component.translatable(igniting ? "primitive.ashen_frontier.igniting"
                : lit ? "primitive.ashen_frontier.lit" : "primitive.ashen_frontier.ready"));
    }

    private void collect(ServerPlayer player) {
        for (int i = 0; i < 2; i++) give(player, station.takeOutput(player, i, 64));
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!stack.isEmpty() && !player.getInventory().add(stack)) player.drop(stack, false);
    }

    /** 通常lootは空。設備アイテム1個だけに入力・出力・燃料・余熱を保存して回収する。 */
    public void dropStored() {
        if (level.isClientSide) return;
        coolTo((ServerLevel) level, level.getGameTime());
        var stack = new ItemStack(getBlockState().getBlock());
        saveToItem(stack, level.registryAccess());
        Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, stack);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            setLit(lit);
            if(restored) { claimSource();retainEnvironment((ServerLevel)level); }
            level.scheduleTick(worldPosition,getBlockState().getBlock(),interval());
        }
    }

    void placed() {
        if(level instanceof ServerLevel server) {
            claimSource();retainEnvironment(server);
            setLit(lit);level.scheduleTick(worldPosition,getBlockState().getBlock(),interval());
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        var ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        tag.putUUID("source_id", sourceId);
        tag.putLong("notification_sequence", notificationSequence);
        tag.put("processing", station.save(registries));
        tag.put("fuel", ItemStack.OPTIONAL_CODEC.encodeStart(ops, fuel).getOrThrow());
        tag.put("tinder", ItemStack.OPTIONAL_CODEC.encodeStart(ops, tinder).getOrThrow());
        tag.putString("selected", selected.toString());
        tag.putDouble("fuel_j", heat.fuelJoules());
        tag.putDouble("heating_value", heat.heatingValueJKg());
        if (Double.isFinite(heat.temperatureK())) tag.putDouble("temperature_k", heat.temperatureK());
        if (fuelProfile != null) tag.putString("fuel_profile", fuelProfile.toString());
        tag.putBoolean("lit", lit);
        tag.putDouble("friction_seconds", frictionSeconds);
        tag.putLong("last_tick",lastTick);
        tag.putLong("cooling_target",coolingTarget);
        tag.putLong("lease_generation",leaseGeneration);tag.putLong("lease_from",leaseFrom);
        tag.putBoolean("discard_closed",discardClosed);
        tag.putBoolean("hearth",hearth());tag.putLong("station_position",worldPosition.asLong());
        tag.putLong("heat_reference",reference().asLong());tag.putLong("handoff_tick",handoffTick);
        tag.putLong("evaporated_mg", evaporatedMg);
        if (Double.isFinite(boilingHeat.temperatureK())) tag.putDouble("water_temperature_k", boilingHeat.temperatureK());
        tag.putDouble("water_reserved_j", boilingHeat.reservedJoules());
        tag.putDouble("water_sensible_j", boilingHeat.sensibleJoules());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        releaseSource();
        super.loadAdditional(tag, registries);
        var ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        if (tag.contains("processing")) {
            restored=true;
            if (!tag.hasUUID("source_id") || !tag.contains("notification_sequence") || tag.getLong("notification_sequence") < 0)
                throw new IllegalArgumentException("熱設備の通知識別情報が不正です");
            sourceId = tag.getUUID("source_id");
            notificationSequence = tag.getLong("notification_sequence");
            station = ProcessingStation.load(tag.getCompound("processing"), registries);
        }
        fuel = ItemStack.OPTIONAL_CODEC.parse(ops, tag.get("fuel")).getOrThrow();
        tinder = ItemStack.OPTIONAL_CODEC.parse(ops, tag.get("tinder")).getOrThrow();
        selected = ResourceLocation.parse(tag.getString("selected"));
        heat.restore(tag.getDouble("fuel_j"), tag.getDouble("heating_value"),
                tag.contains("temperature_k") ? tag.getDouble("temperature_k") : Double.NaN);
        fuelProfile = tag.contains("fuel_profile") ? ResourceLocation.parse(tag.getString("fuel_profile")) : null;
        lit = tag.getBoolean("lit");
        frictionSeconds = tag.getDouble("friction_seconds");
        lastTick=tag.getLong("last_tick");discardClosed=tag.getBoolean("discard_closed");
        coolingTarget=tag.contains("cooling_target")?tag.getLong("cooling_target"):-1;
        leaseGeneration=tag.getLong("lease_generation");leaseFrom=tag.getLong("lease_from");
        heatReference=tag.contains("heat_reference")?BlockPos.of(tag.getLong("heat_reference")):worldPosition.immutable();
        handoffTick=tag.contains("handoff_tick")?tag.getLong("handoff_tick"):-1;workTarget=-1;
        evaporatedMg = tag.getLong("evaporated_mg");
        boilingHeat.restore(tag.contains("water_temperature_k") ? tag.getDouble("water_temperature_k") : Double.NaN,
                tag.getDouble("water_reserved_j"), tag.getDouble("water_sensible_j"));
        if (level != null && !level.isClientSide) setLit(lit);
        worker = null;
        igniting = false;
        claimSource();
    }
}
