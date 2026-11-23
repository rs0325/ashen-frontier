package io.github.rs0325.ashenfrontier.world.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 地域別の承認マーカーだけに遅延lootを割り当てる。容器の実物は生成しない。 */
public final class LootMarkerProcessor extends StructureProcessor {
    public record Marker(BlockPos pos, ResourceLocation table) {
        static final Codec<Marker> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Marker::pos), ResourceLocation.CODEC.fieldOf("table").forGetter(Marker::table)
        ).apply(i, Marker::new));
    }
    public static final MapCodec<LootMarkerProcessor> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.LONG.fieldOf("seed").forGetter(p -> p.seed), Marker.CODEC.listOf().fieldOf("markers").forGetter(p -> p.markers)
    ).apply(i, LootMarkerProcessor::new));
    private static final DeferredRegister<StructureProcessorType<?>> TYPES = DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, "ashen_frontier");
    private static final Supplier<StructureProcessorType<LootMarkerProcessor>> TYPE = TYPES.register("facility_loot", () -> () -> CODEC);
    private final long seed;
    private final List<Marker> markers;
    private final Map<BlockPos, ResourceLocation> tables;

    public LootMarkerProcessor(long seed, List<Marker> markers) {
        this.seed = seed;
        this.markers = List.copyOf(markers);
        tables = markers.stream().collect(Collectors.toUnmodifiableMap(Marker::pos, Marker::table));
    }

    public static void register(IEventBus bus) { TYPES.register(bus); }

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pivot,
            StructureTemplate.StructureBlockInfo original, StructureTemplate.StructureBlockInfo current, StructurePlaceSettings settings) {
        if (settings.getBoundingBox() != null && !settings.getBoundingBox().isInside(current.pos())) return null;
        var table = tables.get(current.pos());
        if (table == null) return current;
        var tag = current.nbt() == null ? new CompoundTag() : current.nbt().copy();
        tag.remove("Items");
        tag.putString("LootTable", table.toString());
        tag.putLong("LootTableSeed", FacilityPlacement.lootSeed(seed, current.pos()));
        return new StructureTemplate.StructureBlockInfo(current.pos(), current.state(), tag);
    }

    @Override
    protected StructureProcessorType<?> getType() { return TYPE.get(); }
}
