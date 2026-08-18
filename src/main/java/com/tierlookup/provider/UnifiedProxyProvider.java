package com.tierlookup.provider;

import com.tierlookup.client.BootstrapLog;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

import com.tierlookup.model.*;
import com.tierlookup.net.Http;

/**
* Adapter for Unified Tiers' documented same-origin proxy.
*
* Uses one bounded request format for player lookups. A UUID-format retry is made only
* when the upstream service explicitly reports an invalid UUID.
*/ public final class UnifiedProxyProvider implements TierProvider {
    public enum Kind {
        GENERIC
    }
    private static final ConcurrentHashMap<String, Long> FAILURE_UNTIL=new ConcurrentHashMap<>();
    private static volatile long frontendProbeAt;
    private static final long FRONTEND_PROBE_TTL=10*60_000L;
    private final String id, name, source, variant;
    private final Kind kind;
    public UnifiedProxyProvider(String id, String name, String source, String variant, Kind kind) {
        this.id=id;
        this.name=name;
        this.source=source;
        this.variant=variant;
        this.kind=kind;
    }
    public String id() {
        return id;
    }
    public String displayName() {
        return name;
    }
    @Override
    public CompletableFuture<ProviderResult> lookup(PlayerIdentity p) {
        String cacheKey=source+"|"+(variant==null?"":variant);
        Long cooldown=FAILURE_UNTIL.get(cacheKey);
        if(cooldown!=null&&System.currentTimeMillis()<cooldown) {
            return CompletableFuture.completedFuture(ProviderResult.error(id, name, "connector cooldown after upstream failure"));
        }
        boolean compactPrimary=false;
        return request(p, cacheKey, compactPrimary, false).exceptionally(e-> {
            if(e instanceof CompletionException ce&&ce.getCause()!=null)e=ce.getCause();
            
            FAILURE_UNTIL.put(cacheKey, System.currentTimeMillis()+15_000L);
            return ProviderResult.error(id, name, Http.rootMessage(e));
        }
        );
    }
    private CompletableFuture<ProviderResult> request(PlayerIdentity p, String cacheKey, boolean compact, boolean alternate) {
        String rawUuid=p.uuid().toString();
        String uuid=compact?rawUuid.replace("-", ""):rawUuid;
        String srcEsc=URLEncoder.encode(source, StandardCharsets.UTF_8);
        String uuidEsc=URLEncoder.encode(uuid, StandardCharsets.UTF_8);
        StringBuilder url=new StringBuilder("https://unifiedtiers.com/api/proxy?service=").append(srcEsc).append("&uuid=").append(uuidEsc);
        if(variant!=null&&!variant.isBlank())url.append("&version=").append(URLEncoder.encode(variant, StandardCharsets.UTF_8));
        int timeout=kind==Kind.GENERIC?5:4;
        return Http.getResponse(url.toString(), timeout).thenCompose(resp-> {
            int status=resp.statusCode(); String body=resp.body()==null?"":resp.body(); if(status>=200&&status<300) {
                String t=body.stripLeading(); if(t.startsWith("<")) {
                    FAILURE_UNTIL.put(cacheKey, System.currentTimeMillis()+60_000L);
                    return CompletableFuture.completedFuture(ProviderResult.error(id, name, "Unified proxy returned HTML"));
                }
                try {
                    
                    ProviderResult parsed=parse(body, p);
                    FAILURE_UNTIL.remove(cacheKey);
                    
                    return CompletableFuture.completedFuture(parsed);
                } catch (Throwable parse) {
                    BootstrapLog.error("UNIFIED parse source="+source, parse);
                    FAILURE_UNTIL.put(cacheKey, System.currentTimeMillis()+30_000L);
                    return CompletableFuture.completedFuture(ProviderResult.error(id, name, "parse error: "+Http.rootMessage(parse)));
                }
            }
            // 404 from the documented proxy means this player is not present in that source.
            // It is a valid lookup result, not provider failure and must never trip the breaker.
            if(status==404) {
                FAILURE_UNTIL.remove(cacheKey);  return CompletableFuture.completedFuture(notRanked());
            }
            // Correct UUID representation once only when upstream explicitly rejects the UUID string.
            if(status==400&&isInvalidUuid(body)&&!alternate) {
                 return request(p, cacheKey, !compact, true);
            }
            long cooldownMs=switch(status) {
                case 429 -> 60_000L; case 400 -> 60_000L; default -> status>=500?30_000L:20_000L;
            }; FAILURE_UNTIL.put(cacheKey, System.currentTimeMillis()+cooldownMs); String msg=switch(status) {
                case 429 -> "rate limited (HTTP 429)"; case 400 -> "invalid proxy request (HTTP 400)"; default -> "HTTP "+status;
            };
            
            return CompletableFuture.completedFuture(ProviderResult.error(id, name, msg));
        }
        );
    }
    private ProviderResult notRanked() {
        return new ProviderResult(id, name, ProviderResult.Status.NOT_RANKED, List.of(), null, System.currentTimeMillis());
    }
    private static boolean isInvalidUuid(String body) {
        String s=body==null?"":body.toLowerCase(Locale.ROOT);
        return s.contains("invalid uuid")||s.contains("uuid string");
    }
    private ProviderResult parse(String body, PlayerIdentity p) {
        if(body==null||body.isBlank()||"null".equalsIgnoreCase(body.trim()))return ProviderResult.error(id, name, "empty proxy payload");
        // PvPTiers currently returns the same rankings map shape as MCTiers/SubTiers.
        if("pvptiers".equals(id))return CommonTierProfileProvider.parseProfileBody(id, name, body);
        List<TierEntry> tiers=GenericTierParser.parse(body);
        return tiers.isEmpty()?ProviderResult.error(id,
            name,
            "proxy payload schema unrecognized"):new ProviderResult(id,
            name,
            ProviderResult.Status.OK,
            tiers,
            null,
            System.currentTimeMillis());
    }
    /**
    * Maintenance-only probe. It may inspect the current frontend for diagnostics, but never runs
    * as part of a player lookup and therefore cannot create per-search request storms.
    */ public static void maintenanceProbeFrontend() {
        long now=System.currentTimeMillis();
        if(now-frontendProbeAt<FRONTEND_PROBE_TTL)return;
        frontendProbeAt=now;
        Http.get("https://unifiedtiers.com/", 3).thenAccept(html-> {
            logProxyContexts(html);
            LinkedHashSet<String> assets=new LinkedHashSet<>();
            Matcher m=Pattern.compile("(?:src|href)=[\\\"']([^\\\"']+\\.js[^\\\"']*)").matcher(html);
            while(m.find())assets.add(absolute("https://unifiedtiers.com/", m.group(1)));
            int n=0;
            for(String asset:assets) {
                if(n++>=10)break; Http.get(asset, 3).thenAccept(UnifiedProxyProvider::logProxyContexts).exceptionally(e-> {
                     return null;
                }
                );
            }
            
        }
        ).exceptionally(e-> {
             return null;
        }
        );
    }
    private static void logProxyContexts(String js) {
        if(js==null||js.isBlank())return;
        String needle="/api/proxy";
        int at=0, shown=0;
        while((at=js.indexOf(needle, at))>=0&&shown++<12) {
            int a=Math.max(0, at-360), b=Math.min(js.length(), at+needle.length()+700);
            
            at+=needle.length();
        }
    }
    private static String absolute(String base, String p) {
        try {
            return URI.create(base).resolve(p).toString();
        } catch (Exception e) {
            return p;
        }
    }
    private static String compact(String s) {
        String x=(s==null?"":s).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        while(x.contains("  "))x=x.replace("  ", " ");
        return x.length()>260?x.substring(0, 260)+"…":x;
    }
}
