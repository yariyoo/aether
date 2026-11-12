package dev.aether.modules.dwarven;

import dev.aether.util.ClientUtils;

public final class DwarvenLog {
    private DwarvenLog() {}

    public static void log(String message) {
        ClientUtils.sendDebugMessage("[Dwarven] " + message);
        dev.aether.modules.dwarven.diag.Diag.event(dev.aether.modules.dwarven.diag.Diag.LOG, message);
    }
}
