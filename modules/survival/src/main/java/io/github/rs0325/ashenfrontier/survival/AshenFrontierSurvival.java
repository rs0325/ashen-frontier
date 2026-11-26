package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.TreeMap;
import java.util.function.Supplier;

@Mod(AshenFrontierSurvival.MOD_ID)
public final class AshenFrontierSurvival {
    public static final String MOD_ID="ashen_frontier_survival";
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS=DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.ATTACHMENT_TYPES,MOD_ID);
    public static final Supplier<AttachmentType<PhysiologyData>> DATA=ATTACHMENTS.register("physiology",
            ()->AttachmentType.builder(PhysiologyData::new).serialize(new PhysiologyAttachment()).copyOnDeath().build());
    static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue EFFECTS;
    static {
        var builder=new ModConfigSpec.Builder();
        EFFECTS=builder.comment("falseは生理効果無効。計算と保存は継続し、無効中の損傷を後払いしない。").define("physiologyEffects",true);
        SPEC=builder.build();
    }
    static final IdentityHashMap<ServerPlayer,SurvivalRuntime> ACTIVE=new IdentityHashMap<>();
    private static final TreeMap<Long,LinkedHashSet<SurvivalRuntime>> DUE=new TreeMap<>();
    static boolean APPLYING_DAMAGE;

    public AshenFrontierSurvival(IEventBus bus,ModContainer container) {
        ATTACHMENTS.register(bus);container.registerConfig(ModConfig.Type.SERVER,SPEC);
        bus.addListener(PhysiologyUpdate::register);
        NeoForge.EVENT_BUS.addListener((io.github.rs0325.ashenfrontier.core.ToolWearEvent event)->{
            if(EFFECTS.get()&&event.user() instanceof ServerPlayer player) {
                var runtime=ACTIVE.get(player);if(runtime==null)return;
                var body=runtime.data.body;double extra=body.wearFraction+event.base()*(body.wearFactor()-1);
                double whole=Math.floor(extra+1e-12);body.wearFraction=Math.max(0,extra-whole);event.add(whole);
            }
        });
        NeoForge.EVENT_BUS.addListener(AshenFrontierSurvival::login);
        NeoForge.EVENT_BUS.addListener(AshenFrontierSurvival::logout);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.Clone event)->{
            if(event.getOriginal() instanceof ServerPlayer original) {
                var previous=ACTIVE.remove(original);if(previous!=null)previous.logout();
                event.getEntity().setData(DATA,PhysiologyAttachment.read(new PhysiologyAttachment().write(original.getData(DATA),original.registryAccess())));
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent event)->login(new PlayerEvent.PlayerLoggedInEvent(event.getEntity())));
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGH,AshenFrontierSurvival::tick);
        NeoForge.EVENT_BUS.addListener(AshenFrontierSurvival::playerTick);
        NeoForge.EVENT_BUS.addListener(AshenFrontierSurvival::damage);
        NeoForge.EVENT_BUS.addListener(AshenFrontierSurvival::starvation);
        NeoForge.EVENT_BUS.addListener(AshenFrontierSurvival::jump);
        NeoForge.EVENT_BUS.addListener(AshenFrontierSurvival::sleep);
        NeoForge.EVENT_BUS.addListener(AshenFrontierSurvival::continueSleep);
        NeoForge.EVENT_BUS.addListener(AshenFrontierSurvival::breakSpeed);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event)->{ACTIVE.clear();DUE.clear();SurvivalRuntime.INPUTS.clear();});
    }
    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(MOD_ID,path); }

    static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) {
            var previous=ACTIVE.remove(player);if(previous!=null)unschedule(previous);
            if(player.getData(DATA).blocked()) {
                PhysiologyUpdate.send(player,new PhysiologyUpdate(0,true,EFFECTS.get(),false,true));return;
            }
            var runtime=new SurvivalRuntime(player);ACTIVE.put(player,runtime);runtime.login();
        }
    }
    static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) {
            var runtime=ACTIVE.remove(player);if(runtime!=null)runtime.logout();
        }
    }
    static void unschedule(SurvivalRuntime runtime) {
        var set=DUE.get(runtime.scheduled);
        if(set!=null) { set.remove(runtime);if(set.isEmpty())DUE.remove(runtime.scheduled); }
        runtime.scheduled=-1;
    }
    static void schedule(SurvivalRuntime runtime,int ticks) {
        unschedule(runtime);runtime.scheduled=(long)runtime.player.server.getTickCount()+ticks;
        DUE.computeIfAbsent(runtime.scheduled,k->new LinkedHashSet<>()).add(runtime);
    }
    private static void tick(ServerTickEvent.Pre event) {
        long tick=event.getServer().getTickCount(),deadline=System.nanoTime()+100_000;
        while(!DUE.isEmpty()&&DUE.firstKey()<=tick&&System.nanoTime()<deadline) {
            var first=DUE.firstEntry();var runtime=first.getValue().getFirst();unschedule(runtime);
            var level=runtime.player.serverLevel();long start=System.nanoTime();
            long allowance=Math.min(deadline-start,EnvironmentBudget.remaining(level));
            if(allowance>0)runtime.work(start+allowance,8);
            EnvironmentBudget.charge(level,System.nanoTime()-start);
            if(runtime.scheduled<0&&runtime.data.online)runtime.schedule(1);
        }
    }
    private static void playerTick(PlayerTickEvent.Post event) {
        if(event.getEntity() instanceof ServerPlayer player) {
            var runtime=ACTIVE.get(player);
            if(runtime!=null) {
                if(EFFECTS.get()&&runtime.data.body.exhausted)player.setSprinting(false);
                runtime.postTick();
            }
        }
    }
    private static void damage(LivingDamageEvent.Post event) {
        if(!APPLYING_DAMAGE&&event.getEntity() instanceof ServerPlayer player) {
            var runtime=ACTIVE.get(player);if(runtime!=null&&event.getNewDamage()>0)runtime.hit(event.getNewDamage());
        }
    }
    private static void starvation(LivingDamageEvent.Pre event) {
        if(event.getEntity() instanceof ServerPlayer player&&ACTIVE.containsKey(player)&&event.getSource().is(DamageTypes.STARVE))
            event.setNewDamage(0);
    }
    private static void jump(LivingEvent.LivingJumpEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) {
            var runtime=ACTIVE.get(player);
            if(runtime!=null) {
                if(EFFECTS.get()&&runtime.data.body.exhausted) {
                    var motion=player.getDeltaMovement();player.setDeltaMovement(motion.x,Math.min(0,motion.y),motion.z);
                } else runtime.jump();
            }
        }
    }
    private static void sleep(CanPlayerSleepEvent event) {
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        var runtime=ACTIVE.get(player);if(runtime!=null)runtime.capture();
        if(runtime==null||!runtime.sleepReady()) { event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);return; }
        if(!player.level().getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)&&event.getProblem()==Player.BedSleepingProblem.NOT_POSSIBLE_NOW)
            event.setProblem(unsafeAt(player,event.getPos())?Player.BedSleepingProblem.NOT_SAFE:null);
    }
    private static boolean unsafeAt(ServerPlayer player,net.minecraft.core.BlockPos bed) {
        if(player.isCreative())return false;
        var center=net.minecraft.world.phys.Vec3.atBottomCenterOf(bed);
        return !player.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
                new net.minecraft.world.phys.AABB(center.add(-8,-5,-8),center.add(8,5,8)),
                monster->monster.isPreventingPlayerRest(player)).isEmpty();
    }
    private static void continueSleep(CanContinueSleepingEvent event) {
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        var runtime=ACTIVE.get(player);
        if(runtime==null||!runtime.sleepReady())event.setContinueSleeping(false);
        else if(!player.level().getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)&&event.getProblem()==Player.BedSleepingProblem.NOT_POSSIBLE_NOW)
            event.setContinueSleeping(true);
    }
    private static void breakSpeed(PlayerEvent.BreakSpeed event) {
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        var runtime=ACTIVE.get(player);
        if(runtime!=null) {
            runtime.heavy();
            if(EFFECTS.get())event.setNewSpeed((float)(event.getNewSpeed()*runtime.data.body.workFactor()));
        }
    }
}
