package com.tierlookup.client;

import java.util.*;

/** Client-visible snapshot of the local player's own inventory and selected active effects. */
public record LocalInventorySnapshot(List<Item> items, boolean speed, boolean strength, boolean regeneration) {
    public LocalInventorySnapshot {
        items = items == null ? List.of() : List.copyOf(items);
    }
    public boolean empty() {
        return items.isEmpty();
    }
    public record Item(String id, int count, boolean customNamed, Set<String> potionEffects) {
        public Item {
            id = id == null ? "" : normalize(id);
            count = Math.max(1, count);
            potionEffects = potionEffects == null ? Set.of() : Set.copyOf(potionEffects);
        }
        private static String normalize(String raw) {
            String s=raw.toLowerCase(Locale.ROOT).trim();
            int colon=s.indexOf(':');
            return colon>=0&&colon+1<s.length()?s.substring(colon+1):s;
        }
    }
}
