package com.tierlookup.api;

import java.util.Set;

/** Stable provider capability description. It describes what a source can prove, not the last sync result. */
public record ProviderCapabilities( BulkCoverage bulkCoverage, boolean incrementalSync, boolean sourceHistory, Set<String> canonicalKits) {
    public enum BulkCoverage {
        FULL_MIRROR, VERIFIABLE_BULK, PARTIAL_BULK, PROFILE_ONLY
    }
    public ProviderCapabilities {
        if(bulkCoverage==null)bulkCoverage=BulkCoverage.PROFILE_ONLY;
        canonicalKits=canonicalKits==null?Set.of():Set.copyOf(canonicalKits);
    }
    public static ProviderCapabilities profileOnly() {
        return new ProviderCapabilities(BulkCoverage.PROFILE_ONLY, false, false, Set.of());
    }
    public String shortLabel() {
        return switch(bulkCoverage) {
            case FULL_MIRROR->"FULL MIRROR";
            case VERIFIABLE_BULK->"VERIFIABLE";
            case PARTIAL_BULK->"PARTIAL";
            case PROFILE_ONLY->"PROFILE ONLY";
        };
    }
}
