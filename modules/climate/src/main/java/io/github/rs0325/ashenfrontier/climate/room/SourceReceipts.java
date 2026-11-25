package io.github.rs0325.ashenfrontier.climate.room;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 発生源ごとの最終1件だけを保持する。過去番号は再適用せず、最終番号の内容違いも拒否する。 */
public final class SourceReceipts {
    public enum Result { ACCEPTED, DUPLICATE, OLD, CONFLICT }
    public record Last(long sequence, String payload) {}
    private final Map<UUID, Last> entries = new HashMap<>();

    public Result accept(UUID source, long sequence, String payload) {
        if (sequence <= 0) throw new IllegalArgumentException("通知番号は正値が必要です");
        var last = entries.get(source);
        if (last != null) {
            if (sequence < last.sequence) return Result.OLD;
            if (sequence == last.sequence) return last.payload.equals(payload) ? Result.DUPLICATE : Result.CONFLICT;
        }
        entries.put(source, new Last(sequence, payload));
        return Result.ACCEPTED;
    }
    public long lastSequence(UUID source) {
        var last = entries.get(source);
        return last == null ? 0 : last.sequence;
    }
    public Map<UUID, Last> entries() { return Map.copyOf(entries); }
    public void restore(UUID source, Last last) {
        if (last.sequence <= 0 || entries.putIfAbsent(source, last) != null)
            throw new IllegalArgumentException("通知受領台帳が不正です");
    }
}
