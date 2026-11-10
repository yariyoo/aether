package dev.aether.modules.commission;

import dev.aether.util.ClientUtils;

public final class CommissionLog {
    private CommissionLog() {}

    public static void log(String message) {
        ClientUtils.sendDebugMessage("[Commission] " + message);
    }
}
