package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.climate.EnvironmentConnectionGameTests;
import io.github.rs0325.ashenfrontier.climate.room.RoomAir;
import io.github.rs0325.ashenfrontier.climate.room.RoomPhysics;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.List;

@GameTestHolder(AshenFrontierSurvival.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExposureLifeGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="exposure126_life",timeoutTicks=400)
    public static void twentyFourGameDaysWithCleanDietVentilationAndActualSleepCredit(GameTestHelper h) throws Exception {
        try(var fixture=new EnvironmentConnectionGameTests.Fixture(h)) {
            for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)if(x*x+z*z<=64)
                fixture.level.setBlock(fixture.pos.offset(x,-1,z),Blocks.DIRT.defaultBlockState(),3);
            var rooms=RoomAir.get(fixture.level);
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)if(Math.abs(x)==2||Math.abs(z)==2)
                for(int y=0;y<=2;y++){var pos=fixture.pos.offset(x,y,z);fixture.level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);rooms.changed(pos);}
            var environment=fixture.ready(h,0,0).getFirst();
            var workshop=rooms.sample(fixture.pos.above(),0);
            h.assertTrue(workshop.status()==RoomAir.Status.INDOOR&&!fixture.level.canSeeSky(fixture.pos.above())&&environment.ventilated(),"既存C06の屋根付き開放域（開口を持つ有限室）を実形状で確認 status="+workshop.status()+" sky="+fixture.level.canSeeSky(fixture.pos.above())+" ventilated="+environment.ventilated());
            double gamma;
            try(var person=new SurvivalGameTests.Person(fixture.level,fixture.pos,"life126");var point=ExposureGameTests.measured(person)) {
                h.assertTrue(point.known&&point.ground>0,"清浄背景の非ゼロBq/kgも線量に含める");gamma=point.ground;
            }
            var bodies=new ArrayList<Body>();var exposures=new ArrayList<Exposure.State>();
            for(int n=0;n<4;n++){bodies.add(new Body());exposures.add(new Exposure.State());}
            // 住環境・継続する清浄飲食は資源fixture。M2の初期配置・備蓄量の完成を主張しない。
            var outside=new RoomPhysics.Air(295.15,101325,.1e-6,420e-6,.2095,.1);
            var indoor=RoomPhysics.State.ambient(outside);double drank=0;
            long consumed=0,lastCredit=-1;int credits=0;
            for(int second=1;second<=24*1200;second++) {
                boolean sleeping=(second-1)%805>=800;double oxygen=0,co2=0,heat=0;
                for(int n=0;n<4;n++) {
                    var body=bodies.get(n);var exposure=exposures.get(n);
                    var air=new Physiology.Air(indoor.temperatureK(),.5,.1,0,101325,indoor.oxygen()*101325,0,true,false);
                    var activity=sleeping?Activity.SLEEP:Activity.REST;
                    var result=Physiology.evaluate(body,air,activity,1,72);oxygen+=result.oxygenMol();co2+=result.co2Mol();heat+=result.heatJ();
                    Exposure.advance(exposure,new Exposure.Air(indoor.co(),indoor.co2(),indoor.oxygen()*101325,5e-9,true),activity,1,72,0);
                    Exposure.radiation(exposure,List.of(new Exposure.Source(gamma,gamma,0)),1,72,true);
                    if(second%300==0)Physiology.ingest(body,.132,1450000,.042,.021,false,true);
                    if(second%1200==0)Physiology.ingest(body,.010,4500000,.025,.04,true,true);
                    if(body.water<41.8){Physiology.ingest(body,.2,0,0,0,false,false);drank+=.2;}
                    h.assertTrue(body.damage==0&&exposure.severity()==0,"適切な住環境・飲食と換気で損傷や有害曝露を蓄積しない second="+second);
                }
                indoor=RoomPhysics.advance(indoor,outside,27,54,.02,new RoomPhysics.Rate(heat,0,co2,-oxygen),1);
                if(second%805==0) {
                    long generation=consumed+1,tick=second*20L;
                    for(var body:bodies) {
                        var result=Physiology.creditSleep(body,generation,consumed,tick,lastCredit,lastCredit<0?Double.MAX_VALUE:(tick-lastCredit)*3.6,true);
                        h.assertTrue(result.applied(),"実際の5REAL秒の深睡眠成立にB2を一回適用");
                    }
                    consumed=generation;lastCredit=tick;credits++;
                }
            }
            for(int n=0;n<4;n++) {
                h.assertTrue(bodies.get(n).water>41.3&&bodies.get(n).energy>10e6&&bodies.get(n).sleepDebt<43200,"24GAME日後も飲食・睡眠が回復可能な範囲");
                h.assertTrue(Math.abs(exposures.get(n).externalUpper-gamma*28800)<1e-17&&exposures.get(n).load[6]<.3,"背景の累積Svを消さず、症状と区別");
            }
            h.assertTrue(credits==35,"架空の睡眠8時間を作らず、実際の深睡眠成立を数える");
            com.mojang.logging.LogUtils.getLogger().info("#126 life GAME_days=24 people=4 real_seconds=28800 B2_credits={} drink_kg_total={} known_gamma_Sv_per_person={} residence_K={} CO2_fraction={}",
                    credits,drank,exposures.getFirst().externalUpper,indoor.temperatureK(),indoor.co2());
        }
        h.succeed();
    }
}
