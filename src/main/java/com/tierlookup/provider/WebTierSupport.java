package com.tierlookup.provider;

import java.util.*;
import java.util.regex.*;

import com.tierlookup.model.*;
import com.tierlookup.net.MiniJson;

/** Small parsers for public tier-list web/API payloads. Kept out of hover/render paths. */
final class WebTierSupport {
    private WebTierSupport() {
    }
    static boolean validName(String s) {
        return s!=null&&s.matches("[A-Za-z0-9_]{1,16}");
    }
    static boolean retiredValue(Object v) {
        return v!=null&&String.valueOf(v).trim().toUpperCase(Locale.ROOT).startsWith("R")&&TierRank.normalize(String.valueOf(v))!=null;
    }
    /** Retired is source-explicit only: R-tier, retired boolean, or a status field equal to "retired". */
    static boolean explicitRetired(Map<?, ?> m, Object rawTier) {
        if(retiredValue(rawTier))return true;
        Object flag=any(m, "retired", "isRetired", "is_retired");
        if(flag instanceof Boolean b&&b)return true;
        if(flag!=null&&"true".equalsIgnoreCase(String.valueOf(flag).trim()))return true;
        Object status=any(m, "status", "state", "tierStatus", "tier_status", "rankStatus", "rank_status");
        return status!=null&&"retired".equalsIgnoreCase(String.valueOf(status).trim());
    }
    static String tier(Object v) {
        return v==null?null:TierRank.normalize(String.valueOf(v));
    }
    static Object any(Map<?, ?> m, String...keys) {
        for(String k:keys)for(var e:m.entrySet())if(String.valueOf(e.getKey()).equalsIgnoreCase(k))return e.getValue();
        return null;
    }
    static String str(Map<?, ?> m, String...keys) {
        Object v=any(m, keys);
        return v==null?null:String.valueOf(v);
    }
    static List<TierEntry> tiersFromKitMap(Object obj) {
        if(!(obj instanceof Map<?, ?> m))return List.of();
        ArrayList<TierEntry> out=new ArrayList<>();
        for(var e:m.entrySet()) {
            String kit=String.valueOf(e.getKey());
            Object value=e.getValue();
            if(value instanceof Map<?, ?> vm) {
                Object cur=any(vm, "tier", "currentTier", "current_tier", "rank", "currentRank", "current_rank");
                Object peak=any(vm, "peakTier", "peak_tier", "peak", "highestTier", "highest_tier", "peakRank");
                String ct=tier(cur), pt=tier(peak);
                if(ct==null)ct=tier(value);
                if(pt==null)pt=ct;
                if(ct!=null)out.add(new TierEntry(kit, ct, pt, explicitRetired(vm, cur), date(vm)));
            } else {
                String t=tier(value);
                if(t!=null)out.add(new TierEntry(kit, t, t, retiredValue(value), null));
            }
        }
        return out;
    }
    static String date(Map<?, ?> m) {
        Object v=any(m, "lastTest", "last_test", "testedAt", "tested_at", "attained", "achievedAt", "achieved_at", "updatedAt", "updated_at", "date");
        return v==null?null:String.valueOf(v);
    }
    /** Extract JSON script bodies embedded in SSR/Next/Vite pages and parse tier-like content. */
    static List<TierEntry> tiersFromHtml(String html) {
        if(html==null||html.isBlank())return List.of();
        LinkedHashMap<String, TierEntry> merged=new LinkedHashMap<>();
        Matcher scripts=Pattern.compile("(?is)<script[^>]*>(.*?)</script>").matcher(html);
        while(scripts.find()) {
            String body=unescapeHtml(scripts.group(1)).trim();
            if(body.isEmpty())continue;
            int first=Math.min(nonNeg(body.indexOf('{')), nonNeg(body.indexOf('[')));
            if(first==Integer.MAX_VALUE)continue;
            String candidate=body.substring(first);
            try {
                add(merged, GenericTierParser.parse(candidate));
            } catch (Throwable ignored) {
            }
        }
        // Some sites place raw serialized JSON in the page without a conventional script tag.
        if(merged.isEmpty())try {
            add(merged, GenericTierParser.parse(unescapeHtml(html)));
        } catch (Throwable ignored) {
        }
        return List.copyOf(merged.values());
    }
    private static void add(Map<String, TierEntry> m, List<TierEntry> list) {
        for(TierEntry e:list) {
            if(e==null||e.currentTier()==null)continue;
            String k=(e.gamemode()+"|"+e.currentTier()).toLowerCase(Locale.ROOT);
            m.putIfAbsent(k, e);
        }
    }
    private static int nonNeg(int n) {
        return n<0?Integer.MAX_VALUE:n;
    }
    private static String unescapeHtml(String s) {
        return s.replace("&nbsp;",
            " ").replace("&quot;",
            "\"").replace("&#34;",
            "\"").replace("&#x27;",
            "'").replace("&#39;",
            "'").replace("&amp;",
            "&").replace("&lt;",
            "<").replace("&gt;",
            ">");
    }
    /**
    * Reconstructs the human-visible text carried by modern Next/React pages. Some leaderboards keep
    * their entire initial roster inside self.__next_f script strings, so deleting script tags first
    * loses the data even though a browser/search crawler can see it. This helper deliberately keeps
    * script payloads, decodes the common flight escapes, and then removes markup/punctuation.
    */ static String renderedTextFromHtml(String html) {
        if(html==null||html.isBlank())return "";
        String s=html;
        for(int i=0; i<3; i++)s=decodeFlightEscapes(s);
        Matcher imgs=Pattern.compile("(?is)<img\\b[^>]*\\balt=[\"']([^\"']+)[\"'][^>]*>").matcher(s);
        StringBuffer b=new StringBuffer();
        while(imgs.find())imgs.appendReplacement(b, Matcher.quoteReplacement(" Image "+imgs.group(1)+" "));
        imgs.appendTail(b);
        s=b.toString();
        s=s.replaceAll("(?is)<style\\b[^>]*>.*?</style>", " ").replaceAll("(?is)<[^>]+>", " ");
        s=unescapeHtml(s);
        for(int i=0; i<2; i++)s=decodeFlightEscapes(s);
        return s.replaceAll("[\\[\\]{}(),;:\"'`|]+", " ").replaceAll("\\s+", " ").trim();
    }
    private static String decodeFlightEscapes(String s) {
        if(s==null||s.isEmpty())return s;
        String v=s.replace("\\n", " ").replace("\\r", " ").replace("\\t", " ").replace("\\\"", "\"").replace("\\/", "/");
        Matcher u=Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(v);
        StringBuffer b=new StringBuffer();
        while(u.find()) {
            char ch=(char)Integer.parseInt(u.group(1), 16);
            u.appendReplacement(b, Matcher.quoteReplacement(String.valueOf(ch)));
        }
        u.appendTail(b);
        Matcher x=Pattern.compile("\\\\x([0-9a-fA-F]{2})").matcher(b.toString());
        StringBuffer c=new StringBuffer();
        while(x.find()) {
            char ch=(char)Integer.parseInt(x.group(1), 16);
            x.appendReplacement(c, Matcher.quoteReplacement(String.valueOf(ch)));
        }
        x.appendTail(c);
        return c.toString();
    }
    /** Extract player-like JSON objects embedded in SSR pages. Used only by explicit bulk sync. */
    static List<Map<String, Object>> namedMapsFromHtml(String html) {
        if(html==null||html.isBlank())return List.of();
        ArrayList<Map<String, Object>> out=new ArrayList<>();
        Matcher scripts=Pattern.compile("(?is)<script[^>]*>(.*?)</script>").matcher(html);
        while(scripts.find()) {
            String body=unescapeHtml(scripts.group(1)).trim();
            if(body.isEmpty())continue;
            int a=body.indexOf('{'), b=body.indexOf('[');
            int first=Math.min(nonNeg(a), nonNeg(b));
            if(first==Integer.MAX_VALUE)continue;
            try {
                out.addAll(namedMaps(MiniJson.parse(body.substring(first))));
            } catch (Throwable ignored) {
            }
        }
        return List.copyOf(out);
    }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> namedMaps(Object node) {
        ArrayList<Map<String, Object>> out=new ArrayList<>();
        collect(node, out, 0);
        return out;
    }
    @SuppressWarnings("unchecked") private static void collect(Object node, List<Map<String, Object>> out, int depth) {
        if(node==null||depth>12)return;
        if(node instanceof Map<?, ?> raw) {
            Map<String, Object> m=(Map<String, Object>)raw;
            String n=str(m, "name", "username", "nickname", "ign", "minecraft_username", "lastKnownName");
            if(validName(n))out.add(m);
            for(Object v:m.values())collect(v, out, depth+1);
        } else if(node instanceof List<?> l)for(Object v:l)collect(v, out, depth+1);
    }
    static List<TierEntry> genericRowTiers(Map<String, Object> row) {
        Object tiers=any(row, "kitTiers", "tiers", "rankings", "modes", "gamemodes", "perLadder");
        List<TierEntry> out=tiersFromKitMap(tiers);
        if(!out.isEmpty())return out;
        try {
            return GenericTierParser.parse(MiniJson.stringify(row));
        } catch (Throwable t) {
            return List.of();
        }
    }
}
