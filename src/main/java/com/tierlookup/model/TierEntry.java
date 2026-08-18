package com.tierlookup.model;

/** Raw tier-list entry. Rendering uses the source's current tier directly and never computes a best tier. */
public record TierEntry(String gamemode, String currentTier, String peakTier, boolean retired, String lastTest) {
}
