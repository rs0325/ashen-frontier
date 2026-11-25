package io.github.rs0325.ashenfrontier.climate;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.concurrent.atomic.AtomicReference;

/** サーバー権威の期限付き更新。参加者に依存せず全セルを計算し、送信だけ周辺に限定する。 */
final class WeatherService {
    private static final AtomicReference<WeatherSettings> PENDING=new AtomicReference<>();
    private WeatherService() {}
    static WeatherSettings settings(long tick) {
        int mask=0;for(var entry:AshenFrontierClimate.EVENT_ENABLED.entrySet())if(entry.getValue().get())mask|=entry.getKey().bit();
        return new WeatherSettings(tick,AshenFrontierClimate.WEATHER_INTERVAL.get(),mask,AshenFrontierClimate.DYNAMIC_WEATHER.get());
    }
    static void reset() { PENDING.set(null); }
    static void reload() { PENDING.set(settings(0)); }
    static void started(ServerStartedEvent event) {
        var level=event.getServer().overworld();
        if(ClimateQuery.mapVersion(level).isEmpty())return;
        WeatherStore.get(level);
        PENDING.compareAndSet(null,settings(0));
    }
    static void tick(ServerTickEvent.Post event) {
        var level=event.getServer().overworld();
        if(ClimateQuery.mapVersion(level).isEmpty())return;
        var data=WeatherStore.get(level);
        var clock=ClimateClock.get(level);
        if(clock.revision()!=data.clockRevision) {
            var history=clock.history();
            data.clock(history,clock.revision(),history.changes().getLast().tick());
        }
        var request=PENDING.get();
        if(request!=null&&data.settings(new WeatherSettings(level.getGameTime(),request.intervalTicks(),request.enabledEvents(),request.dynamic())))
            PENDING.compareAndSet(request,null);
        long start=System.nanoTime();
        data.service(level.getGameTime(),AshenFrontierClimate.WEATHER_CELL_BUDGET.get(),
                start+AshenFrontierClimate.WEATHER_MICROS.get()*1000L);
        if(level.getGameTime()%20==0&&data.timeline.next(data.current.tick(),data.time)>level.getGameTime()) {
            for(var player:level.players()) {
                var payload=WeatherCellsPayload.around(level,player.blockPosition());
                if(payload!=null)PacketDistributor.sendToPlayer(player,payload);
            }
        }
    }
}
