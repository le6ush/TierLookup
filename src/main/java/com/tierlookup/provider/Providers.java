package com.tierlookup.provider;

import java.util.*;

import com.tierlookup.api.ProviderCapabilities;
import com.tierlookup.api.TierLookupApi;
import com.tierlookup.api.TierLookupProvider;

public final class Providers {
    private Providers() {
    }
    private static final Map<String,
        ProviderCapabilities> BUILTIN_CAPABILITIES=Map.of( "mctiers",
        caps(ProviderCapabilities.BulkCoverage.FULL_MIRROR,
        false,
        false),
        "pvptiers",
        caps(ProviderCapabilities.BulkCoverage.PROFILE_ONLY,
        false,
        false),
        "subtiers",
        caps(ProviderCapabilities.BulkCoverage.FULL_MIRROR,
        false,
        false),
        "flowpvp",
        caps(ProviderCapabilities.BulkCoverage.VERIFIABLE_BULK,
        false,
        false),
        "cistiers",
        caps(ProviderCapabilities.BulkCoverage.FULL_MIRROR,
        false,
        true),
        "atiers",
        caps(ProviderCapabilities.BulkCoverage.VERIFIABLE_BULK,
        false,
        false),
        "mytiers",
        caps(ProviderCapabilities.BulkCoverage.PARTIAL_BULK,
        false,
        false),
        "centraltierlist",
        caps(ProviderCapabilities.BulkCoverage.PARTIAL_BULK,
        false,
        false));
    private static ProviderCapabilities caps(ProviderCapabilities.BulkCoverage coverage, boolean incremental, boolean history) {
        return new ProviderCapabilities(coverage, incremental, history, Set.of());
    }
    /** Static source capability contract. Actual run completeness still comes from provider_sync_manifest. */
    public static ProviderCapabilities capabilities(TierLookupProvider provider) {
        if(provider==null)return ProviderCapabilities.profileOnly();
        ProviderCapabilities builtin=BUILTIN_CAPABILITIES.get(provider.id());
        return builtin!=null?builtin:provider.capabilities();
    }
    public static ProviderCapabilities capabilities(String providerId) {
        ProviderCapabilities builtin=BUILTIN_CAPABILITIES.get(providerId);
        return builtin!=null?builtin:ProviderCapabilities.profileOnly();
    }
    public static Map<String, ProviderCapabilities> builtinCapabilities() {
        return BUILTIN_CAPABILITIES;
    }
    public static List<TierProvider> all() {
        ArrayList<TierProvider> all=new ArrayList<>(List.of( new CommonTierProfileProvider("mctiers",
            "MCTiers",
            "https://mctiers.com/api/v2/profile/{uuid32}"),
            new UnifiedProxyProvider("pvptiers",
            "PvPTiers",
            "pvptiers",
            null,
            UnifiedProxyProvider.Kind.GENERIC),
            new CommonTierProfileProvider("subtiers",
            "SubTiers",
            "https://subtiers.net/api/v2/profile/{uuid32}",
            "https://subtiers.net/api/profile/{uuid32}"),
            new FlowPvpProvider(),
            new CisTiersProvider(),
            new ATiersProvider(),
            new MyTiersProvider(),
            new CentralTierListProvider()));
        HashSet<String> ids=new HashSet<>();
        for(TierProvider p:all)ids.add(p.id());
        for(TierProvider p:TierLookupApi.externalAdapters())if(ids.add(p.id()))all.add(p);
        return List.copyOf(all);
    }
}
