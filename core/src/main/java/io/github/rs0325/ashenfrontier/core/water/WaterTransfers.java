package io.github.rs0325.ashenfrontier.core.water;

import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import io.github.rs0325.ashenfrontier.core.recipe.WaterMass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Optional;
import java.util.OptionalLong;

/** サーバー上の移送入口。書込みの間にイベント・ネットワーク・任意コールバックを挟まない。 */
public final class WaterTransfers {
    public enum Status { SUCCESS, PARTIAL, NO_BINDING, NO_SOURCE, DEPLETED, FULL, QUALITY_MISMATCH,
        INVALID_CONTAINER, UNLOADED, ACCESS_DENIED }
    public record Result(Status status, long movedMg, Optional<ResourceLocation> quality) {
        public Component message() {
            return Component.translatable("water.ashen_frontier." + status.name().toLowerCase(Locale.ROOT),
                    WaterMass.toKg(movedMg));
        }
    }
    public record Snapshot(Status status, Optional<WaterSourceDefinition> definition, OptionalLong remainingMg) {}
    private WaterTransfers() {}

    /** 読み取りで初期化・補給・チャンク生成をしない。精密残量はサーバー内向け。 */
    public static Snapshot inspect(ServerLevel level, BlockPos intake, WaterSourceKey.Kind kind) {
        WaterSources data = WaterSources.get(level);
        if (data == null || data.provider == null) return snapshot(Status.NO_BINDING);
        if (!level.hasChunkAt(intake)) return snapshot(Status.UNLOADED);
        var access = data.provider.resolve(level, intake, kind);
        if (access.isEmpty()) return snapshot(Status.NO_SOURCE);
        var key = access.get().key();
        var definition = data.definition(key);
        if (key.kind() != kind) return snapshot(Status.NO_SOURCE);
        if (kind == WaterSourceKey.Kind.SURFACE) {
            var fluid = level.getFluidState(intake);
            if (!fluid.isSource() || fluid.getType() != NatureContent.fluids().get(definition.quality().getPath()).source().get())
                return snapshot(Status.NO_SOURCE);
        }
        if (definition.stock().isEmpty() && !access.get().designatedOceanSource()) return snapshot(Status.NO_SOURCE);
        WaterBudget budget = data.budget(key);
        return new Snapshot(budget != null && budget.remaining() == 0 ? Status.DEPLETED : Status.SUCCESS,
                Optional.of(definition), budget == null ? OptionalLong.empty() : OptionalLong.of(budget.remaining()));
    }

    /** 手の登録補給容器1個へ移す。空なら旧品質によらず受入、非空なら同品質のみ。 */
    public static Result sourceToContainer(ServerPlayer player, BlockPos intake, InteractionHand hand, double requestedKg) {
        long requested = positive(requestedKg);
        ServerLevel level = player.serverLevel();
        WaterSources.requireThread(level);
        if (!allowed(player, intake, hand)) return result(Status.ACCESS_DENIED);
        Snapshot source = inspect(level, intake, WaterSourceKey.Kind.SURFACE);
        if (source.status() != Status.SUCCESS) return result(source.status());
        ItemStack held = player.getItemInHand(hand);
        Double capacityKg = WaterContainerData.capacity(held);
        Double stored = held.get(RelicProperties.CONTENT_MASS);
        if (held.getCount() != 1 || capacityKg == null || stored == null || !Double.isFinite(stored) || stored < 0 || stored > WaterMass.MAX_KG) return result(Status.INVALID_CONTAINER);
        long contained = WaterMass.fromKg(stored);
        long capacity = WaterMass.fromKg(capacityKg);
        if (contained > capacity) return result(Status.INVALID_CONTAINER);
        var definition = source.definition().orElseThrow();
        if (contained > 0 && !definition.quality().equals(held.get(NatureProperties.QUALITY)))
            return result(Status.QUALITY_MISMATCH);
        long moved = Math.min(requested, Math.min(capacity - contained, source.remainingMg().orElse(requested)));
        if (moved == 0) return result(Status.FULL);
        ItemStack filled = held.copy();
        filled.set(RelicProperties.CONTENT_MASS, WaterMass.toKg(contained + moved));
        filled.set(NatureProperties.QUALITY, definition.quality());
        // provider照会後に現在の手を取得済み。以降の書込みに外部呼出しを挟まない。
        debit(level, definition, moved);
        player.setItemInHand(hand, filled);
        return moved(requested, moved, definition.quality());
    }

