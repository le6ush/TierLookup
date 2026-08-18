package com.tierlookup.model;

/** One trustworthy local snapshot transition. It records only observed source changes, never inferred rankings. */
public record TierHistoryEvent(long at, String providerId, String gamemode, String oldTier, String newTier, boolean oldRetired, boolean newRetired) {
}
