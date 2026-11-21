package io.github.rs0325.ashenfrontier.core;

import net.minecraft.data.PackOutput;
import net.minecraft.core.HolderLookup;
import net.neoforged.neoforge.common.data.DataMapProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import io.github.rs0325.ashenfrontier.core.catalog.FuelData;
import io.github.rs0325.ashenfrontier.core.catalog.NatureCatalog;
import io.github.rs0325.ashenfrontier.core.catalog.RelicCatalog;
import java.util.stream.Stream;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class MaterialDataProvider extends DataMapProvider {
    MaterialDataProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
        super(output, lookup);
    }

    static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(event.includeServer(),
                new MaterialDataProvider(event.getGenerator().getPackOutput(), event.getLookupProvider()));
    }

    @Override
    protected void gather(HolderLookup.Provider lookup) {
        for (var entry : Stream.concat(NatureCatalog.entries().stream(), RelicCatalog.entries().stream()).toList()) {
            if (entry.fuel() != null) {
                var fuel = FuelData.from(entry);
                if (entry.fluid()) {
                    builder(FuelData.FLUID)
                            .add(AshenFrontierCore.id(entry.id()), fuel, false)
                            .add(AshenFrontierCore.id("flowing_" + entry.id()), fuel, false);
                } else {
                    builder(FuelData.ITEM)
                            .add(AshenFrontierCore.id(entry.id()), fuel, false);
                }
            }
            if (entry.fluid()) continue;
            builder(MaterialData.TYPE).add(AshenFrontierCore.id(entry.id()),
                    new MaterialData(entry.massKg(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            entry.physical() == null ? Optional.empty()
                                    : Optional.ofNullable(entry.physical().get("mohs_hardness"))), false);
        }
    }
}
