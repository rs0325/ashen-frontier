package io.github.rs0325.ashenfrontier.core.research;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 前提参照を検証し、前提順に並べた研究定義を返す。 */
public final class ResearchTree {
    private ResearchTree() {}
    public static Resolution resolve(Map<String, Definition> definitions) {
        Map<String, Definition> valid = new LinkedHashMap<>(definitions);
        Set<String> invalid = new HashSet<>();
        for (var entry : definitions.entrySet()) {
            boolean missing = entry.getValue().prerequisites().stream()
                    .anyMatch(id -> !definitions.containsKey(id));
            if (missing) invalid.add(entry.getKey());
        }
        Map<String, Integer> marks = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (String id : definitions.keySet()) visit(id, definitions, marks, invalid, order);
        invalid.forEach(valid::remove);
        order.removeIf(invalid::contains);
        return new Resolution(Map.copyOf(valid), List.copyOf(order), Set.copyOf(invalid));
    }
    private static void visit(String id, Map<String, Definition> defs,
                              Map<String, Integer> marks, Set<String> invalid, List<String> order) {
        int mark = marks.getOrDefault(id, 0);
        if (mark == 2 || invalid.contains(id)) return;
        if (mark == 1) { invalid.add(id); return; }
        marks.put(id, 1);
        for (String dependency : defs.get(id).prerequisites()) {
            if (defs.containsKey(dependency)) visit(dependency, defs, marks, invalid, order);
            if (invalid.contains(dependency)) invalid.add(id);
        }
        marks.put(id, 2);
        order.add(id);
    }
    public record Definition(String id, String name, String description,
                             List<String> prerequisites, List<Condition> conditions, List<String> unlocks) {}
    public record Condition(String type, String id) {}
    public record Resolution(Map<String, Definition> definitions, List<String> order, Set<String> invalid) {}
}
