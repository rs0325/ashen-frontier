package io.github.rs0325.ashenfrontier.world;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MovedBranchTest {
    @Test
    void branchStaysOnLandAndKeepsTheSourceValleyAndWesternBase() throws Exception {
        var map=MountainReliefTest.map();
        int peaks=0,previous=0;
        double length=Math.hypot(1432,2972);
        for (int station=0;station<=length;station+=16) {
            int x=(int)Math.round(-3800+1432*station/length),z=(int)Math.round(-12020-2972*station/length);
            assertTrue(map.coastDistance(x,z)>0,"新分岐の軸は全区間陸上");
            int y=FixedTerrain.surfaceY(map,x,z);
            if (station>160 && station<length-160 && y>220) {
                int next=FixedTerrain.surfaceY(map,(int)Math.round(x+1432*16/length),(int)Math.round(z-2972*16/length));
                if(y>previous && y>=next)peaks++;
            }
            previous=y;
        }
        assertTrue(peaks>=4,"固定した複数の峰: "+peaks);
        assertEquals(0,map.mountainWeight(-5448,-13264),"旧分岐を撤去する");
        var source=map.river(-4000,-12100);
        assertTrue(Math.hypot(source.x()+4000,source.z()+12100)<16,"源流の固定中心線を維持する");
        int water=map.waterSurface(source.x(),source.z()),bed=FixedTerrain.surfaceY(map,source.x(),source.z());
        assertTrue(water>=63 && water<=110 && bed==water-1,"谷頭の源流は水深1の沢");
        for (int x=-4128;x<=-3872;x+=16) assertTrue(FixedTerrain.surfaceY(map,x,-12100)<180,"谷頭の周囲を山で埋めない");
        assertEquals(100,FixedTerrain.surfaceY(map,-4000,-14100));
        assertEquals(0,map.mountainWeight(-4000,-14100),"拠点は新分岐の西のふもと");
        System.out.println("MOVED_BRANCH peaks="+peaks+" source_water="+water+" source_bed="+bed);
        String destination=System.getenv("ASHEN_MOUNTAIN_PREVIEW");
        if(destination!=null) {
            var rows=new StringBuilder("x,z,height,coast\n");
            for(int z=-13000;z<=-11400;z+=8)for(int x=-4600;x<=-2800;x+=8)
                rows.append(x).append(',').append(z).append(',').append(FixedTerrain.surfaceY(map,x,z)).append(',').append(map.coastDistance(x,z)).append('\n');
            Files.createDirectories(Path.of(destination));Files.writeString(Path.of(destination).resolve("headwater.csv"),rows);
        }
    }

    @Test
    void trenchHasSteepWallsAndKeepsItsFloor() throws Exception {
        var map=MountainReliefTest.map();double steep=0,min=100;
        for (int z=-15200;z<=-14900;z++) {
            double coast=map.coastDistance(-8500,z);var shore=OceanRelief.shore(map,-8500,z);
            double y=map.ocean().height(map,-8500,z,-coast,shore);
            min=Math.min(min,y);
            double previous=map.ocean().height(map,-8500,z-1,-map.coastDistance(-8500,z-1),OceanRelief.shore(map,-8500,z-1));
            steep=Math.max(steep,Math.abs(y-previous));
        }
        assertTrue(min>=-120 && min<=-100,"海溝の中央を−100以下へ深める: "+min);
        assertTrue(steep>=.45,"海溝の急な両壁: "+steep);
        System.out.println("TRENCH_WALL max_gradient="+steep+" floor="+min);
    }
}
