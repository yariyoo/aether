package dev.aether.modules.commission;

import dev.aether.util.ClientUtils;

final class CommissionLog {
    private CommissionLog() {}

    static void log(String message) {
        ClientUtils.sendDebugMessage("[Commission] " + message);
    }
}
