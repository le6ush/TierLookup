package com.tierlookup.provider;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

import com.tierlookup.model.*;
import com.tierlookup.net.*;

/**
* Adaptive ATiers provider.
*
* The HTML frontend at atiers.net is a SPA; /api/... on that host is therefore just the
* frontend fallback HTML. The frontend CSP points at the actual API infrastructure on
* api.atiers.net and tiertagger.atiers.net. We probe those hosts first and, when needed,
* inspect the current Vite JS bundle for route literals. The first verified endpoint is
* cached for the Minecraft process so later lookups are a single request.
*/ public final class ATiersProvider implements TierProvider {
    private record Candidate(String template, boolean bulk, String origin) {
    }
    private record BulkParse(List<TierEntry> tiers, int matches) {
    }
    private static volatile Candidate working;
    private static volatile long failureUntil;
    private static volatile List<Candidate> discoveredCache = List.of();
    private static volatile long discoveredAt;
    private static final long DISCOVERY_TTL = 10 * 60_000L;
    // This route is not a guess: the current ATiers API answers it with a structured
    // success envelope (including a clear "Player not found" response). Treating that
    // response as a valid NOT_RANKED result prevents the old expensive discovery loop.
    private static final Candidate CANONICAL = c("https://api.atiers.net/api/v1/player/{name}", false, "api-v1-player-canonical");
    private static final List<Candidate> QUICK = List.of( CANONICAL,
        c("https://api.atiers.net/api/v1/player?nickname={name}",
        false,
        "api-v1-player-query"),
        c("https://api.atiers.net/api/v1/players/search?nickname={name}",
        false,
        "api-v1-player-search"),
        c("https://api.atiers.net/api/v1/players?search={name}",
        true,
        "api-v1-player-list-search"),
        c("https://api.atiers.net/api/v1/leaderboard?search={name}",
        true,
        "api-v1-leaderboard-search"),
        c("https://api.atiers.net/api/v1/leaderboard?q={name}",
        true,
        "api-v1-leaderboard-q"),
        c("https://api.atiers.net/api/v1/leaderboard?nickname={name}",
        true,
        "api-v1-leaderboard-nickname"),
        c("https://api.atiers.net/api/v1/search?nickname={name}",
        true,
        "api-v1-search"),
        c("https://api.atiers.net/api/v1/profile/{name}",
        false,
        "api-v1-profile"),
        c("https://api.atiers.net/api/v1/users/{name}",
        false,
        "api-v1-users"),
        c("https://api.atiers.net/api/v1/users/search?nickname={name}",
        false,
        "api-v1-search-nickname"),
        c("https://api.atiers.net/api/v1/users/search?username={name}",
        false,
        "api-v1-search-username"),
        c("https://tiertagger.atiers.net/api/v1/player/{name}",
        false,
        "tagger-v1-player"),
        c("https://tiertagger.atiers.net/api/player/{name}",
        false,
        "tagger-player"));
    private static final List<Candidate> BULK = List.of( c("https://api.atiers.net/players",
        true,
        "api-bulk"),
        c("https://api.atiers.net/leaderboard",
        true,
        "api-bulk"),
        c("https://api.atiers.net/rankings",
        true,
        "api-bulk"),
        c("https://api.atiers.net/tiers",
        true,
        "api-bulk"),
        c("https://api.atiers.net/api/players",
        true,
        "api-bulk"),
        c("https://api.atiers.net/api/leaderboard",
        true,
        "api-bulk"),
        c("https://api.atiers.net/api/rankings",
        true,
        "api-bulk"),
        c("https://api.atiers.net/api/tiers",
        true,
        "api-bulk"),
        c("https://tiertagger.atiers.net/players",
        true,
        "tagger-bulk"),
        c("https://tiertagger.atiers.net/api/players",
        true,
        "tagger-bulk"));
    private static Candidate c(String t, boolean b, String o) {
        return new Candidate(t, b, o);
    }
    @Override
    public String id() {
        return "atiers";
    }
    @Override
    public String displayName() {
        return "ATiers";
    }
    @Override
    public CompletableFuture<ProviderResult> lookup(PlayerIdentity p) {
        // The canonical public player route is confirmed and bounded. Normal lookups never fan out into
        // guessed endpoints or frontend-JS discovery: one explicit provider request means one ATiers request.
        if(System.currentTimeMillis()<failureUntil)return CompletableFuture.completedFuture(ProviderResult.error(id(), displayName(), "ATiers API temporarily unavailable"));
        return lookupFresh(p);
    }
    private CompletableFuture<ProviderResult> lookupFresh(PlayerIdentity p) {
        return request(CANONICAL, p).thenApply(r-> {
            // Both OK and NOT_RANKED prove the canonical route worked. Cache the route and do not probe alternatives.
            working=CANONICAL; failureUntil=0;  return r;
        }
        ).exceptionally(e-> {
            failureUntil=System.currentTimeMillis()+60_000L;
            
            return ProviderResult.error(id(), displayName(), "ATiers API temporarily unavailable");
        }
        );
    }
    /** Expensive frontend discovery is a maintenance action, never part of normal player lookup. */
    public static CompletableFuture<Integer> maintenanceDiscover() {
        discoveredAt=0;
        return frontendCandidates().thenApply(List::size);
    }
    private static boolean containsTemplate(List<Candidate> list, String t) {
        for(Candidate c:list)if(c.template().equals(t))return true;
        return false;
    }
    private CompletableFuture<ProviderResult> waveBatched(List<Candidate> all, PlayerIdentity p, int start, int batch) {
        if(all==null||start>=all.size())return CompletableFuture.completedFuture(null);
        int end=Math.min(all.size(), start+batch);
        return wave(all.subList(start, end), p).thenCompose(r->r!=null?CompletableFuture.completedFuture(r):waveBatched(all, p, end, batch));
    }
    private CompletableFuture<ProviderResult> wave(List<Candidate> candidates, PlayerIdentity p) {
        if(candidates.isEmpty())return CompletableFuture.completedFuture(null);
        CompletableFuture<ProviderResult> winner=new CompletableFuture<>();
        AtomicInteger remaining=new AtomicInteger(candidates.size());
        for(Candidate c:candidates) {
            request(c, p).whenComplete((r, e)-> {
                if(e==null&&r!=null&&r.status()==ProviderResult.Status.OK&&winner.complete(r)) {
                    working=c;
                    failureUntil=0;
                    
                } else if(e!=null) {
                    
                }
                if(remaining.decrementAndGet()==0)winner.complete(null);
            }
            );
        }
        return winner;
    }
    private CompletableFuture<ProviderResult> request(Candidate c, PlayerIdentity p) {
        String url=expand(c.template(), p);
        if(!validExpandedUrl(url)) {
            IllegalArgumentException bad=new IllegalArgumentException("rejected malformed ATiers route: "+url);
            
            return CompletableFuture.failedFuture(bad);
        }
        return Http.get(url, 3).thenApply(body-> {
            
            String t=body==null?"":body.stripLeading();
            if(!(t.startsWith("{")||t.startsWith("[")))throw new IllegalStateException("non-JSON response");
            if(c.bulk()) {
                BulkParse bp=parseBulk(body, p);
                if(bp.matches()==0 && body.length()<4000)throw new IllegalStateException("JSON did not look like ATiers bulk data");
                return new ProviderResult(id(),
                    displayName(),
                    bp.tiers().isEmpty()?ProviderResult.Status.NOT_RANKED:ProviderResult.Status.OK,
                    bp.tiers(),
                    null,
                    System.currentTimeMillis());
            }
            // ATiers uses a public envelope like {success:false, reason:"Player not found: ...", data:null}.
            // A not-found envelope proves the route is correct and means exactly NOT_RANKED;
            // it must not be treated as endpoint discovery failure.
            Object parsed=MiniJson.parse(body); if(parsed instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked") Map<String, Object> m=(Map<String, Object>)raw;
                Object success=valueIgnoreCase(m, "success");
                String reason=String.valueOf(valueIgnoreCase(m, "reason"));
                if(Boolean.FALSE.equals(success) && isPlayerNotFoundReason(reason)) {
                    
                    return new ProviderResult(id(), displayName(), ProviderResult.Status.NOT_RANKED, List.of(), null, System.currentTimeMillis());
                }
            }
            List<TierEntry> tiers=GenericTierParser.parse(body);
            if(tiers.isEmpty()&&!identitySeen(body, p))throw new IllegalStateException("JSON response does not identify requested player");
            return new ProviderResult(id(), displayName(), tiers.isEmpty()?ProviderResult.Status.NOT_RANKED:ProviderResult.Status.OK, tiers, null, System.currentTimeMillis());
        }
        );
    }
    private static Object valueIgnoreCase(Map<String, Object> m, String key) {
        for(var e:m.entrySet())if(e.getKey().equalsIgnoreCase(key))return e.getValue();
        return null;
    }
    private static boolean isPlayerNotFoundReason(String reason) {
        if(reason==null)return false;
        String r=reason.toLowerCase(Locale.ROOT);
        return r.contains("player not found")||r.equals("not found")||r.contains("игрок не найден");
    }
    static boolean validExpandedUrl(String url) {
        if(url==null||url.isBlank())return false;
        if(url.contains("${")||url.indexOf('`')>=0||url.indexOf(' ')>=0||url.indexOf('\n')>=0||url.indexOf('\r')>=0)return false;
        // After expansion there must be no unresolved neutral placeholder.
        if(url.contains("{name}")||url.contains("{uuid}")||url.contains("{uuid32}"))return false;
        if(url.endsWith("?")||url.endsWith("&"))return false;
        try {
            URI u=URI.create(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost()!=null && (u.getHost().equals("api.atiers.net")||u.getHost().equals("tiertagger.atiers.net"));
        } catch (Exception e) {
            return false;
        }
    }
    private static boolean identitySeen(String body, PlayerIdentity p) {
        String b=body.toLowerCase(Locale.ROOT);
        String name=p.name().toLowerCase(Locale.ROOT), uuid=p.uuid().toString().toLowerCase(Locale.ROOT), u32=uuid.replace("-", "");
        return b.contains('"'+name+'"')||b.contains(uuid)||b.contains(u32);
    }
    private static String expand(String template, PlayerIdentity p) {
        String uuid=p.uuid().toString(), uuid32=uuid.replace("-", "");
        String name=URLEncoder.encode(p.name(), StandardCharsets.UTF_8);
        return template.replace("{uuid}", uuid).replace("{uuid32}", uuid32).replace("{name}", name);
    }
    @SuppressWarnings("unchecked") private static BulkParse parseBulk(String body, PlayerIdentity p) {
        Object root=MiniJson.parse(body);
        List<Object> matches=new ArrayList<>();
        collect(root, p, matches, 0);
        LinkedHashMap<String, TierEntry> out=new LinkedHashMap<>();
        for(Object m:matches)for(TierEntry e:GenericTierParser.parse(MiniJson.stringify(m)))out.putIfAbsent((e.gamemode()+"|"+e.currentTier()+"|"+e.peakTier()).toLowerCase(Locale.ROOT),
            e);
        
        return new BulkParse(new ArrayList<>(out.values()), matches.size());
    }
    @SuppressWarnings("unchecked") private static void collect(Object node, PlayerIdentity p, List<Object>out, int depth) {
        if(node==null||depth>14)return;
        String uuid=p.uuid().toString().toLowerCase(Locale.ROOT), u32=uuid.replace("-", ""), name=p.name().toLowerCase(Locale.ROOT);
        if(node instanceof Map<?, ?> raw) {
            Map<String, Object>m=(Map<String, Object>)raw;
            if(matches(m, uuid, u32, name)) {
                out.add(m);
                return;
            }
            for(var e:m.entrySet()) {
                String k=e.getKey().toLowerCase(Locale.ROOT);
                if(k.equals(uuid)||k.replace("-", "").equals(u32)||k.equals(name)) {
                    out.add(e.getValue());
                    return;
                }
            }
            for(Object v:m.values())collect(v, p, out, depth+1);
        } else if(node instanceof List<?> l)for(Object v:l)collect(v, p, out, depth+1);
    }
    private static boolean matches(Map<String, Object>m, String uuid, String u32, String name) {
        for(var e:m.entrySet()) {
            Object ov=e.getValue();
            if(!(ov instanceof String s))continue;
            String k=e.getKey().toLowerCase(Locale.ROOT);
            if(!(k.contains("uuid")||k.equals("id")||k.contains("name")||k.equals("ign")||k.equals("player")||k.equals("username")))continue;
            String v=s.toLowerCase(Locale.ROOT).trim();
            if(v.equals(name)||v.equals(uuid)||v.replace("-", "").equals(u32))return true;
        }
        return false;
    }
    /** Read current ATiers frontend JS, including Vite lazy chunks, and derive API route candidates. */
    private static CompletableFuture<List<Candidate>> frontendCandidates() {
        long now=System.currentTimeMillis();
        List<Candidate> cached=discoveredCache;
        if(!cached.isEmpty()&&now-discoveredAt<DISCOVERY_TTL) {
            
            return CompletableFuture.completedFuture(cached);
        }
        return Http.get("https://atiers.net/", 3).thenCompose(html-> {
            LinkedHashSet<String> firstAssets=new LinkedHashSet<>(); Matcher m=Pattern.compile("(?:src|href)=[\"']([^\"']+\\.js[^\"']*)").matcher(html); while(m.find()) {
                String asset=absolute("https://atiers.net/", m.group(1));  firstAssets.add(asset);
            }
            // The HTML currently references a number of small split bundles. Fetch index/api/config
            // first, but keep other JS entries because a deployment may reshuffle chunk names.
            List<String> ordered=prioritizeAssets(firstAssets); if(ordered.size()>8)ordered=ordered.subList(0, 8); return fetchAssets(ordered).thenCompose(firstBodies-> {
                LinkedHashSet<String> secondAssets=new LinkedHashSet<>(); for(String js:firstBodies) {
                    Matcher am=Pattern.compile("(?:assets/)?[A-Za-z0-9_.-]+\\.js").matcher(js); while(am.find()) {
                        String rel=am.group(); if(!rel.startsWith("assets/"))rel="assets/"+rel; secondAssets.add(absolute("https://atiers.net/", rel));
                    }
                }
                secondAssets.removeAll(firstAssets);
                List<String> second=prioritizeAssets(secondAssets);
                if(second.size()>18)second=second.subList(0, 18);
                
                return fetchAssets(second).thenApply(secondBodies-> {
                    LinkedHashMap<String, Candidate> out=new LinkedHashMap<>();
                    for(String js:firstBodies)extractRoutes(js, out);
                    for(String js:secondBodies)extractRoutes(js, out);
                    List<Candidate> list=new ArrayList<>(out.values());
                    if(list.size()>48)list=list.subList(0, 48);
                    discoveredCache=List.copyOf(list);
                    discoveredAt=System.currentTimeMillis();
                    
                    return list;
                }
                );
            }
            );
        }
        ).exceptionally(e-> {
             return List.of();
        }
        );
    }
    private static CompletableFuture<List<String>> fetchAssets(List<String> assets) {
        if(assets==null||assets.isEmpty())return CompletableFuture.completedFuture(List.of());
        List<CompletableFuture<String>> fs=new ArrayList<>();
        for(String asset:assets)fs.add(Http.get(asset, 3).exceptionally(e-> {
             return "";
        }
        ));
        return CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)).thenApply(v->fs.stream().map(CompletableFuture::join).toList());
    }
    private static List<String> prioritizeAssets(Collection<String> assets) {
        List<String> out=new ArrayList<>(new LinkedHashSet<>(assets));
        out.sort(Comparator.comparingInt(ATiersProvider::assetPriority));
        return out;
    }
    private static int assetPriority(String u) {
        String s=u.toLowerCase(Locale.ROOT);
        if(s.contains("profile")||s.contains("player"))return 0;
        if(s.contains("api-")||s.contains("tier")||s.contains("search"))return 1;
        if(s.contains("index-")||s.contains("config-"))return 2;
        if(s.contains("leader")||s.contains("rank"))return 3;
        return 9;
    }
    private static void extractRoutes(String js, LinkedHashMap<String, Candidate> out) {
        if(js==null||js.isBlank())return;
        logContexts(js, "api.atiers.net", "ATIERS JS api-context=", 12, 760);
        logContexts(js, "tiertagger.atiers.net", "ATIERS JS tagger-context=", 8, 760);
        logContexts(js, "/api/v1/", "ATIERS JS v1-context=", 18, 900);
        logContexts(js, "nickname", "ATIERS JS nickname-context=", 10, 900);
        // ATiers exposes some API routes through frontend template literals, for example
        // `/api/v1/player/${encodeURIComponent(e)}`.
        Matcher templates=Pattern.compile("`([^`]{1,360})`").matcher(js);
        int templateLogged=0;
        while(templates.find()) {
            String lit=templates.group(1).replace("\\/", "/");
            String normalized=normalizeJsRouteTemplate(lit);
            if(normalized==null)continue;
            String low=normalized.toLowerCase(Locale.ROOT);
            if(!(low.contains("player")||low.contains("profile")||low.contains("ranking")||low.contains("leaderboard")||low.contains("tier")||low.contains("nickname")||low.contains("search")))continue;
            if(templateLogged++<35)
            addLiteral(normalized, out);
        }
        Matcher strings=Pattern.compile("[\"'`]([^\"'`]{1,260})[\"'`]").matcher(js);
        int logged=0;
        while(strings.find()) {
            String lit=strings.group(1).replace("\\/", "/");
            String low=lit.toLowerCase(Locale.ROOT);
            if(!(low.contains("player")||low.contains("profile")||low.contains("ranking")||low.contains("leaderboard")||low.contains("tier")||low.contains("username")||low.contains("nickname")||low.contains("search")))continue;
            if(!(lit.startsWith("/")||low.startsWith("https://api.atiers.net")||low.startsWith("https://tiertagger.atiers.net")))continue;
            if(logged++<45)
            addLiteral(lit, out);
        }
    }
    private static void logContexts(String js, String needle, String prefix, int limit, int span) {
        int from=0, shown=0;
        while((from=js.indexOf(needle, from))>=0&&shown++<limit) {
            int a=Math.max(0, from-span/3), b=Math.min(js.length(), from+needle.length()+span*2/3);
            
            from+=needle.length();
        }
    }
    /** Convert only simple/safe frontend template expressions into our neutral
    * {name} placeholder. Complex URLSearchParams expressions stay rejected. */ static String normalizeJsRouteTemplate(String literal) {
        if(literal==null)return null;
        String x=literal.replace("\\/", "/").trim();
        if(!x.contains("${"))return x;
        String low=x.toLowerCase(Locale.ROOT);
        if(!(low.contains("player")||low.contains("profile")||low.contains("nickname")||low.contains("search")||low.contains("leaderboard")))return null;
        // encodeURIComponent(variable) / encodeURIComponent(variable.trim()) is the
        // standard public-profile form in the minified ATiers bundle.
        x=x.replaceAll("\\$\\{encodeURIComponent\\([A-Za-z_$][A-Za-z0-9_$]*(?:\\.trim\\(\\))?\\)\\}", "{name}");
        // A bare single variable is accepted only in player/profile/search routes.
        x=x.replaceAll("\\$\\{[A-Za-z_$][A-Za-z0-9_$]*(?:\\.trim\\(\\))?\\}", "{name}");
        // Anything more complex (URLSearchParams.toString(), arithmetic, ternaries, etc.)
        // is intentionally not turned into a URI.
        if(x.contains("${"))return null;
        return x;
    }
    private static void addLiteral(String literal, LinkedHashMap<String, Candidate> out) {
        String lit=literal.trim();
        if(lit.length()>240||lit.contains(" ")||lit.indexOf('`')>=0)return;
        // Complex query expressions are not treated as URL templates. Keep them out of
        // the request path instead of guessing how the frontend evaluates them.
        if(lit.contains("${"))return;
        List<String> bases=List.of("https://api.atiers.net", "https://tiertagger.atiers.net");
        List<String> raw=new ArrayList<>();
        if(lit.startsWith("http://")||lit.startsWith("https://"))raw.add(lit);
        else if(lit.startsWith("/"))for(String base:bases)raw.add(base+lit);
        for(String u:raw) {
            u=u.replace(":username",
                "{name}").replace(":nickname",
                "{name}").replace(":name",
                "{name}").replace(":uuid",
                "{uuid}").replace("{username}",
                "{name}").replace("{nickname}",
                "{name}");
            if(!validTemplate(u))continue;
            boolean hasPlaceholder=u.contains("{name}")||u.contains("{uuid}")||u.contains("{uuid32}");
            String low=u.toLowerCase(Locale.ROOT);
            boolean bulk=low.contains("leaderboard")||(!hasPlaceholder&&(low.endsWith("/players")||low.endsWith("/rankings")||low.endsWith("/tiers")));
            add(out, new Candidate(u, bulk, "frontend-js"));
            if(!hasPlaceholder&&!bulk&&!u.contains("?")) {
                add(out, new Candidate(trimSlash(u)+"/{name}", false, "frontend-js+name"));
                add(out, new Candidate(trimSlash(u)+"/{uuid32}", false, "frontend-js+uuid"));
            }
        }
    }
    private static boolean validTemplate(String u) {
        if(u==null||u.isBlank()||u.contains("${")||u.indexOf('`')>=0||u.indexOf(' ')>=0||u.endsWith("?")||u.endsWith("&"))return false;
        String probe=u.replace("{name}", "Player_123").replace("{uuid}", "01234567-89ab-cdef-0123-456789abcdef").replace("{uuid32}", "0123456789abcdef0123456789abcdef");
        return validExpandedUrl(probe);
    }
    private static void add(Map<String, Candidate> out, Candidate c) {
        String u=c.template();
        if(!validTemplate(u))return;
        String low=u.toLowerCase(Locale.ROOT);
        if(low.matches(".*\\.(png|jpg|jpeg|svg|webp|css)(\\?.*)?$"))return;
        out.putIfAbsent(u, c);
    }
    private static String trimSlash(String s) {
        while(s.endsWith("/"))s=s.substring(0, s.length()-1);
        return s;
    }
    private static String absolute(String base, String p) {
        try {
            return URI.create(base).resolve(p).toString();
        } catch (Exception e) {
            return p;
        }
    }
    private static String compact(String s) {
        String x=s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        while(x.contains("  "))x=x.replace("  ", " ");
        return x.length()>520?x.substring(0, 520)+"…":x;
    }
    private static String compactLong(String s) {
        String x=s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        while(x.contains("  "))x=x.replace("  ", " ");
        return x.length()>1100?x.substring(0, 1100)+"…":x;
    }
}
