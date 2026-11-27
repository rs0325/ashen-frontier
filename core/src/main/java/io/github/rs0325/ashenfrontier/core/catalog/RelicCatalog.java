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

    /** 同じ劣化段階の鉄骨・鋼板・波板に共通の材質画像IDを返す。登録IDは変更しない。 */
    static String textureId(String id) {
        return switch (id) {
            case "coated_steel_sheet", "coated_corrugated_steel_sheet" -> "intact_steel_beam";
            case "rusted_steel_sheet", "rusted_corrugated_steel_sheet" -> "weathered_steel_beam";
            case "perforated_steel_sheet", "perforated_corrugated_steel_sheet" -> "pitted_steel_beam";
            case "remnant_steel_sheet", "remnant_corrugated_steel_sheet" -> "section_loss_steel_beam";
            default -> id;
        };
    }

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
