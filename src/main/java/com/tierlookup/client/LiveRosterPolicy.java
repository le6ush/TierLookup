package com.tierlookup.client;

/** Explicit, testable network budget for passive TAB enrichment. Manual K searches use a separate policy. */
public final class LiveRosterPolicy {
    public static final int SCAN_TICKS=300;
    // 15 s at 20 client ticks/s
    public static final long ROSTER_SETTLE_MS=750L;
    public static final long ATTEMPT_COOLDOWN_MS=5L*60_000L;
    public static final long PLAYER_GAP_MS=1_000L;
    public static final int QUEUE_LIMIT=512;
    private LiveRosterPolicy() {
    }
    /** Passive TAB traffic is join-only: never refresh an already-known player merely because time passed. */
    public static boolean shouldQueue(boolean liveEnabled, boolean joined, boolean missingCoverage) {
        return liveEnabled&&joined&&missingCoverage;
    }
}
