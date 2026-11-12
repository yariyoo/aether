package dev.aether.modules.dwarven.fight;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

// learns from the commission's own progress which mob types count: kills followed by a progress tick credit their type,
// and a type whose kills never move it is dropped from the hunt
public final class KillCredit {
    // the tab list updates a few seconds after the kill
    static final long WINDOW_MS = 6000L;
    static final int DROP_AFTER = 5;

    private record Kill(String type, long at) {}

    private final ArrayDeque<Kill> pending = new ArrayDeque<>();
    private final Set<String> credited = new HashSet<>();
    private final Map<String, Integer> uncredited = new HashMap<>();
    private final Set<String> dropped = new HashSet<>();

    public void killed(String type, long now) {
        pending.addLast(new Kill(type, now));
    }

    public void progressed(long now) {
        for (Kill k : pending) {
            if (now - k.at() <= WINDOW_MS) {
                credited.add(k.type());
                uncredited.remove(k.type());
            }
        }
        pending.clear();
    }

    // returns the type that was just dropped, or null
    public String tick(long now) {
        String droppedNow = null;
        Iterator<Kill> it = pending.iterator();
        while (it.hasNext()) {
            Kill k = it.next();
            if (now - k.at() <= WINDOW_MS) break;
            it.remove();
            if (credited.contains(k.type())) continue;
            if (uncredited.merge(k.type(), 1, Integer::sum) >= DROP_AFTER && dropped.add(k.type())) droppedNow = k.type();
        }
        return droppedNow;
    }

    public boolean counts(String type) {
        return !dropped.contains(type);
    }

    public boolean verified(String type) {
        return credited.contains(type);
    }

    public void reset() {
        pending.clear();
        credited.clear();
        uncredited.clear();
        dropped.clear();
    }
}
