package com.tierlookup.provider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import com.tierlookup.client.OverlayRenderer;
import com.tierlookup.model.PlayerIdentity;
import com.tierlookup.model.TierRank;
import com.tierlookup.net.Http;
import com.tierlookup.net.MiniJson;

/**
* Explicit, Full-Mode-only CISTiers source history adapter.
*
* The official CISTierTagger uses /api/profile/{nickname}; unlike /api/dump this response contains
* tierStats.tierHistory with source dates. This adapter is intentionally NOT wired into hover, join,
* render, ordinary provider refresh, or background maintenance paths.
*/ public final class CisTiersHistoryClient {
    private static final String BASE="https://cistiers.com/api/profile/";
    private CisTiersHistoryClient() {
    }
    public record SourceEvent(long at, String gamemode, String canonicalKit, String tier, String rawDate) {
    }
    public record Payload(List<SourceEvent> events, Map<String, String> currentTierDates) {
    }
    public static CompletableFuture<Payload> fetch(PlayerIdentity player) {
        if(player==null||player.name()==null||player.name().isBlank())return CompletableFuture.failedFuture(new IllegalArgumentException("player required"));
        String url=BASE+URLEncoder.encode(player.name(), StandardCharsets.UTF_8);
        return Http.getResponse(url, 6).thenApply(r-> {
            if(r.statusCode()==404)return new Payload(List.of(), Map.of());
            if(!r.ok())throw new Http.HttpException(r.statusCode(), r.url(), compact(r.body()));
            Payload p=parse(r.body());
            
            return p;
        }
        );
    }
    @SuppressWarnings("unchecked") public static Payload parse(String body) {
        Object parsed=MiniJson.parse(body);
        if(!(parsed instanceof Map<?, ?> rootRaw))return new Payload(List.of(), Map.of());
        Map<String, Object> root=(Map<String, Object>)rootRaw;
        Object tsObj=root.get("tierStats");
        if(!(tsObj instanceof Map<?, ?> statsRaw))return new Payload(List.of(), Map.of());
        Map<String, Object> stats=(Map<String, Object>)statsRaw;
        ArrayList<SourceEvent> events=new ArrayList<>();
        Object hObj=stats.get("tierHistory");
        if(hObj instanceof List<?> list) {
            for(Object item:list) {
                if(!(item instanceof Map<?, ?> m))continue;
                String rawKit=str(m.get("kit")), tier=TierRank.normalize(str(m.get("tier"))), rawDate=str(m.get("date"));
                String canonical=OverlayRenderer.canonicalKit(rawKit);
                long at=parseDate(rawDate);
                if(canonical!=null&&tier!=null&&at>0)events.add(new SourceEvent(at, rawKit, canonical, tier, rawDate));
            }
        }
        events.sort(Comparator.comparingLong(SourceEvent::at));
        LinkedHashMap<String, String> current=new LinkedHashMap<>();
        Object cObj=stats.get("currentTiers");
        if(cObj instanceof List<?> list) {
            for(Object item:list) {
                if(!(item instanceof Map<?, ?> m))continue;
                String canonical=OverlayRenderer.canonicalKit(str(m.get("kit"))), tier=TierRank.normalize(str(m.get("tier")));
                if(canonical==null||tier==null)continue;
                long newest=0;
                for(SourceEvent e:events)if(canonical.equals(e.canonicalKit())&&tier.equals(e.tier())&&e.at()>newest)newest=e.at();
                if(newest>0)current.put(canonical, Long.toString(newest));
            }
        }
        return new Payload(List.copyOf(events), Map.copyOf(current));
    }
    static long parseDate(String raw) {
        if(raw==null||raw.isBlank())return 0;
        String s=raw.trim();
        try {
            long n=Long.parseLong(s);
            if(n>10_000_000_000L)return n;
            if(n>1_000_000_000L)return n*1000L;
        } catch (Exception ignored) {
        }
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return ZonedDateTime.parse(s).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {
        }
        for(DateTimeFormatter f:List.of( DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("dd.MM.yyyy"), DateTimeFormatter.ofPattern("dd/MM/yyyy")))try {
            return LocalDate.parse(s, f).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        for(DateTimeFormatter f:List.of( DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")))try {
            return LocalDateTime.parse(s, f).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {
        }
        return 0;
    }
    private static String str(Object o) {
        return o==null?null:String.valueOf(o);
    }
    private static String compact(String body) {
        if(body==null)return "";
        String s=body.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length()>180?s.substring(0, 180)+"…":s;
    }
}
