package com.tierlookup.api;

import java.util.*;
import java.util.concurrent.*;

import com.tierlookup.TierLookupClient;
import com.tierlookup.model.*;
import com.tierlookup.provider.TierProvider;
import com.tierlookup.service.ProfileService;

/**
* Stable 1.x facade. Providers register against the small public contract rather than UI/service
* internals; the core adapts them to its engine. Registration is intentionally client-local.
*/ public final class TierLookupApi {
    public static final int API_MAJOR=1;
    private static final CopyOnWriteArrayList<TierLookupProvider> EXTERNAL=new CopyOnWriteArrayList<>();
    private TierLookupApi() {
    }
    /** Register an additional provider. Must be called before TierLookup finishes client init. */
    public static synchronized boolean registerProvider(TierLookupProvider provider) {
        if(provider==null||!validProviderId(provider.id())||provider.displayName()==null||provider.displayName().isBlank())return false;
        // Late registration used to report success even though the provider registry had already been frozen.
        if(TierLookupClient.profileServiceInstance()!=null)return false;
        for(TierLookupProvider p:EXTERNAL)if(p.id().equalsIgnoreCase(provider.id()))return false;
        EXTERNAL.add(provider);
        return true;
    }
    /** Provider ids are persisted in config/SQLite and may be used in diagnostics; keep them path/key safe. */
    public static boolean validProviderId(String id) {
        return id!=null&&id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    }
    public static List<TierLookupProvider> registeredProviders() {
        return List.copyOf(EXTERNAL);
    }
    /** Internal bridge used once while building the provider registry. */
    public static List<TierProvider> externalAdapters() {
        ArrayList<TierProvider> out=new ArrayList<>();
        for(TierLookupProvider p:EXTERNAL) {
            if(p instanceof TierProvider tp)out.add(tp);
            else out.add(new TierProvider() {
                public String id() {
                    return p.id();
                }
                public String displayName() {
                    return p.displayName();
                }
                public ProviderCapabilities capabilities() {
                    return p.capabilities();
                }
                public CompletableFuture<ProviderResult> lookup(PlayerIdentity player) {
                    return p.lookup(player);
                }
            }
            );
        }
        return List.copyOf(out);
    }
    public static Optional<PlayerProfile> cached(UUID uuid) {
        ProfileService s=TierLookupClient.profileServiceInstance();
        return Optional.ofNullable(s==null?null:s.cached(uuid));
    }
    public static Optional<PlayerProfile> cached(String name) {
        ProfileService s=TierLookupClient.profileServiceInstance();
        return Optional.ofNullable(s==null?null:s.cachedByName(name));
    }
    public static CompletableFuture<PlayerProfile> refreshProvider(PlayerIdentity player, String providerId) {
        ProfileService s=TierLookupClient.profileServiceInstance();
        return s==null?CompletableFuture.failedFuture(new IllegalStateException("TierLookup not initialized")):s.refreshProvider(player, providerId);
    }
}
