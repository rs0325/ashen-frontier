package io.github.rs0325.ashenfrontier.structures;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import java.nio.file.Path;
import java.io.IOException;

/** 配布jarに含まれない制作起動口。coreを含む登録の完了後にだけ変換する。 */
@EventBusSubscriber(modid = "ashen_frontier_world")
public final class StructureTool {
    @SubscribeEvent
    public static void gather(GatherDataEvent event) throws IOException {
        String config = System.getProperty("ashen_frontier.structureConfig", "");
        if (!config.isBlank()) new StructureConverter(event.getLookupProvider().join()).convert(Path.of(config));
        String fixtures = System.getProperty("ashen_frontier.structureFixtures", "");
        if (!fixtures.isBlank()) FixtureWriter.write(Path.of(fixtures));
    }
}
