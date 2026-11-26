package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.world.MapVersion;
import io.github.rs0325.ashenfrontier.world.MapCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.ArrayList;
import java.util.List;

/** 受信者のセルと四周1セルだけ。サーバーは全域の数値を配信しない。 */
record WeatherCellsPayload(ResourceLocation dimension,MapVersion map,long tick,WeatherSettings settings,List<Cell> cells) implements CustomPacketPayload {
    record Cell(int index,WeatherState state) {}
    static final Type<WeatherCellsPayload> TYPE=new Type<>(AshenFrontierCore.id("weather_cells"));
    static final StreamCodec<RegistryFriendlyByteBuf,WeatherCellsPayload> CODEC=StreamCodec.of((buf,value)->{
        buf.writeResourceLocation(value.dimension);buf.writeUtf(value.map.mapVersion(),128);buf.writeInt(value.map.formatVersion());
        buf.writeUtf(value.map.manifestHash(),128);buf.writeLong(value.tick);
        buf.writeLong(value.settings.tick());buf.writeInt(value.settings.intervalTicks());buf.writeInt(value.settings.enabledEvents());buf.writeBoolean(value.settings.dynamic());buf.writeVarInt(value.cells.size());
        for(var cell:value.cells) { buf.writeVarInt(cell.index);for(long bits:WeatherQuery.encode(cell.state))buf.writeLong(bits); }
    },buf->{
        var dimension=buf.readResourceLocation();var map=new MapVersion(buf.readUtf(128),buf.readInt(),buf.readUtf(128));
        long tick=buf.readLong();var setting=new WeatherSettings(buf.readLong(),buf.readInt(),buf.readInt(),buf.readBoolean());int count=buf.readVarInt();
        if(count<0||count>9)throw new IllegalArgumentException("気象同期のセル数が不正です");
        var cells=new ArrayList<Cell>();
        for(int i=0;i<count;i++) {
            int at=buf.readVarInt();if(at<0||at>=4096)throw new IllegalArgumentException("気象同期のセル位置が不正です");
            long[] bits=new long[12];for(int field=0;field<bits.length;field++)bits[field]=buf.readLong();
            cells.add(new Cell(at,WeatherQuery.decode(bits)));
        }
        return new WeatherCellsPayload(dimension,map,tick,setting,List.copyOf(cells));
    });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE,CODEC,(packet,context)->context.enqueueWork(()->{
            if(context.player().level().dimension().location().equals(packet.dimension))WeatherClientState.accept(packet);
        }));
    }
    static WeatherCellsPayload around(ServerLevel level,BlockPos pos) {
        if(!MapCoordinates.contains(pos.getX(),pos.getZ()))return null;
        var data=WeatherStore.get(level);int x=(pos.getX()-MapCoordinates.MIN)/512,z=(pos.getZ()-MapCoordinates.MIN)/512;
        var cells=new ArrayList<Cell>();
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
            int cx=x+dx,cz=z+dz;if(cx<0||cx>=64||cz<0||cz>=64)continue;
            int at=cz*64+cx;cells.add(new Cell(at,data.current.cell(at)));
        }
        return new WeatherCellsPayload(level.dimension().location(),data.version,data.current.tick(),data.timeline.at(data.current.tick()),List.copyOf(cells));
    }
}
