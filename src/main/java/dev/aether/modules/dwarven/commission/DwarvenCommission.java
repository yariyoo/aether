package dev.aether.modules.dwarven.commission;

// one line of the commission list as the game shows it; progress runs 0 to 1
public record DwarvenCommission(String name, double progress, boolean done) {}
