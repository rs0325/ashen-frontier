package io.github.rs0325.ashenfrontier.core.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.rs0325.ashenfrontier.core.catalog.NatureCatalog;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 設備が所有する槽。内部の量・容量と全演算はWaterMassの整数mgで持つ。世界の水源には触れない。
 * 変更はサーバースレッド専用（違反はIllegalStateException）。
 * 指定量・容量は丸め後も正値が必要で、不正値はIllegalArgumentException。
 * 満杯・異品質・容器成分不足は移送量0で変更しない。容器のkg保存値は操作時に丸めて読む。
 */
public final class WaterTank {
    /** 保存境界のkg表現。読み込み時に1 mg格子へ丸め、空槽の品質を消す。 */
    public record Contents(double capacityKg, double massKg, Optional<ResourceLocation> quality) {
        public static final Codec<Contents> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.doubleRange(Double.MIN_VALUE, WaterMass.MAX_KG).fieldOf("capacity_kg").forGetter(Contents::capacityKg),
                Codec.doubleRange(0, WaterMass.MAX_KG).fieldOf("mass_kg").forGetter(Contents::massKg),
                ResourceLocation.CODEC.optionalFieldOf("quality").forGetter(Contents::quality)
        ).apply(i, Contents::new));

        public Contents {
            long capacity = positiveUnits(capacityKg);
            long mass = WaterMass.fromKg(massKg);
            if (mass > capacity || mass > 0 && (quality.isEmpty() || !isWater(quality.get()))) {
                throw new IllegalArgumentException("槽の残量・水質が不正です");
            }
            capacityKg = WaterMass.toKg(capacity);
            massKg = WaterMass.toKg(mass);
            if (mass == 0) quality = Optional.empty();
        }
    }

    private final long capacity;
    private long mass;
    private Optional<ResourceLocation> quality;

    public WaterTank(double capacityKg) { this(new Contents(capacityKg, 0, Optional.empty())); }

    public WaterTank(Contents contents) {
        capacity = WaterMass.fromKg(contents.capacityKg());
        mass = WaterMass.fromKg(contents.massKg());
        quality = contents.quality();
    }

    public Contents contents() { return new Contents(WaterMass.toKg(capacity), WaterMass.toKg(mass), quality); }

    /** #55等が既に確保した水を受け、受入kgだけを返す。水源の減算はしない。 */
    public double fill(ServerPlayer player, ResourceLocation incomingQuality, double offeredKg) {
        requireServer(player);
        long offered = positiveUnits(offeredKg);
        if (!isWater(incomingQuality) || !accepts(incomingQuality)) return 0;
        long moved = Math.min(offered, capacity - mass);
        if (moved > 0) {
            mass += moved;
            quality = Optional.of(incomingQuality);
        }
        return WaterMass.toKg(moved);
    }

    /** 単一容器から槽へ一操作で移し、実移送kgを返す。複数個スタックは事前に分割する。 */
    public double fromContainer(ServerPlayer player, ItemStack container, double requestedKg) {
        requireServer(player);
        long requested = positiveUnits(requestedKg);
        Double stored = container.get(RelicProperties.CONTENT_MASS);
        ResourceLocation incomingQuality = container.get(NatureProperties.QUALITY);
        if (container.getCount() != 1 || stored == null || !WaterMass.inRange(stored)
                || incomingQuality == null || !isWater(incomingQuality) || !accepts(incomingQuality)) return 0;
        long available = WaterMass.fromKg(stored);
        long moved = Math.min(requested, Math.min(available, capacity - mass));
        if (moved == 0) return 0;
        mass += moved;
        quality = Optional.of(incomingQuality);
        container.set(RelicProperties.CONTENT_MASS, WaterMass.toKg(available - moved));
        return WaterMass.toKg(moved);
    }

    /** 同じIDの容器へ移す。容量kgは登録仕様から渡す。空容器は残った品質によらず補充できる。 */
    public double toContainer(ServerPlayer player, ItemStack container, double capacityKg, double requestedKg) {
        requireServer(player);
        long requested = positiveUnits(requestedKg);
        long containerCapacity = positiveUnits(capacityKg);
        Double stored = container.get(RelicProperties.CONTENT_MASS);
        if (container.getCount() != 1 || stored == null || !WaterMass.inRange(stored) || mass == 0) return 0;
        long contained = WaterMass.fromKg(stored);
        ResourceLocation currentQuality = quality.orElseThrow();
        if (contained > containerCapacity || contained > 0 && !currentQuality.equals(container.get(NatureProperties.QUALITY))) return 0;
        long moved = Math.min(requested, Math.min(mass, containerCapacity - contained));
        if (moved == 0) return 0;
        mass -= moved;
        if (mass == 0) quality = Optional.empty();
        container.set(RelicProperties.CONTENT_MASS, WaterMass.toKg(contained + moved));
        container.set(NatureProperties.QUALITY, currentQuality);
        return WaterMass.toKg(moved);
    }

    public boolean contains(ResourceLocation expectedQuality, double massKg) {
        return WaterMass.inRange(massKg) && WaterMass.fromKg(massKg) <= mass
                && quality.filter(expectedQuality::equals).isPresent();
    }

    void consume(ResourceLocation expectedQuality, double massKg) {
        long used = positiveUnits(massKg);
        if (used > mass || quality.filter(expectedQuality::equals).isEmpty()) {
            throw new IllegalStateException("槽の水量または品質が不足しています");
        }
        mass -= used;
        if (mass == 0) quality = Optional.empty();
    }

    private boolean accepts(ResourceLocation incomingQuality) {
        return mass == 0 || quality.filter(incomingQuality::equals).isPresent();
    }

    static boolean isWater(ResourceLocation quality) {
        return quality.getNamespace().equals("ashen_frontier") && NatureCatalog.entries().stream()
                .anyMatch(e -> e.id().equals(quality.getPath()) && e.kind().equals("water"));
    }

    static void requireServer(ServerPlayer player) {
        if (!player.server.isSameThread()) throw new IllegalStateException("加工操作はサーバースレッド専用です");
    }

    private static long positiveUnits(double kg) {
        long units = WaterMass.fromKg(kg);
        if (units == 0) throw new IllegalArgumentException("水量は丸め後も1 mg以上が必要です");
        return units;
    }
}
