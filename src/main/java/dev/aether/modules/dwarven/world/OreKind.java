package dev.aether.modules.dwarven.world;

public enum OreKind {
    MITHRIL_GRAY, MITHRIL_GREEN, MITHRIL_BLUE, TITANIUM;

    public boolean isMithril() {
        return this != TITANIUM;
    }
}
