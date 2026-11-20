package io.github.rs0325.ashenfrontier.core.vanilla;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;

@EventBusSubscriber(modid = AshenFrontierCore.MOD_ID)
public final class VanillaWorldEvents {
    private VanillaWorldEvents() {}

    @SubscribeEvent
    public static void preventNaturalSpawns(FinalizeSpawnEvent event) {
        if (shouldCancelSpawn(event.getSpawnType())) {
            event.setSpawnCancelled(true);
        }
    }

    public static boolean shouldCancelSpawn(MobSpawnType type) {
        return type == MobSpawnType.NATURAL || type == MobSpawnType.CHUNK_GENERATION
                || type == MobSpawnType.PATROL || type == MobSpawnType.EVENT;
    }

    @SubscribeEvent
    public static void removeVillagerTrades(VillagerTradesEvent event) {
        event.getTrades().clear();
    }

    @SubscribeEvent
    public static void preventNetherPortal(BlockEvent.PortalSpawnEvent event) {
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void preventEndPortal(PlayerInteractEvent.RightClickBlock event) {
        if (event.getItemStack().is(Items.ENDER_EYE)
                && event.getLevel().getBlockState(event.getPos()).is(Blocks.END_PORTAL_FRAME)) {
            event.setCanceled(true);
        }
    }
}
