package io.github.rs0325.ashenfrontier.core;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.DataMapProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;

final class MaterialDataProvider extends DataMapProvider {
    MaterialDataProvider(PackOutput output, java.util.concurrent.CompletableFuture<net.minecraft.core.HolderLookup.Provider> lookup) {
        super(output, lookup);
    }

    static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(event.includeServer(),
                new MaterialDataProvider(event.getGenerator().getPackOutput(), event.getLookupProvider()));
    }

    @Override
    protected void gather() {
    }
}
