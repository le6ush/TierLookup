package com.tierlookup.provider;

import java.util.*;
import java.util.concurrent.*;

import com.tierlookup.model.*;
import com.tierlookup.net.Http;
import com.tierlookup.net.MiniJson;

/** FlowPvP public ranked API adapter. Source rank wins; SR is used only when Flow supplies no rank label. */
public final class FlowPvpProvider implements TierProvider {
    private static final String BASE = "https://flowpvp.gg/api/ranked/";
    private static final Map<String, String> HEADERS=Map.of("Accept", "application/json", "User-Agent", "FlowTiers/1.14 (Disc: .fecl. X: @FeclMC)");
    @Override
    public String id() {
        return "flowpvp";
    }
    @Override
    public String displayName() {
        return "FlowPvP";
    }
    public static Map<String, String> requestHeaders() {
        return HEADERS;
    }
    @Override
    public CompletableFuture<ProviderResult> lookup(PlayerIdentity p) {
        return Http.get(BASE + p.uuid(), 4, HEADERS).handle((body, error) -> {
            if (error != null) {
                Http.HttpException h = Http.findHttpException(error);
                if (h != null && h.statusCode() == 404) return notRankedStatic();
                return ProviderResult.error(id(), displayName(), Http.rootMessage(error));
            }
            try {
                return parseBody(body);
            } catch (Throwable t) {
                return ProviderResult.error(id(), displayName(), "parse: "+Http.rootMessage(t));
            }
        }
        );
    }
    @SuppressWarnings("unchecked") public static ProviderResult parseBody(String body) {
        
        if (body == null || body.isBlank() || "null".equalsIgnoreCase(body.trim())) return ProviderResult.error("flowpvp", "FlowPvP", "empty payload");
        Object obj = MiniJson.parse(body);
        if (!(obj instanceof Map<?, ?> raw)) return ProviderResult.error("flowpvp", "FlowPvP", "payload is not an object");
        Map<String, Object> root = (Map<String, Object>) raw;
        Object laddersObj = get(root, "perLadder");
        if (!(laddersObj instanceof Map<?, ?>)) laddersObj=get(root, "data");
        if (!(laddersObj instanceof Map<?, ?> ladders)) return ProviderResult.error("flowpvp", "FlowPvP", "missing perLadder/data map");
        if(ladders.isEmpty())return notRankedStatic();
        List<TierEntry> tiers = new ArrayList<>();
        for (var e : ladders.entrySet()) {
            String mode=normalizeMode(String.valueOf(e.getKey()));
            if (!(e.getValue() instanceof Map<?, ?> lr)) {
                String rawRank=e.getValue()==null?null:String.valueOf(e.getValue());
                String rank=normalizeFlowRank(rawRank, 0);
                if(rank!=null)tiers.add(new TierEntry(mode, rank, rank, explicitRetired(Map.of(), rawRank), null));
                continue;
            }
            Map<String, Object> ladder = (Map<String, Object>) lr;
            int rating=firstInt(ladder, 0, "sr", "skillRating", "rating", "totalRating", "elo");
            // Match Flow's current client schema. grantedTier is legacy/fallback and must never override currentRank.
            String rawRank=firstString(ladder, "currentRank", "tier", "tierTag", "rank", "grantedTier");
            String rank=normalizeFlowRank(rawRank, rating);
            if(rank==null)continue;
            String rawPeak=firstString(ladder, "peakTier", "peakRank", "peak_tier", "highestRank", "highestTier");
            String peak=normalizeFlowRank(rawPeak, 0);
            if (peak == null) peak = rank;
            // numeric peakRating is not a tier label.
            boolean retired=explicitRetired(ladder, rawRank);
            tiers.add(new TierEntry(mode, rank, peak, retired, date(ladder)));
        }
        
        if(tiers.isEmpty())return ProviderResult.error("flowpvp", "FlowPvP", "ladder schema present but no valid ranks parsed");
        return new ProviderResult("flowpvp", "FlowPvP", ProviderResult.Status.OK, List.copyOf(tiers), null, System.currentTimeMillis());
    }
    /**
    * Flow-specific rank normalization. An explicit source tier/rank always wins. Rating conversion is only a
    * fallback for leaderboard rows that contain SR but no rank label, mirroring FlowTiers' current rank system.
    */ public static String normalizeFlowRank(String raw, int rating) {
        if(raw!=null&&!raw.isBlank()) {
            String direct=TierRank.normalize(raw);
            if(direct!=null)return direct;
            String compact=raw.trim().toUpperCase(Locale.ROOT).replace('-',
                '_').replace(' ',
                '_').replace("LOW_TIER_",
                "LT").replace("MID_TIER_",
                "MT").replace("HIGH_TIER_",
                "HT").replace("LOWTIER_",
                "LT").replace("MIDTIER_",
                "MT").replace("HIGHTIER_",
                "HT").replace("_",
                "");
            direct=TierRank.normalize(compact);
            if(direct!=null)return direct;
            String legacy=switch(compact) {
                case "GRANDMASTER", "NETHERITE"->"HT1";
                case "DIAMONDIII"->"MT1";
                case "DIAMONDII"->"LT1";
                case "DIAMONDI"->"HT2";
                case "EMERALDIII"->"MT2";
                case "EMERALDII"->"LT2";
                case "EMERALDI"->"HT3";
                case "GOLDIII"->"MT3";
                case "GOLDII"->"LT3";
                case "GOLDI"->"HT4";
                case "IRONIII"->"MT4";
                case "IRONII"->"LT4";
                case "IRONI"->"HT5";
                case "COPPERIII", "COPPERII"->"MT5";
                case "COPPERI", "COALIII", "COALII", "COALI"->"LT5";
                default->null;
            };
            if(legacy!=null)return legacy;
        }
        return rating>0?rankFromRating(rating):null;
    }
    /** Current FlowTiers SR fallback thresholds, including MT. */
    public static String rankFromRating(int rating) {
        if(rating>=2175)return "HT1";
        if(rating>=1900)return "MT1";
        if(rating>=1770)return "LT1";
        if(rating>=1650)return "HT2";
        if(rating>=1525)return "MT2";
        if(rating>=1400)return "LT2";
        if(rating>=1275)return "HT3";
        if(rating>=1125)return "MT3";
        if(rating>=1025)return "LT3";
        if(rating>=900)return "HT4";
        if(rating>=800)return "MT4";
        if(rating>=700)return "LT4";
        if(rating>=600)return "HT5";
        if(rating>=400)return "MT5";
        return "LT5";
    }
    private static boolean explicitRetired(Map<?, ?> m, String rawRank) {
        return WebTierSupport.explicitRetired(m, rawRank);
    }
    private static ProviderResult notRankedStatic() {
        return new ProviderResult("flowpvp", "FlowPvP", ProviderResult.Status.NOT_RANKED, List.of(), null, System.currentTimeMillis());
    }
    private static Object get(Map<?, ?> m, String k) {
        for (var e:m.entrySet()) if(String.valueOf(e.getKey()).equalsIgnoreCase(k)) return e.getValue();
        return null;
    }
    private static String firstString(Map<String, Object> m, String...keys) {
        for(String k:keys) {
            Object v=get(m, k);
            if(v!=null&&!String.valueOf(v).isBlank())return String.valueOf(v);
        }
        return null;
    }
    private static int firstInt(Map<String, Object> m, int fallback, String...keys) {
        for(String k:keys) {
            Object v=get(m, k);
            if(v instanceof Number n)return n.intValue();
            if(v!=null)try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }
    private static String date(Map<String, Object> m) {
        Object v=get(m, "lastMatchAt");
        if(v==null)v=get(m, "updatedAt");
        if(v==null)v=get(m, "attained");
        return v==null?null:String.valueOf(v);
    }
    public static String normalizeMode(String value) {
        String s=value==null?"":value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch(s) {
            case "spear_mace", "spearmace", "spear"->"Spear";
            case "nethop", "nethpot", "neth_pot", "netheritepot", "netherite_pot"->"NethPot";
            case "netherite_op", "netheriteop"->"OP";
            case "diamondsmp", "diamond_smp", "dia_smp", "diasmp"->"DiaSMP";
            case "cart", "minecart"->"Minecart";
            case "pot", "dpot", "diapot", "diamond_pot", "diamondpot"->"DPot";
            case "crystal", "vanilla", "cpvp"->"Vanilla";
            case "sword"->"Sword";
            case "axe"->"Axe";
            case "mace"->"Mace";
            case "uhc"->"UHC";
            case "smp"->"SMP";
            case "global"->"Global";
            default->value==null?"Ranked":value.replace('_', ' ').replace('-', ' ');
        };
    }
}