    /**
     * ロード済み同一levelの所属BE槽へ移す。設備への到達と入口/設備の保護権限を検査する。
     * 揚程・流量・仕事量は呼出し側設備が先に検査する。裸のキーで遠隔採水しない。
     */
    public static Result sourceToTank(ServerPlayer player, BlockPos intake, WaterSourceKey.Kind kind,
                                       InteractionHand hand, BlockEntity owner, double requestedKg) {
        long requested = positive(requestedKg);
        ServerLevel level = player.serverLevel();
        WaterSources.requireThread(level);
        if (!validOwner(player, owner, hand) || !level.mayInteract(player, intake)) return result(Status.ACCESS_DENIED);
        Snapshot source = inspect(level, intake, kind);
        if (source.status() != Status.SUCCESS) return result(source.status());
        var definition = source.definition().orElseThrow();
        var tank = ((WaterTankOwner) owner).waterTank();
        var contents = tank.contents();
        if (contents.massKg() > 0 && !contents.quality().orElseThrow().equals(definition.quality()))
            return result(Status.QUALITY_MISMATCH);
        long amount = Math.min(requested, Math.min(WaterMass.fromKg(contents.capacityKg()) - WaterMass.fromKg(contents.massKg()),
                source.remainingMg().orElse(requested)));
        if (amount == 0) return result(Status.FULL);
        long actual = WaterMass.fromKg(tank.fill(player, definition.quality(), WaterMass.toKg(amount)));
        debit(level, definition, actual);
        owner.setChanged();
        return moved(requested, actual, definition.quality());
    }

    /** 承認された容器から所属槽へ既存WaterTankで移す。世界台帳には触れない。 */
    public static Result containerToTank(ServerPlayer player, InteractionHand hand, BlockEntity owner, double requestedKg) {
        long request = positive(requestedKg);
        WaterSources.requireThread(player.serverLevel());
        if (!validOwner(player, owner, hand)) return result(Status.ACCESS_DENIED);
        var held = player.getItemInHand(hand);
        if (!WaterContainerData.canEmpty(held) || held.getCount() != 1) return result(Status.INVALID_CONTAINER);
        var tank = ((WaterTankOwner) owner).waterTank();
        var quality = held.get(NatureProperties.QUALITY);
        var contents = tank.contents();
        if (contents.massKg() > 0 && !contents.quality().orElseThrow().equals(quality)) return result(Status.QUALITY_MISMATCH);
        long actual = WaterMass.fromKg(tank.fromContainer(player, held, requestedKg));
        if (actual == 0) return result(Status.FULL);
        owner.setChanged();
        return moved(request, actual, quality);
    }

    /** 登録補給容器へ既存WaterTankで移す。密封容器への補給は拒否。 */
    public static Result tankToContainer(ServerPlayer player, InteractionHand hand, BlockEntity owner, double requestedKg) {
        long request = positive(requestedKg);
        WaterSources.requireThread(player.serverLevel());
        if (!validOwner(player, owner, hand)) return result(Status.ACCESS_DENIED);
        var held = player.getItemInHand(hand);
        Double capacity = WaterContainerData.capacity(held);
        if (capacity == null || held.getCount() != 1) return result(Status.INVALID_CONTAINER);
        var tank = ((WaterTankOwner) owner).waterTank();
        var quality = tank.contents().quality();
        Double stored = held.get(RelicProperties.CONTENT_MASS);
        if (stored == null || !Double.isFinite(stored) || stored < 0 || stored > WaterMass.MAX_KG)
            return result(Status.INVALID_CONTAINER);
        if (WaterMass.fromKg(stored) > 0 && quality.isPresent()
                && !quality.get().equals(held.get(NatureProperties.QUALITY))) return result(Status.QUALITY_MISMATCH);
        long actual = WaterMass.fromKg(tank.toContainer(player, held, capacity, requestedKg));
        if (actual == 0) return result(Status.FULL);
        owner.setChanged();
        return moved(request, actual, quality.orElseThrow());
    }

    private static boolean validOwner(ServerPlayer player, BlockEntity owner, InteractionHand hand) {
        return owner instanceof WaterTankOwner && owner.getLevel() == player.serverLevel() && !owner.isRemoved()
                && player.serverLevel().hasChunkAt(owner.getBlockPos())
                && player.serverLevel().getBlockEntity(owner.getBlockPos()) == owner && allowed(player, owner.getBlockPos(), hand);
    }
    private static boolean allowed(ServerPlayer player, BlockPos pos, InteractionHand hand) {
        double range = player.blockInteractionRange() + 1;
        return !player.isSpectator() && player.distanceToSqr(pos.getCenter()) <= range * range
                && player.serverLevel().mayInteract(player, pos)
                && player.mayUseItemAt(pos, Direction.UP, player.getItemInHand(hand));
    }
    private static void debit(ServerLevel level, WaterSourceDefinition definition, long moved) {
        WaterSources data = WaterSources.get(level);
        var budget = data.budget(definition.key());
        if (budget != null) {
            if (budget.take(moved) != moved) throw new IllegalStateException("確保済み水量が変化しました");
            data.setDirty();
        }
    }
    private static long positive(double kg) {
        long amount = WaterMass.fromKg(kg);
        if (amount == 0) throw new IllegalArgumentException("移送量は1 mg以上が必要です");
        return amount;
    }
    private static Result result(Status status) { return new Result(status, 0, Optional.empty()); }
    private static Result moved(long request, long actual, ResourceLocation quality) {
        return new Result(actual == request ? Status.SUCCESS : Status.PARTIAL, actual, Optional.of(quality));
    }
    private static Snapshot snapshot(Status status) { return new Snapshot(status, Optional.empty(), OptionalLong.empty()); }
}
