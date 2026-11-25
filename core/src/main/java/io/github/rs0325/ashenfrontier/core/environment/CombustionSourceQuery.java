package io.github.rs0325.ashenfrontier.core.environment;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import java.util.UUID;

/** 設備の復元時に受領済み番号を照会する。サーバースレッド専用で、通知の受領・発行は行わない。 */
public final class CombustionSourceQuery extends Event {
    private final ServerLevel level;
    private final UUID sourceId;
    private long lastSequence;

    private CombustionSourceQuery(ServerLevel level, UUID sourceId) { this.level = level; this.sourceId = sourceId; }
    public ServerLevel level() { return level; }
    public UUID sourceId() { return sourceId; }

    /** 受信側が永続台帳の最終番号を提供する。複数次元の回答は最大値を採用する。 */
    public void provide(long sequence) {
        if (sequence < 0) throw new IllegalArgumentException("通知番号は非負です");
        lastSequence = Math.max(lastSequence, sequence);
    }

    /** 全受信側で確認できた最終番号。0は受領記録がないことで、環境条件の既定値ではない。 */
    public static long lastSequence(ServerLevel level, UUID sourceId) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("通知番号の照会はサーバースレッド専用です");
        var query = new CombustionSourceQuery(level, sourceId);
        NeoForge.EVENT_BUS.post(query);
        return query.lastSequence;
    }
}
