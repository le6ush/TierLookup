package com.tierlookup.client;

import com.tierlookup.model.TierRank;

/** Pure tier comparison semantics, independent from rendering, provider origin and client state. */
public final class TierComparison {
    private TierComparison() {
    }
    public static int compareTier(String a, String b) {
        return Integer.compare(TierSelection.tierScore(TierRank.normalize(a)), TierSelection.tierScore(TierRank.normalize(b)));
    }
    /** State used by compare cells; Retired affects presentation only when raw tier strength is tied. */
    public static String cellState(String ownTier, boolean ownRetired, String otherTier, boolean otherRetired) {
        String a=TierRank.normalize(ownTier), b=TierRank.normalize(otherTier);
        if(a==null)return "missing-own";
        if(b==null)return "missing-other";
        if(a.equals(b)) {
            if(ownRetired!=otherRetired)return "retired-mismatch";
            return "tied";
        }
        int cmp=compareTier(a, b);
        return cmp==0?"tied":cmp>0?"better":"worse";
    }
}
