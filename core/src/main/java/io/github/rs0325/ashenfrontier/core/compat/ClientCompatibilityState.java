package io.github.rs0325.ashenfrontier.core.compat;

import java.util.Set;

final class ClientCompatibilityState {
    static Set<String> unsupported = Set.of();
    static boolean titleWarningShown;
    static boolean worldWarningShown;

    private ClientCompatibilityState() {}
}
