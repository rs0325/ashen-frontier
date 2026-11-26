package io.github.rs0325.ashenfrontier.core.water;

/** 有限水源の整数mg収支。アクセス・保存はWaterSourcesが担当する。 */
final class WaterBudget {
    record Receipt(long fromTick, long toTick, long offeredMg, long acceptedMg, long overflowMg) {
        Receipt {
            if (fromTick < 0 || toTick <= fromTick || offeredMg < 0 || acceptedMg < 0
                    || acceptedMg > offeredMg || overflowMg != offeredMg - acceptedMg)
                throw new IllegalArgumentException("涵養レシートが不正です");
        }
    }
    record Recharge(Receipt receipt, boolean committedNow) {}
    final long capacity;
    private long remaining;
    private long through;
    private Receipt last;

    WaterBudget(long capacity, long remaining, long through, Receipt last) {
        if (capacity <= 0 || remaining < 0 || remaining > capacity || through < 0
                || last != null && (last.toTick() != through || last.acceptedMg() > capacity))
            throw new IllegalArgumentException("水源残高が不正です");
        this.capacity = capacity;
        this.remaining = remaining;
        this.through = through;
        this.last = last;
    }
    long remaining() { return remaining; }
    long through() { return through; }
    Receipt last() { return last; }

    long take(long request) {
        if (request <= 0) throw new IllegalArgumentException("移送は正のmgが必要です");
        long moved = Math.min(request, remaining);
        remaining -= moved;
        return moved;
    }

    Recharge recharge(long from, long to, long offered) {
        if (from < 0 || to <= from || offered < 0) throw new IllegalArgumentException("涵養区間・量が不正です");
        if (last != null && from == last.fromTick() && to == last.toTick()) {
            if (offered != last.offeredMg()) throw new IllegalArgumentException("同一区間の涵養量が変わりました");
            return new Recharge(last, false);
        }
        // 古い区間は結果を再構成せず拒否し、再加算もしない。
        if (from != through) throw new IllegalArgumentException("涵養区間が連続していません（過去・重複・gap）");
        long accepted = Math.min(offered, capacity - remaining);
        remaining = Math.addExact(remaining, accepted);
        through = to;
        last = new Receipt(from, to, offered, accepted, offered - accepted);
        return new Recharge(last, true);
    }
}
