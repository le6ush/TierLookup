package com.tierlookup.provider;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

import com.tierlookup.model.*;
import com.tierlookup.net.Http;

/**
* Central Tier List adapter for the current public per-mode ranking pages.
*
* The website renders a small visible prefix of every tier and exposes the declared tier/player
* counts. That is useful positive data, but it is not a proven complete roster: "Show more" rows
* may be client-side. We therefore cache and mirror the visible rows as PARTIAL and never use
* absence from this snapshot as proof that a player is unranked.
*/ public final class CentralTierListProvider implements TierProvider {
    private static final String BASE="https://www.centraltierlist.com/rankings/";
    private static final List<ModePage> MODES=List.of( new ModePage("sword",
        "Sword"),
        new ModePage("crystal",
        "Vanilla"),
        new ModePage("netherite",
        "NethPot"),
        new ModePage("potion",
        "Pot"),
        new ModePage("mace",
        "Mace"),
        new ModePage("uhc",
        "UHC"),
        new ModePage("axe",
        "Axe"),
        new ModePage("smp",
        "SMP"),
        new ModePage("diasmp",
        "DiaSMP"));
    private static final long SNAPSHOT_TTL=3*60_000L;
    private static volatile Snapshot snapshot;
    private static final Pattern TOTAL=Pattern.compile("(?i)\\b([0-9][0-9,]*)\\s+players\\s+across\\s+([1-5])\\s+tiers?\\b");
    private static final Pattern TIER_HEAD=Pattern.compile("(?i)\\bTier\\s+([1-5])\\b");
    private static final Pattern PLAYER_REGION=Pattern.compile("\\b([A-Za-z0-9_]{1,16})\\s*([A-Z]{2,5}(?:/[A-Z]{2,5})?)\\b");
    private static final Pattern INT=Pattern.compile("\\b([0-9][0-9,]*)\\b");
    public record Row(String name, List<TierEntry> tiers, int position) {
    }
    /** declaredAssignments is the sum of per-mode declared players, not a unique-player total. */
    public record BulkSnapshot(List<Row> rows, int pagesFetched, int pagesFailed, int declaredAssignments, int parsedAssignments, List<String> failures) {
    }
    private record Snapshot(long at, BulkSnapshot bulk, Map<String, Row> byName) {
    }
    private record ModePage(String slug, String kit) {
    }
    private record ModeRow(String name, TierEntry tier) {
    }
    private record ModeParse(List<ModeRow> rows, int declaredPlayers) {
    }
    @Override
    public String id() {
        return "centraltierlist";
    }
    @Override
    public String displayName() {
        return "Central Tier List";
    }
    @Override
    public CompletableFuture<ProviderResult> lookup(PlayerIdentity p) {
        return snapshot(false).handle((s, e)-> {
            if(e!=null)return ProviderResult.error(id(), displayName(), Http.rootMessage(e));
            Row row=s.byName().get(p.name().toLowerCase(Locale.ROOT));
            if(row!=null)return result(row.tiers());
            return ProviderResult.error(id(), displayName(), "public mode-page snapshot is partial; absence does not prove unranked");
        }
        );
    }
    public static CompletableFuture<List<Row>> fetchRows(boolean force) {
        return snapshot(force).thenApply(s->s.bulk().rows());
    }
    public static CompletableFuture<BulkSnapshot> fetchBulk(boolean force) {
        return snapshot(force).thenApply(Snapshot::bulk);
    }
    private static CompletableFuture<Snapshot> snapshot(boolean force) {
        Snapshot s=snapshot;
        long now=System.currentTimeMillis();
        if(!force&&s!=null&&now-s.at()<SNAPSHOT_TTL)return CompletableFuture.completedFuture(s);
        Acc acc=new Acc();
        CompletableFuture<Acc> chain=CompletableFuture.completedFuture(acc);
        for(ModePage mode:MODES)chain=chain.thenCompose(a->fetchMode(a, mode));
        return chain.thenApply(a-> {
            ArrayList<Row> rows=new ArrayList<>(); LinkedHashMap<String, Row> by=new LinkedHashMap<>(); for(PlayerAcc pa:a.players.values())if(!pa.tiers.isEmpty()) {
                Row r=new Row(pa.name, List.copyOf(pa.tiers.values()), 0); rows.add(r); by.put(pa.name.toLowerCase(Locale.ROOT), r);
            }
            BulkSnapshot bulk=new BulkSnapshot(List.copyOf(rows), a.pagesFetched, a.pagesFailed, a.declaredAssignments, a.parsedAssignments, List.copyOf(a.failures));
            Snapshot fresh=new Snapshot(System.currentTimeMillis(), bulk, Map.copyOf(by));
            snapshot=fresh;
            
            return fresh;
        }
        );
    }
    private static CompletableFuture<Acc> fetchMode(Acc a, ModePage mode) {
        String url=BASE+mode.slug();
        return Http.getResponse(url, 10).handle((r, e)-> {
            if(e!=null||r==null||!r.ok()) {
                a.pagesFailed++; a.failures.add(mode.slug()+": "+(e!=null?Http.rootMessage(e):(r==null?"empty response":"HTTP "+r.statusCode()))); return a;
            }
            a.pagesFetched++;
            
            ModeParse parsed=parseModePage(r.body(), mode.kit());
            a.declaredAssignments+=parsed.declaredPlayers();
            a.parsedAssignments+=parsed.rows().size();
            if(parsed.rows().isEmpty()) {
                a.pagesFailed++; a.failures.add(mode.slug()+": no visible tier rows parsed"); return a;
            }
            for(ModeRow mr:parsed.rows())a.players.computeIfAbsent(mr.name().toLowerCase(Locale.ROOT),
                k->new PlayerAcc(mr.name())).tiers.put(mode.kit().toLowerCase(Locale.ROOT),
                mr.tier());
            return a;
        }
        );
    }
    static ModeParse parseModePage(String html, String kit) {
        if(html==null||html.isBlank()||kit==null||kit.isBlank())return new ModeParse(List.of(), 0);
        String text=WebTierSupport.renderedTextFromHtml(html);
        int declared=0;
        Matcher total=TOTAL.matcher(text);
        if(total.find())declared=parseInt(total.group(1));
        ArrayList<int[]> heads=new ArrayList<>();
        Matcher hm=TIER_HEAD.matcher(text);
        while(hm.find())heads.add(new int[] {
            hm.start(), hm.end(), parseInt(hm.group(1))
        }
        );
        ArrayList<ModeRow> out=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(int i=0; i<heads.size(); i++) {
            int[] h=heads.get(i);
            int end=i+1<heads.size()?heads.get(i+1)[0]:text.length();
            String section=text.substring(h[1], Math.max(h[1], end));
            Matcher pm=PLAYER_REGION.matcher(section);
            ArrayList<String> names=new ArrayList<>();
            int firstPlayer=-1;
            while(pm.find()) {
                if(firstPlayer<0)firstPlayer=pm.start();
                String name=pm.group(1);
                if(WebTierSupport.validName(name)&&names.stream().noneMatch(x->x.equalsIgnoreCase(name)))names.add(name);
            }
            if(names.isEmpty())continue;
            String countText=section.substring(0, firstPlayer<0?0:firstPlayer);
            ArrayList<Integer> nums=new ArrayList<>();
            Matcher im=INT.matcher(countText);
            while(im.find()&&nums.size()<3)nums.add(parseInt(im.group(1)));
            if(nums.isEmpty())continue;
            int high, totalCount;
            if(nums.size()>=3) {
                high=nums.get(0);
                totalCount=nums.get(2);
            } else if(nums.size()==2) {
                high=nums.get(0);
                totalCount=nums.get(1);
            } else {
                high=0;
                totalCount=nums.get(0);
            }
            high=Math.max(0, Math.min(high, totalCount));
            int tierNo=h[2];
            for(int n=0; n<names.size(); n++) {
                String name=names.get(n), key=name.toLowerCase(Locale.ROOT)+"|"+kit.toLowerCase(Locale.ROOT);
                if(!seen.add(key))continue;
                String rank=(n<high?"HT":"LT")+tierNo;
                out.add(new ModeRow(name, new TierEntry(kit, rank, rank, false, null)));
            }
        }
        return new ModeParse(List.copyOf(out), declared);
    }
    private static int parseInt(String s) {
        try {
            return Integer.parseInt(String.valueOf(s).replace(",", ""));
        } catch (Exception e) {
            return 0;
        }
    }
    private static ProviderResult result(List<TierEntry> t) {
        return new ProviderResult("centraltierlist",
            "Central Tier List",
            t==null||t.isEmpty()?ProviderResult.Status.NOT_RANKED:ProviderResult.Status.OK,
            t==null?List.of():List.copyOf(t),
            null,
            System.currentTimeMillis());
    }
    private static final class PlayerAcc {
        final String name;
        final LinkedHashMap<String, TierEntry> tiers=new LinkedHashMap<>();
        PlayerAcc(String name) {
            this.name=name;
        }
    }
    private static final class Acc {
        final LinkedHashMap<String, PlayerAcc> players=new LinkedHashMap<>();
        final ArrayList<String> failures=new ArrayList<>();
        int pagesFetched, pagesFailed, declaredAssignments, parsedAssignments;
    }
    public static boolean selfTestSiteParser() {
        String h="<h1>Mace PvP</h1><p>3 players across 1 tiers</p><h3>Tier 2</h3><b>2</b><b>1</b><b>3</b>"+ "<img alt='Havoidz'><span>Havoidz</span><span>AS/AU</span><img alt='tatnat'><span>tatnat</span><span>EU</span><img alt='Jxydon'><span>Jxydon</span><span>AS/AU</span>";
        ModeParse p=parseModePage(h, "Mace");
        return p.declaredPlayers()==3&&p.rows().size()==3&&"HT2".equals(p.rows().get(0).tier().currentTier())&&"HT2".equals(p.rows().get(1).tier().currentTier())&&"LT2".equals(p.rows().get(2).tier().currentTier());
    }
}
