package io.github.rs0325.ashenfrontier.world;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 地図上の地点への移動要求。automaticならYを無視してサーバーの実地表上へ移動する。 */
record MapTeleportRequest(int x, int z, int y, boolean automatic) implements CustomPacketPayload {
    static final Type<MapTeleportRequest> TYPE = new Type<>(AshenFrontierCore.id("map_teleport"));
    static final StreamCodec<RegistryFriendlyByteBuf, MapTeleportRequest> CODEC = StreamCodec.of(
            (buf, value) -> { buf.writeInt(value.x); buf.writeInt(value.z); buf.writeInt(value.y); buf.writeBoolean(value.automatic); },
            buf -> new MapTeleportRequest(buf.readInt(), buf.readInt(), buf.readInt(), buf.readBoolean()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE, CODEC, MapTeleportRequest::handle);
    }

    private static void handle(MapTeleportRequest packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && !packet.teleport(player)) {
                player.displayClientMessage(Component.translatable("map.ashen_frontier.teleport_rejected"), false);
            }
        });
    }

    static boolean isAllowed(Player player) {
        return player.hasPermissions(2) && player.isCreative();
    }

    boolean withinBounds(ServerLevel level) {
        return MapCoordinates.contains(x, z) && level.getWorldBorder().isWithinBounds(new BlockPos(x, 0, z))
                && (automatic || y >= level.getMinBuildHeight() && y <= level.getMaxBuildHeight() - 2);
    }

    boolean teleport(ServerPlayer player) {
        var level = player.serverLevel();
        if (!isAllowed(player) || level.dimension() != Level.OVERWORLD
                || !withinBounds(level)
                || !(level.getChunkSource().getGenerator() instanceof FixedMapChunkGenerator)) return false;
        int targetY = automatic ? level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 2 : y;
        if (targetY < level.getMinBuildHeight() || targetY > level.getMaxBuildHeight() - 2) return false;
        var target = new BlockPos(x, targetY, z);
        if (!level.getBlockState(target).getCollisionShape(level, target).isEmpty()
                || !level.getBlockState(target.above()).getCollisionShape(level, target.above()).isEmpty()
                || !level.getFluidState(target).isEmpty() || !level.getFluidState(target.above()).isEmpty()) return false;
        player.teleportTo(level, x + 0.5, targetY, z + 0.5, player.getYRot(), player.getXRot());
        player.resetFallDistance();
        return true;
    }
}
