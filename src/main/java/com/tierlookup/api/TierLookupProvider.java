package com.tierlookup.api;

import java.util.concurrent.CompletableFuture;

import com.tierlookup.model.PlayerIdentity;
import com.tierlookup.model.ProviderResult;

/** Provider contract used by TierLookup 1.x integrations. */
public interface TierLookupProvider {
    String id();
    String displayName();
    /** Optional provider capability descriptor. Profile-only is the safe default. */ default ProviderCapabilities capabilities() {
        return ProviderCapabilities.profileOnly();
    }
    CompletableFuture<ProviderResult> lookup(PlayerIdentity player);
}
