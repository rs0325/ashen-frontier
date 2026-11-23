package io.github.rs0325.ashenfrontier.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class ItemProperties {
    public static final double TEMPERATURE_STEP_K = 5.0;
    public static final double WEAR_STEP = 1.0;
    public static final double COMPOSITION_STEP = 0.01;
    public static final double ACTIVITY_STEP_BQ = 0.1;
    public static final long TIME_STEP_TICKS = 20;
    public static final double ROT_STEP_SECONDS = 600.0;
    public static final double ROT_REFERENCE_K = 293.15;
    public static final double ROT_Q10 = 2.0;

    private static final double HEAT_CONDUCTANCE_W_K = 1.0;
    private static final Map<String, Double> HALF_LIFE_SECONDS = new HashMap<>();

    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(AshenFrontierCore.MOD_ID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Temperature>> TEMPERATURE = COMPONENTS.registerComponentType("temperature", builder -> builder.persistent(Temperature.CODEC).networkSynchronized(Temperature.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Wear>> WEAR = COMPONENTS.registerComponentType("wear", builder -> builder.persistent(Wear.CODEC).networkSynchronized(Wear.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Composition>> COMPOSITION = COMPONENTS.registerComponentType("composition", builder -> builder.persistent(Composition.CODEC).networkSynchronized(Composition.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Contamination>> CONTAMINATION = COMPONENTS.registerComponentType("contamination", builder -> builder.persistent(Contamination.CODEC).networkSynchronized(Contamination.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Radioactivity>> RADIOACTIVITY = COMPONENTS.registerComponentType("radioactivity", builder -> builder.persistent(Radioactivity.CODEC).networkSynchronized(Radioactivity.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Rot>> ROT = COMPONENTS.registerComponentType("rot", builder -> builder.persistent(Rot.CODEC).networkSynchronized(Rot.STREAM_CODEC));

    private ItemProperties() {}

    public static void register(IEventBus bus) { COMPONENTS.register(bus); }

    /** 材料の質量と比熱から現在温度を遅延計算する。物性が揃わない場合はempty。 */
    public static Optional<Double> currentTemperature(ItemStack stack, long gameTime, double ambientK) {
        Optional<Temperature> temperature = Optional.ofNullable(stack.get(TEMPERATURE.get()));
        Optional<MaterialData> material = MaterialData.get(stack.getItem());
        if (temperature.isEmpty() || material.isEmpty() || material.get().specificHeatJKgK().isEmpty()) return Optional.empty();
        double heatCapacity = material.get().massKg() * material.get().specificHeatJKgK().get();
        double timeConstantSeconds = heatCapacity / HEAT_CONDUCTANCE_W_K;
        return Optional.of(ItemPropertyMath.cool(temperature.get().kelvin(), ambientK,
                GameTime.gameSeconds(gameTime - temperature.get().gameTime()), timeConstantSeconds));
    }

    public static Optional<Double> writeCurrentTemperature(ItemStack stack, long gameTime, double ambientK) {
        return currentTemperature(stack, gameTime, ambientK).map(kelvin -> {
            long recordedAt = ItemPropertyMath.floorTime(gameTime, TIME_STEP_TICKS);
            double recordedKelvin = currentTemperature(stack, recordedAt, ambientK).orElseThrow();
            stack.set(TEMPERATURE.get(), Temperature.of(recordedKelvin, recordedAt));
            return kelvin;
        });
    }

    /** 半減期表に登録された核種の現在放射能を返す。未登録核種はempty。 */
    public static Optional<Double> currentActivity(ItemStack stack, long gameTime) {
        return Optional.ofNullable(stack.get(RADIOACTIVITY.get())).flatMap(radioactivity -> {
            Double halfLifeSeconds = HALF_LIFE_SECONDS.get(radioactivity.nuclide());
            return halfLifeSeconds == null ? Optional.empty() : Optional.of(ItemPropertyMath.activity(
                    radioactivity.becquerels(), GameTime.gameSeconds(gameTime - radioactivity.gameTime()), halfLifeSeconds));
        });
    }

    public static Optional<Double> writeCurrentActivity(ItemStack stack, long gameTime) {
        return Optional.ofNullable(stack.get(RADIOACTIVITY.get())).flatMap(current -> {
            Double halfLifeSeconds = HALF_LIFE_SECONDS.get(current.nuclide());
            if (halfLifeSeconds == null) return Optional.empty();
            double becquerels = ItemPropertyMath.activity(current.becquerels(),
                    GameTime.gameSeconds(gameTime - current.gameTime()), halfLifeSeconds);
            long recordedAt = ItemPropertyMath.floorTime(gameTime, TIME_STEP_TICKS);
            double recordedBq = ItemPropertyMath.activity(current.becquerels(),
                    GameTime.gameSeconds(recordedAt - current.gameTime()), halfLifeSeconds);
            stack.set(RADIOACTIVITY.get(), Radioactivity.of(current.nuclide(), recordedBq, recordedAt));
            return Optional.of(becquerels);
        });
    }

    /** 最後に確定した保存温度以降の腐敗進行量を基準温度換算ゲーム内秒で返す。 */
    public static Optional<Double> currentRotProgress(ItemStack stack, long gameTime, double storageTemperatureK) {
        return Optional.ofNullable(stack.get(ROT.get())).map(rot -> ItemPropertyMath.rotProgress(rot.referenceSeconds(),
                GameTime.gameSeconds(gameTime - rot.lastCalculatedAt()), storageTemperatureK, ROT_REFERENCE_K, ROT_Q10));
    }

    /** 保存温度を切り替える直前に、変更前温度で腐敗量を確定して書き戻す。 */
    public static Optional<Double> writeRotAtTemperature(ItemStack stack, long gameTime, double storageTemperatureK) {
        return currentRotProgress(stack, gameTime, storageTemperatureK).map(referenceSeconds -> {
            Rot previous = stack.get(ROT);
            long recordedAt = ItemPropertyMath.floorTime(gameTime, TIME_STEP_TICKS);
            stack.set(ROT.get(), Rot.of(previous.createdAt(), recordedAt,
                    ItemPropertyMath.rotProgress(previous.referenceSeconds(),
                            GameTime.gameSeconds(recordedAt - previous.lastCalculatedAt()), storageTemperatureK,
                            ROT_REFERENCE_K, ROT_Q10)));
            return referenceSeconds;
        });
    }

    /** 核種の半減期を秒単位で登録する。mod初期化時に呼び出す。 */
    public static void registerHalfLife(String nuclide, double halfLifeSeconds) {
        HALF_LIFE_SECONDS.put(nuclide, halfLifeSeconds);
    }

    public record Temperature(double kelvin, long gameTime) {
        /** 丸めて温度を生成する。標準コンストラクターは値を丸めないため、設定時はこのメソッドを使う。 */
        public static Temperature of(double kelvin, long gameTime) {
            return new Temperature(ItemPropertyMath.roundDown(kelvin, TEMPERATURE_STEP_K),
                    ItemPropertyMath.floorTime(gameTime, TIME_STEP_TICKS));
        }
        public static final Codec<Temperature> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.fieldOf("kelvin").forGetter(Temperature::kelvin),
                Codec.LONG.fieldOf("game_time").forGetter(Temperature::gameTime)).apply(i, Temperature::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, Temperature> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, Temperature::kelvin, ByteBufCodecs.VAR_LONG, Temperature::gameTime, Temperature::new);
    }

    public record Wear(double value, double maximum) {
        /** 丸めて摩耗を生成する。標準コンストラクターは値を丸めないため、設定時はこのメソッドを使う。 */
        public static Wear of(double value, double maximum) {
            return new Wear(ItemPropertyMath.roundDown(value, WEAR_STEP), ItemPropertyMath.roundDown(maximum, WEAR_STEP));
        }
        public static final Codec<Wear> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.fieldOf("value").forGetter(Wear::value),
                Codec.DOUBLE.fieldOf("maximum").forGetter(Wear::maximum)).apply(i, Wear::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, Wear> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, Wear::value, ByteBufCodecs.DOUBLE, Wear::maximum, Wear::new);
    }

    public record Composition(Map<String, Double> massFractions) {
        /** 質量比を丸めて生成する。標準コンストラクターは値を丸めないため、設定時はこのメソッドを使う。 */
        public static Composition of(Map<String, Double> massFractions) {
            return new Composition(quantizeMap(massFractions, COMPOSITION_STEP, false));
        }
        public static final Codec<Composition> CODEC = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)
                .xmap(Composition::new, Composition::massFractions);
        public static final StreamCodec<ByteBuf, Composition> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);
    }

    public record Contamination(Map<String, Double> amounts) {
        public Contamination { amounts = Map.copyOf(amounts); }
        /** 指標別のSI値をdoubleの精度で保持する。キーと単位はSoilContaminationを参照。 */
        public static Contamination of(Map<String, Double> amounts) {
            return new Contamination(amounts);
        }
        public static final Codec<Contamination> CODEC = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)
                .xmap(Contamination::new, Contamination::amounts);
        public static final StreamCodec<ByteBuf, Contamination> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);
    }

    public record Radioactivity(String nuclide, double becquerels, long gameTime) {
        /** 放射能と時刻を丸めて生成する。標準コンストラクターは値を丸めないため、設定時はこのメソッドを使う。 */
        public static Radioactivity of(String nuclide, double becquerels, long gameTime) {
            return new Radioactivity(nuclide, ItemPropertyMath.roundDown(becquerels, ACTIVITY_STEP_BQ),
                    ItemPropertyMath.floorTime(gameTime, TIME_STEP_TICKS));
        }
        public static final Codec<Radioactivity> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("nuclide").forGetter(Radioactivity::nuclide),
                Codec.DOUBLE.fieldOf("becquerels").forGetter(Radioactivity::becquerels),
                Codec.LONG.fieldOf("game_time").forGetter(Radioactivity::gameTime)).apply(i, Radioactivity::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, Radioactivity> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Radioactivity::nuclide, ByteBufCodecs.DOUBLE, Radioactivity::becquerels,
                ByteBufCodecs.VAR_LONG, Radioactivity::gameTime, Radioactivity::new);
    }

    public record Rot(long createdAt, long lastCalculatedAt, double referenceSeconds) {
        /** 作成・確定時刻と腐敗量を丸めて生成する。標準コンストラクターは値を丸めないため、設定時はこのメソッドを使う。 */
        public static Rot of(long createdAt, long lastCalculatedAt, double referenceSeconds) {
            return new Rot(ItemPropertyMath.floorTime(createdAt, TIME_STEP_TICKS),
                    ItemPropertyMath.floorTime(lastCalculatedAt, TIME_STEP_TICKS),
                    ItemPropertyMath.roundUp(referenceSeconds, ROT_STEP_SECONDS));
        }
        public static final Codec<Rot> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("created_at").forGetter(Rot::createdAt),
                Codec.LONG.fieldOf("last_calculated_at").forGetter(Rot::lastCalculatedAt),
                Codec.DOUBLE.fieldOf("reference_seconds").forGetter(Rot::referenceSeconds)).apply(i, Rot::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, Rot> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Rot::createdAt, ByteBufCodecs.VAR_LONG, Rot::lastCalculatedAt,
                ByteBufCodecs.DOUBLE, Rot::referenceSeconds, Rot::new);
    }

    private static Map<String, Double> quantizeMap(Map<String, Double> values, double step, boolean roundUp) {
        Map<String, Double> quantized = new HashMap<>();
        values.forEach((key, value) -> quantized.put(key,
                roundUp ? ItemPropertyMath.roundUp(value, step) : ItemPropertyMath.roundDown(value, step)));
        return Map.copyOf(quantized);
    }

}
