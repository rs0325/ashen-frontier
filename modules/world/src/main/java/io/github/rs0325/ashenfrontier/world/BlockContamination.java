package io.github.rs0325.ashenfrontier.world;

import com.mojang.serialization.Codec;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.SoilContamination;
import io.github.rs0325.ashenfrontier.core.catalog.NatureCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import java.util.List;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** サーバー権威の位置API。生成分布と改変記録を統合し、対象外ブロックはemptyを返す。 */
public final class BlockContamination {
    static final Codec<Map<Long, SoilContamination>> CODEC = Codec.unboundedMap(
            Codec.STRING.xmap(Long::parseLong, Object::toString), SoilContamination.CODEC)
            .<Map<Long, SoilContamination>>xmap(HashMap::new, values -> values);
    private static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, AshenFrontierWorld.MOD_ID);
    static final Supplier<AttachmentType<Map<Long, SoilContamination>>> CHANGES = TYPES.register("block_contamination",
            () -> AttachmentType.<Map<Long, SoilContamination>>builder(() -> new HashMap<>()).serialize(CODEC).build());
    private static final Supplier<AttachmentType<SoilContamination>> FALLING = TYPES.register("falling_contamination",
            () -> AttachmentType.builder(() -> SoilContamination.CLEAN).serialize(SoilContamination.CODEC).build());
    private static final Supplier<AttachmentType<SoilContamination>> CARRIED = TYPES.register("carried_contamination",
            () -> AttachmentType.builder(() -> SoilContamination.CLEAN).serialize(SoilContamination.CODEC).build());

    private static final class Targets {
        private static final Set<Block> BLOCKS = create();
        private static Set<Block> create() {
            var result = new HashSet<>(Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT,
                    Blocks.PODZOL, Blocks.MYCELIUM, Blocks.ROOTED_DIRT, Blocks.DIRT_PATH, Blocks.FARMLAND,
                    Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.MUD, Blocks.PACKED_MUD, Blocks.CLAY,
                    Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL));
            NatureCatalog.entries().stream().filter(entry -> entry.kind().equals("soil") || entry.sediment()
                    || entry.id().equals("clay_sediment") || entry.id().equals("peat"))
                    .forEach(entry -> result.add(BuiltInRegistries.BLOCK.get(AshenFrontierCore.id(entry.id()))));
            return Set.copyOf(result);
        }
    }

    /** 正本の連続値を明示変更した後の通知。量の生成や減衰を代行しない。 */
    public static final class Changed extends net.neoforged.bus.api.Event {
        public final ServerLevel level;
        public final BlockPos position;
        Changed(ServerLevel level,BlockPos position) { this.level=level;this.position=position.immutable(); }
    }
    private BlockContamination() {}

    static void register(IEventBus bus) {
        TYPES.register(bus);
        ContaminationDisplay.register(bus);
        NeoForge.EVENT_BUS.addListener(BlockContamination::drops);
    }

    public static boolean isTarget(BlockState state) { return Targets.BLOCKS.contains(state.getBlock()); }

    /** 実ブロックを判定して6指標を返す。読取りだけではチャンクに添付データを作らない。 */
    public static Optional<SoilContamination> get(ServerLevel level, BlockPos pos) {
        return isTarget(level.getBlockState(pos)) ? Optional.of(sample(level, pos, level.getBlockState(pos))) : Optional.empty();
    }

    static SoilContamination sample(ServerLevel level, BlockPos pos, BlockState state) {
        var chunk = level.getChunkAt(pos);
        if (chunk.hasData(CHANGES)) {
            var changed = chunk.getData(CHANGES).get(pos.asLong());
            if (changed != null) return changed;
        }
        if (!(level.getChunkSource().getGenerator() instanceof FixedMapChunkGenerator generator)) return SoilContamination.CLEAN;
        if (state.is(BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("fertile_soil")))
                && Math.abs(pos.getX() + 8500) < 96 && Math.abs(pos.getZ() + 8192) < 96 && pos.getY() < 100)
            return SoilContamination.CLEAN;
        return generator.terrain().column(pos.getX(), pos.getZ()).contamination(pos.getY());
    }

    /** 対象位置の値を明示保存する。清浄値も削除せず、チャンクを保存対象にする。対象外は例外。 */
    public static void set(ServerLevel level, BlockPos pos, SoilContamination value) {
        if (!isTarget(level.getBlockState(pos))) throw new IllegalArgumentException("汚染対象外の位置: " + pos);
        record(level, pos, value);
    }

    private static void record(ServerLevel level, BlockPos pos, SoilContamination value) {
        var chunk = level.getChunkAt(pos);
        chunk.getData(CHANGES).put(pos.asLong(), value);
        chunk.setUnsaved(true);NeoForge.EVENT_BUS.post(new Changed(level,pos));
    }

    /** 空いた位置の上書き記録を除去する。未改変チャンクには添付を作らない。 */
    public static void clear(ServerLevel level, BlockPos pos) {
        var chunk = level.getChunkAt(pos);
        if (chunk.hasData(CHANGES) && chunk.getData(CHANGES).remove(pos.asLong()) != null) {
                if (chunk.getData(CHANGES).isEmpty()) chunk.removeData(CHANGES);
            chunk.setUnsaved(true);NeoForge.EVENT_BUS.post(new Changed(level,pos));
        }
    }

    /** ピストンの実移動が成功した後、すべての元位置を消してから移動先へ同時に転写する。 */
    public static void moved(ServerLevel level, List<BlockPos> positions, Map<BlockPos, SoilContamination> values, Direction direction) {
        for (var pos : positions) {
            clear(level, pos);
            clear(level, pos.relative(direction));
        }
        values.forEach((pos, value) -> record(level, pos.relative(direction), value));
    }

    static void writeItem(ItemStack stack, SoilContamination value) {
        var stages = value.stages();
        if (stages.equals(SoilContamination.Stages.CLEAN)) stack.remove(ItemProperties.SOIL_CONTAMINATION);
        else stack.set(ItemProperties.SOIL_CONTAMINATION, stages);
    }

    static SoilContamination readItem(ItemStack stack) {
        return stack.getOrDefault(ItemProperties.SOIL_CONTAMINATION, SoilContamination.Stages.CLEAN).representative();
    }

    /** 爆発・押し壊し・流体破壊を含む共通lootと、エンダーマンの運搬ブロックの回収に適用する。 */
    public static void loot(BlockState state, LootParams.Builder params, List<ItemStack> stacks) {
        if (!isTarget(state)) return;
        SoilContamination value;
        if (params.getOptionalParameter(LootContextParams.THIS_ENTITY) instanceof EnderMan enderman
                && state.equals(enderman.getCarriedBlock())) {
            value = enderman.hasData(CARRIED) ? enderman.getData(CARRIED) : SoilContamination.CLEAN;
        } else {
            var pos = BlockPos.containing(params.getParameter(LootContextParams.ORIGIN));
            value = sample(params.getLevel(), pos, state);
        }
        for (var stack : stacks) writeItem(stack, value);
    }

    private static void drops(BlockDropsEvent event) {
        if (isTarget(event.getState()) || event.getState().is(Blocks.MOVING_PISTON)) clear(event.getLevel(), event.getPos());
    }

    /** 設置が成功し、取消し処理を通過した後だけ呼ぶ。コピーした消費前アイテムを渡す。 */
    public static void placed(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (isTarget(level.getBlockState(pos))) {
            var value = readItem(stack);
            set(level, pos, value);
            ContaminationDisplay.wither(level, pos, value);
        }
    }

    /** 落下する砂・砂利にも保存可能な値を移し、空いた元位置の記録を除去する。 */
    public static void falling(ServerLevel level, BlockPos pos, FallingBlockEntity entity, SoilContamination value) {
        entity.setData(FALLING, value);
        clear(level, pos);
    }

    public static void landed(ServerLevel level, BlockPos pos, FallingBlockEntity entity) {
        if (entity.hasData(FALLING) && isTarget(level.getBlockState(pos))) set(level, pos, entity.getData(FALLING));
    }

    public static void fallingDrop(FallingBlockEntity entity, ItemEntity drop) {
        if (drop != null && entity.hasData(FALLING)) writeItem(drop.getItem(), entity.getData(FALLING));
    }

    public static void carried(ServerLevel level, BlockPos pos, EnderMan entity, SoilContamination value) {
        entity.setData(CARRIED, value);
        clear(level, pos);
    }

    public static void placedCarried(ServerLevel level, BlockPos pos, EnderMan entity) {
        set(level, pos, entity.hasData(CARRIED) ? entity.getData(CARRIED) : SoilContamination.CLEAN);
        entity.removeData(CARRIED);
    }
}
