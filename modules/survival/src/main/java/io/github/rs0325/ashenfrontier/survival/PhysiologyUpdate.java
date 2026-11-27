package io.github.rs0325.ashenfrontier.survival;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** 主観的段階だけを本人へ送る。計測していないK/kg/Jは配信しない。 */
public record PhysiologyUpdate(int stages,boolean pending,boolean enabled,boolean exhausted,boolean corrupted) implements CustomPacketPayload {
    public static final Type<PhysiologyUpdate> TYPE=new Type<>(AshenFrontierSurvival.id("physiology"));
    public static final StreamCodec<RegistryFriendlyByteBuf,PhysiologyUpdate> CODEC=StreamCodec.of((buf,value)->{
        buf.writeInt(value.stages);buf.writeBoolean(value.pending);buf.writeBoolean(value.enabled);buf.writeBoolean(value.exhausted);buf.writeBoolean(value.corrupted);
    },buf->new PhysiologyUpdate(buf.readInt(),buf.readBoolean(),buf.readBoolean(),buf.readBoolean(),buf.readBoolean()));
    private static PhysiologyUpdate client=new PhysiologyUpdate(0,true,true,false,false);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE,CODEC,(packet,context)->context.enqueueWork(()->{
            client=packet;
            if(packet.corrupted)context.player().displayClientMessage(Component.translatable("survival.ashen_frontier.corrupted"),true);
            else if(!packet.enabled)context.player().displayClientMessage(Component.translatable("survival.ashen_frontier.disabled"),true);
            else if(packet.pending)context.player().displayClientMessage(Component.translatable("survival.ashen_frontier.pending"),true);
            else {
                int selected=-1;
                for(int i=0;i<13;i++)if(packet.stage(i)>0&&(selected<0||packet.stage(i)>packet.stage(selected)))selected=i;
                if(selected>=0)context.player().displayClientMessage(Component.translatable("survival.ashen_frontier.symptom."+selected,
                        Component.translatable("survival.ashen_frontier.stage."+packet.stage(selected))).append(" ")
                        .append(Component.translatable("survival.ashen_frontier.remedy."+selected)),true);
            }
        }));
    }
    public int stage(int index) { return stages>>>(index*2)&3; }
    public static PhysiologyUpdate client() { return client; }
    static PhysiologyUpdate snapshot(Body body,boolean pending,boolean enabled) {
        int stages=0;for(int i=0;i<13;i++)stages|=body.level(i)<<(2*i);
        return new PhysiologyUpdate(stages,pending,enabled,body.exhausted,false);
    }
    static void send(ServerPlayer player,PhysiologyUpdate value) { PacketDistributor.sendToPlayer(player,value); }
}
