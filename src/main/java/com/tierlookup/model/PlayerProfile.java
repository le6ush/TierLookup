package com.tierlookup.model;

import java.util.Map;

/** Raw per-provider profile data. No best/average/combined tier is calculated. */
public record PlayerProfile(PlayerIdentity player, Map<String, ProviderResult> providers, long fetchedAt) {
}
