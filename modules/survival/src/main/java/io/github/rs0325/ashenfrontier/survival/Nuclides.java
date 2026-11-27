package io.github.rs0325.ashenfrontier.survival;

import com.google.gson.JsonParser;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 成人固定の核種別換算データ。未登録を別核種やゼロ線量へ置き換えない。 */
public final class Nuclides {
    public record Definition(double halfLifeGameSeconds,double ingestionSvBq,double inhalationSvBq,
                             double gammaSvM2SecondBq,String source) {
        public Definition {
            if(!Double.isFinite(halfLifeGameSeconds)||halfLifeGameSeconds<=0
                    ||!Double.isFinite(ingestionSvBq)||ingestionSvBq<0
                    ||!Double.isFinite(inhalationSvBq)||inhalationSvBq<0
                    ||!Double.isFinite(gammaSvM2SecondBq)||gammaSvM2SecondBq<0||source==null||source.isBlank())
                throw new IllegalArgumentException("核種の係数または出典が不正です");
        }
    }
    private static final Map<String,Definition> DEFINITIONS=load();
    private Nuclides() {}
    /** 同梱した物理係数を核種IDで引く。モデルが未定義ならempty。 */
    public static Optional<Definition> get(String id) { return Optional.ofNullable(DEFINITIONS.get(id)); }
    static void install() { DEFINITIONS.forEach((id,d)->ItemProperties.registerHalfLife(id,d.halfLifeGameSeconds())); }
    private static Map<String,Definition> load() {
        try(var reader=new InputStreamReader(Nuclides.class.getResourceAsStream("/catalog/nuclides.json"),StandardCharsets.UTF_8)) {
            var loaded=new LinkedHashMap<String,Definition>();
            var json=JsonParser.parseReader(reader).getAsJsonObject();
            for(var entry:json.entrySet()) {
                String id=net.minecraft.resources.ResourceLocation.parse(entry.getKey()).toString();var value=entry.getValue().getAsJsonObject();
                loaded.put(id,new Definition(value.get("half_life_game_seconds").getAsDouble(),
                        value.get("ingestion_sv_bq").getAsDouble(),value.get("inhalation_sv_bq").getAsDouble(),
                        value.get("gamma_sv_m2_second_bq").getAsDouble(),value.get("source").getAsString()));
            }
            return Map.copyOf(loaded);
        } catch(java.io.IOException exception) { throw new IllegalStateException("核種係数を読めません",exception); }
    }
}
