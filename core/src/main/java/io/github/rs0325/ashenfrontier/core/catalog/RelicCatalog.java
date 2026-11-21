package io.github.rs0325.ashenfrontier.core.catalog;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 遺物の登録・制作・配置用カタログ。列と単位はNatureCatalog.Entryと共通。 */
public final class RelicCatalog {
    private static final List<NatureCatalog.Entry> ENTRIES = load();

    private RelicCatalog() {}

    public static List<NatureCatalog.Entry> entries() { return ENTRIES; }

    /** 未登録IDは制作エラーとして停止する。 */
    public static NatureCatalog.Entry require(String id) {
        return ENTRIES.stream().filter(entry -> entry.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未登録の遺物: " + id));
    }

    private static List<NatureCatalog.Entry> load() {
        try (var reader = new InputStreamReader(RelicCatalog.class.getResourceAsStream("/catalog/relics.json"),
                StandardCharsets.UTF_8)) {
            return List.copyOf(new Gson().fromJson(reader, Document.class).entries());
        } catch (IOException exception) {
            throw new IllegalStateException("遺物カタログを読めません", exception);
        }
    }

    private record Document(List<NatureCatalog.Entry> entries) {}
}
