package com.tierlookup.model;

import java.util.List;

/** Provider result with source-exact tier data. Region is intentionally not part of the model. */
public record ProviderResult(String providerId, String displayName, Status status, List<TierEntry> tiers, String message, long fetchedAt) {
    public enum Status {
        OK, NOT_RANKED, ERROR, DISABLED, LOADING
    }
    public ProviderResult {
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
    }
    public static ProviderResult loading(String id, String name) {
        return new ProviderResult(id, name, Status.LOADING, List.of(), null, System.currentTimeMillis());
    }
    public static ProviderResult error(String id, String name, String message) {
        return new ProviderResult(id, name, Status.ERROR, List.of(), message, System.currentTimeMillis());
    }
}
