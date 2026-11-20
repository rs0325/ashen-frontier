package io.github.rs0325.ashenfrontier.core.compat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class ModCompatibility {
    private static final Set<String> BUILT_IN_ALLOWED = Set.of(
            "jei", "emi", "sodium", "iris", "minecraft", "neoforge");

    private ModCompatibility() {}

    public static Set<String> unsupported(List<String> loadedModIds, List<String> configuredAllowed) {
        Set<String> allowed = configuredAllowed.stream().collect(Collectors.toSet());
        allowed.addAll(BUILT_IN_ALLOWED);
        return loadedModIds.stream()
                .filter(id -> !allowed.contains(id) && !id.startsWith("ashen_frontier_"))
                .collect(Collectors.toUnmodifiableSet());
    }
}
