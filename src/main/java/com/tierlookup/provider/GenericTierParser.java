package com.tierlookup.provider;

import java.time.*;
import java.util.*;

import com.tierlookup.model.*;
import com.tierlookup.net.MiniJson;

/** Heuristic fallback parser for unknown tier-list JSON schemas. */
public final class GenericTierParser {
    private GenericTierParser() {
    }
    public static List<TierEntry> parse(String body) {
        Object root = MiniJson.parse(body);
        LinkedHashMap<String, TierEntry> out = new LinkedHashMap<>();
        walk(root, null, out, 0);
        return new ArrayList<>(out.values());
    }
    @SuppressWarnings("unchecked") private static void walk(Object node, String parentKey, Map<String, TierEntry> out, int depth) {
        if (node == null || depth > 12) return;
        if (node instanceof Map<?, ?> raw) {
            Map<String, Object> m = (Map<String, Object>) raw;
            String tier = firstTier(m,
                "tier",
                "currentTier",
                "current_tier",
                "current",
                "currentRank",
                "current_rank",
                "rank",
                "rankName",
                "rank_name",
                "tierDisplay",
                "tier_display",
                "division");
            String peak = firstTier(m, "peakTier", "peak_tier", "peak", "peakRank", "peak_rank", "highestTier", "highest_tier", "highestRank", "highest_rank");
            // Common MCTiers/PvPTiers style: numeric tier + pos (0 = HT, otherwise LT).
            if (tier == null) tier = numericTier(m, "tier", "pos");
            if (peak == null) peak = numericTier(m, "peak_tier", "peak_pos");
            if (peak == null && tier != null) peak = tier;
            if (tier != null || peak != null) {
                String mode = firstString(m, "gamemode", "gameMode", "game_mode", "mode", "kit", "category", "type", "name");
                if (mode == null || TierRank.normalize(mode)!=null || mode.length()>35) mode = pretty(parentKey==null?"Overall":parentKey);
                Object rawTier=firstValue(m,
                    "tier",
                    "currentTier",
                    "current_tier",
                    "current",
                    "currentRank",
                    "current_rank",
                    "rank",
                    "rankName",
                    "rank_name",
                    "tierDisplay",
                    "tier_display",
                    "division");
                boolean retired = WebTierSupport.explicitRetired(m, rawTier);
                Object date = firstValue(m,
                    "attained",
                    "attainedAt",
                    "attained_at",
                    "lastTest",
                    "last_test",
                    "tested",
                    "testedAt",
                    "tested_at",
                    "updatedAt",
                    "updated_at",
                    "achievedAt",
                    "achieved_at",
                    "assignedAt",
                    "assigned_at",
                    "createdAt",
                    "created_at",
                    "timestamp",
                    "date");
                String key=mode.toLowerCase(Locale.ROOT)+"|"+(tier==null?"":tier)+"|"+(peak==null?"":peak);
                out.putIfAbsent(key, new TierEntry(mode, tier, peak, retired, shortDate(date)));
            }
            // CIS dumps (and ATiers-like APIs) commonly store a compact map such as
            // {"tiers":{"mace":"ht3","uhc":"lt2"}}. The old parser skipped these
            // because the tier value is a scalar rather than an object. Parse them here.
            if (flatTierContainer(parentKey)) {
                for (var e : m.entrySet()) {
                    if (!(e.getValue() instanceof String sv)) continue;
                    String normalized = TierRank.normalize(sv);
                    if (normalized == null || reservedFlatKey(e.getKey())) continue;
                    String mode = pretty(e.getKey());
                    String key = mode.toLowerCase(Locale.ROOT)+"|"+normalized+"|"+normalized;
                    out.putIfAbsent(key, new TierEntry(mode, normalized, normalized, sv.trim().toUpperCase(Locale.ROOT).startsWith("R"), null));
                }
            }
            for(var e:m.entrySet()) walk(e.getValue(), e.getKey(), out, depth+1);
        } else if (node instanceof List<?> l) {
            for(Object v:l) walk(v, parentKey, out, depth+1);
        }
    }
    private static boolean flatTierContainer(String parentKey) {
        if(parentKey==null)return false;
        String k=parentKey.toLowerCase(Locale.ROOT);
        return k.equals("tiers")||k.equals("tier")||k.equals("rankings")||k.equals("ranks")||k.equals("modes")||k.equals("gamemodes")||k.equals("ratings");
    }
    private static boolean reservedFlatKey(String key) {
        String k=key==null?"":key.toLowerCase(Locale.ROOT);
        return k.equals("tier")||k.equals("rank")||k.contains("peak")||k.contains("highest")||k.contains("overall");
    }
    private static String numericTier(Map<String, Object> m, String tierKey, String posKey) {
        Object tv=find(m, tierKey), pv=find(m, posKey);
        if(tv==null||pv==null)return null;
        Integer t=asInt(tv), p=asInt(pv);
        if(t==null||p==null||t<1||t>5)return null;
        return (p==0?"HT":"LT")+t;
    }
    private static Integer asInt(Object v) {
        if(v instanceof Number n)return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }
    private static String firstTier(Map<String, Object> m, String...ks) {
        for(String k:ks) {
            Object v=find(m, k);
            if(v!=null) {
                String n=TierRank.normalize(String.valueOf(v));
                if(n!=null)return n;
            }
        }
        return null;
    }
    private static String firstString(Map<String, Object> m, String...ks) {
        for(String k:ks) {
            Object v=find(m, k);
            if(v instanceof String s&&!s.isBlank())return s;
        }
        return null;
    }
    private static Object firstValue(Map<String, Object> m, String...ks) {
        for(String k:ks) {
            Object v=find(m, k);
            if(v!=null)return v;
        }
        return null;
    }
    private static boolean firstBool(Map<String, Object> m, String...ks) {
        for(String k:ks) {
            Object v=find(m, k);
            if(v instanceof Boolean b)return b;
            if(v!=null&&"true".equalsIgnoreCase(String.valueOf(v)))return true;
        }
        return false;
    }
    private static boolean firstTierLooksRetired(Map<String, Object> m, String...ks) {
        for(String k:ks) {
            Object v=find(m, k);
            if(v!=null&&String.valueOf(v).trim().toUpperCase(Locale.ROOT).startsWith("R")&&TierRank.normalize(String.valueOf(v))!=null)return true;
        }
        return false;
    }
    private static Object find(Map<String, Object> m, String key) {
        for(var e:m.entrySet())if(e.getKey().equalsIgnoreCase(key))return e.getValue();
        return null;
    }
    private static String pretty(String s) {
        if(s==null)return "Overall";
        return s.replace('_', ' ').replace('-', ' ');
    }
    private static String shortDate(Object v) {
        if(v==null)return null;
        try {
            long n=v instanceof Number num?num.longValue():Long.parseLong(String.valueOf(v).trim());
            if(n<10_000_000_000L)n*=1000L;
            return String.valueOf(n);
        } catch (Exception ignored) {
        }
        String s=String.valueOf(v);
        try {
            return String.valueOf(Instant.parse(s).toEpochMilli());
        } catch (Exception ignored) {
        }
        return s.length()>32?s.substring(0, 32):s;
    }
}
