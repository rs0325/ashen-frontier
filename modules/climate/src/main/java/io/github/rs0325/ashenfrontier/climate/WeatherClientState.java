package io.github.rs0325.ashenfrontier.climate;

import net.minecraft.resources.ResourceLocation;
import java.util.Map;
import java.util.Optional;

/** 受信済みの周辺セルだけを保持する。未受信を晴天・安全と見なさない。 */
public final class WeatherClientState {
    private static ResourceLocation dimension;
    private static long tick=-1;
    private static WeatherSettings settings;
    private static Map<Integer,WeatherState> cells=Map.of();
    private WeatherClientState() {}
    static void accept(WeatherCellsPayload packet) {
        if(packet.dimension().equals(dimension)&&packet.tick()<tick)return;
        dimension=packet.dimension();tick=packet.tick();settings=packet.settings();
        var next=new java.util.HashMap<Integer,WeatherState>();
        for(var cell:packet.cells())next.put(cell.index(),cell.state());
        cells=Map.copyOf(next);
    }
    public static Optional<WeatherState> cell(ResourceLocation currentDimension,int index) {
        return currentDimension.equals(dimension)?Optional.ofNullable(cells.get(index)):Optional.empty();
    }
    public static Optional<WeatherSettings> settings(ResourceLocation currentDimension) {
        return currentDimension.equals(dimension)?Optional.of(settings):Optional.empty();
    }
    public static void clear() { dimension=null;tick=-1;settings=null;cells=Map.of(); }
}
