package io.github.rs0325.ashenfrontier.world;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RevisionEighteenTest {
    @Test void ownershipEdgesDoNotBecomeVerticalCuts() throws Exception {
        var map=MountainReliefTest.map();
        for(var p:new int[][]{{-4858,-14826,2,0},{-4856,-14826,0,2},{-4650,-14704,2,0}}) {
            int a=FixedTerrain.surfaceY(map,p[0],p[1]),b=FixedTerrain.surfaceY(map,p[0]+p[2],p[1]+p[3]);
            assertTrue(Math.abs(a-b)<=4,"島の所属切替を2ブロックで垂直に切らない: "+a+"/"+b);
        }
        for(int x=-4864;x<-4844;x++) {
            double a=FixedTerrain.landHeight(map,x,-14826),b=FixedTerrain.landHeight(map,x+1,-14826);
            assertTrue(Math.abs(a-b)<4,"セル境界の海岸接続は連続");
        }
    }

    @Test void rockyStraitBedContinuesBelowTheWater() throws Exception {
        var map=MountainReliefTest.map();
        assertEquals(0,map.islandStraitWeight(-5070,-15049),"北側の正常な島間は修正しない");
        assertNull(map.islandJunction(-5070,-15049));
        for(var p:new int[][]{{-4848,-14860},{-4840,-14840}}) {
            assertTrue(map.coastDistance(p[0],p[1])<0);
            int height=FixedTerrain.surfaceY(map,p[0],p[1]);
            assertTrue(height>=50 && height<=59,"水面直下の平らな台を、深すぎる溝にせず岩斜面へつなぐ: "+height);
        }
    }

    @Test void terrainOutsideStraitCorrectionsKeepsR17Heights() throws Exception {
        var map=MountainReliefTest.map();int unchanged=0,total=0;
        var output=new StringBuilder("x,z,r17,r18,coast,weight,junction\n");
        try(var stream=getClass().getResourceAsStream("/revision18-r17-heights.csv");
            var reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            reader.readLine();String line;
            while((line=reader.readLine())!=null) {
                var fields=line.split(",");int x=Integer.parseInt(fields[0]),z=Integer.parseInt(fields[1]),old=Integer.parseInt(fields[2]);
                int height=FixedTerrain.surfaceY(map,x,z);double weight=map.islandStraitWeight(x,z);
                boolean junction=map.islandJunction(x,z)!=null;
                if(weight==0 && !junction) {assertEquals(old,height,"正常箇所のr17地形: "+x+","+z);unchanged++;}
                output.append(x).append(',').append(z).append(',').append(old).append(',').append(height).append(',')
                        .append(map.coastDistance(x,z)).append(',').append(weight).append(',').append(junction).append('\n');total++;
            }
        }
        assertTrue(unchanged>1800 && unchanged>total*.8,"修正を海峡の接触帯へ限定: "+unchanged+"/"+total);
        String folder=System.getenv("ASHEN_MOUNTAIN_PREVIEW");
        if(folder!=null)Files.writeString(Path.of(folder).resolve("revision18-unchanged.csv"),output);
    }
}
