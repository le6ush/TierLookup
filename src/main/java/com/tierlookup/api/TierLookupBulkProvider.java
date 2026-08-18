package com.tierlookup.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.tierlookup.model.PlayerProfile;

/**
* Optional future extension point for providers that can expose a trustworthy bulk/delta transport.
* Built-ins do not opt into incremental mode until their upstream contract can prove a safe cursor.
*/ public interface TierLookupBulkProvider extends TierLookupProvider {
    record BulkRequest(String cursor, boolean fullReset) {
    }
    record BulkResult(List<PlayerProfile> players, String nextCursor, boolean completeRoster, boolean delta) {
        public BulkResult {
            players=players==null?List.of():List.copyOf(players);
        }
    }
    CompletableFuture<BulkResult> bulkSnapshot(BulkRequest request);
}
