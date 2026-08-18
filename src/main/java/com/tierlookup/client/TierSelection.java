package com.tierlookup.client;

import java.util.*;

import com.tierlookup.model.*;
import com.tierlookup.service.TierLookupConfig;

/**
* One exact-tier selector shared by TAB, max-compare and any future aggregate UI.
* Source, region and provider metadata do not change tier strength.
*/ public final class TierSelection {
    public record Best(String providerId, String providerName, String kit, String tier, boolean retired, long fetchedAt, long lastTestAt) {
    }
    private TierSelection() {
    }
    public static Map<String, Best> bestByKit(PlayerProfile profile, TierLookupConfig cfg) {
        LinkedHashMap<String, Best> out=new LinkedHashMap<>();
        if(profile==null||profile.providers()==null)return out;
        for(ProviderResult pr:profile.providers().values()) {
            if(pr==null||pr.status()!=ProviderResult.Status.OK)continue;
            if(cfg!=null&&!cfg.enabled(pr.providerId()))continue;
            for(TierEntry e:pr.tiers()) {
                if(e==null)continue;
                String kit=OverlayRenderer.canonicalKit(e.gamemode());
                String tier=TierRank.normalize(e.currentTier());
                if(kit==null||tier==null)continue;
                if(cfg!=null&&!cfg.kitEnabled(kit))continue;
                Best next=new Best(pr.providerId(), pr.displayName(), kit, tier, e.retired(), pr.fetchedAt(), OverlayRenderer.lastTestMillis(e.lastTest(), pr.fetchedAt()));
                Best old=out.get(kit);
                if(old==null||betterSameKit(next, old))out.put(kit, next);
            }
        }
        return out;
    }
    public static Best displayed(PlayerProfile profile, TierLookupConfig cfg) {
        return displayed(profile, cfg, cfg==null?"max":cfg.tabDisplayedKit());
    }
    public static Best displayed(PlayerProfile profile, TierLookupConfig cfg, String requestedKit) {
        String mode=requestedKit==null?"max":requestedKit;
        Map<String, Best> by=bestByKit(profile, cfg);
        if(!"max".equals(mode))return by.get(mode);
        Best best=null;
        int bestOrder=Integer.MAX_VALUE;
        for(Best v:by.values()) {
            int order=TierLookupConfig.KNOWN_KITS.indexOf(v.kit());
            if(order<0)order=1000;
            if(best==null||tierScore(v.tier())>tierScore(best.tier()) ||(tierScore(v.tier())==tierScore(best.tier())&&best.retired()&&!v.retired()) ||(tierScore(v.tier())==tierScore(best.tier())&&best.retired()==v.retired()&&order<bestOrder)) {
                best=v;
                bestOrder=order;
            }
        }
        return best;
    }
    private static boolean betterSameKit(Best next, Best old) {
        int ns=tierScore(next.tier()), os=tierScore(old.tier());
        if(ns!=os)return ns>os;
        if(next.retired()!=old.retired())return !next.retired();
        if(next.fetchedAt()!=old.fetchedAt())return next.fetchedAt()>old.fetchedAt();
        return String.valueOf(next.providerId()).compareToIgnoreCase(String.valueOf(old.providerId()))<0;
    }
    /** HT1 > MT1 > LT1 > HT2 ... > LT5. */
    public static int tierScore(String tier) {
        String t=TierRank.normalize(tier);
        if(t==null||t.length()<3)return 0;
        int n=t.charAt(t.length()-1)-'0';
        if(n<1||n>5)return 0;
        int band=t.startsWith("HT")?2:t.startsWith("MT")?1:0;
        return (6-n)*3+band;
    }
}
