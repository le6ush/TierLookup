package com.tierlookup.service;

import com.tierlookup.client.BootstrapLog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.*;

import com.tierlookup.client.ClientFeatures;
import com.tierlookup.client.MinecraftBridge;
import com.tierlookup.model.*;
import com.tierlookup.net.MiniJson;
import com.tierlookup.provider.*;

public final class ProfileService {
    private final List<TierProvider> providers;
    private final TierLookupConfig config;
    private final ConcurrentHashMap<UUID, PlayerProfile> cache=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlayerProfile> live=new ConcurrentHashMap<>();
    private final ConcurrentSkipListMap<String, UUID> nameIndex=new ConcurrentSkipListMap<>();
    // Name-only mirror rows are temporary. A real TAB UUID replaces them when the player is observed.
    private final ConcurrentHashMap<String, UUID> syntheticNameIndex=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<String>> aliases=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<PlayerProfile>> inFlight=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<PlayerProfile>> diskWarmInFlight=new ConcurrentHashMap<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    // Only full profiles count against the RAM budget; lightweight indexes stay resident.
    private final ConcurrentHashMap<UUID, Long> ramAccess=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> ramWeight=new ConcurrentHashMap<>();
    private final AtomicLong ramEstimatedBytes=new AtomicLong();
    private volatile boolean ramBudgetSuspended;
    private final ExecutorService localReadExecutor=Executors.newSingleThreadExecutor(r-> {
        Thread t=new Thread(r, "TierLookup-LocalRead"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t;
    }
    );
    private final ConcurrentHashMap<UUID, Long> lastSeenAt=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastSeenWriteAt=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastSeenServer=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<UUID>> serverIndex=new ConcurrentHashMap<>();
    // Autocomplete uses a per-server prefix index instead of rescanning the roster for every key press.
    private final ConcurrentHashMap<String, ConcurrentSkipListMap<String, UUID>> serverNameIndex=new ConcurrentHashMap<>();
    private final Set<UUID> watchlist=ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Long> favoriteAddedAt=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> favoriteLastUsedAt=new ConcurrentHashMap<>();
    /** Last history timestamp the user has acknowledged for a watchlisted player. */
    private final ConcurrentHashMap<UUID, Long> watchSeenAt=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<TierHistoryEvent>> history=new ConcurrentHashMap<>();
    /** Keeps the latest history timestamp in memory for search/favorites markers. */
    private final ConcurrentHashMap<UUID, Long> historyLatestAt=new ConcurrentHashMap<>();
    /** History stays lazy; normal hover/search does not load it from SQLite. */
    private final Set<UUID> historyLoaded=ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, List<NotableStatus>> notable=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> notes=new ConcurrentHashMap<>();
    /** Hidden encounter metadata may change rapidly; it is accumulated in RAM and flushed outside hover. */
    private final Set<UUID> dirtySeen=ConcurrentHashMap.newKeySet();
    /** Provider history is loaded only from the Full Mode flow. */
    private final ConcurrentHashMap<String, Long> sourceHistoryAttemptAt=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> sourceHistoryInFlight=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SourceHistoryState> sourceHistoryState=new ConcurrentHashMap<>();
    /** Providers whose authoritative source history has already been imported and persisted. */
    private final ConcurrentHashMap<UUID, Set<String>> sourceHistoryLoaded=new ConcurrentHashMap<>();
    private static final long SOURCE_HISTORY_COOLDOWN_MS=60L*60_000L;
    public enum SourceHistoryState {
        UNSUPPORTED, IDLE, LOADING, LOADED, EMPTY, ERROR
    }
    private final ArrayDeque<UUID> recentSeen=new ArrayDeque<>();
    private final Object recentLock=new Object();
    private final ProviderEngine providerEngine;
    // Kept only for migration/recovery from older installations.
    private final Path cacheFile;
    private final Path legacyCacheFile;
    private final Path sqliteFile;
    private final Object diskLock=new Object();
    private volatile SqliteProfileStore sqlite;
    private volatile ProviderMirrorStore mirrorStore;
    private volatile boolean sqliteReady=false;
    public static final int STORAGE_SCHEMA=SqliteProfileStore.SCHEMA;
    public record SyncManifest(String providerId,
        String displayName,
        String status,
        long generation,
        String mode,
        long startedAt,
        long completedAt,
        int received,
        int rawReceived,
        int uniqueIdentities,
        int duplicateIdentities,
        int duplicatePages,
        int parsed,
        int rejected,
        int pages,
        int failedPages,
        int verifiedChecked,
        int verifiedGaps,
        int snapshotRows,
        int liveRows,
        int discoveryGaps,
        String terminationReason,
        String failedPageDetails,
        String rejectionSummary,
        String message) {
    }
    record MirrorRow(PlayerIdentity player, ProviderResult result) {
    }
    record MirrorReject(String reason, String raw) {
    }
    public ProfileService(List<TierProvider> providers, TierLookupConfig config) {
        this(providers,
            config,
            MinecraftBridge.gameDir().resolve("tierlists").resolve("players.json"),
            MinecraftBridge.configDir().resolve("tierlookup").resolve("profiles-cache.json"));
    }
    ProfileService(List<TierProvider> providers, TierLookupConfig config, Path cacheFile, Path legacyCacheFile) {
        this.providers=List.copyOf(providers);
        this.config=config;
        this.cacheFile=cacheFile;
        this.legacyCacheFile=legacyCacheFile;
        this.sqliteFile=(cacheFile.getParent()==null?Path.of("."):cacheFile.getParent()).resolve("tierlookup.db");
        this.providerEngine=new ProviderEngine(config);
        initializeStorage();
        rebuildRecentQueues();
    }
    public void close() {
        if(!closed.compareAndSet(false, true))return;
        providerEngine.close();
        localReadExecutor.shutdownNow();
        for(CompletableFuture<Boolean> f:sourceHistoryInFlight.values())if(f!=null&&!f.isDone())f.cancel(true);
        sourceHistoryInFlight.clear();
        flushSeenMetadata();
        flushDisk();
        if(sqlite!=null)try {
            sqlite.close();
        } catch (Exception e) {
            BootstrapLog.error("PROFILE sqlite close", e);
        }
        inFlight.clear();
        diskWarmInFlight.clear();
        live.clear();
    }
    public PlayerProfile cached(UUID id) {
        return id==null?null:cache.get(id);
    }
    public PlayerProfile cachedByName(String name) {
        if(name==null)return null;
        UUID id=nameIndex.get(normalizeName(name));
        return id==null?null:cache.get(id);
    }
    public PlayerProfile searchRam(String name, String currentServer) {
        return searchLocal(name, currentServer);
    }
    public long ramEstimatedBytes() {
        return ramEstimatedBytes.get();
    }
    public int ramCacheMb() {
        return config.ramCacheMb();
    }
    public void enforceRamBudgetNow() {
        trimRamToBudget();
    }
    /** Exact SQLite lookup used by explicit/offline searches; hits are promoted to the RAM cache. */
    public CompletableFuture<PlayerProfile> searchSqliteAsync(String name) {
        if(name==null||name.isBlank()||!sqliteReady||sqlite==null)return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(()->installLoaded(sqlite.loadByName(name)), localReadExecutor);
    }
    /** Warm one player observed in TAB from persistent storage without touching provider network. */
    public CompletableFuture<PlayerProfile> warmObservedFromSqliteAsync(PlayerIdentity observed) {
        if(observed==null||!sqliteReady||sqlite==null)return CompletableFuture.completedFuture(null);
        UUID key=observed.uuid();
        CompletableFuture<PlayerProfile> existing=diskWarmInFlight.get(key);
        if(existing!=null)return existing;
        CompletableFuture<PlayerProfile> created=CompletableFuture.supplyAsync(()-> {
            SqliteProfileStore.Loaded l=sqlite.loadByUuid(observed.uuid());
            if(l==null)l=sqlite.loadByName(observed.name());
            PlayerProfile p=installLoaded(l);
            if(p!=null)p=bindObservedIdentity(observed);
            return p;
        },localReadExecutor);
        CompletableFuture<PlayerProfile> race=diskWarmInFlight.putIfAbsent(key, created);
        CompletableFuture<PlayerProfile> out=race==null?created:race;
        if(race==null)created.whenComplete((r, e)->diskWarmInFlight.remove(key, created));
        return out;
    }
    private PlayerProfile installLoaded(SqliteProfileStore.Loaded l) {
        if(l==null)return null;
        synchronized(diskLock) {
            loadSqliteRecord(l);
            PlayerProfile p=cache.get(l.profile().player().uuid());
            touchRam(l.profile().player().uuid());
            trimRamToBudget();
            return p;
        }
    }
    /**
    * Current display snapshot for UI surfaces that must agree while an internet refresh is still in flight.
    * A transient live snapshot wins over the persisted RAM cache; this prevents TAB from showing an older
    * tier while K/Full already renders a newly arrived provider result. No disk or network work is started.
    */ public PlayerProfile profileForDisplay(UUID id, String name) {
        PlayerProfile p=id==null?null:live.get(id);
        if(p!=null)return p;
        p=id==null?null:cache.get(id);
        if(p!=null)return p;
        if(name==null)return null;
        UUID mapped=nameIndex.get(normalizeName(name));
        if(mapped==null)return null;
        p=live.get(mapped);
        return p!=null?p:cache.get(mapped);
    }
    /**
    * Bind a name-only bulk record to a real UUID observed directly in the current client world.
    * This is local-only: no resolver/provider request is started. A different non-synthetic UUID is never
    * merged by nickname, because nicknames can be reassigned and UUID remains the primary identity.
    */ public PlayerProfile bindObservedIdentity(PlayerIdentity observed) {
        if(observed==null)return null;
        synchronized(diskLock) {
            String key=normalizeName(observed.name());
            PlayerProfile exact=cache.get(observed.uuid());
            UUID syntheticId=syntheticNameIndex.get(key);
            PlayerProfile synthetic=syntheticId==null?null:cache.get(syntheticId);
            if(exact!=null) {
                if(!exact.player().name().equalsIgnoreCase(observed.name()))renameIdentity(exact, observed);
                exact=cache.get(observed.uuid());
                if(synthetic!=null&&!synthetic.player().uuid().equals(observed.uuid())) {
                    PlayerProfile merged=mergeSyntheticIntoReal(exact, synthetic, observed);
                    
                    return merged;
                }
                return exact;
            }
            PlayerProfile byName=cachedByName(observed.name());
            if(byName==null&&synthetic!=null)byName=synthetic;
            if(byName==null)return null;
            if(byName.player().uuid().equals(observed.uuid()))return byName;
            if(!isSynthetic(byName.player().uuid(), byName.player().name()))return null;
            UUID oldId=byName.player().uuid();
            promoteIdentity(byName, observed);
            PlayerProfile promoted=cache.get(observed.uuid());
            
            return promoted;
        }
    }
    private PlayerProfile mergeSyntheticIntoReal(PlayerProfile real, PlayerProfile synthetic, PlayerIdentity observed) {
        if(real==null)return synthetic;
        if(synthetic==null)return real;
        LinkedHashMap<String, ProviderResult> merged=new LinkedHashMap<>(real.providers());
        for(var e:synthetic.providers().entrySet()) {
            ProviderResult old=merged.get(e.getKey()), next=e.getValue();
            ProviderResult chosen=preferIdentityMergeResult(old, next);
            if(chosen!=null)merged.put(e.getKey(), chosen);
        }
        UUID sid=synthetic.player().uuid();
        cache.remove(sid);
        forgetRamAccounting(sid);
        syntheticNameIndex.remove(normalizeName(synthetic.player().name()), sid);
        transferAliases(sid, observed.uuid(), synthetic.player().name());
        transferMetadata(sid, observed.uuid());
        PlayerProfile out=new PlayerProfile(observed, merged, Math.max(real.fetchedAt(), synthetic.fetchedAt()));
        putMemory(out);
        if(sqliteReady&&sqlite!=null)sqlite.delete(sid);
        queuePersist(observed.uuid());
        return out;
    }
    /**
    * Identity coalescing must not let a newer empty/error lookup erase an older bulk tier assignment.
    * A real network refresh after the identity is bound may still authoritatively replace it; this rule is
    * only for merging two local identities that we have just proven are the same player.
    */ private static ProviderResult preferIdentityMergeResult(ProviderResult a, ProviderResult b) {
        if(a==null)return b;
        if(b==null)return a;
        int qa=identityMergeQuality(a), qb=identityMergeQuality(b);
        if(qa!=qb)return qb>qa?b:a;
        return b.fetchedAt()>=a.fetchedAt()?b:a;
    }
    private static int identityMergeQuality(ProviderResult r) {
        if(r==null)return -1;
        if(r.status()==ProviderResult.Status.OK&&r.tiers()!=null&&!r.tiers().isEmpty())return 4;
        if(r.status()==ProviderResult.Status.NOT_RANKED)return 3;
        if(r.status()==ProviderResult.Status.OK)return 2;
        if(r.status()==ProviderResult.Status.LOADING)return 1;
        return 0;
    }
    public int cachedCount() {
        return cache.size();
    }
    public List<PlayerProfile> allCachedProfiles() {
        ArrayList<PlayerProfile> out=new ArrayList<>(cache.values());
        out.sort(Comparator.comparing(a->a.player().name().toLowerCase(Locale.ROOT)));
        return List.copyOf(out);
    }
    public ProviderEngine providerEngine() {
        return providerEngine;
    }
    /** Stop active provider traffic while keeping the service/cache reusable. No SQLite data is cleared. */
    public void cancelActiveNetworkLookups() {
        providerEngine.cancelActive();
        live.clear();
    }
    public List<SyncManifest> syncManifests() {
        ProviderMirrorStore m=mirrorStore;
        if(!sqliteReady||m==null)return List.of();
        ArrayList<SyncManifest> out=new ArrayList<>();
        for(ProviderMirrorStore.Manifest x:m.manifests())out.add(new SyncManifest(x.providerId(),
            x.displayName(),
            x.status().name(),
            x.generation(),
            x.mode(),
            x.startedAt(),
            x.completedAt(),
            x.received(),
            x.rawReceived(),
            x.uniqueIdentities(),
            x.duplicateIdentities(),
            x.duplicatePages(),
            x.parsed(),
            x.rejected(),
            x.pages(),
            x.failedPages(),
            x.verifiedChecked(),
            x.verifiedGaps(),
            x.snapshotRows(),
            x.liveRows(),
            m.discoveryGapCount(x.providerId()),
            x.terminationReason(),
            x.failedPageDetails(),
            m.rejectionSummary(x.providerId(),
            2),
            x.message()));
        return List.copyOf(out);
    }
    long beginMirrorSync(String providerId, String displayName, String mode) {
        long generation=System.currentTimeMillis()*1000L+(Math.abs(providerId==null?0:providerId.hashCode())%997);
        ProviderMirrorStore m=mirrorStore;
        if(m!=null)m.begin(providerId, displayName, generation, mode);
        return generation;
    }
    void failMirrorSync(String providerId, String displayName, long generation, String mode, String message) {
        ProviderMirrorStore m=mirrorStore;
        if(m!=null)m.fail(providerId, displayName, generation, mode, message);
    }
    void commitMirrorSnapshot(String providerId,
        String displayName,
        long generation,
        String mode,
        String status,
        Collection<MirrorRow> rows,
        Collection<MirrorReject> rejects,
        int received,
        int rawReceived,
        int uniqueIdentities,
        int duplicateIdentities,
        int duplicatePages,
        int parsed,
        int rejected,
        int pages,
        int failedPages,
        int verifiedChecked,
        int verifiedGaps,
        int stagedRows,
        String terminationReason,
        String failedPageDetails,
        String message,
        Map<UUID,
        List<NotableStatus>> notable,
        boolean replaceMissing) {
        ProviderMirrorStore m=mirrorStore;
        if(m==null) {
            for(MirrorRow row:rows)mergeProvider(row.player(), row.result(), false);
            return;
        }
        flushDisk();
        ArrayList<ProviderMirrorStore.Row> sr=new ArrayList<>();
        for(MirrorRow row:rows)sr.add(new ProviderMirrorStore.Row(row.player(), row.result()));
        ArrayList<ProviderMirrorStore.Reject> rj=new ArrayList<>();
        for(MirrorReject r:rejects)rj.add(new ProviderMirrorStore.Reject(r.reason(), r.raw()));
        ProviderMirrorStore.Status st;
        try {
            st=ProviderMirrorStore.Status.valueOf(status);
        } catch (Exception e) {
            st=ProviderMirrorStore.Status.PARTIAL;
        }
        try {
            m.commit(providerId,
                displayName,
                generation,
                mode,
                st,
                sr,
                rj,
                received,
                rawReceived,
                uniqueIdentities,
                duplicateIdentities,
                duplicatePages,
                parsed,
                rejected,
                pages,
                failedPages,
                verifiedChecked,
                verifiedGaps,
                stagedRows,
                terminationReason,
                failedPageDetails,
                message,
                notable,
                replaceMissing);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }
    void reloadMirrorFromSqlite() {
        if(!sqliteReady||sqlite==null)return;
        flushDisk();
        try {
            List<SqliteProfileStore.Loaded> loaded=sqlite.loadAll();
            synchronized(diskLock) {
                ramBudgetSuspended=true;
                try {
                    clearMemoryOnly();
                    for(SqliteProfileStore.Loaded l:loaded)loadSqliteRecord(l);
                } finally {
                    ramBudgetSuspended=false;
                }
                trimRamToBudget();
            }
            rebuildRecentQueues();
            
        } catch (Throwable t) {
            ramBudgetSuspended=false;
            BootstrapLog.error("MIRROR RAM reload", t);
        }
    }
    int discoveryGapCount(String providerId) {
        ProviderMirrorStore m=mirrorStore;
        return m==null?0:m.discoveryGapCount(providerId);
    }
    public String lastSeenServer(UUID id) {
        return id==null?null:lastSeenServer.get(id);
    }
    public long lastSeenAt(UUID id) {
        return id==null?0:lastSeenAt.getOrDefault(id, 0L);
    }
    public boolean watchlisted(UUID id) {
        return id!=null&&watchlist.contains(id);
    }
    public void setWatchlisted(UUID id, boolean value) {
        if(id==null)return;
        long now=System.currentTimeMillis();
        if(value) {
            watchlist.add(id);
            favoriteAddedAt.putIfAbsent(id, now);
            favoriteLastUsedAt.put(id, now);
            watchSeenAt.putIfAbsent(id, latestHistoryAt(id));
        } else {
            watchlist.remove(id);
            favoriteAddedAt.remove(id);
            favoriteLastUsedAt.remove(id);
            watchSeenAt.remove(id);
        }
        queuePersist(id);
    }
    public void markFavoriteUsed(UUID id) {
        if(id==null||!watchlist.contains(id))return;
        favoriteLastUsedAt.put(id, System.currentTimeMillis());
        queuePersist(id);
    }
    public long latestHistoryAt(UUID id) {
        return id==null?0L:historyLatestAt.getOrDefault(id, 0L);
    }
    public TierHistoryEvent latestHistoryEvent(UUID id) {
        TierHistoryEvent best=null;
        for(TierHistoryEvent e:history(id))if(best==null||e.at()>best.at())best=e;
        return best;
    }
    public boolean hasWatchNews(UUID id) {
        return watchlisted(id)&&latestHistoryAt(id)>watchSeenAt.getOrDefault(id, 0L);
    }
    public void markWatchViewed(UUID id) {
        if(id==null||!watchlisted(id))return;
        watchSeenAt.put(id, Math.max(System.currentTimeMillis(), latestHistoryAt(id)));
        queuePersist(id);
    }
    public List<PlayerProfile> watchlistedProfiles() {
        ArrayList<PlayerProfile> out=new ArrayList<>();
        for(UUID id:watchlist) {
            PlayerProfile p=cache.get(id);
            if(p!=null)out.add(p);
        }
        out.sort(Comparator.comparing(a->a.player().name().toLowerCase(Locale.ROOT)));
        return List.copyOf(out);
    }
    public List<TierHistoryEvent> history(UUID id) {
        if(id==null)return List.of();
        if(!historyLoaded.contains(id)&&sqliteReady&&sqlite!=null) {
            List<TierHistoryEvent> loaded=sqlite.loadHistory(id);
            history.put(id, Collections.synchronizedList(new ArrayList<>(loaded)));
            historyLoaded.add(id);
            long latest=0;
            for(TierHistoryEvent e:loaded)latest=Math.max(latest, e.at());
            if(latest>0)historyLatestAt.merge(id, latest, Math::max);
        }
        List<TierHistoryEvent> h=history.get(id);
        if(h==null)return List.of();
        synchronized(h) {
            return List.copyOf(h);
        }
    }
    public List<NotableStatus> notableStatuses(UUID id) {
        List<NotableStatus> n=id==null?null:notable.get(id);
        return n==null?List.of():List.copyOf(n);
    }
    public NotableStatus primaryNotable(UUID id) {
        List<NotableStatus> statuses=id==null?null:notable.get(id);
        if(statuses==null)return null;
        NotableStatus best=null;
        for(NotableStatus n:statuses) {
            if(best==null||notablePriority(n.type())>notablePriority(best.type())||(n.type()==best.type()&&n.rank()>0&&(best.rank()<=0||n.rank()<best.rank())))best=n;
        }
        return best;
    }
    public void setNotableStatuses(UUID id, List<NotableStatus> statuses) {
        if(id==null)return;
        if(statuses==null||statuses.isEmpty())notable.remove(id);
        else notable.put(id, List.copyOf(statuses));
        queuePersist(id);
    }
    /** Replace one authoritative ranking slice without touching statuses coming from other sources. */
    public void replaceNotableRanking(NotableStatus.Type type, String source, Map<UUID, Integer> ranks) {
        if(type==null||source==null||source.isBlank())return;
        Map<UUID, Integer> clean=ranks==null?Map.of():ranks;
        long now=System.currentTimeMillis();
        LinkedHashSet<UUID> changed=new LinkedHashSet<>();
        for(UUID id:new ArrayList<>(notable.keySet())) {
            List<NotableStatus> old=notable.get(id);
            if(old==null)continue;
            ArrayList<NotableStatus> keep=new ArrayList<>();
            for(NotableStatus n:old)if(!(n.type()==type&&source.equals(n.source())))keep.add(n);
            if(keep.size()!=old.size()) {
                if(keep.isEmpty())notable.remove(id);
                else notable.put(id, List.copyOf(keep));
                changed.add(id);
            }
        }
        for(var e:clean.entrySet()) {
            UUID id=e.getKey();
            int rank=e.getValue()==null?0:e.getValue();
            if(id==null||rank<=0)continue;
            ArrayList<NotableStatus> list=new ArrayList<>(notableStatuses(id));
            list.add(new NotableStatus(type, rank, source, now));
            notable.put(id, List.copyOf(list));
            changed.add(id);
        }
        for(UUID id:changed)queuePersist(id);
    }
    /** Creator metadata comes from loaded source data, not nickname heuristics. */
    public void replaceNotableCreators(String source, Collection<UUID> ids) {
        LinkedHashMap<UUID, Integer> ranked=new LinkedHashMap<>();
        if(ids!=null)for(UUID id:ids)if(id!=null)ranked.put(id, 0);
        if(source==null||source.isBlank())return;
        long now=System.currentTimeMillis();
        LinkedHashSet<UUID> changed=new LinkedHashSet<>();
        for(UUID id:new ArrayList<>(notable.keySet())) {
            List<NotableStatus> old=notable.get(id);
            if(old==null)continue;
            ArrayList<NotableStatus> keep=new ArrayList<>();
            for(NotableStatus n:old)if(!(n.type()==NotableStatus.Type.CREATOR&&source.equals(n.source())))keep.add(n);
            if(keep.size()!=old.size()) {
                if(keep.isEmpty())notable.remove(id);
                else notable.put(id, List.copyOf(keep));
                changed.add(id);
            }
        }
        if(ids!=null)for(UUID id:ids) {
            if(id==null)continue;
            ArrayList<NotableStatus> list=new ArrayList<>(notableStatuses(id));
            list.add(new NotableStatus(NotableStatus.Type.CREATOR, 0, source, now));
            notable.put(id, List.copyOf(list));
            changed.add(id);
        }
        for(UUID id:changed)queuePersist(id);
    }
    private static int notablePriority(NotableStatus.Type t) {
        return switch(t) {
            case WORLD_LEGEND->3;
            case CIS_LEGEND->2;
            case CREATOR->1;
        };
    }
    private void ensureHistoryLoaded(UUID id) {
        if(id==null||historyLoaded.contains(id))return;
        synchronized(historyLoaded) {
            if(historyLoaded.contains(id))return;
            List<TierHistoryEvent> loaded=sqliteReady&&sqlite!=null?sqlite.loadHistory(id):List.of();
            history.put(id, Collections.synchronizedList(new ArrayList<>(loaded)));
            historyLoaded.add(id);
            long latest=0;
            for(TierHistoryEvent e:loaded)latest=Math.max(latest, e.at());
            if(latest>0)historyLatestAt.merge(id, latest, Math::max);
        }
    }
    /** Full Mode status; ATiers history remains local-only until its source API is dependable. */
    public SourceHistoryState sourceHistoryState(UUID id, String providerId) {
        if(!ClientFeatures.TIER_HISTORY_VISIBLE)return SourceHistoryState.UNSUPPORTED;
        if(id==null||providerId==null)return SourceHistoryState.UNSUPPORTED;
        if(!"cistiers".equals(providerId))return SourceHistoryState.UNSUPPORTED;
        String key=sourceHistoryKey(id, providerId);
        SourceHistoryState st=sourceHistoryState.get(key);
        if(st!=null)return st;
        Set<String> loaded=sourceHistoryLoaded.get(id);
        if(loaded!=null&&loaded.contains(providerId))return SourceHistoryState.LOADED;
        return SourceHistoryState.IDLE;
    }
    /**
    * Fetch exact CISTiers source history only after the user explicitly opens/clicks CISTiers history in Full Mode.
    * Repeated clicks are coalesced and errors/empty results are cooled down for one hour.
    */ public CompletableFuture<Boolean> ensureSourceHistory(UUID id, String providerId) {
        if(!ClientFeatures.TIER_HISTORY_VISIBLE)return CompletableFuture.completedFuture(false);
        if(closed.get()||id==null||!"cistiers".equals(providerId))return CompletableFuture.completedFuture(false);
        PlayerProfile profile=cache.get(id);
        if(profile==null)return CompletableFuture.completedFuture(false);
        String key=sourceHistoryKey(id, providerId);
        SourceHistoryState current=sourceHistoryState(id, providerId);
        if(current==SourceHistoryState.LOADED)return CompletableFuture.completedFuture(true);
        long now=System.currentTimeMillis(), last=sourceHistoryAttemptAt.getOrDefault(key, 0L);
        if(last>0&&now-last<SOURCE_HISTORY_COOLDOWN_MS&&(current==SourceHistoryState.ERROR||current==SourceHistoryState.EMPTY))return CompletableFuture.completedFuture(false);
        AtomicBoolean owner=new AtomicBoolean(false);
        CompletableFuture<Boolean> shared=sourceHistoryInFlight.computeIfAbsent(key, k-> {
            owner.set(true);
            sourceHistoryAttemptAt.put(k, System.currentTimeMillis());
            sourceHistoryState.put(k, SourceHistoryState.LOADING);
            return CisTiersHistoryClient.fetch(profile.player()).thenApply(payload-> {
                boolean nonEmpty=!payload.events().isEmpty(); synchronized(diskLock) {
                    importCisTiersSourceHistory(id, payload.events()); enrichCisTiersCurrentDates(id, payload.currentTierDates());
                }
                if(nonEmpty)sourceHistoryLoaded.computeIfAbsent(id, ignored->ConcurrentHashMap.newKeySet()).add(providerId);
                sourceHistoryState.put(k, nonEmpty?SourceHistoryState.LOADED:SourceHistoryState.EMPTY);
                if(nonEmpty||!payload.currentTierDates().isEmpty())queuePersist(id);
                return nonEmpty;
            }
            ).exceptionally(err-> {
                if(!(unwrapCompletion(err) instanceof CancellationException)) {
                    sourceHistoryState.put(k, SourceHistoryState.ERROR);
                    
                }
                return false;
            }
            );
        }
        );
        if(owner.get())shared.whenComplete((v, e)->sourceHistoryInFlight.remove(key, shared));
        return shared;
    }
    private static String sourceHistoryKey(UUID id, String providerId) {
        return id+"|"+providerId;
    }
    private void importCisTiersSourceHistory(UUID id, List<CisTiersHistoryClient.SourceEvent> source) {
        if(id==null||source==null)return;
        ensureHistoryLoaded(id);
        List<TierHistoryEvent> list=history.computeIfAbsent(id, k->Collections.synchronizedList(new ArrayList<>()));
        synchronized(list) {
            // Once the official full profile is available it is the authority for CISTiers history. Remove
            // approximate locally-observed CISTiers transitions to avoid duplicated dates in the Full panel.
            list.removeIf(e->"cistiers".equals(e.providerId()));
            HashMap<String, String> previous=new HashMap<>();
            HashSet<String> dedup=new HashSet<>();
            ArrayList<CisTiersHistoryClient.SourceEvent> ordered=new ArrayList<>(source);
            ordered.sort(Comparator.comparingLong(CisTiersHistoryClient.SourceEvent::at));
            for(CisTiersHistoryClient.SourceEvent e:ordered) {
                String old=previous.put(e.canonicalKit(), e.tier());
                String dk=e.canonicalKit()+"|"+e.tier()+"|"+e.at();
                if(!dedup.add(dk))continue;
                list.add(new TierHistoryEvent(e.at(), "cistiers", e.gamemode(), old, e.tier(), false, false));
            }
            list.sort(Comparator.comparingLong(TierHistoryEvent::at));
            if(list.size()>5000)list.subList(0, list.size()-5000).clear();
            long latest=0;
            for(TierHistoryEvent e:list)latest=Math.max(latest, e.at());
            if(latest>0)historyLatestAt.put(id, latest);
            else historyLatestAt.remove(id);
        }
    }
    private void enrichCisTiersCurrentDates(UUID id, Map<String, String> dates) {
        if(id==null||dates==null||dates.isEmpty())return;
        PlayerProfile p=cache.get(id);
        if(p==null)return;
        ProviderResult old=p.providers().get("cistiers");
        if(old==null||old.status()!=ProviderResult.Status.OK)return;
        ArrayList<TierEntry> tiers=new ArrayList<>();
        boolean changed=false;
        for(TierEntry t:old.tiers()) {
            String canonical=com.tierlookup.client.OverlayRenderer.canonicalKit(t.gamemode());
            String date=canonical==null?null:dates.get(canonical);
            String current=TierRank.normalize(t.currentTier());
            if(date!=null&&current!=null&&!date.equals(t.lastTest())) {
                tiers.add(new TierEntry(t.gamemode(), t.currentTier(), t.peakTier(), t.retired(), date));
                changed=true;
            } else tiers.add(t);
        }
        if(!changed)return;
        LinkedHashMap<String, ProviderResult> providers=new LinkedHashMap<>(p.providers());
        providers.put("cistiers", new ProviderResult(old.providerId(), old.displayName(), old.status(), List.copyOf(tiers), old.message(), old.fetchedAt()));
        putMemory(new PlayerProfile(p.player(), providers, p.fetchedAt()));
    }
    public record WatchNews(UUID playerId, String playerName, TierHistoryEvent event) {
    }
    public List<WatchNews> watchlistNews(int limit) {
        ArrayList<WatchNews> out=new ArrayList<>();
        for(UUID id:watchlist) {
            PlayerProfile p=cache.get(id);
            String name=p==null?id.toString().substring(0, 8):p.player().name();
            long seen=watchSeenAt.getOrDefault(id, 0L);
            for(TierHistoryEvent e:history(id))if(e.at()>seen)out.add(new WatchNews(id, name, e));
        }
        out.sort(Comparator.comparingLong((WatchNews n)->n.event().at()).reversed());
        return List.copyOf(out.subList(0, Math.min(Math.max(0, limit), out.size())));
    }
    /**
    * Strict RAM-only hover lookup. It performs no mutation, disk access, resolver work or provider work.
    * Exact UUID wins. If a proxy/offline server exposes a different runtime UUID, an exact nickname/alias
    * RAM-index hit is still allowed for DISPLAY ONLY. This fallback never promotes/merges identities.
    */ public PlayerProfile hoverLookup(PlayerIdentity p) {
        if(p==null)return null;
        PlayerProfile exact=cache.get(p.uuid());
        if(exact!=null)return exact;
        UUID id=nameIndex.get(normalizeName(p.name()));
        if(id==null)return null;
        return cache.get(id);
    }
    /** RAM-only encounter metadata used by hover. Persistence happens elsewhere. */
    public void markSeenMemoryOnly(PlayerIdentity p, String server) {
        if(p==null)return;
        long now=System.currentTimeMillis();
        UUID id=p.uuid();
        PlayerProfile exact=cache.get(id);
        PlayerProfile fallback=exact==null?hoverLookup(p):exact;
        if(fallback==null) {
            putMemory(new PlayerProfile(p, Map.of(), 0L));
            fallback=cache.get(id);
        }
        UUID stored=fallback==null?id:fallback.player().uuid();
        touchRam(stored);
        lastSeenAt.put(stored, now);
        String key=normalizeServer(server);
        if(key!=null) {
            String old=lastSeenServer.put(stored, key);
            if(old!=null&&!old.equals(key))removeServerIndex(old, stored);
            addServerIndex(key, stored);
        }
        touchRecent(stored);
        dirtySeen.add(stored);
    }
    /** Explicit/workspace encounter: may reconcile a synthetic identity and queues one incremental SQLite row set. */
    public void markSeen(PlayerIdentity p, String server) {
        if(p==null)return;
        PlayerProfile bound=bindObservedIdentity(p);
        if(bound==null) {
            putMemory(new PlayerProfile(p, Map.of(), 0L));
            bound=cache.get(p.uuid());
        }
        UUID id=bound==null?p.uuid():bound.player().uuid();
        long now=System.currentTimeMillis();
        touchRam(id);
        lastSeenAt.put(id, now);
        lastSeenWriteAt.put(id, now);
        String key=normalizeServer(server);
        if(key!=null) {
            String old=lastSeenServer.put(id, key);
            if(old!=null&&!old.equals(key))removeServerIndex(old, id);
            addServerIndex(key, id);
        }
        touchRecent(id);
        dirtySeen.remove(id);
        queueSeenPersist(id);
    }
    public void flushSeenMetadata() {
        if(dirtySeen.isEmpty())return;
        for(UUID id:new ArrayList<>(dirtySeen))if(dirtySeen.remove(id))queueSeenPersist(id);
    }
    /** Exact local lookup is a direct nickname/alias index hit. Priority queues are used for prefix discovery, never O(N) exact scans. */
    public PlayerProfile searchLocal(String name, String currentServer) {
        String q=normalizeName(name);
        if(q.isEmpty())return null;
        UUID id=nameIndex.get(q);
        PlayerProfile p=id==null?null:cache.get(id);
        if(p!=null)touchRam(id);
        return p;
    }
    private void touchRecent(UUID id) {
        synchronized(recentLock) {
            recentSeen.remove(id);
            recentSeen.addFirst(id);
            while(recentSeen.size()>TierLookupConfig.RECENT_SEEN_SIZE)recentSeen.removeLast();
        }
    }
    private void rebuildRecentQueues() {
        ArrayList<UUID> ids=new ArrayList<>(cache.keySet());
        ids.sort(Comparator.comparingLong((UUID id)->lastSeenAt.getOrDefault(id, 0L)).reversed());
        synchronized(recentLock) {
            recentSeen.clear();
            for(UUID id:ids) {
                if(lastSeenAt.getOrDefault(id, 0L)<=0)continue;
                if(recentSeen.size()>=TierLookupConfig.RECENT_SEEN_SIZE)break;
                recentSeen.addLast(id);
            }
        }
    }
    public List<String> autocomplete(String prefix, int limit) {
        return autocomplete(prefix, limit, null);
    }
    public List<String> autocomplete(String prefix, int limit, String currentServer) {
        String q=normalizeName(prefix);
        if(q.isEmpty()||limit<=0)return List.of();
        LinkedHashSet<String> out=new LinkedHashSet<>();
        HashSet<UUID> seen=new HashSet<>();
        ArrayList<UUID> recentSnapshot;
        synchronized(recentLock) {
            recentSnapshot=new ArrayList<>(recentSeen);
        }
        int hotEnd=Math.min(TierLookupConfig.HOT_RECENT_SIZE, recentSnapshot.size());
        for(int i=0; i<hotEnd&&out.size()<limit; i++)addAutocomplete(recentSnapshot.get(i), q, out, seen, limit);
        String serverKey=normalizeServer(currentServer);
        ConcurrentSkipListMap<String, UUID> sameNames=serverKey==null?null:serverNameIndex.get(serverKey);
        if(out.size()<limit&&sameNames!=null) {
            int inspected=0;
            for(var e:sameNames.tailMap(q, true).entrySet()) {
                if(!e.getKey().startsWith(q))break;
                addAutocomplete(e.getValue(), q, out, seen, limit);
                if(++inspected>=128||out.size()>=limit)break;
            }
        }
        if(out.size()<limit)for(int i=hotEnd; i<recentSnapshot.size()&&out.size()<limit; i++)addAutocomplete(recentSnapshot.get(i), q, out, seen, limit);
        if(out.size()<limit) {
            int inspected=0;
            for(var e:nameIndex.tailMap(q, true).entrySet()) {
                if(!e.getKey().startsWith(q))break;
                addAutocomplete(e.getValue(), q, out, seen, limit);
                if(++inspected>=256||out.size()>=limit)break;
            }
        }
        // Fuzzy matching is deliberately limited to the tiny notable-player index, never the full 100k-player cache.
        if(out.size()<limit&&q.length()>=3) {
            ArrayList<PlayerProfile> candidates=new ArrayList<>();
            for(UUID id:notable.keySet()) {
                PlayerProfile p=cache.get(id);
                if(p!=null)candidates.add(p);
            }
            candidates.sort(Comparator.comparingInt(p->editDistanceBounded(q, normalizeName(p.player().name()), 3)));
            for(PlayerProfile p:candidates) {
                if(out.size()>=limit)break;
                String n=normalizeName(p.player().name());
                if(editDistanceBounded(q, n, 3)<=2||n.startsWith(q)||q.startsWith(n))out.add(p.player().name());
            }
        }
        return List.copyOf(out);
    }
    private static int editDistanceBounded(String a, String b, int stop) {
        if(a==null||b==null)return stop;
        if(Math.abs(a.length()-b.length())>=stop)return stop;
        int[] prev=new int[b.length()+1], cur=new int[b.length()+1];
        for(int j=0; j<=b.length(); j++)prev[j]=j;
        for(int i=1; i<=a.length(); i++) {
            cur[0]=i;
            int row=cur[0];
            for(int j=1; j<=b.length(); j++) {
                int v=Math.min(Math.min(prev[j]+1, cur[j-1]+1), prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
                cur[j]=v;
                row=Math.min(row, v);
            }
            if(row>=stop)return stop;
            int[] t=prev;
            prev=cur;
            cur=t;
        }
        return Math.min(stop, prev[b.length()]);
    }
    private void addAutocomplete(UUID id, String q, LinkedHashSet<String> out, Set<UUID> seen, int limit) {
        if(out.size()>=limit||!seen.add(id))return;
        PlayerProfile p=cache.get(id);
        if(p==null)return;
        String display=null;
        if(normalizeName(p.player().name()).startsWith(q))display=p.player().name();
        else {
            Set<String>a=aliases.get(id);
            if(a!=null)synchronized(a) {
                for(String n:a)if(normalizeName(n).startsWith(q)) {
                    display=n;
                    break;
                }
            }
        }
        if(display!=null)out.add(display);
    }
    public List<String> aliases(UUID id) {
        Set<String> a=aliases.get(id);
        if(a==null)return List.of();
        synchronized(a) {
            return List.copyOf(a);
        }
    }
    /** Recent players for the empty K command center. Hot recent keeps the same priority as autocomplete. */
    public List<PlayerProfile> recentProfiles(int limit) {
        if(limit<=0)return List.of();
        LinkedHashSet<UUID> ids=new LinkedHashSet<>();
        synchronized(recentLock) {
            ids.addAll(recentSeen);
        }
        ArrayList<PlayerProfile> out=new ArrayList<>();
        for(UUID id:ids) {
            PlayerProfile p=cache.get(id);
            if(p!=null) {
                out.add(p);
                if(out.size()>=limit)break;
            }
        }
        return List.copyOf(out);
    }
    /** Stable local identity for bulk sources that expose names but no Minecraft UUID. */
    public static UUID syntheticUuid(String name) {
        return UUID.nameUUIDFromBytes(("tierlookup:name:"+normalizeName(name)).getBytes(StandardCharsets.UTF_8));
    }
    public static boolean isSynthetic(UUID id, String name) {
        return id!=null&&id.equals(syntheticUuid(name));
    }
    public boolean providerFresh(String name, String providerId, long ttlMs) {
        PlayerProfile p=cachedByName(name);
        if(p==null)return false;
        ProviderResult r=p.providers().get(providerId);
        if(r==null)return false;
        if(!usableCached(r))return false;
        return fresh(r.fetchedAt(), ttlMs);
    }
    public boolean providerFresh(UUID id, String providerId, long ttlMs) {
        PlayerProfile p=cached(id);
        if(p==null)return false;
        ProviderResult r=p.providers().get(providerId);
        return usableCached(r)&&fresh(r.fetchedAt(), ttlMs);
    }
    /** Passive TAB enrichment only fills missing enabled sources. */
    public boolean needsInitialCoverage(PlayerIdentity identity) {
        if(!config.normalRuntimeNetworkAllowed()||identity==null)return false;
        PlayerProfile p=cached(identity.uuid());
        if(p==null)p=cachedByName(identity.name());
        if(p==null)return true;
        for(TierProvider pr:providers) {
            if(!config.enabled(pr.id()))continue;
            ProviderResult r=p.providers().get(pr.id());
            if(!usableCached(r))return true;
        }
        return false;
    }
    /** Explicit/manual refresh policy may use TTL; passive TAB does not call this just because data aged. */
    public boolean needsRefresh(PlayerIdentity identity, long ttlMs) {
        if(!config.normalRuntimeNetworkAllowed())return false;
        if(identity==null)return false;
        PlayerProfile p=cached(identity.uuid());
        if(p==null)p=cachedByName(identity.name());
        if(p==null)return true;
        for(TierProvider pr:providers) {
            if(!config.enabled(pr.id()))continue;
            ProviderResult r=p.providers().get(pr.id());
            if(!usableCached(r)||!fresh(r.fetchedAt(), ttlMs))return true;
        }
        return false;
    }
    private static boolean fresh(long at, long ttl) {
        return at>0&&System.currentTimeMillis()-at<Math.max(1, ttl);
    }
    public CompletableFuture<PlayerProfile> lookup(PlayerIdentity player, boolean force) {
        return lookup(player, force, null);
    }
    public CompletableFuture<PlayerProfile> lookup(PlayerIdentity player, boolean force, Consumer<PlayerProfile> progress) {
        if(player==null)return CompletableFuture.failedFuture(new IllegalArgumentException("player is null"));
        if(closed.get())return CompletableFuture.failedFuture(new CancellationException("profile service closed"));
        if(!config.normalRuntimeNetworkAllowed()) {
            PlayerProfile local=profileForDisplay(player.uuid(), player.name());
            String mode=config.dataModeId();
            if(hasProfileData(local))return CompletableFuture.completedFuture(local);
            if(config.offlineMode())return warmObservedFromSqliteAsync(player).thenCompose(p->hasProfileData(p)?CompletableFuture.completedFuture(p):CompletableFuture.failedFuture(new IllegalStateException(mode+": profile missing from local data")));
            return CompletableFuture.failedFuture(new IllegalStateException(mode+": profile missing from local data"));
        }
        PlayerProfile byId=cache.get(player.uuid());
        if(byId!=null&&!byId.player().name().equalsIgnoreCase(player.name()))renameIdentity(byId, player);
        PlayerProfile byName=cachedByName(player.name());
        // Only name-only synthetic bulk identities may be promoted by a nickname match. Never merge two real
        // UUIDs merely because a nickname currently matches; names can be reassigned.
        if(byName!=null&&!byName.player().uuid().equals(player.uuid())&&isSynthetic(byName.player().uuid(), byName.player().name()))promoteIdentity(byName, player);
        if(!force) {
            PlayerProfile c=cached(player.uuid());
            if(c==null)c=cachedByName(player.name());
            if(c!=null) {
                safeProgress(progress, c);
                return CompletableFuture.completedFuture(c);
            }
        }
        AtomicBoolean owner=new AtomicBoolean(false);
        UUID requestId=player.uuid();
        CompletableFuture<PlayerProfile> shared=inFlight.computeIfAbsent(requestId, id-> {
            owner.set(true); return doLookup(player, progress);
        }
        );
        if(owner.get())shared.whenComplete((r, e)-> {
            inFlight.remove(requestId, shared); live.remove(requestId);
        }
        );
        else attachSharedProgress(requestId, shared, progress);
        return shared;
    }
    private CompletableFuture<PlayerProfile> doLookup(PlayerIdentity p, Consumer<PlayerProfile> progress) {
        LinkedHashMap<String, ProviderResult> state=new LinkedHashMap<>();
        PlayerProfile old=cached(p.uuid());
        if(old==null)old=cachedByName(p.name());
        if(old!=null)state.putAll(old.providers());
        final Map<String, ProviderResult> previousResults=old==null?Map.of():new LinkedHashMap<>(old.providers());
        List<TierProvider> enabled=new ArrayList<>();
        // Cache-first rendering: keep a usable stored result visible while it refreshes. Only a
        // provider with no usable cache becomes LOADING. This prevents the whole matrix from
        // disappearing just because one upstream is slow.
        for(TierProvider pr:providers) {
            if(!config.enabled(pr.id()))state.put(pr.id(),
                new ProviderResult(pr.id(),
                pr.displayName(),
                ProviderResult.Status.DISABLED,
                List.of(),
                null,
                System.currentTimeMillis()));
            else {
                ProviderResult cached=previousResults.get(pr.id());
                state.put(pr.id(), usableCached(cached)?cached:ProviderResult.loading(pr.id(), pr.displayName()));
                enabled.add(pr);
            }
        }
        publish(p, state, progress);
        if(enabled.isEmpty()) {
            PlayerProfile fin=new PlayerProfile(p, new LinkedHashMap<>(state), System.currentTimeMillis());
            store(fin, true);
            return CompletableFuture.completedFuture(fin);
        }
        Object lock=new Object();
        List<CompletableFuture<?>> futures=new ArrayList<>();
        for(TierProvider pr:providerEngine.ordered(enabled)) {
            CompletableFuture<ProviderResult> f=providerEngine.lookup(pr, p).handle((r, e)-> {
                ProviderResult result=r; if(e!=null||result==null)result=ProviderResult.error(pr.id(), pr.displayName(), e==null?"Unavailable":rootMessage(e)); synchronized(lock) {
                    ProviderResult before=previousResults.get(pr.id()); if(result.status()==ProviderResult.Status.ERROR&&usableCached(before)) {
                         state.put(pr.id(), before);
                    } else {
                        recordHistory(p.uuid(), before, result); state.put(pr.id(), result);
                    }
                    publish(p, state, progress);
                }
                return result;
            }
            );
            futures.add(f);
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).handle((v, e)-> {
            PlayerProfile fin; synchronized(lock) {
                fin=new PlayerProfile(p, new LinkedHashMap<>(state), System.currentTimeMillis());
            }
            store(fin, true); if(progress!=null)safeProgress(progress, fin); return fin;
        }
        );
    }
    /**
    * Refresh only enabled providers whose stored result is missing/stale. This is the normal explicit-search
    * path: a single old source must never fan out into requests to every already-fresh tierlist.
    */ public CompletableFuture<PlayerProfile> refreshStale(PlayerIdentity p, long ttlMs, Consumer<PlayerProfile> progress) {
        if(p==null)return CompletableFuture.failedFuture(new IllegalArgumentException("player is null"));
        if(closed.get())return CompletableFuture.failedFuture(new CancellationException("profile service closed"));
        if(!config.normalRuntimeNetworkAllowed()) {
            PlayerProfile local=profileForDisplay(p.uuid(), p.name());
            if(hasProfileData(local)) {
                safeProgress(progress, local);
                return CompletableFuture.completedFuture(local);
            }
            if(config.offlineMode())return warmObservedFromSqliteAsync(p).thenCompose(disk-> {
                if(hasProfileData(disk)) {
                    safeProgress(progress, disk); return CompletableFuture.completedFuture(disk);
                }
                return CompletableFuture.failedFuture(new IllegalStateException(config.dataModeId()+": profile missing from local data"));
            }
            );
            return CompletableFuture.failedFuture(new IllegalStateException(config.dataModeId()+": profile missing from local data"));
        }
        PlayerProfile base=cached(p.uuid());
        if(base==null)base=cachedByName(p.name());
        if(base==null)return lookup(p, true, progress);
        ArrayList<TierProvider> stale=new ArrayList<>();
        long now=System.currentTimeMillis();
        for(TierProvider pr:providers) {
            if(!config.enabled(pr.id()))continue;
            ProviderResult r=base.providers().get(pr.id());
            if(!usableCached(r)||r.fetchedAt()<=0||now-r.fetchedAt()>=Math.max(1, ttlMs))stale.add(pr);
        }
        if(stale.isEmpty()) {
            safeProgress(progress, base);
            return CompletableFuture.completedFuture(base);
        }
        PlayerProfile refreshBase=base;
        AtomicBoolean owner=new AtomicBoolean(false);
        UUID requestId=refreshBase.player().uuid();
        CompletableFuture<PlayerProfile> shared=inFlight.computeIfAbsent(requestId, id-> {
            owner.set(true); return doRefreshStale(p, refreshBase, List.copyOf(stale), progress);
        }
        );
        if(owner.get())shared.whenComplete((r, e)-> {
            inFlight.remove(requestId, shared); live.remove(requestId);
        }
        );
        else attachSharedProgress(requestId, shared, progress);
        return shared;
    }
    private CompletableFuture<PlayerProfile> doRefreshStale(PlayerIdentity requested, PlayerProfile base, List<TierProvider> stale, Consumer<PlayerProfile> progress) {
        LinkedHashMap<String, ProviderResult> state=new LinkedHashMap<>(base.providers());
        Object lock=new Object();
        ArrayList<CompletableFuture<?>> futures=new ArrayList<>();
        PlayerIdentity target=base.player();
        for(TierProvider pr:providerEngine.ordered(stale)) {
            ProviderResult before=state.get(pr.id());
            CompletableFuture<ProviderResult> f=providerEngine.lookup(pr, requested).handle((r, e)-> {
                ProviderResult result=r; if(e!=null||result==null)result=ProviderResult.error(pr.id(), pr.displayName(), e==null?"Unavailable":rootMessage(e)); synchronized(lock) {
                    if(result.status()==ProviderResult.Status.ERROR&&usableCached(before)) {
                        
                    } else {
                        recordHistory(target.uuid(), before, result); state.put(pr.id(), result);
                    }
                    publish(target, state, progress);
                }
                return result;
            }
            );
            futures.add(f);
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).handle((v, e)-> {
            PlayerProfile fin; synchronized(lock) {
                fin=new PlayerProfile(target, new LinkedHashMap<>(state), System.currentTimeMillis());
            }
            store(fin, true); safeProgress(progress, fin); return fin;
        }
        );
    }
    private void attachSharedProgress(UUID id, CompletableFuture<PlayerProfile> shared, Consumer<PlayerProfile> progress) {
        if(progress==null||shared==null)return;
        PlayerProfile snapshot=live.get(id);
        if(snapshot!=null)safeProgress(progress, snapshot);
        shared.thenAccept(p->safeProgress(progress, p));
    }
    private static boolean usableCached(ProviderResult r) {
        return r!=null&&(r.status()==ProviderResult.Status.OK||r.status()==ProviderResult.Status.NOT_RANKED);
    }
    private static boolean hasProfileData(PlayerProfile p) {
        return p!=null&&p.providers()!=null&&!p.providers().isEmpty();
    }
    /** Internal hook for refreshing a single provider. */
    public CompletableFuture<PlayerProfile> refreshProvider(PlayerIdentity identity, String providerId) {
        if(identity==null||providerId==null)return CompletableFuture.failedFuture(new IllegalArgumentException("player/provider required"));
        TierProvider provider=null;
        for(TierProvider p:providers)if(providerId.equals(p.id())) {
            provider=p;
            break;
        }
        if(provider==null)return CompletableFuture.failedFuture(new IllegalArgumentException("unknown provider: "+providerId));
        TierProvider selected=provider;
        return providerEngine.lookup(selected,
            identity).handle((r,
            e)->e==null&&r!=null?r:ProviderResult.error(selected.id(),
            selected.displayName(),
            e==null?"Unavailable":rootMessage(e))).thenApply(r->mergeProvider(identity,
            r,
            false));
    }
    /** Merge one source into a player record without destroying data from other tierlists. */
    public PlayerProfile mergeProvider(PlayerIdentity identity, ProviderResult result, boolean saveNow) {
        if(identity==null||result==null)return null;
        synchronized(diskLock) {
            PlayerProfile existing=cache.get(identity.uuid());
            if(existing==null)existing=cachedByName(identity.name());
            PlayerIdentity target=identity;
            LinkedHashMap<String, ProviderResult> map=new LinkedHashMap<>();
            long fetched=System.currentTimeMillis();
            boolean identityCoalescing=false;
            if(existing!=null) {
                map.putAll(existing.providers());
                fetched=Math.max(existing.fetchedAt(), fetched);
                // A real UUID always replaces the temporary name-only UUID from bulk imports. During this
                // one-time identity coalescing step, informative ranked data wins over a newer empty/not-ranked
                // row. A later refresh on the already-bound real UUID remains authoritative as usual.
                if(isSynthetic(existing.player().uuid(), existing.player().name())&&!isSynthetic(identity.uuid(), identity.name())) {
                    identityCoalescing=true;
                    cache.remove(existing.player().uuid());
                    forgetRamAccounting(existing.player().uuid());
                    transferAliases(existing.player().uuid(), identity.uuid(), existing.player().name());
                    transferMetadata(existing.player().uuid(), identity.uuid());
                    target=identity;
                } else if(!isSynthetic(existing.player().uuid(), existing.player().name())&&isSynthetic(identity.uuid(), identity.name())) {
                    identityCoalescing=true;
                    target=existing.player();
                } else target=identity;
            }
            ProviderResult before=map.get(result.providerId());
            if(before==null&&result.status()==ProviderResult.Status.OK&&mirrorStore!=null)mirrorStore.recordDiscoveryGap(result.providerId(), target.uuid(), target.name());
            if(identityCoalescing&&before!=null) {
                ProviderResult chosen=preferIdentityMergeResult(before, result);
                if(chosen==before&&chosen!=result)
                if(chosen!=before) {
                    recordHistory(target.uuid(), before, chosen);
                    map.put(result.providerId(), chosen);
                }
            } else if(result.status()==ProviderResult.Status.ERROR&&usableCached(before)) {
                
            } else {
                recordHistory(target.uuid(), before, result);
                map.put(result.providerId(), result);
            }
            PlayerProfile merged=new PlayerProfile(target, map, fetched);
            putMemory(merged);
            queuePersist(merged.player().uuid());
            if(saveNow)flushDisk();
            return merged;
        }
    }
    public void flushDisk() {
        flushSeenMetadata();
        if(sqliteReady&&sqlite!=null)sqlite.flush();
    }
    /** One-shot bulk writer used after explicit sync; still uses WAL+NORMAL durability. */
    public void flushDiskFast() {
        flushSeenMetadata();
        if(sqliteReady&&sqlite!=null)sqlite.flushBulk();
    }
    private void publish(PlayerIdentity p, LinkedHashMap<String, ProviderResult> state, Consumer<PlayerProfile> progress) {
        PlayerProfile snap=new PlayerProfile(p, new LinkedHashMap<>(state), System.currentTimeMillis());
        live.put(p.uuid(), snap);
        if(progress!=null)safeProgress(progress, snap);
    }
    private void store(PlayerProfile p, boolean save) {
        synchronized(diskLock) {
            PlayerProfile old=cachedByName(p.player().name());
            if(old!=null&&!old.player().uuid().equals(p.player().uuid())&&isSynthetic(old.player().uuid(), old.player().name())) {
                cache.remove(old.player().uuid());
                forgetRamAccounting(old.player().uuid());
                transferAliases(old.player().uuid(), p.player().uuid(), old.player().name());
                transferMetadata(old.player().uuid(), p.player().uuid());
            }
            putMemory(p);
        }
        if(save)queuePersist(p.player().uuid());
    }
    /** Merge duplicate UUID records found in older/user-edited players.json instead of last-write-wins data loss. */
    private void putLoadedMemory(PlayerProfile p) {
        PlayerProfile old=cache.get(p.player().uuid());
        if(old==null) {
            putMemory(p);
            return;
        }
        LinkedHashMap<String, ProviderResult> merged=new LinkedHashMap<>(old.providers());
        for(var e:p.providers().entrySet()) {
            ProviderResult prev=merged.get(e.getKey()), next=e.getValue();
            ProviderResult chosen=preferIdentityMergeResult(prev, next);
            if(chosen!=null)merged.put(e.getKey(), chosen);
        }
        String name=p.fetchedAt()>=old.fetchedAt()?p.player().name():old.player().name();
        if(!old.player().name().equalsIgnoreCase(name))rememberAlias(p.player().uuid(), old.player().name());
        if(!p.player().name().equalsIgnoreCase(name))rememberAlias(p.player().uuid(), p.player().name());
        putMemory(new PlayerProfile(new PlayerIdentity(p.player().uuid(), name), merged, Math.max(old.fetchedAt(), p.fetchedAt())));
    }
    private void putMemory(PlayerProfile p) {
        if(p==null||p.player()==null||p.player().uuid()==null)return;
        UUID id=p.player().uuid();
        PlayerProfile previous=cache.put(id, p);
        if(previous!=null&&!previous.player().name().equalsIgnoreCase(p.player().name()))rememberAlias(id, previous.player().name());
        String normalized=normalizeName(p.player().name());
        nameIndex.put(normalized, id);
        if(isSynthetic(id, p.player().name()))syntheticNameIndex.put(normalized, id);
        else syntheticNameIndex.remove(normalized, id);
        Set<String> a=aliases.get(id);
        if(a!=null)synchronized(a) {
            for(String name:a)nameIndex.put(normalizeName(name), id);
        }
        refreshServerNames(id);
        int weight=estimateProfileBytes(p, a);
        Integer before=ramWeight.put(id, weight);
        ramEstimatedBytes.addAndGet(weight-(before==null?0:before));
        ramAccess.putIfAbsent(id, Math.max(1L, p.fetchedAt()));
        if(!ramBudgetSuspended&&ramEstimatedBytes.get()>config.ramCacheBytes()+Math.max(1L, config.ramCacheBytes()/20L))trimRamToBudget();
    }
    private void touchRam(UUID id) {
        if(id!=null&&cache.containsKey(id))ramAccess.put(id, System.currentTimeMillis());
    }
    private static int estimateProfileBytes(PlayerProfile p, Set<String> aliases) {
        long n=320L+chars(p.player().name());
        for(ProviderResult r:p.providers().values()) {
            n+=176L+chars(r.providerId())+chars(r.displayName())+chars(r.message());
            for(TierEntry t:r.tiers())n+=136L+chars(t.gamemode())+chars(t.currentTier())+chars(t.peakTier())+chars(t.lastTest());
        }
        if(aliases!=null)synchronized(aliases) {
            for(String a:aliases)n+=48L+chars(a);
        }
        return (int)Math.min(Integer.MAX_VALUE, Math.max(256L, n));
    }
    private static long chars(String s) {
        return s==null?0L:40L+(long)s.length()*2L;
    }
    private void forgetRamAccounting(UUID id) {
        Integer w=ramWeight.remove(id);
        if(w!=null)ramEstimatedBytes.addAndGet(-w);
        ramAccess.remove(id);
    }
    private void trimRamToBudget() {
        if(ramBudgetSuspended)return;
        long budget=config.ramCacheBytes();
        if(ramEstimatedBytes.get()<=budget)return;
        long target=Math.max(1L, budget*9L/10L);
        ArrayList<UUID> ids=new ArrayList<>(cache.keySet());
        ids.sort(Comparator.comparingLong(id->ramAccess.getOrDefault(id, 0L)));
        for(UUID id:ids) {
            if(ramEstimatedBytes.get()<=target)break;
            if(watchlist.contains(id)||live.containsKey(id)||inFlight.containsKey(id))continue;
            evictRamProfile(id);
        }
    }
    private boolean evictRamProfile(UUID id) {
        PlayerProfile p=cache.remove(id);
        if(p==null)return false;
        Integer w=ramWeight.remove(id);
        if(w!=null)ramEstimatedBytes.addAndGet(-w);
        ramAccess.remove(id);
        live.remove(id);
        nameIndex.remove(normalizeName(p.player().name()), id);
        syntheticNameIndex.remove(normalizeName(p.player().name()), id);
        Set<String> a=aliases.remove(id);
        if(a!=null)synchronized(a) {
            for(String name:a)nameIndex.remove(normalizeName(name), id);
        }
        String srv=lastSeenServer.get(id);
        if(srv!=null) {
            ConcurrentSkipListMap<String, UUID> idx=serverNameIndex.get(srv);
            if(idx!=null) {
                idx.entrySet().removeIf(e->id.equals(e.getValue()));
                if(idx.isEmpty())serverNameIndex.remove(srv, idx);
            }
        }
        synchronized(recentLock) {
            recentSeen.remove(id);
        }
        return true;
    }
    private void rememberAlias(UUID id, String name) {
        if(id==null||name==null||name.isBlank())return;
        PlayerProfile p=cache.get(id);
        if(p!=null&&p.player().name().equalsIgnoreCase(name))return;
        Set<String> set=aliases.computeIfAbsent(id, k->Collections.synchronizedSet(new LinkedHashSet<>()));
        set.add(name);
        nameIndex.put(normalizeName(name), id);
        String srv=lastSeenServer.get(id);
        if(srv!=null)serverNameIndex.computeIfAbsent(srv, k->new ConcurrentSkipListMap<>()).put(normalizeName(name), id);
    }
    private void transferAliases(UUID from, UUID to, String oldName) {
        if(from==null||to==null||from.equals(to))return;
        Set<String> src=aliases.remove(from);
        if(oldName!=null)rememberAlias(to, oldName);
        if(src!=null)synchronized(src) {
            for(String a:src)rememberAlias(to, a);
        }
    }
    private void transferMetadata(UUID from, UUID to) {
        if(from==null||to==null||from.equals(to))return;
        Long seen=lastSeenAt.remove(from);
        if(seen!=null)lastSeenAt.merge(to, seen, Math::max);
        lastSeenWriteAt.remove(from);
        dirtySeen.remove(from);
        String srv=lastSeenServer.remove(from);
        if(srv!=null) {
            removeServerIndex(srv, from);
            lastSeenServer.putIfAbsent(to, srv);
            addServerIndex(lastSeenServer.get(to), to);
        }
        if(watchlist.remove(from))watchlist.add(to);
        Long fa=favoriteAddedAt.remove(from);
        if(fa!=null)favoriteAddedAt.merge(to, fa, Math::min);
        Long fu=favoriteLastUsedAt.remove(from);
        if(fu!=null)favoriteLastUsedAt.merge(to, fu, Math::max);
        Long watchSeen=watchSeenAt.remove(from);
        if(watchSeen!=null)watchSeenAt.merge(to, watchSeen, Math::max);
        Long histLatest=historyLatestAt.remove(from);
        if(histLatest!=null)historyLatestAt.merge(to, histLatest, Math::max);
        Set<String> sh=sourceHistoryLoaded.remove(from);
        if(sh!=null&&!sh.isEmpty())sourceHistoryLoaded.computeIfAbsent(to, k->ConcurrentHashMap.newKeySet()).addAll(sh);
        ensureHistoryLoaded(from);
        ensureHistoryLoaded(to);
        List<TierHistoryEvent> h=history.remove(from);
        historyLoaded.remove(from);
        if(h!=null) {
            List<TierHistoryEvent> dest=history.computeIfAbsent(to, k->Collections.synchronizedList(new ArrayList<>()));
            synchronized(h) {
                synchronized(dest) {
                    dest.addAll(h);
                    dest.sort(Comparator.comparingLong(TierHistoryEvent::at));
                }
            }
            historyLoaded.add(to);
        }
        List<NotableStatus> ns=notable.remove(from);
        if(ns!=null&&!ns.isEmpty())notable.put(to, ns);
        String note=notes.remove(from);
        if(note!=null&&!note.isBlank())notes.putIfAbsent(to, note);
        synchronized(recentLock) {
            boolean recent=recentSeen.remove(from);
            if(recent) {
                recentSeen.remove(to);
                recentSeen.addFirst(to);
            }
        }
    }
    private void addServerIndex(String server, UUID id) {
        if(server==null||id==null)return;
        serverIndex.computeIfAbsent(server, k->ConcurrentHashMap.newKeySet()).add(id);
        refreshServerNames(id);
    }
    private void refreshServerNames(UUID id) {
        String server=lastSeenServer.get(id);
        if(server==null)return;
        ConcurrentSkipListMap<String, UUID> idx=serverNameIndex.computeIfAbsent(server, k->new ConcurrentSkipListMap<>());
        PlayerProfile p=cache.get(id);
        if(p!=null)idx.put(normalizeName(p.player().name()), id);
        Set<String>a=aliases.get(id);
        if(a!=null)synchronized(a) {
            for(String n:a)idx.put(normalizeName(n), id);
        }
    }
    private void removeServerIndex(String server, UUID id) {
        Set<UUID> set=serverIndex.get(server);
        if(set!=null) {
            set.remove(id);
            if(set.isEmpty())serverIndex.remove(server, set);
        }
        ConcurrentSkipListMap<String, UUID> idx=serverNameIndex.get(server);
        if(idx!=null) {
            idx.entrySet().removeIf(e->id.equals(e.getValue()));
            if(idx.isEmpty())serverNameIndex.remove(server, idx);
        }
    }
    private static String normalizeServer(String server) {
        if(server==null)return null;
        String s=server.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty()?null:s;
    }
    private void promoteIdentity(PlayerProfile old, PlayerIdentity real) {
        synchronized(diskLock) {
            if(old==null||real==null)return;
            cache.remove(old.player().uuid());
            forgetRamAccounting(old.player().uuid());
            syntheticNameIndex.remove(normalizeName(old.player().name()), old.player().uuid());
            transferAliases(old.player().uuid(), real.uuid(), old.player().name());
            transferMetadata(old.player().uuid(), real.uuid());
            PlayerProfile p=new PlayerProfile(real, new LinkedHashMap<>(old.providers()), old.fetchedAt());
            putMemory(p);
            if(sqliteReady&&sqlite!=null)sqlite.delete(old.player().uuid());
            queuePersist(real.uuid());
        }
    }
    private void renameIdentity(PlayerProfile old, PlayerIdentity current) {
        synchronized(diskLock) {
            if(old==null||current==null||!old.player().uuid().equals(current.uuid()))return;
            rememberAlias(current.uuid(), old.player().name());
            PlayerProfile p=new PlayerProfile(current, new LinkedHashMap<>(old.providers()), old.fetchedAt());
            putMemory(p);
            queuePersist(current.uuid());
        }
    }
    private void recordHistory(UUID id, ProviderResult before, ProviderResult after) {
        if(id==null||after==null||after.status()!=ProviderResult.Status.OK)return;
        ensureHistoryLoaded(id);
        Map<String, TierEntry> old=new LinkedHashMap<>();
        if(before!=null&&before.status()==ProviderResult.Status.OK)for(TierEntry t:before.tiers())old.put(normalizeName(t.gamemode()), t);
        List<TierHistoryEvent> list=history.computeIfAbsent(id, k->Collections.synchronizedList(new ArrayList<>()));
        synchronized(list) {
            long latest=0;
            for(TierHistoryEvent e:list)latest=Math.max(latest, e.at());
            long fallback=Math.max(System.currentTimeMillis(), latest+1);
            HashSet<String> seen=new HashSet<>();
            for(TierEntry n:after.tiers()) {
                String key=normalizeName(n.gamemode());
                seen.add(key);
                TierEntry o=old.get(key);
                String ot=o==null?null:TierRank.normalize(o.currentTier()), nt=TierRank.normalize(n.currentTier());
                boolean or=o!=null&&o.retired();
                long authoritative=tierEventMillis(n.lastTest(), after.fetchedAt());
                if(o==null) {
                    // An initial flat dump with no source timestamp is not a tier-award event. Do not invent history.
                    if(nt!=null&&authoritative>0)list.add(new TierHistoryEvent(authoritative, after.providerId(), n.gamemode(), null, nt, false, n.retired()));
                } else if(!Objects.equals(ot, nt)||or!=n.retired()) {
                    list.add(new TierHistoryEvent(authoritative>0?authoritative:fallback++, after.providerId(), n.gamemode(), ot, nt, or, n.retired()));
                }
            }
            for(var e:old.entrySet())if(!seen.contains(e.getKey())) {
                TierEntry o=e.getValue();
                String ot=TierRank.normalize(o.currentTier());
                if(ot!=null)list.add(new TierHistoryEvent(fallback++, after.providerId(), o.gamemode(), ot, null, o.retired(), false));
            }
            list.sort(Comparator.comparingLong(TierHistoryEvent::at));
            if(list.size()>5000)list.subList(0, list.size()-5000).clear();
            long max=0;
            for(TierHistoryEvent e:list)max=Math.max(max, e.at());
            if(max>0)historyLatestAt.put(id, max);
            else historyLatestAt.remove(id);
        }
    }
    private static final Pattern HISTORY_AGO=Pattern.compile("(?i)^\\s*(\\d+)\\s*([dhm])(?:\\s*ago)?\\s*$");
    private static long tierEventMillis(String raw, long fetchedAt) {
        if(raw==null||raw.isBlank())return 0;
        String v=raw.trim();
        try {
            long n=Long.parseLong(v);
            if(n>10_000_000_000L)return n;
            if(n>1_000_000_000L)return n*1000L;
        } catch (Exception ignored) {
        }
        try {
            return Instant.parse(v).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(v).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(v, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        long base=fetchedAt>0?fetchedAt:System.currentTimeMillis();
        if(v.equalsIgnoreCase("today"))return base;
        if(v.equalsIgnoreCase("yesterday"))return base-86_400_000L;
        Matcher m=HISTORY_AGO.matcher(v);
        if(m.matches()) {
            long n;
            try {
                n=Long.parseLong(m.group(1));
            } catch (Exception e) {
                return 0;
            }
            long unit=switch(m.group(2).toLowerCase(Locale.ROOT)) {
                case "h"->3_600_000L;
                case "m"->60_000L;
                default->86_400_000L;
            };
            return Math.max(1, base-n*unit);
        }
        return 0;
    }
    private static void safeProgress(Consumer<PlayerProfile> progress, PlayerProfile p) {
        if(progress==null||p==null)return;
        try {
            progress.accept(p);
        } catch (Throwable ignored) {
        }
    }
    private static Throwable unwrapCompletion(Throwable t) {
        Throwable x=t;
        while(x!=null&&x.getCause()!=null&&(x instanceof CompletionException||x instanceof ExecutionException))x=x.getCause();
        return x==null?t:x;
    }
    private static String rootMessage(Throwable e) {
        Throwable x=e;
        while(x instanceof CompletionException||x instanceof ExecutionException) {
            if(x.getCause()==null)break;
            x=x.getCause();
        }
        String m=x.getMessage();
        return m==null?x.getClass().getSimpleName():m;
    }
    public void invalidate(UUID id) {
        PlayerProfile p=cache.remove(id);
        Integer weight=ramWeight.remove(id);
        if(weight!=null)ramEstimatedBytes.addAndGet(-weight);
        ramAccess.remove(id);
        if(p!=null) {
            nameIndex.remove(normalizeName(p.player().name()), id);
            syntheticNameIndex.remove(normalizeName(p.player().name()), id);
        }
        Set<String> a=aliases.remove(id);
        if(a!=null)synchronized(a) {
            for(String n:a)nameIndex.remove(normalizeName(n), id);
        }
        live.remove(id);
        String srv=lastSeenServer.remove(id);
        if(srv!=null)removeServerIndex(srv, id);
        lastSeenAt.remove(id);
        lastSeenWriteAt.remove(id);
        dirtySeen.remove(id);
        watchlist.remove(id);
        favoriteAddedAt.remove(id);
        favoriteLastUsedAt.remove(id);
        watchSeenAt.remove(id);
        history.remove(id);
        historyLatestAt.remove(id);
        historyLoaded.remove(id);
        notable.remove(id);
        notes.remove(id);
        sourceHistoryLoaded.remove(id);
        sourceHistoryState.keySet().removeIf(k->k.startsWith(id+"|"));
        sourceHistoryAttemptAt.keySet().removeIf(k->k.startsWith(id+"|"));
        synchronized(recentLock) {
            recentSeen.remove(id);
        }
        if(sqliteReady&&sqlite!=null)sqlite.delete(id);
    }
    public void clearCache() {
        cache.clear();
        ramWeight.clear();
        ramAccess.clear();
        ramEstimatedBytes.set(0L);
        nameIndex.clear();
        syntheticNameIndex.clear();
        aliases.clear();
        live.clear();
        lastSeenAt.clear();
        lastSeenWriteAt.clear();
        dirtySeen.clear();
        lastSeenServer.clear();
        serverIndex.clear();
        serverNameIndex.clear();
        watchlist.clear();
        favoriteAddedAt.clear();
        favoriteLastUsedAt.clear();
        watchSeenAt.clear();
        history.clear();
        historyLatestAt.clear();
        historyLoaded.clear();
        notable.clear();
        notes.clear();
        sourceHistoryLoaded.clear();
        sourceHistoryState.clear();
        sourceHistoryAttemptAt.clear();
        sourceHistoryInFlight.clear();
        synchronized(recentLock) {
            recentSeen.clear();
        }
        if(sqliteReady&&sqlite!=null)sqlite.clearAll();
    }
    /** Initializes SQLite, runs the legacy migration, then rebuilds gameplay indexes in memory. */
    private void initializeStorage() {
        try {
            sqlite=new SqliteProfileStore(sqliteFile);
            mirrorStore=new ProviderMirrorStore(sqliteFile);
            sqliteReady=true;
            if(sqlite.playerCount()==0) {
                Path source=Files.exists(cacheFile)?cacheFile:(Files.exists(legacyCacheFile)?legacyCacheFile:null);
                if(source!=null) {
                    ramBudgetSuspended=true;
                    int imported;
                    try {
                        imported=loadLegacyJson(source);
                    } finally {
                        ramBudgetSuspended=false;
                    }
                    if(imported>0) {
                        ArrayList<SqliteProfileStore.Bundle> initial=new ArrayList<>(cache.size());
                        for(UUID id:new ArrayList<>(cache.keySet())) {
                            PlayerProfile p=cache.get(id);
                            if(p!=null)initial.add(bundleFor(id, p));
                        }
                        sqlite.importFreshBulk(initial);
                        preserveLegacyBackup(source);
                        
                    }
                }
            }
            if(sqlite.playerCount()>0) {
                ramBudgetSuspended=true;
                try {
                    clearMemoryOnly();
                    for(SqliteProfileStore.Loaded l:sqlite.loadAll())loadSqliteRecord(l);
                } finally {
                    ramBudgetSuspended=false;
                }
                trimRamToBudget();
            }
        } catch (Throwable t) {
            sqliteReady=false;
            sqlite=null;
            mirrorStore=null;
            BootstrapLog.error("SQLITE init; using RAM/legacy fallback", t);
            Path source=Files.exists(cacheFile)?cacheFile:(Files.exists(legacyCacheFile)?legacyCacheFile:null);
            if(source!=null)loadLegacyJson(source);
        }
    }
    private int loadLegacyJson(Path source) {
        synchronized(diskLock) {
            int before=cache.size();
            try {
                Object root=MiniJson.parse(Files.readString(source, StandardCharsets.UTF_8));
                if(!(root instanceof Map<?, ?> rm))return 0;
                Object po=rm.get("players");
                if(po instanceof List<?> players) {
                    for(Object item:players) {
                        try {
                            PlayerProfile p=parseProfileRecord(item);
                            if(p!=null) {
                                putLoadedMemory(p);
                                loadAliases(p.player().uuid(), item);
                                loadMetadata(p.player().uuid(), item);
                                historyLoaded.add(p.player().uuid());
                            }
                        } catch (Exception ignored) {
                        }
                    }
                } else if(po instanceof Map<?, ?> players) {
                    for(var en:players.entrySet()) {
                        try {
                            UUID id=UUID.fromString(String.valueOf(en.getKey()));
                            PlayerProfile p=parseProfile(id, en.getValue());
                            if(p!=null) {
                                putLoadedMemory(p);
                                loadAliases(id, en.getValue());
                                loadMetadata(id, en.getValue());
                                historyLoaded.add(id);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                
                return cache.size()-before;
            } catch (Throwable t) {
                BootstrapLog.error("LEGACY CACHE load", t);
                return 0;
            }
        }
    }
    private void preserveLegacyBackup(Path source) {
        if(source==null||!Files.exists(source))return;
        try {
            Path backup=source.resolveSibling(source.getFileName()+".migrated-backup");
            if(!Files.exists(backup))Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
            
        } catch (Exception e) {
            BootstrapLog.error("SQLITE legacy backup", e);
        }
    }
    private void clearMemoryOnly() {
        cache.clear();
        ramWeight.clear();
        ramAccess.clear();
        ramEstimatedBytes.set(0L);
        live.clear();
        nameIndex.clear();
        syntheticNameIndex.clear();
        aliases.clear();
        lastSeenAt.clear();
        lastSeenWriteAt.clear();
        dirtySeen.clear();
        lastSeenServer.clear();
        serverIndex.clear();
        serverNameIndex.clear();
        watchlist.clear();
        favoriteAddedAt.clear();
        favoriteLastUsedAt.clear();
        watchSeenAt.clear();
        history.clear();
        historyLatestAt.clear();
        historyLoaded.clear();
        notable.clear();
        notes.clear();
        sourceHistoryLoaded.clear();
        sourceHistoryState.clear();
        sourceHistoryAttemptAt.clear();
        sourceHistoryInFlight.clear();
        synchronized(recentLock) {
            recentSeen.clear();
        }
    }
    private void loadSqliteRecord(SqliteProfileStore.Loaded l) {
        PlayerProfile p=l.profile();
        putLoadedMemory(p);
        for(String a:l.aliases())rememberAlias(p.player().uuid(), a);
        if(l.lastSeenAt()>0) {
            lastSeenAt.put(p.player().uuid(), l.lastSeenAt());
            lastSeenWriteAt.put(p.player().uuid(), l.lastSeenAt());
            ramAccess.put(p.player().uuid(), l.lastSeenAt());
        }
        String server=normalizeServer(l.lastSeenServer());
        if(server!=null) {
            lastSeenServer.put(p.player().uuid(), server);
            addServerIndex(server, p.player().uuid());
        }
        if(l.favorite()) {
            watchlist.add(p.player().uuid());
            favoriteAddedAt.put(p.player().uuid(), l.favoriteAddedAt());
            favoriteLastUsedAt.put(p.player().uuid(), l.favoriteLastUsedAt());
            if(l.watchSeenAt()>0)watchSeenAt.put(p.player().uuid(), l.watchSeenAt());
        }
        if(l.historyLatestAt()>0)historyLatestAt.put(p.player().uuid(), l.historyLatestAt());
        if(!l.sourceHistoryLoaded().isEmpty()) {
            Set<String> set=ConcurrentHashMap.newKeySet();
            set.addAll(l.sourceHistoryLoaded());
            sourceHistoryLoaded.put(p.player().uuid(), set);
        }
        if(!l.notable().isEmpty())notable.put(p.player().uuid(), List.copyOf(l.notable()));
        if(l.note()!=null&&!l.note().isBlank())notes.put(p.player().uuid(), sanitizeNote(l.note()));
    }
    @SuppressWarnings("unchecked") private static PlayerProfile parseProfileRecord(Object obj) {
        if(!(obj instanceof Map<?, ?> raw))return null;
        Object u=raw.get("uuid");
        if(u==null)return null;
        UUID id=UUID.fromString(String.valueOf(u));
        return parseProfile(id, obj);
    }
    @SuppressWarnings("unchecked") private static PlayerProfile parseProfile(UUID id, Object obj) {
        if(!(obj instanceof Map<?, ?> raw))return null;
        Map<String, Object> m=(Map<String, Object>)raw;
        String name=String.valueOf(m.getOrDefault("name", "Unknown"));
        long fetched=asLong(m.get("fetchedAt"), 0);
        LinkedHashMap<String, ProviderResult> prs=new LinkedHashMap<>();
        Object prObj=m.get("providers");
        if(prObj instanceof Map<?, ?> pmap)for(var pe:pmap.entrySet()) {
            String pid=String.valueOf(pe.getKey());
            if(!(pe.getValue() instanceof Map<?, ?> vr))continue;
            Map<String, Object> v=(Map<String, Object>)vr;
            String dn=String.valueOf(v.getOrDefault("displayName", pid));
            ProviderResult.Status st;
            try {
                st=ProviderResult.Status.valueOf(String.valueOf(v.getOrDefault("status", "NOT_RANKED")));
            } catch (Exception e) {
                st=ProviderResult.Status.NOT_RANKED;
            }
            List<TierEntry> tiers=new ArrayList<>();
            Object to=v.get("tiers");
            if(to instanceof List<?> list)for(Object item:list)if(item instanceof Map<?, ?> tr) {
                Map<String, Object> t=(Map<String, Object>)tr;
                tiers.add(new TierEntry(str(t.get("gamemode")), str(t.get("currentTier")), str(t.get("peakTier")), Boolean.TRUE.equals(t.get("retired")), str(t.get("lastTest"))));
            }
            prs.put(pid, new ProviderResult(pid, dn, st, tiers, str(v.get("message")), asLong(v.get("fetchedAt"), fetched)));
        }
        return new PlayerProfile(new PlayerIdentity(id, name), prs, fetched);
    }
    @SuppressWarnings("unchecked") private void loadAliases(UUID id, Object obj) {
        if(!(obj instanceof Map<?, ?> raw))return;
        Object a=raw.get("aliases");
        if(!(a instanceof List<?> list))return;
        for(Object n:list) {
            String s=String.valueOf(n);
            if(!s.isBlank())rememberAlias(id, s);
        }
    }
    @SuppressWarnings("unchecked") private void loadMetadata(UUID id, Object obj) {
        if(!(obj instanceof Map<?, ?> raw))return;
        long seen=asLong(raw.get("lastSeenAt"), 0);
        if(seen>0) {
            lastSeenAt.put(id, seen);
            lastSeenWriteAt.put(id, seen);
        }
        Object srv=raw.get("lastSeenServer");
        if(srv!=null&&!String.valueOf(srv).isBlank()) {
            String key=normalizeServer(String.valueOf(srv));
            if(key!=null) {
                lastSeenServer.put(id, key);
                addServerIndex(key, id);
            }
        }
        if(Boolean.TRUE.equals(raw.get("watchlisted"))) {
            watchlist.add(id);
            long now=System.currentTimeMillis();
            favoriteAddedAt.put(id, asLong(raw.get("favoriteAddedAt"), now));
            favoriteLastUsedAt.put(id, asLong(raw.get("favoriteLastUsedAt"), 0));
        }
        long wsa=asLong(raw.get("watchSeenAt"), 0);
        if(wsa>0)watchSeenAt.put(id, wsa);
        Object sh=raw.get("sourceHistoryLoaded");
        if(sh instanceof List<?> sl) {
            Set<String> loaded=ConcurrentHashMap.newKeySet();
            for(Object v:sl)if(v!=null&&!String.valueOf(v).isBlank())loaded.add(String.valueOf(v));
            if(!loaded.isEmpty())sourceHistoryLoaded.put(id, loaded);
        }
        Object note=raw.get("note");
        if(note!=null) {
            String n=sanitizeNote(String.valueOf(note));
            if(!n.isBlank())notes.put(id, n);
        }
        Object h=raw.get("history");
        if(h instanceof List<?> list) {
            List<TierHistoryEvent> out=Collections.synchronizedList(new ArrayList<>());
            long latest=0;
            for(Object item:list)if(item instanceof Map<?, ?> m) {
                TierHistoryEvent e=new TierHistoryEvent(asLong(m.get("at"),
                    0),
                    str(m.get("providerId")),
                    str(m.get("gamemode")),
                    str(m.get("oldTier")),
                    str(m.get("newTier")),
                    Boolean.TRUE.equals(m.get("oldRetired")),
                    Boolean.TRUE.equals(m.get("newRetired")));
                out.add(e);
                latest=Math.max(latest, e.at());
            }
            if(!out.isEmpty())history.put(id, out);
            if(latest>0)historyLatestAt.put(id, latest);
            historyLoaded.add(id);
        }
    }
    private void queuePersist(UUID id) {
        if(!sqliteReady||sqlite==null||id==null)return;
        PlayerProfile p=cache.get(id);
        if(p==null)return;
        sqlite.queue(bundleFor(id, p));
    }
    private void queueSeenPersist(UUID id) {
        if(!sqliteReady||sqlite==null||id==null)return;
        PlayerProfile p=cache.get(id);
        if(p==null)return;
        sqlite.queueSeen(id, p.player().name(), lastSeenAt(id), lastSeenServer(id));
    }
    private SqliteProfileStore.Bundle bundleFor(UUID id, PlayerProfile p) {
        List<String> al=aliases(id);
        List<String> sh=sourceHistoryLoaded.containsKey(id)?List.copyOf(sourceHistoryLoaded.get(id)):List.of();
        List<TierHistoryEvent> h=historyLoaded.contains(id)?history(id):null;
        return new SqliteProfileStore.Bundle(p,
            al,
            lastSeenAt(id),
            lastSeenServer(id),
            watchlisted(id),
            favoriteAddedAt.getOrDefault(id,
            0L),
            favoriteLastUsedAt.getOrDefault(id,
            0L),
            watchSeenAt.getOrDefault(id,
            0L),
            sh,
            h,
            notableStatuses(id),
            playerNote(id));
    }
    public String playerNote(UUID id) {
        String n=id==null?null:notes.get(id);
        return n==null?"":n;
    }
    public String playerNoteForDisplay(UUID id, String name) {
        String n=playerNote(id);
        if(!n.isBlank()||name==null||name.isBlank())return n;
        PlayerProfile byName=cachedByName(name);
        if(byName!=null) {
            n=playerNote(byName.player().uuid());
            if(!n.isBlank())return n;
        }
        UUID synthetic=syntheticUuid(name);
        n=playerNote(synthetic);
        if(!n.isBlank())return n;
        UUID mapped=nameIndex.get(normalizeName(name));
        return mapped==null?"":playerNote(mapped);
    }
    public boolean hasNote(UUID id) {
        return !playerNote(id).isBlank();
    }
    public void setPlayerNote(UUID id, String note) {
        if(id==null)return;
        String n=sanitizeNote(note);
        if(n.isBlank())notes.remove(id);
        else notes.put(id, n);
        queuePersist(id);
    }
    private static String sanitizeNote(String note) {
        if(note==null)return "";
        String n=note.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s{2,}", " ");
        if(n.length()>240)n=n.substring(0, 240);
        return n;
    }
    public Path sqlitePath() {
        return sqliteFile;
    }
    public boolean sqliteReady() {
        return sqliteReady;
    }
    private static String str(Object o) {
        return o==null?null:String.valueOf(o);
    }
    private static long asLong(Object o, long d) {
        if(o instanceof Number n)return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return d;
        }
    }
    private static String normalizeName(String s) {
        return s==null?"":s.trim().toLowerCase(Locale.ROOT);
    }
    private static String prettyJson(Object value, int depth) {
        if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean)return MiniJson.stringify(value);
        String ind="  ".repeat(depth), next="  ".repeat(depth+1);
        if(value instanceof Map<?, ?> m) {
            if(m.isEmpty())return "{}";
            StringBuilder b=new StringBuilder("{\n");
            int i=0;
            for(var e:m.entrySet()) {
                if(i++>0)b.append(",\n");
                b.append(next).append(MiniJson.stringify(String.valueOf(e.getKey()))).append(": ").append(prettyJson(e.getValue(), depth+1));
            }
            return b.append("\n").append(ind).append('}').toString();
        }
        if(value instanceof List<?> l) {
            if(l.isEmpty())return "[]";
            StringBuilder b=new StringBuilder("[\n");
            for(int i=0; i<l.size(); i++) {
                if(i>0)b.append(",\n");
                b.append(next).append(prettyJson(l.get(i), depth+1));
            }
            return b.append("\n").append(ind).append(']').toString();
        }
        return MiniJson.stringify(String.valueOf(value));
    }
}
