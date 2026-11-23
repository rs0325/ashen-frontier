package io.github.rs0325.ashenfrontier.world.structure;

import com.google.gson.Gson;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProcessorRule;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** FEATURES段階専用。完了済みチャンクは標準の保存済み生成状態によって再配置しない。 */
public final class FacilityPlacement {
    private record Entry(FacilityManifest manifest, FacilityManifest.Piece piece) {}
    private final Map<String, List<Entry>> chunks = new HashMap<>();

    public FacilityPlacement(List<FacilityManifest> facilities) {
        var ids = new HashSet<String>();
        for (var facility : facilities.stream().sorted(Comparator.comparing(FacilityManifest::id)).toList()) {
            facility.validate();
            if (!ids.add(facility.id())) throw new IllegalArgumentException("施設IDが重複しています: " + facility.id());
            facility.chunks().forEach((key, pieces) -> {
                var entries = chunks.computeIfAbsent(key, k -> new ArrayList<>());
                for (int index : pieces) {
                    var piece = facility.pieces().get(index);
                    for (var prior : entries) {
                        if (facility.box(piece.bounds()).intersects(prior.manifest.box(prior.piece.bounds())))
                            throw new IllegalArgumentException("施設同士の配置範囲が重複しています: " + facility.id());
                    }
                    entries.add(new Entry(facility, piece));
                }
            });
        }
    }

    /** リソースの索引は施設manifestの配列。Iが版ごとに制作し、存在しない版は施設なし。 */
    public static FacilityPlacement load(WorldGenLevel level, String mapVersion) {
        var id = ResourceLocation.fromNamespaceAndPath("ashen_frontier", "structures/" + mapVersion + "/index.json");
        var resource = level.getLevel().getServer().getResourceManager().getResource(id);
        if (resource.isEmpty()) return new FacilityPlacement(List.of());
        try (var reader = resource.get().openAsReader()) {
            return new FacilityPlacement(List.of(new Gson().fromJson(reader, FacilityManifest[].class)));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("施設索引を読めません: " + id, e);
        }
    }

    /** 隣接チャンクの読取り・更新を行わず、当該チャンクに交差する断片だけを配置する。 */
    public void placeChunk(WorldGenLevel level, ChunkAccess chunk) {
        if (chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) return;
        ChunkPos pos = chunk.getPos();
        var bounds = new BoundingBox(pos.getMinBlockX(), level.getMinBuildHeight(), pos.getMinBlockZ(),
                pos.getMaxBlockX(), level.getMaxBuildHeight() - 1, pos.getMaxBlockZ());
        for (var entry : chunks.getOrDefault(FacilityManifest.key(pos), List.of())) {
            var facility = entry.manifest;
            var template = level.getLevel().getStructureManager().get(ResourceLocation.parse(entry.piece.template())).orElseThrow();
            var pieceBounds = entry.piece.bounds();
            if (!template.getSize().equals(new Vec3i(pieceBounds[3] - pieceBounds[0] + 1,
                    pieceBounds[4] - pieceBounds[1] + 1, pieceBounds[5] - pieceBounds[2] + 1)))
                throw new IllegalStateException("断片の寸法がmanifestと不一致です: " + entry.piece.template());
            var offset = facility.transform(FacilityManifest.pos(entry.piece.offset()));
            var processor = new ProtectedRuleProcessor(facility);
            var settings = new StructurePlaceSettings().setRotation(Rotation.valueOf(facility.rotation()))
                    .setMirror(Mirror.valueOf(facility.mirror())).setIgnoreEntities(true).setKnownShape(true)
                    .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING).setBoundingBox(bounds)
                    .addProcessor(processor).addProcessor(new LootMarkerProcessor(level.getSeed(), facility.loot().stream()
                            .map(marker -> new LootMarkerProcessor.Marker(facility.transform(FacilityManifest.pos(marker.pos())), ResourceLocation.parse(marker.table()))).toList()));
            template.placeInWorld(level, offset, FacilityManifest.pos(facility.origin()), settings,
                    RandomSource.create(level.getSeed()), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            // WorldGenRegionではProtoChunkのtickリストへ積み、周辺生成後のロード時に実行する。
            processor.updates.forEach((target, state) -> {
                if (state.getBlock() instanceof FallingBlock) level.scheduleTick(target, state.getBlock(), 1);
                var fluid = state.getFluidState();
                if (!fluid.isEmpty()) level.scheduleTick(target, fluid.getType(), fluid.getType().getTickDelay(level));
            });
            // 標準templateが容器へ割り当てる乱数を、座標由来のloot seedで確定し直す。
            for (var marker : facility.loot()) {
                var target = facility.transform(FacilityManifest.pos(marker.pos()));
                if (bounds.isInside(target) && level.getBlockEntity(target) instanceof RandomizableContainer container)
                    container.setLootTableSeed(lootSeed(level.getSeed(), target));
            }
        }
    }

    static long lootSeed(long seed, BlockPos pos) {
        long value = seed ^ Mth.getSeed(pos) ^ 0x58d3a72e6914bc01L;
        return value == 0 ? 1 : value;
    }

    /** manifestからのみ生成する、境界・保護経路で制限された標準ルールプロセッサ。 */
    private static final class ProtectedRuleProcessor extends RuleProcessor {
        private final List<BoundingBox> protectedPaths;
        private final Map<BlockPos, BlockState> updates = new HashMap<>();

        ProtectedRuleProcessor(FacilityManifest facility) {
            super(facility.decay().stream().map(rule -> new ProcessorRule(
                    new RandomBlockMatchTest(rule.inputState().getBlock(), rule.probability()), AlwaysTrueTest.INSTANCE,
                    rule.outputState())).toList());
            protectedPaths = facility.protectedPaths().stream().map(facility::box).toList();
        }

        @Override
        public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pivot,
                StructureTemplate.StructureBlockInfo original, StructureTemplate.StructureBlockInfo current,
                StructurePlaceSettings settings) {
            if (!settings.getBoundingBox().isInside(current.pos()) || current.state().is(Blocks.STRUCTURE_VOID)) return null;
            var result = protectedPaths.stream().anyMatch(box -> box.isInside(current.pos())) ? current
                    : super.processBlock(level, offset, pivot, original, current, settings);
            if (result.state().is(Blocks.STRUCTURE_VOID)) return null;
            if (result.state().getBlock() instanceof FallingBlock || !result.state().getFluidState().isEmpty())
                updates.put(result.pos(), result.state());
            return result;
        }
    }
}
