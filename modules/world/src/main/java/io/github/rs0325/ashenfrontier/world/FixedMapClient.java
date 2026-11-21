package io.github.rs0325.ashenfrontier.world;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = AshenFrontierWorld.MOD_ID, value = Dist.CLIENT)
public final class FixedMapClient {
    private static final Set<CreateWorldScreen> INITIALIZED = Collections.newSetFromMap(new WeakHashMap<>());

    @SubscribeEvent
    public static void opening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof CreateWorldScreen screen) || !INITIALIZED.add(screen)) return;
        var state = screen.getUiState();
        if (!state.getSeed().isEmpty()) return;
        var selected = state.getWorldType().preset();
        if (selected == null || !selected.is(WorldPresets.NORMAL)) return;
        var key = ResourceKey.create(Registries.WORLD_PRESET, AshenFrontierCore.id("fixed_map"));
        var preset = state.getSettings().worldgenLoadContext().registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(key);
        state.setWorldType(new WorldCreationUiState.WorldTypeEntry(preset));
    }
}
