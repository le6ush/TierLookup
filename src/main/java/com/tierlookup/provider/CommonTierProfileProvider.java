package com.tierlookup.provider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import com.tierlookup.model.*;
import com.tierlookup.net.Http;
import com.tierlookup.net.MiniJson;

/** Parser for the MCTiers/PvPTiers/SubTiers-style profile schema. */
public final class CommonTierProfileProvider implements TierProvider {
    private final String id, name;
    private final List<String> templates;
    public CommonTierProfileProvider(String id, String name, String... templates) {
        this.id = id;
        this.name = name;
        this.templates = List.of(templates);
    }
    @Override
    public String id() {
        return id;
    }
    @Override
    public String displayName() {
        return name;
    }
    @Override
    public CompletableFuture<ProviderResult> lookup(PlayerIdentity p) {
        CompletableFuture<ProviderResult> result = new CompletableFuture<>();
        tryNext(p, 0, result, null);
        return result;
    }
    private void tryNext(PlayerIdentity p, int idx, CompletableFuture<ProviderResult> result, Throwable previous) {
        if (result.isDone()) return;
        if (idx >= templates.size()) {
            if (previous != null) {
                Http.HttpException h = Http.findHttpException(previous);
                if (h != null && h.statusCode() == 404) {
                    result.complete(notRanked());
                    return;
                }
            }
            result.complete(ProviderResult.error(id, name, previous == null ? "No endpoint" : Http.rootMessage(previous)));
            return;
        }
        String uuid = p.uuid().toString();
        String compact = uuid.replace("-", "");
        String encodedName = URLEncoder.encode(p.name(), StandardCharsets.UTF_8);
        String url = templates.get(idx).replace("{uuid}", uuid).replace("{uuid32}", compact).replace("{name}", encodedName);
        Http.get(url).thenApply(body -> parseProfileBody(id, name, body)).whenComplete((r, e) -> {
            if (result.isDone()) return; if (e == null) result.complete(r); else {
                Http.HttpException h = Http.findHttpException(e); if (h != null && h.statusCode() == 404) result.complete(notRanked()); else tryNext(p, idx + 1, result, e);
            }
        }
        );
    }
    private ProviderResult notRanked() {
        return new ProviderResult(id, name, ProviderResult.Status.NOT_RANKED, List.of(), null, System.currentTimeMillis());
    }
    @SuppressWarnings("unchecked") public static ProviderResult parseProfileBody(String id, String name, String body) {
        
        Object rootObj = MiniJson.parse(body);
        if (!(rootObj instanceof Map<?, ?> raw)) {
            List<TierEntry> fallback = GenericTierParser.parse(body);
            return fallback.isEmpty()?ProviderResult.error(id, name, "profile payload is not an object"):result(id, name, fallback);
        }
        Map<String, Object> root = (Map<String, Object>) raw;
        Object rankingsObj = getIgnoreCase(root, "rankings");
        if (!(rankingsObj instanceof Map<?, ?> rankingsRaw)) {
            List<TierEntry> fallback = GenericTierParser.parse(body);
            
            return fallback.isEmpty()?ProviderResult.error(id, name, "missing rankings map"):result(id, name, fallback);
        }
        List<TierEntry> tiers = new ArrayList<>();
        Map<?, ?> rankings = rankingsRaw;
        if(rankings.isEmpty())return result(id, name, List.of());
        for (var e : rankings.entrySet()) {
            String mode = pretty(String.valueOf(e.getKey()));
            if (!(e.getValue() instanceof Map<?, ?> rr)) continue;
            Map<String, Object> r = (Map<String, Object>) rr;
            String current = tierFromNumeric(r, "tier", "pos");
            if (current == null) current = firstTier(r, "currentTier", "current_tier", "rank", "division");
            String peak = tierFromNumeric(r, "peak_tier", "peak_pos");
            if (peak == null) peak = firstTier(r, "peakTier", "peak", "highestTier", "highest_tier");
            if (peak == null) peak = current;
            if (current == null && peak == null) continue;
            Object rawCurrent = firstValue(r, "currentTier", "current_tier", "rank", "division", "tier");
            boolean retired = WebTierSupport.explicitRetired(r, rawCurrent);
            String attained = timestamp(firstValue(r,
                "attained",
                "lastTest",
                "last_test",
                "testedAt",
                "tested_at",
                "achievedAt",
                "achieved_at",
                "updatedAt",
                "updated_at",
                "date"));
            tiers.add(new TierEntry(mode, current, peak, retired, attained));
        }
        
        return tiers.isEmpty()?ProviderResult.error(id, name, "rankings map present but no valid tiers parsed"):result(id, name, tiers);
    }
    private static ProviderResult result(String id, String name, List<TierEntry> tiers) {
        return new ProviderResult(id, name, tiers.isEmpty() ? ProviderResult.Status.NOT_RANKED : ProviderResult.Status.OK, tiers, null, System.currentTimeMillis());
    }
    private static String tierFromNumeric(Map<String, Object> m, String tierKey, String posKey) {
        Object t = getIgnoreCase(m, tierKey);
        if (t == null) return null;
        String direct = TierRank.normalize(String.valueOf(t));
        if (direct != null) return direct;
        Integer n = intValue(t);
        if (n == null || n < 1 || n > 5) return null;
        Object pos = getIgnoreCase(m, posKey);
        if (pos == null) return null;
        Integer p = intValue(pos);
        if (p == null) return null;
        return (p == 0 ? "HT" : "LT") + n;
    }
    private static String firstTier(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = getIgnoreCase(m, k);
            if (v != null) {
                String t = TierRank.normalize(String.valueOf(v));
                if (t != null) return t;
            }
        }
        return null;
    }
    private static Object firstValue(Map<String, Object> m, String... keys) {
        for(String k:keys) {
            Object v=getIgnoreCase(m, k);
            if(v!=null)return v;
        }
        return null;
    }
    private static boolean bool(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = getIgnoreCase(m, k);
            if (v instanceof Boolean b) return b;
            if (v != null && "true".equalsIgnoreCase(String.valueOf(v))) return true;
        }
        return false;
    }
    private static Object getIgnoreCase(Map<String, Object> m, String key) {
        for (var e : m.entrySet()) if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        return null;
    }
    private static Integer intValue(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception ignored) {
        }
        return null;
    }
    /** Keep the absolute tier-test instant so cached data continues ageing correctly after restarts. */
    private static String timestamp(Object v) {
        if (v == null) return null;
        try {
            long n = v instanceof Number num ? num.longValue() : Long.parseLong(String.valueOf(v).trim());
            if (n < 10_000_000_000L) n *= 1000L;
            return String.valueOf(n);
        } catch (Exception ignored) {
        }
        try {
            return String.valueOf(Instant.parse(String.valueOf(v)).toEpochMilli());
        } catch (Exception ignored) {
        }
        return String.valueOf(v);
    }
    private static String pretty(String s) {
        if (s == null || s.isBlank()) return "Overall";
        String[] parts = s.replace('-', '_').split("_");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return b.length() == 0 ? s : b.toString();
    }
}
