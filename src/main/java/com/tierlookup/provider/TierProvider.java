package com.tierlookup.provider;

import java.util.concurrent.CompletableFuture;

import com.tierlookup.model.*;

public interface TierProvider extends com.tierlookup.api.TierLookupProvider {
    String id();
    String displayName();
    CompletableFuture<ProviderResult> lookup(PlayerIdentity player);
}
