package com.tierlookup.provider;

import com.tierlookup.client.BootstrapLog;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import com.tierlookup.model.*;
import com.tierlookup.net.Http;
import com.tierlookup.net.MiniJson;

/**
* CISTiers live/profile adapter.
*
* Bulk database sync still uses /api/dump. Individual internet-first lookups intentionally use
* the official /api/profile/{nickname} contract so the UI does not depend on dump matching or dump staleness.
*/ public final class CisTiersProvider implements TierProvider {
    private static final String ID="cistiers", NAME="CISTiers";
    @Override
    public String id() {
        return ID;
    }
    @Override
    public String displayName() {
        return NAME;
    }
    @Override
    public CompletableFuture<ProviderResult> lookup(PlayerIdentity player) {
        if(player==null||player.name()==null||player.name().isBlank())return CompletableFuture.completedFuture(ProviderResult.error(ID, NAME, "missing nickname"));
        String url="https://cistiers.com/api/profile/"+URLEncoder.encode(player.name(), StandardCharsets.UTF_8);
        return Http.getResponse(url, 8).handle((resp, err)-> {
            if(err!=null) {
                String message="profile unavailable: "+Http.rootMessage(err);
                
                return ProviderResult.error(ID, NAME, message);
            }
            if(resp==null)return ProviderResult.error(ID, NAME, "empty profile response");
            if(resp.statusCode()==404)return new ProviderResult(ID, NAME, ProviderResult.Status.NOT_RANKED, List.of(), "authoritative 404", System.currentTimeMillis());
            if(!resp.ok())return ProviderResult.error(ID, NAME, "profile HTTP "+resp.statusCode());
            try {
                return parseProfileBody(resp.body());
            } catch (Throwable parse) {
                BootstrapLog.error("CISTiers profile parse player="+player.name(), parse); return ProviderResult.error(ID, NAME, "profile parse: "+parse.getClass().getSimpleName());
            }
        }
        );
    }
    static ProviderResult parseProfileBody(String body) {
        
        Object root=MiniJson.parse(body);
        if(!(root instanceof Map<?, ?> m))return ProviderResult.error(ID, NAME, "profile response is not an object");
        Object stats=get(m, "tierStats", "tier_stats");
        if(!(stats instanceof Map<?, ?> sm))return ProviderResult.error(ID, NAME, "profile schema: tierStats missing");
        Map<String, String> lastDates=latestDates(sm);
        Object current=get(sm, "currentTiers", "current_tiers");
        if(!(current instanceof List<?> list))return ProviderResult.error(ID, NAME, "profile schema: currentTiers missing");
        if(list.isEmpty())return new ProviderResult(ID, NAME, ProviderResult.Status.NOT_RANKED, List.of(), "authoritative empty currentTiers", System.currentTimeMillis());
        LinkedHashMap<String, TierEntry> out=new LinkedHashMap<>();
        for(Object row:list) {
            if(!(row instanceof Map<?, ?> rm))continue;
            String kit=str(get(rm, "kit", "mode", "gamemode"));
            String rawTier=str(get(rm, "tier", "rank"));
            String tier=TierRank.normalize(rawTier);
            if(kit==null||kit.isBlank()||tier==null)continue;
            boolean retired=explicitRetired(rm, rawTier);
            String date=lastDates.get(normalizeKitKey(kit));
            String canonical=com.tierlookup.client.OverlayRenderer.canonicalKit(kit);
            String key=canonical==null?normalizeKitKey(kit):canonical;
            TierEntry next=new TierEntry(kit, tier, tier, retired, date), previous=out.get(key);
            if(previous!=null) {
                String oldTier=TierRank.normalize(previous.currentTier());
                if(!Objects.equals(oldTier, tier)||previous.retired()!=retired)return ProviderResult.error(ID, NAME, "profile schema: conflicting currentTiers for "+key);
                if(previous.lastTest()==null&&date!=null)out.put(key, next);
            } else out.put(key, next);
        }
        List<TierEntry> tiers=List.copyOf(out.values());
        if(tiers.isEmpty())return ProviderResult.error(ID, NAME, "profile schema: currentTiers contained no valid tier rows");
        return new ProviderResult(ID, NAME, ProviderResult.Status.OK, tiers, null, System.currentTimeMillis());
    }
    private static boolean explicitRetired(Map<?, ?> row, String rawTier) {
        if(rawTier!=null&&rawTier.trim().toUpperCase(Locale.ROOT).startsWith("R")&&TierRank.normalize(rawTier)!=null)return true;
        Object flag=get(row, "retired", "isRetired", "is_retired");
        if(Boolean.TRUE.equals(flag)||flag!=null&&"true".equalsIgnoreCase(String.valueOf(flag).trim()))return true;
        Object status=get(row, "status", "state", "tierStatus", "tier_status");
        return status!=null&&"retired".equalsIgnoreCase(String.valueOf(status).trim());
    }
    private static Map<String, String> latestDates(Map<?, ?> stats) {
        LinkedHashMap<String, String> out=new LinkedHashMap<>();
        Object hist=get(stats, "tierHistory", "tier_history");
        if(!(hist instanceof List<?> list))return out;
        for(Object row:list) {
            if(!(row instanceof Map<?, ?> rm))continue;
            String kit=str(get(rm, "kit", "mode", "gamemode")), date=str(get(rm, "date", "testedAt", "tested_at"));
            if(kit==null||date==null)continue;
            String key=normalizeKitKey(kit);
            String old=out.get(key);
            if(old==null||date.compareTo(old)>0)out.put(key, date);
        }
        return out;
    }
    private static String normalizeKitKey(String s) {
        return s==null?"":s.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ");
    }
    private static Object get(Map<?, ?> m, String... keys) {
        for(String key:keys)for(var e:m.entrySet())if(String.valueOf(e.getKey()).equalsIgnoreCase(key))return e.getValue();
        return null;
    }
    private static String str(Object v) {
        if(v==null)return null;
        String s=String.valueOf(v).trim();
        return s.isEmpty()?null:s;
    }
    public static boolean selfTestSchemaFailSafe() {
        ProviderResult missing=parseProfileBody("{\"nickname\":\"X\"}");
        ProviderResult empty=parseProfileBody("{\"tierStats\":{\"currentTiers\":[]}}");
        ProviderResult conflict=parseProfileBody("{\"tierStats\":{\"currentTiers\":[{\"kit\":\"op\",\"tier\":\"LT3\"},{\"kit\":\"op\",\"tier\":\"HT3\"}]}}");
        return missing.status()==ProviderResult.Status.ERROR&&empty.status()==ProviderResult.Status.NOT_RANKED&&conflict.status()==ProviderResult.Status.ERROR;
    }
}
