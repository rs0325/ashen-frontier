package io.github.rs0325.ashenfrontier.core.compat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class ModCompatibility {
    private static final Set<String> BUILT_IN_ALLOWED = Set.of(
            "jei", "emi", "sodium", "iris", "mezz_config", "minecraft", "neoforge");

    private static final int MAX_DISPLAYED_MODS = 5;

    private ModCompatibility() {}

    public static Set<String> unsupported(List<String> loadedModIds, List<String> configuredAllowed) {
        Set<String> allowed = configuredAllowed.stream().collect(Collectors.toSet());
        allowed.addAll(BUILT_IN_ALLOWED);
        return loadedModIds.stream()
                .filter(id -> !allowed.contains(id) && !id.startsWith("ashen_frontier_"))
                .collect(Collectors.toUnmodifiableSet());
    }

    public static String displayedMods(Set<String> unsupported) {
        List<String> mods = unsupported.stream().sorted().toList();
        return String.join(", ", mods.subList(0, Math.min(mods.size(), MAX_DISPLAYED_MODS)));
    }

    public static int omittedCount(Set<String> unsupported) {
        return Math.max(0, unsupported.size() - MAX_DISPLAYED_MODS);
    }
}
