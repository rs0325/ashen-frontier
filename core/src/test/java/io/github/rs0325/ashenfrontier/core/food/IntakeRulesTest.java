package io.github.rs0325.ashenfrontier.core.food;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class IntakeRulesTest {
    @Test void waterEvidenceMustMatchMapManifestProfileAndQuality() {
        assertTrue(CultivationRules.waterOrigin("earth_001","starter","clean_water","earth_001","starter","ashen_frontier:clean_water","CONFIRMED").accepted());
        assertEquals(IntakeRules.Status.UNKNOWN,CultivationRules.waterOrigin("earth_001","starter","clean_water","earth_002","starter","clean_water","CONFIRMED").status());
        assertEquals(IntakeRules.Status.UNKNOWN,CultivationRules.waterOrigin("earth_001","starter","clean_water","earth_001","other","clean_water","CONFIRMED").status());
        assertEquals(IntakeRules.Status.UNKNOWN,CultivationRules.waterOrigin("earth_001","starter","clean_water","earth_001","starter","unknown","CONFIRMED").status());
        assertEquals(IntakeRules.Status.UNKNOWN,CultivationRules.waterOrigin("earth_001","starter","clean_water","earth_001","starter","pure_water","CONFIRMED").status());
        assertEquals(IntakeRules.Status.INELIGIBLE,CultivationRules.waterOrigin("earth_001","starter","clean_water","earth_001","starter","ashen_frontier:concentrated_residue","CONFIRMED").status());
    }
    private static IntakeRules.Result food(double rot, Map<String,Double> chemicals, String nuclide, double activity, boolean zero) {
        return IntakeRules.check("canned_food","",.2,rot,Set.of("fat_soluble"),chemicals,nuclide,activity,zero,0);
    }
    @Test void boundariesUnknownAndWarning() {
        assertTrue(food(.799,Map.of("lead",2e-7,"pcb",2e-7),"",0,true).accepted());
        assertTrue(food(.8,Map.of(),"",0,true).warning());
        assertEquals(IntakeRules.Status.INELIGIBLE,food(1,Map.of(),"",0,true).status());
        assertFalse(food(0,Map.of("lead",Math.nextUp(2e-7)),"",0,true).accepted());
        assertEquals(IntakeRules.Status.UNKNOWN,food(0,Map.of("unknown",0.0),"",0,true).status());
        assertEquals(IntakeRules.Status.UNKNOWN,food(0,Map.of(),"unknown",0,false).status());
        assertEquals(IntakeRules.Status.UNKNOWN,food(0,Map.of(),"",0,false).status());
        assertFalse(food(0,Map.of(),"ashen_frontier:cs137",20,true).accepted());
        assertTrue(food(0,Map.of(),"ashen_frontier:cs137",19.8,true).accepted());
        assertFalse(food(Double.NaN,Map.of(),"",0,true).accepted());
        assertFalse(food(0,Map.of("lead",Double.NaN),"",0,true).accepted());
        assertEquals(IntakeRules.Status.INELIGIBLE,food(0,Map.of("lead",1e-4,"unknown",0.0),"",0,true).status());
        assertFalse(IntakeRules.check("undefined","",1,0,Set.of(),Map.of(),"",0,true,0).accepted());
        assertFalse(IntakeRules.check("canned_food","",1,0,Set.of("unknown"),Map.of(),"",0,true,0).accepted());
    }
    @Test void drinkingQualitiesAndConcentrations() {
        for(String quality:Set.of("clean_water","pure_water","ultrapure_water")) {
            assertTrue(IntakeRules.check("water",quality,1,0,Set.of(),Map.of("lead",1e-8,"pcb",5e-10),"",0,true,0).accepted());
            assertFalse(IntakeRules.check("water",quality,1,0,Set.of(),Map.of("pcb",Math.nextUp(5e-10)),"",0,true,0).accepted());
        }
        for(String quality:Set.of("raw_water","turbid_water","contaminated_water","toxic_water","polluted_seawater","unknown","concentrated_residue"))
            assertFalse(IntakeRules.check("water",quality,1,0,Set.of(),Map.of(),"",0,true,0).accepted());
    }
    @Test void cultivationUsesOriginAndSpeciesRatherThanCleanStagesAlone() {
        assertTrue(CultivationRules.check("shelter_food_soil","CONFIRMED","barley","fertile_soil","clean_water",true,true).accepted());
        assertTrue(CultivationRules.check("heated_oyster_substrate","CONFIRMED","oyster_mushroom","heated_oyster_substrate","pure_water",true,true).accepted());
        assertFalse(CultivationRules.check("unknown","CONFIRMED","barley","fertile_soil","clean_water",true,true).accepted());
        assertFalse(CultivationRules.check("shelter_food_soil","UNCONFIRMED","barley","fertile_soil","clean_water",true,true).accepted());
        assertFalse(CultivationRules.check("shelter_food_soil","INVALIDATED","barley","fertile_soil","clean_water",true,true).accepted());
        assertFalse(CultivationRules.check("shelter_food_soil","CONFIRMED","flax","fertile_soil","clean_water",true,true).accepted());
        assertFalse(CultivationRules.check("shelter_food_soil","CONFIRMED","barley","poor_soil","clean_water",true,true).accepted());
        assertFalse(CultivationRules.check("nonfood_cultivation","CONFIRMED","flax","fertile_soil","raw_water",true,true).accepted());
        assertFalse(CultivationRules.check("shelter_food_soil","CONFIRMED","barley","fertile_soil","clean_water",false,true).accepted());
    }
    @Test void generatedIndependentConcentrationAndRelativeAgeOracle() {
        var random=new Random(12520261126L);
        int allowed=0,denied=0,unknown=0,transforms=0;
        for(int n=0;n<8192;n++) {
            boolean water=random.nextBoolean();double mass=.01+random.nextDouble()*2;
            double lead=random.nextDouble()*(water?2e-8:4e-7),pcb=random.nextDouble()*(water?1e-9:4e-7);
            double activity=random.nextDouble()*(water?20:200)*mass,age=random.nextDouble()*1.2;
            boolean known=n%7!=0;
            // 期待値は製品の閾値表・減衰関数を呼ばず、承認表から独立に計算する。
            boolean expected=(!water&&age>=1)?false:lead<=(water?.01:.2)/1e6&&pcb<=(water?.0005:.2)/1e6
                    &&(activity+.1)/mass<=(water?10:100)&&known;
            var result=IntakeRules.check(water?"water":"canned_food","clean_water",mass,water?0:age,Set.of(),Map.of("lead",lead,"pcb",pcb),
                    known?"ashen_frontier:cs137":"unknown",activity,false,0);
            assertEquals(expected,result.accepted(),"seed125 step="+n);
            if(result.accepted())allowed++;else if(result.status()==IntakeRules.Status.UNKNOWN)unknown++;else denied++;
            double before=random.nextDouble()*20*86400;
            double after=IntakeRules.inheritedRot(before,"cooked_staple","dried_vegetables");
            assertEquals(before/(20*86400),after/(200*86400),1e-14);transforms++;
        }
        assertTrue(allowed>=256);assertTrue(denied>=4096);assertTrue(unknown>=32);assertEquals(8192,transforms);
    }
}
