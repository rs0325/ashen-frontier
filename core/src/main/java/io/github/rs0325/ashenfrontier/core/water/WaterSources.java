package io.github.rs0325.ashenfrontier.core.water;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.saveddata.SavedData;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Objects;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** dimension単位の水源の正本。操作の公開入口はWaterTransfers。 */
public final class WaterSources extends SavedData {
    public static final String ID = "ashen_frontier_water_sources";
    private static final Codec<WaterBudget.Receipt> RECEIPT = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("from_tick").forGetter(WaterBudget.Receipt::fromTick),
            Codec.LONG.fieldOf("to_tick").forGetter(WaterBudget.Receipt::toTick),
            Codec.LONG.fieldOf("offered_mg").forGetter(WaterBudget.Receipt::offeredMg),
            Codec.LONG.fieldOf("accepted_mg").forGetter(WaterBudget.Receipt::acceptedMg),
            Codec.LONG.fieldOf("overflow_mg").forGetter(WaterBudget.Receipt::overflowMg)
    ).apply(i, WaterBudget.Receipt::new));
    private record Balance(long remaining, long through, Optional<WaterBudget.Receipt> last) {
        static final Codec<Balance> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("remaining_mg").forGetter(Balance::remaining),
                Codec.LONG.fieldOf("recharge_through_tick").forGetter(Balance::through),
                RECEIPT.optionalFieldOf("last_recharge").forGetter(Balance::last)
        ).apply(i, Balance::new));
    }
    private record Entry(WaterSourceDefinition definition, Optional<Balance> balance) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                WaterSourceDefinition.CODEC.fieldOf("definition").forGetter(Entry::definition),
                Balance.CODEC.optionalFieldOf("balance").forGetter(Entry::balance)
        ).apply(i, Entry::new));
    }
    private record Stored(int schema, boolean initialized, WaterSourceKey.MapRef map, String hash, List<Entry> sources) {
        static final Codec<Stored> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("water_schema_version").forGetter(Stored::schema),
                Codec.BOOL.fieldOf("initialized").forGetter(Stored::initialized),
                WaterSourceKey.MapRef.CODEC.fieldOf("map").forGetter(Stored::map),
                Codec.STRING.fieldOf("definition_hash").forGetter(Stored::hash),
                Entry.CODEC.listOf().fieldOf("sources").forGetter(Stored::sources)
        ).apply(i, Stored::new));
    }
    private final WaterSourceKey.MapRef map;
    private final String hash;
    private final Map<WaterSourceKey, WaterSourceDefinition> definitions = new LinkedHashMap<>();
    private final Map<WaterSourceKey, WaterBudget> budgets = new LinkedHashMap<>();
    WaterSourceAccessProvider provider;

    WaterSources(WaterSourceKey.MapRef map, List<WaterSourceDefinition> catalogue, long epoch) {
        this.map = map;
        hash = fingerprint(catalogue);
        for (var definition : sorted(catalogue)) {
            addDefinition(definition);
            definition.stock().ifPresent(stock -> budgets.put(definition.key(),
                    new WaterBudget(stock.capacityMg(), stock.initialMg(), epoch, null)));
        }
    }

    private WaterSources(Stored stored) {
        if (stored.schema() != 1 || !stored.initialized()) throw new IllegalStateException("未知・未初期化の水源保存形式です");
        map = stored.map();
        hash = stored.hash();
        for (Entry entry : stored.sources()) {
            var definition = entry.definition();
            addDefinition(definition);
            if (definition.stock().isPresent() != entry.balance().isPresent())
                throw new IllegalStateException("水源残高が欠損・過剰です");
            entry.balance().ifPresent(b -> budgets.put(definition.key(),
                    new WaterBudget(definition.stock().orElseThrow().capacityMg(),
                            b.remaining(), b.through(), b.last().orElse(null))));
        }
        if (!hash.equals(fingerprint(new ArrayList<>(definitions.values()))))
            throw new IllegalStateException("水源定義hashが一致しません");
    }

    private void addDefinition(WaterSourceDefinition definition) {
        if (!map.equals(definition.key().map()) || definitions.putIfAbsent(definition.key(), definition) != null)
            throw new IllegalArgumentException("水源の版または重複キーが不正です");
    }

    /**
     * worldの新規作成処理だけが呼ぶ初期化。ファイル欠損を見て選んではならない。
     * カタログ・版照合済み、サーバースレッド専用。既存記録があれば例外で拒否する。
     */
    public static void initializeNewWorld(ServerLevel level, WaterSourceKey.MapRef map,
            List<WaterSourceDefinition> definitions, WaterSourceAccessProvider provider) {
        requireThread(level);
        Objects.requireNonNull(provider);
        var proposed = new WaterSources(map, definitions, level.getGameTime());
        if (get(level) != null) throw new IllegalStateException("既存の水源を新規初期化できません");
        var data = level.getDataStorage().computeIfAbsent(factory(proposed, dataFile(level)), ID);
        data.provider = provider;
    }

    /**
     * 保存済み台帳への再接続。初期量・涵養epochは作らない。
     * ファイル/レコード欠損・版/定義不一致は例外で停止する。
     */
    public static void bindDefinitions(ServerLevel level, WaterSourceKey.MapRef map,
                                       List<WaterSourceDefinition> definitions, WaterSourceAccessProvider provider) {
        requireThread(level);
        Objects.requireNonNull(provider);
        var proposed = new WaterSources(map, definitions, level.getGameTime());
        WaterSources data = get(level);
        if (data == null) throw new IllegalStateException("再接続する水源台帳が欠損しています");
        if (!data.map.equals(map) || !data.hash.equals(proposed.hash))
            throw new IllegalStateException("保存済み水源と接続カタログの版・定義が異なります");
        data.provider = provider;
    }

    static Factory<WaterSources> factory(WaterSources proposed, Path file) {
        return new Factory<>(() -> {
            if (!Files.notExists(file))
                throw new IllegalStateException("既存の水源ファイルを読めないため初期化を拒否します");
            proposed.setDirty();
            return proposed;
        }, (tag, registries) -> decode(tag), null);
    }

    static Path dataFile(ServerLevel level) {
        return DimensionType.getStorageFolder(level.dimension(),
                level.getServer().getWorldPath(LevelResource.ROOT))
                .resolve("data").resolve(ID + ".dat");
    }

    static WaterSources get(ServerLevel level) {
        requireThread(level);
        WaterSources data = level.getDataStorage().get(new Factory<>(() -> {
            throw new IllegalStateException("水源は明示初期化が必要です");
        }, (tag, registries) -> decode(tag), null), ID);
        if (data == null && !Files.notExists(dataFile(level)))
            throw new IllegalStateException("既存の水源ファイルを読めません");
        return data;
    }

    static void requireThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("水源操作はサーバースレッド専用です");
    }

    WaterSourceDefinition definition(WaterSourceKey key) {
        WaterSourceDefinition result = definitions.get(key);
        if (result == null) throw new IllegalArgumentException("未登録または別版の水源キーです");
        return result;
    }
    WaterBudget budget(WaterSourceKey key) { return budgets.get(key); }

    /** M3が次窓のfromTickに使う保存済みgameTime。有限・接続済みキーが前提。読み取りのみ。 */
    public static long rechargeThroughTick(ServerLevel level, WaterSourceKey key) {
        WaterSources data = get(level);
        if (data == null || data.provider == null) throw new IllegalStateException("水源が未接続です");
        data.definition(key);
        WaterBudget budget = data.budget(key);
        if (budget == null) throw new IllegalArgumentException("無限源に涵養時刻はありません");
        return budget.through();
    }

    /** M3からのみ呼ぶ。同品質の非負mgを連続gameTime窓で確定。0も窓を進める。 */
    public static RechargeResult applyRecharge(ServerLevel level, WaterSourceKey key,
            ResourceLocation quality, long fromTick, long toTick, long offeredMg) {
        WaterSources data = get(level);
        if (data == null || data.provider == null) throw new IllegalStateException("水源が未接続です");
        var definition = data.definition(key);
        if (definition.stock().isEmpty() || !definition.quality().equals(quality))
            throw new IllegalArgumentException("有限源・同品質の涵養だけを受け付けます");
        if (toTick > level.getGameTime()) throw new IllegalArgumentException("未来の涵養窓は確定できません");
        var result = data.budget(key).recharge(fromTick, toTick, offeredMg);
        if (result.committedNow()) data.setDirty();
        var receipt = result.receipt();
        return new RechargeResult(receipt.fromTick(), receipt.toTick(), receipt.offeredMg(),
                receipt.acceptedMg(), receipt.overflowMg(), result.committedNow());
    }

    /** 再送では元の受入量/越流を返しcommittedNow=false。量はすべて非負mg。 */
    public record RechargeResult(long fromTick, long toTick, long offeredMg, long acceptedMg,
                                 long overflowMg, boolean committedNow) {}

    static WaterSources decode(CompoundTag tag) {
        return new WaterSources(Stored.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow());
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var entries = definitions.values().stream().map(d -> {
            WaterBudget budget = budgets.get(d.key());
            return new Entry(d, budget == null ? Optional.empty() : Optional.of(new Balance(
                    budget.remaining(), budget.through(), Optional.ofNullable(budget.last()))));
        }).toList();
        return (CompoundTag) Stored.CODEC.encodeStart(NbtOps.INSTANCE, new Stored(1, true, map, hash, entries)).getOrThrow();
    }

    private static List<WaterSourceDefinition> sorted(List<WaterSourceDefinition> definitions) {
        if (definitions.isEmpty()) throw new IllegalArgumentException("空の水源カタログは接続できません");
        return definitions.stream().sorted(Comparator.comparing(d -> d.key().kind().name() + ":" + d.key().localKey())).toList();
    }

    private static String fingerprint(List<WaterSourceDefinition> definitions) {
        String canonical = WaterSourceDefinition.CODEC.listOf().encodeStart(JsonOps.INSTANCE, sorted(definitions)).getOrThrow().toString();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
