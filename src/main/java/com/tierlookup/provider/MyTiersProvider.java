package com.tierlookup.provider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import com.tierlookup.model.*;
import com.tierlookup.net.*;

/** MyTiers source. Region is deliberately not part of TierLookup rank semantics. */
public final class MyTiersProvider implements TierProvider {
    private static final List<String> API=List.of( "https://mytiers.ru/api/players/{name}", "https://mytiers.ru/api/v1/player/{name}", "https://mytiers.ru/api/player/{name}");
    private static final List<String> SNAPSHOTS=List.of( "https://mytiers.ru/api/players",
        "https://mytiers.ru/api/leaderboard",
        "https://mytiers.ru/api/rankings",
        "https://mytiers.ru/api/v1/players",
        "https://mytiers.ru/");
    private static final long SNAPSHOT_TTL=3*60_000L;
    private static volatile Snapshot snapshot;
    public record Row(String name, List<TierEntry> tiers, int position) {
    }
    private record Snapshot(long at, List<Row> rows, Map<String, Row> byName) {
    }
    @Override
    public String id() {
        return "mytiers";
    }
    @Override
    public String displayName() {
        return "MyTiers";
    }
    @Override
    public CompletableFuture<ProviderResult> lookup(PlayerIdentity p) {
        // The live site exposes current tiers in /api/players. Prefer that snapshot over the /api/player/{name}
        // history route, which can legitimately return success with an empty history and no current tiers.
        return fetchRows(false).thenCompose(rows-> {
            Row row=find(rows, p.name());
            if(row!=null)return CompletableFuture.completedFuture(result(row.tiers()));
            CompletableFuture<ProviderResult> out=new CompletableFuture<>();
            tryApi(p, 0, out);
            return out;
        }
        );
    }
    private static Row find(List<Row> rows, String name) {
        if(name==null)return null;
        for(Row r:rows)if(r.name().equalsIgnoreCase(name))return r;
        return null;
    }
    private void tryApi(PlayerIdentity p, int i, CompletableFuture<ProviderResult> out) {
        if(out.isDone())return;
        if(i>=API.size()) {
            page(p, out);
            return;
        }
        String name=URLEncoder.encode(p.name(), StandardCharsets.UTF_8);
        Http.getResponse(API.get(i).replace("{name}", name), 6).whenComplete((r, e)-> {
            if(e!=null||r==null||!r.ok()) {
                tryApi(p, i+1, out); return;
            }
            ProviderResult pr=parsePayload(r.body());
            if(pr.status()==ProviderResult.Status.OK||pr.status()==ProviderResult.Status.NOT_RANKED)out.complete(pr);
            else tryApi(p, i+1, out);
        }
        );
    }
    private void page(PlayerIdentity p, CompletableFuture<ProviderResult> out) {
        String u="https://mytiers.ru/player/"+URLEncoder.encode(p.name(), StandardCharsets.UTF_8);
        Http.getResponse(u, 7).whenComplete((r, e)-> {
            if(e!=null) {
                out.complete(ProviderResult.error(id(), displayName(), "network: "+Http.rootMessage(e))); return;
            }
            if(r==null) {
                out.complete(ProviderResult.error(id(), displayName(), "empty HTTP response")); return;
            }
            if(r.statusCode()==404) {
                out.complete(notRanked()); return;
            }
            if(!r.ok()) {
                out.complete(ProviderResult.error(id(), displayName(), "HTTP "+r.statusCode())); return;
            }
             List<TierEntry> tiers=WebTierSupport.tiersFromHtml(r.body()); if(!tiers.isEmpty()) {
                out.complete(result(tiers)); return;
            }
            out.complete(explicitlyNotRankedPage(r.body())?notRanked():ProviderResult.error(id(), displayName(), "profile page schema unrecognized"));
        }
        );
    }
    public static ProviderResult parsePayload(String body) {
        
        if(body==null||body.isBlank())return ProviderResult.error("mytiers", "MyTiers", "empty payload");
        if(explicitlyNotRankedPage(body))return new ProviderResult("mytiers", "MyTiers", ProviderResult.Status.NOT_RANKED, List.of(), null, System.currentTimeMillis());
        List<Row> rows=parseRows(body);
        if(rows.size()==1)return result(rows.get(0).tiers());
        List<TierEntry> tiers;
        try {
            tiers=GenericTierParser.parse(body);
        } catch (Throwable t) {
            return ProviderResult.error("mytiers", "MyTiers", "parse: "+Http.rootMessage(t));
        }
        if(tiers.isEmpty()&&body.contains("<"))tiers=WebTierSupport.tiersFromHtml(body);
        if(!tiers.isEmpty())return result(tiers);
        return explicitlyNotRankedPage(body)?new ProviderResult("mytiers",
            "MyTiers",
            ProviderResult.Status.NOT_RANKED,
            List.of(),
            null,
            System.currentTimeMillis()):ProviderResult.error("mytiers",
            "MyTiers",
            "payload schema unrecognized");
    }
    private static boolean explicitlyNotRankedPage(String body) {
        if(body==null)return false;
        String s=body.toLowerCase(Locale.ROOT);
        return s.contains("player not found")||s.contains("игрок не найден")||s.contains("not ranked")||s.contains("нет в рейтинге")||s.contains("не ранжирован");
    }
    /** Best-effort full public snapshot used by explicit bulk sync and cached profile lookups. */
    public static CompletableFuture<List<Row>> fetchRows(boolean force) {
        Snapshot s=snapshot;
        long now=System.currentTimeMillis();
        if(!force&&s!=null&&now-s.at()<SNAPSHOT_TTL)return CompletableFuture.completedFuture(s.rows());
        CompletableFuture<List<Row>> out=new CompletableFuture<>();
        trySnapshot(0, out);
        return out.thenApply(rows-> {
            List<Row> frozen=List.copyOf(rows);
            LinkedHashMap<String, Row> index=new LinkedHashMap<>();
            for(Row r:frozen)index.put(r.name().toLowerCase(Locale.ROOT), r);
            snapshot=new Snapshot(System.currentTimeMillis(), frozen, Map.copyOf(index));
            
            return frozen;
        }
        );
    }
    private static void trySnapshot(int i, CompletableFuture<List<Row>> out) {
        if(out.isDone())return;
        if(i>=SNAPSHOTS.size()) {
            out.complete(List.of());
            return;
        }
        String url=SNAPSHOTS.get(i);
        Http.getResponse(url, 9).whenComplete((r, e)-> {
            if(e!=null||r==null||!r.ok()) {
                trySnapshot(i+1, out); return;
            }
             List<Row> rows=parseRows(r.body()); if(!rows.isEmpty())out.complete(rows); else trySnapshot(i+1, out);
        }
        );
    }
    /** Parses MyTiers' flat current/peak tier fields. */
    static List<Row> parseRows(String body) {
        Object root=null;
        try {
            if(body!=null&&!body.stripLeading().startsWith("<"))root=MiniJson.parse(body);
        } catch (Throwable ignored) {
        }
        List<Map<String, Object>> maps=root==null?List.of():WebTierSupport.namedMaps(root);
        if(maps.isEmpty()&&body!=null&&body.contains("<"))maps=WebTierSupport.namedMapsFromHtml(body);
        LinkedHashMap<String, Row> rows=new LinkedHashMap<>();
        for(Map<String, Object> m:maps) {
            String n=WebTierSupport.str(m, "name", "username", "nickname", "ign", "minecraft_username");
            if(!WebTierSupport.validName(n))continue;
            List<TierEntry> tiers=myTiersFlatTiers(m);
            if(tiers.isEmpty())tiers=WebTierSupport.genericRowTiers(m);
            if(tiers.isEmpty())continue;
            int pos=asInt(WebTierSupport.any(m, "position", "rank", "overallRank", "overall_rank", "place"));
            rows.put(n.toLowerCase(Locale.ROOT), new Row(n, List.copyOf(tiers), pos));
        }
        return List.copyOf(rows.values());
    }
    private static List<TierEntry> myTiersFlatTiers(Map<String, Object> row) {
        ArrayList<TierEntry> out=new ArrayList<>();
        for(var e:row.entrySet()) {
            String key=e.getKey();
            if(key==null)continue;
            String low=key.toLowerCase(Locale.ROOT);
            if(!low.endsWith("tiers")||low.endsWith("peaktiers"))continue;
            String base=key.substring(0, key.length()-5);
            String kit=kitName(base);
            if(kit==null)continue;
            Object curObj=e.getValue();
            String raw=currentRaw(curObj);
            String current=TierRank.normalize(raw);
            if(current==null)continue;
            Object peakObj=caseValue(row, base+"PeakTiers");
            String peak=strongestTier(peakObj);
            if(peak==null)peak=current;
            boolean retired=WebTierSupport.explicitRetired(row, raw);
            out.add(new TierEntry(kit, current, peak, retired, null));
        }
        return out;
    }
    private static Object caseValue(Map<String, Object> m, String key) {
        for(var e:m.entrySet())if(e.getKey().equalsIgnoreCase(key))return e.getValue();
        return null;
    }
    private static String currentRaw(Object v) {
        if(v instanceof List<?> l) {
            for(Object x:l) {
                String t=TierRank.normalize(String.valueOf(x));
                if(t!=null)return String.valueOf(x);
            }
            return null;
        }
        return v==null?null:String.valueOf(v);
    }
    private static String strongestTier(Object v) {
        String best=null;
        int bestScore=Integer.MIN_VALUE;
        if(v instanceof List<?> l) {
            for(Object x:l) {
                String t=TierRank.normalize(String.valueOf(x));
                int s=rankScore(t);
                if(t!=null&&s>bestScore) {
                    best=t;
                    bestScore=s;
                }
            }
        } else {
            String t=TierRank.normalize(v==null?null:String.valueOf(v));
            if(t!=null)best=t;
        }
        return best;
    }
    private static int rankScore(String t) {
        if(t==null||t.length()!=3)return Integer.MIN_VALUE;
        int tier=t.charAt(2)-'0';
        int band=t.startsWith("HT")?2:t.startsWith("MT")?1:0;
        return (6-tier)*3+band;
    }
    private static String kitName(String raw) {
        String s=raw==null?"":raw.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return switch(s) {
            case "sword"->"Sword";
            case "op"->"OP";
            case "netherpot", "nethpot", "npot"->"NethPot";
            case "pot", "dpot", "diapot"->"Pot";
            case "uhc"->"UHC";
            case "suhc", "shieldless", "shieldlessuhc"->"SUHC";
            case "smp"->"SMP";
            case "diasmp", "dsmp"->"DiaSMP";
            case "mace"->"Mace";
            case "axe"->"Axe";
            case "vanilla", "crystal", "cpvp"->"Vanilla";
            case "creeper"->"Creeper";
            case "minecart", "cart"->"Minecart";
            case "bow"->"Bow";
            case "shield"->"Shield";
            case "trident"->"Trident";
            case "spear", "spearmace"->"Spear";
            case "speed"->"Speed";
            default->null;
        };
    }
    private static int asInt(Object v) {
        if(v instanceof Number n)return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }
    private static ProviderResult result(List<TierEntry> tiers) {
        return new ProviderResult("mytiers",
            "MyTiers",
            tiers==null||tiers.isEmpty()?ProviderResult.Status.NOT_RANKED:ProviderResult.Status.OK,
            tiers==null?List.of():List.copyOf(tiers),
            null,
            System.currentTimeMillis());
    }
    private ProviderResult notRanked() {
        return result(List.of());
    }
}
