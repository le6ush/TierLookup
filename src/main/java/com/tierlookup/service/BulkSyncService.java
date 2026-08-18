package com.tierlookup.service;

import com.tierlookup.client.BootstrapLog;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import com.tierlookup.model.*;
import com.tierlookup.net.*;
import com.tierlookup.provider.*;

/**
* Explicit ModMenu-only database mirror builder.
*
* Stability contract:
*  - every provider downloads into a staging snapshot first;
*  - COMPLETE replaces only that provider's old mirror in one SQLite transaction;
*  - PARTIAL never deletes missing old rows;
*  - REBUILD is strict: a partial staging snapshot is recorded but not merged;
*  - final RAM indexes are rebuilt from SQLite only after provider commits, never while pages stream in;
*  - bulk sync uses one network-limited execution mode.
*/ public final class BulkSyncService {
    public enum Scope {
        ALL, PROVIDER, SELECTED
    }
    public record Summary(Scope scope, int touched, int skipped, int sourcesComplete, int sourcesPartial, int sourcesFailed, List<String> failures) {
    }
    // Exact leaderboard ids used by the current FlowTiers 1.21.x client.
    // Spear remains a TierLookup canonical kit, but the current public Flow leaderboard client does not expose a Spear ladder.
    private static final List<String> FLOW_LADDERS=List.of("GLOBAL", "SWORD", "AXE", "UHC", "VANILLA", "MACE", "DIAMOND_POT", "NETHERITE_OP", "SMP", "DIAMOND_SMP");
    private static final List<String> ALL_PROVIDER_IDS=List.of("cistiers", "atiers", "mytiers", "mctiers", "subtiers", "pvptiers", "flowpvp", "centraltierlist");
    private static final int REJECT_LIMIT=250;
    private static final int COMMON_PAGE_SIZE=50, COMMON_HARD_CAP=250_000, COMMON_MAX_WINDOW=8;
    private static final int FAST_NETWORK_PERMITS=8, FAST_PROVIDER_THREADS=8;
    private static final long FLOW_PAGE_PACE_MS=300L;
    private final List<TierProvider> providers;
    private final Map<String, TierProvider> byId=new LinkedHashMap<>();
    private final TierLookupConfig config;
    private final ProfileService profiles;
    private final AtomicBoolean running=new AtomicBoolean(), cancelRequested=new AtomicBoolean(), committedAny=new AtomicBoolean();
    private final Set<CompletableFuture<?>> activeRequests=ConcurrentHashMap.newKeySet();
    /** Bulk HTTP/profile work is capped independently of the worker pool so sync cannot monopolize the client network stack. */
    private final Semaphore bulkNetworkPermits=new Semaphore(FAST_NETWORK_PERMITS, true);
    private volatile CompletableFuture<Summary> activeRun;
    private volatile ExecutorService activeFastPool;
    private volatile ExecutorService activeCoordinator;
    private volatile boolean rebuildRun;
    private volatile int fastParallelism=4;
    public BulkSyncService(List<TierProvider> providers, TierLookupConfig config, ProfileService profiles) {
        this.providers=providers;
        this.config=config;
        this.profiles=profiles;
        for(TierProvider p:providers)byId.put(p.id(), p);
    }
    public boolean running() {
        return running.get();
    }
    public CompletableFuture<Summary> currentFuture() {
        return activeRun;
    }
    public void cancel() {
        if(!running.get())return;
        cancelRequested.set(true);
        for(CompletableFuture<?> f:activeRequests)if(f!=null&&!f.isDone())f.cancel(true);
        ExecutorService pool=activeFastPool;
        if(pool!=null)pool.shutdownNow();
        ExecutorService coordinator=activeCoordinator;
        if(coordinator!=null)coordinator.shutdownNow();
        
    }
    public CompletableFuture<Summary> start(Scope scope, Consumer<String> progress) {
        return start(scope, false, null, null, progress);
    }
    public CompletableFuture<Summary> startRebuild(Scope scope, Consumer<String> progress) {
        return start(scope, true, null, null, progress);
    }
    public CompletableFuture<Summary> startProvider(String providerId, Consumer<String> progress) {
        String id=providerId==null?"":providerId.trim().toLowerCase(Locale.ROOT);
        if(!supportsSingleProvider(id))return CompletableFuture.failedFuture(new IllegalArgumentException("unsupported bulk provider: "+providerId));
        return start(Scope.PROVIDER, false, id, null, progress);
    }
    public CompletableFuture<Summary> startSelected(Collection<String> providerIds, boolean rebuild, Consumer<String> progress) {
        ArrayList<String> ids=new ArrayList<>();
        if(providerIds!=null)for(String raw:providerIds) {
            String id=raw==null?"":raw.trim().toLowerCase(Locale.ROOT);
            if(supportsSingleProvider(id)&&!ids.contains(id))ids.add(id);
        }
        if(ids.isEmpty())return CompletableFuture.failedFuture(new IllegalArgumentException("no tierlists selected"));
        return start(Scope.SELECTED, rebuild, null, List.copyOf(ids), progress);
    }
    public static boolean supportsSingleProvider(String providerId) {
        if(providerId==null)return false;
        return switch(providerId.trim().toLowerCase(Locale.ROOT)) {
            case "mctiers", "pvptiers", "subtiers", "flowpvp", "cistiers", "atiers", "mytiers", "centraltierlist"->true;
            default->false;
        };
    }
    public static boolean selfTestSingleProviderSelection() {
        return supportsSingleProvider("mctiers")&&supportsSingleProvider("flowpvp")&&supportsSingleProvider("centraltierlist")&&!supportsSingleProvider("unknown") &&providerIdsFor(Scope.PROVIDER,
            "flowpvp",
            null).equals(List.of("flowpvp")) &&providerIdsFor(Scope.PROVIDER,
            "MCTIERS",
            null).equals(List.of("mctiers")) &&providerIdsFor(Scope.PROVIDER,
            "unknown",
            null).isEmpty() &&providerIdsFor(Scope.SELECTED,
            null,
            List.of("flowpvp",
            "mctiers",
            "flowpvp")).equals(List.of("flowpvp",
            "mctiers")) &&providerIdsFor(Scope.ALL,
            null,
            null).equals(ALL_PROVIDER_IDS) &&ALL_PROVIDER_IDS.size()==8;
    }
    private CompletableFuture<Summary> start(Scope scope, boolean rebuild, String targetProviderId, List<String> selectedProviderIds, Consumer<String> progress) {
        if(scope==Scope.PROVIDER&&(rebuild||!supportsSingleProvider(targetProviderId)))return CompletableFuture.failedFuture(new IllegalArgumentException("invalid single-provider sync request"));
        if(scope==Scope.SELECTED&&(selectedProviderIds==null||selectedProviderIds.isEmpty()))return CompletableFuture.failedFuture(new IllegalArgumentException("no tierlists selected"));
        if(!running.compareAndSet(false, true))return CompletableFuture.failedFuture(new IllegalStateException("bulk sync already running"));
        cancelRequested.set(false);
        committedAny.set(false);
        rebuildRun=rebuild;
        int cpu=Math.max(1, Runtime.getRuntime().availableProcessors());
        fastParallelism=FAST_NETWORK_PERMITS;
        int threads=Math.min(FAST_PROVIDER_THREADS, Math.max(4, Math.min(cpu, FAST_PROVIDER_THREADS)));
        ThreadPoolExecutor turbo=new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r-> {
            Thread th=new Thread(r, "TierLookup-BulkSync-Fast"); th.setDaemon(true); th.setPriority(Thread.MIN_PRIORITY); return th;
        }
        );
        ExecutorService coordinator=Executors.newSingleThreadExecutor(r-> {
            Thread th=new Thread(r, "TierLookup-BulkSync-Coordinator"); th.setDaemon(true); th.setPriority(Thread.MIN_PRIORITY); return th;
        }
        );
        activeFastPool=turbo;
        activeCoordinator=coordinator;
        
        CompletableFuture<Summary> future=CompletableFuture.supplyAsync(()->run(scope, targetProviderId, selectedProviderIds, progress), coordinator);
        activeRun=future;
        return future.whenComplete((sum, e)-> {
            running.set(false);
            if(activeRun==future)activeRun=null;
            ExecutorService pool=activeFastPool;
            activeFastPool=null;
            if(pool!=null)pool.shutdownNow();
            ExecutorService coord=activeCoordinator;
            activeCoordinator=null;
            if(coord!=null)coord.shutdownNow();
            activeRequests.clear();
            boolean rb=rebuildRun;
            rebuildRun=false;
            if(e!=null&&!isCancelled(e))BootstrapLog.error("BULK SYNC "+scope+" target="+targetProviderId, e);
        }
        );
    }
    private Summary run(Scope scope, String targetProviderId, List<String> selectedProviderIds, Consumer<String> progress) {
        Counters c=new Counters();
        List<String> failures=Collections.synchronizedList(new ArrayList<>());
        checkCancelled();
        profiles.flushDisk();
        List<SourceJob> jobs=jobsFor(scope, targetProviderId, selectedProviderIds, progress);
        if(jobs.isEmpty())throw new IllegalArgumentException("no bulk jobs for "+scope+(targetProviderId==null?"":" / "+targetProviderId));
        try {
            emit(progress, "БЫСТРАЯ СИНХРОНИЗАЦИЯ: до "+fastParallelism+" HTTP одновременно · низкий приоритет");
            ExecutorService pool=activeFastPool;
            if(pool==null)throw new IllegalStateException("fast bulk pool unavailable");
            List<CompletableFuture<Void>> fs=new ArrayList<>();
            for(SourceJob job:jobs)fs.add(CompletableFuture.runAsync(()->source(job, progress, c, failures), pool));
            CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)).join();
        } finally {
            if(committedAny.get()) {
                emit(progress, "SQLite → RAM: перестраиваю локальный индекс…");
                profiles.reloadMirrorFromSqlite();
            }
        }
        checkCancelled();
        String label=scope==Scope.PROVIDER?jobs.get(0).display():(scope==Scope.SELECTED?"Выбрано "+jobs.size():"Все");
        emit(progress, label+": локальная база "+profiles.cachedCount()+" игроков");
        synchronized(failures) {
            return new Summary(scope, c.touched.get(), c.skipped.get(), c.complete.get(), c.partial.get(), c.failed.get(), List.copyOf(failures));
        }
    }
    private static List<String> providerIdsFor(Scope scope, String targetProviderId, List<String> selectedProviderIds) {
        if(scope==Scope.PROVIDER) {
            String id=targetProviderId==null?"":targetProviderId.trim().toLowerCase(Locale.ROOT);
            return supportsSingleProvider(id)?List.of(id):List.of();
        }
        if(scope==Scope.SELECTED) {
            ArrayList<String> out=new ArrayList<>();
            if(selectedProviderIds!=null)for(String raw:selectedProviderIds) {
                String id=raw==null?"":raw.trim().toLowerCase(Locale.ROOT);
                if(supportsSingleProvider(id)&&!out.contains(id))out.add(id);
            }
            return List.copyOf(out);
        }
        if(scope==Scope.ALL)return ALL_PROVIDER_IDS;
        return List.of();
    }
    private List<SourceJob> jobsFor(Scope scope, String targetProviderId, List<String> selectedProviderIds, Consumer<String> progress) {
        ArrayList<SourceJob> jobs=new ArrayList<>();
        for(String id:providerIdsFor(scope, targetProviderId, selectedProviderIds)) {
            SourceJob j=sourceJob(id, progress);
            if(j!=null)jobs.add(j);
        }
        return jobs;
    }
    private SourceJob sourceJob(String providerId, Consumer<String> progress) {
        String id=providerId==null?"":providerId.trim().toLowerCase(Locale.ROOT);
        return switch(id) {
            case "cistiers"->new SourceJob("cistiers", "CISTiers", ()->stageCisTiers(progress));
            case "atiers"->new SourceJob("atiers", "ATiers", ()->stageATiers(progress));
            case "mytiers"->new SourceJob("mytiers", "MyTiers", ()->stageMyTiers(progress));
            case "mctiers"->new SourceJob("mctiers",
                "MCTiers",
                ()->stagePagedCommon("mctiers",
                "MCTiers",
                List.of("https://mctiers.com/api/v2/mode/overall?count=50&from="),
                progress));
            case "subtiers"->new SourceJob("subtiers",
                "SubTiers",
                ()->stagePagedCommon("subtiers",
                "SubTiers",
                List.of("https://subtiers.net/api/v2/mode/overall?count=50&from=",
                "https://subtiers.net/api/mode/overall?count=50&from="),
                progress));
            case "pvptiers"->new SourceJob("pvptiers",
                "PvPTiers",
                ()->stagePreserve("pvptiers",
                "PvPTiers",
                "PROFILE_ONLY_NO_BULK_ROSTER",
                "individual lookups available; public full-roster endpoint not available"));
            case "flowpvp"->new SourceJob("flowpvp", "FlowPVP", ()->stageFlowPvp(progress));
            case "centraltierlist"->new SourceJob("centraltierlist", "Central Tier List", ()->stageCentralTierList(progress));
            default->null;
        };
    }
    private void source(SourceJob job, Consumer<String> progress, Counters c, List<String> failures) {
        long generation=profiles.beginMirrorSync(job.id(), job.display(), rebuildRun?"REBUILD":"UPDATE");
        try {
            checkCancelled();
            emit(progress, job.display()+": staging…");
            Snapshot s=job.action().get();
            s.finalizeCoverage();
            checkCancelled();
            Verification v=verifySnapshot(s, progress);
            boolean structurallyComplete=s.fullRoster&&s.parsed.get()>0&&s.rejected.get()==0&&s.failedPages.get()==0;
            boolean complete=structurallyComplete&&v.gaps==0&&!v.uncertain;
            String status=complete?"COMPLETE":"PARTIAL";
            String note=joinNotes(s.note, v.message, complete?"полный mirror":"неполный mirror");
            int stagedRows=s.rows().size();
            Collection<ProfileService.MirrorRow> rows=(rebuildRun&&!complete)?List.of():s.rows();
            boolean replaceMissing=complete;
            profiles.commitMirrorSnapshot(job.id(),
                job.display(),
                generation,
                rebuildRun?"REBUILD":"UPDATE",
                status,
                rows,
                s.rejects(),
                s.received.get(),
                s.rawReceived.get(),
                s.uniqueIdentities.get(),
                s.duplicateIdentities.get(),
                s.duplicatePages.get(),
                s.parsed.get(),
                s.rejected.get(),
                s.pages.get(),
                s.failedPages.get(),
                v.checked,
                v.gaps,
                stagedRows,
                s.terminationReason,
                s.failedPageDetails(),
                note,
                s.notable,
                replaceMissing);
            committedAny.set(committedAny.get()||!rows.isEmpty()||complete);
            c.touched.addAndGet(rows.size());
            c.skipped.addAndGet(s.rejected.get());
            if(complete) {
                c.complete.incrementAndGet();
                emit(progress, job.display()+": COMPLETE · "+s.parsed.get()+" игроков · term="+s.terminationReason+(s.pages.get()>0?" · "+s.pages.get()+" стр.":""));
            } else {
                c.partial.incrementAndGet();
                emit(progress,
                    job.display()+": PARTIAL · parsed "+s.parsed.get()+" · raw "+s.rawReceived.get()+" · unique "+s.uniqueIdentities.get()+" · term="+s.terminationReason+" · старый mirror сохранён"+(rebuildRun?" · staging не применён":""));
            }
        } catch (CancellationException e) {
            throw e;
        } catch (Throwable t) {
            c.failed.incrementAndGet();
            String msg=job.display()+": "+Http.rootMessage(t);
            failures.add(msg);
            profiles.failMirrorSync(job.id(), job.display(), generation, rebuildRun?"REBUILD":"UPDATE", msg);
            emit(progress, msg);
            
        }
    }
    private Snapshot stageCisTiers(Consumer<String> progress) {
        Snapshot s=new Snapshot("cistiers", "CISTiers", true);
        String body=awaitGet("https://cistiers.com/api/dump", 12, 3);
        Object root=MiniJson.parse(body);
        if(!(root instanceof Map<?, ?> rr))throw new IllegalStateException("CISTiers dump is not an object");
        Object data=getAny(rr, "data");
        if(!(data instanceof Map<?, ?> dm))throw new IllegalStateException("CISTiers dump has no data map");
        s.pages.incrementAndGet();
        int done=0;
        for(var en:dm.entrySet()) {
            checkCancelled();
            s.received.incrementAndGet();
            String name=String.valueOf(en.getKey());
            if(!validName(name)||!(en.getValue() instanceof Map<?, ?> v)) {
                s.reject("invalid player row", String.valueOf(en));
                continue;
            }
            PlayerIdentity ident=knownOrSynthetic(name);
            List<TierEntry> tiers=new ArrayList<>();
            Object tiersObj=getAny(v, "tiers");
            if(tiersObj instanceof Map<?, ?> tm)for(var te:tm.entrySet()) {
                String raw=String.valueOf(te.getValue());
                String tier=TierRank.normalize(raw);
                if(tier==null) {
                    s.reject("unknown tier "+te.getKey()+"="+raw, name);
                    continue;
                }
                tiers.add(new TierEntry(String.valueOf(te.getKey()), tier, tier, raw.trim().toUpperCase(Locale.ROOT).startsWith("R"), null));
            }
            ProviderResult r=new ProviderResult("cistiers",
                "CISTiers",
                tiers.isEmpty()?ProviderResult.Status.NOT_RANKED:ProviderResult.Status.OK,
                List.copyOf(tiers),
                null,
                System.currentTimeMillis());
            s.add(ident, r);
            int rank=officialOverallRank(v);
            if(rank>0&&rank<=10)s.addNotable(ident.uuid(), new NotableStatus(NotableStatus.Type.CIS_LEGEND, rank, "CISTiers", System.currentTimeMillis()));
            if(authoritativeCreator(v))s.addNotable(ident.uuid(), new NotableStatus(NotableStatus.Type.CREATOR, 0, "CISTiers", System.currentTimeMillis()));
            if(++done%500==0)emit(progress, "CISTiers staging: "+done+"/"+dm.size());
        }
        return s;
    }
    private Snapshot stageATiers(Consumer<String> progress) {
        Snapshot s=new Snapshot("atiers", "ATiers", true);
        String body=awaitGet("https://api.atiers.net/api/v1/players", 10, 3);
        Object root=MiniJson.parse(body);
        s.pages.incrementAndGet();
        List<Map<String, Object>> namedMaps=new ArrayList<>();
        collectNamedMaps(root, namedMaps, 0);
        LinkedHashSet<String> names=new LinkedHashSet<>();
        for(Map<String, Object> m:namedMaps) {
            String name=strAny(m, "nickname", "name", "username", "minecraft_username", "ign");
            if(!validName(name))continue;
            names.add(name);
            PlayerIdentity p=knownOrSynthetic(name);
            int rank=officialOverallRank(m);
            if(rank>0&&rank<=10)s.addNotable(p.uuid(), new NotableStatus(NotableStatus.Type.CIS_LEGEND, rank, "ATiers", System.currentTimeMillis()));
            if(authoritativeCreator(m))s.addNotable(p.uuid(), new NotableStatus(NotableStatus.Type.CREATOR, 0, "ATiers", System.currentTimeMillis()));
        }
        if(names.isEmpty()) {
            List<String> found=new ArrayList<>();
            collectNames(root, found, 0);
            for(String n:found)if(validName(n))names.add(n);
        }
        if(names.isEmpty())throw new IllegalStateException("ATiers players list is empty");
        s.received.set(names.size());
        stageLookups(s, requireProvider("atiers"), names.stream().map(this::knownOrSynthetic).toList(), progress);
        return s;
    }
    private Snapshot stageMyTiers(Consumer<String> progress) {
        try {
            // /api/players is known to return a small roster (50 in the last live run), but the endpoint currently
            // provides no proven terminal/pagination contract to this client. Parse it, stage it, but never call it COMPLETE.
            Snapshot s=new Snapshot("mytiers", "MyTiers", false);
            List<MyTiersProvider.Row> rows=await(MyTiersProvider.fetchRows(true));
            s.pages.incrementAndGet();
            if(rows.isEmpty())return stageExisting("mytiers", "MyTiers", "full roster unavailable", progress);
            s.received.set(rows.size());
            s.rawReceived.set(rows.size());
            s.terminationReason="UNPROVEN_API_ROSTER";
            int done=0;
            for(MyTiersProvider.Row row:rows) {
                if(row==null||!validName(row.name())) {
                    s.reject("invalid player row", String.valueOf(row));
                    continue;
                }
                PlayerIdentity p=knownOrSynthetic(row.name());
                s.observeIdentity(identityKey(p));
                s.add(p,
                    new ProviderResult("mytiers",
                    "MyTiers",
                    row.tiers().isEmpty()?ProviderResult.Status.NOT_RANKED:ProviderResult.Status.OK,
                    row.tiers(),
                    null,
                    System.currentTimeMillis()));
                if(row.position()>0&&row.position()<=10)s.addNotable(p.uuid(),
                    new NotableStatus(NotableStatus.Type.CIS_LEGEND,
                    row.position(),
                    "MyTiers",
                    System.currentTimeMillis()));
                if(++done%250==0)emit(progress, "MyTiers staging: "+done+"/"+rows.size());
            }
            return s;
        } catch (Throwable t) {
            if(isCancelled(t))throw new CancellationException("bulk sync cancelled");
            return stageExisting("mytiers", "MyTiers", "snapshot unavailable: "+Http.rootMessage(t), progress);
        }
    }
    private Snapshot stageCentralTierList(Consumer<String> progress) {
        try {
            CentralTierListProvider.BulkSnapshot bulk=await(CentralTierListProvider.fetchBulk(true));
            List<CentralTierListProvider.Row> rows=bulk.rows();
            if(rows.isEmpty())return stagePreserve("centraltierlist", "Central Tier List", "MODE_PAGES_UNPARSED", "public mode pages reachable, but no visible tier rows parsed");
            Snapshot s=new Snapshot("centraltierlist", "Central Tier List", false);
            s.terminationReason="VISIBLE_MODE_ROWS_ONLY";
            s.pages.set(bulk.pagesFetched());
            for(String f:bulk.failures())s.failedPage(f);
            s.received.set(rows.size());
            s.rawReceived.set(rows.size());
            s.note="public mode pages: "+bulk.pagesFetched()+" fetched, "+bulk.pagesFailed()+" failed; visible assignments "+bulk.parsedAssignments()+" / declared "+bulk.declaredAssignments()+"; hidden Show-more roster is not claimed as complete";
            for(CentralTierListProvider.Row row:rows) {
                if(row==null||!validName(row.name())) {
                    s.reject("invalid player row", String.valueOf(row));
                    continue;
                }
                PlayerIdentity p=knownOrSynthetic(row.name());
                s.add(p,
                    new ProviderResult("centraltierlist",
                    "Central Tier List",
                    row.tiers().isEmpty()?ProviderResult.Status.NOT_RANKED:ProviderResult.Status.OK,
                    row.tiers(),
                    null,
                    System.currentTimeMillis()));
            }
            return s;
        } catch (Throwable t) {
            if(isCancelled(t))throw new CancellationException("bulk sync cancelled");
            return stagePreserve("centraltierlist", "Central Tier List", "PUBLIC_MODE_PAGES_UNAVAILABLE", "snapshot unavailable: "+Http.rootMessage(t));
        }
    }
    private Snapshot stageFlowPvp(Consumer<String> progress) {
        Snapshot s=new Snapshot("flowpvp", "FlowPVP", true);
        LinkedHashMap<UUID, PlayerIdentity> players=new LinkedHashMap<>();
        LinkedHashMap<UUID, LinkedHashMap<String, TierEntry>> leaderboardTiers=new LinkedHashMap<>();
        int workingLadders=0;
        for(String ladder:FLOW_LADDERS) {
            checkCancelled();
            int before=players.size();
            Set<String> fingerprints=new HashSet<>(), boundaries=new HashSet<>();
            Set<UUID> ladderSeen=new HashSet<>();
            boolean ladderWorked=false, terminal=false;
            for(int page=1; page<=500; page++) {
                Http.Response resp;
                try {
                    if(page>1)sleepMillis(FLOW_PAGE_PACE_MS);
                    resp=awaitResponseWithRetry("https://flowpvp.gg/api/leaderboard/"+ladder+"?page="+page, 8, FlowPvpProvider.requestHeaders(), 5);
                } catch (Throwable requestError) {
                    if(isCancelled(requestError))throw new CancellationException("bulk sync cancelled");
                    s.failedPage(ladder+"#"+page);
                    s.fullRoster=false;
                    s.terminationReason="FLOW_PAGE_FAILED";
                    s.note=joinNotes(s.note, ladder+" page "+page+" network: "+Http.rootMessage(requestError));
                    
                    break;
                }
                if(resp.statusCode()==404&&page==1) {
                    s.failedPage(ladder+"#1");
                    s.fullRoster=false;
                    s.terminationReason="FLOW_LADDER_UNAVAILABLE";
                    s.note=joinNotes(s.note, ladder+" returned 404");
                    break;
                }
                if(!resp.ok()) {
                    s.failedPage(ladder+"#"+page);
                    s.fullRoster=false;
                    s.terminationReason="FLOW_PAGE_FAILED";
                    s.note=joinNotes(s.note, ladder+" HTTP "+resp.statusCode());
                    break;
                }
                s.pages.incrementAndGet();
                Object root;
                try {
                    root=MiniJson.parse(resp.body());
                } catch (Throwable t) {
                    s.failedPage(ladder+"#"+page);
                    s.reject("invalid JSON "+ladder+" page "+page, resp.body());
                    s.fullRoster=false;
                    s.terminationReason="FLOW_INVALID_JSON";
                    break;
                }
                List<Map<String, Object>> maps=new ArrayList<>();
                collectNamedMaps(root, maps, 0);
                s.rawReceived.addAndGet(maps.size());
                s.received.addAndGet(maps.size());
                if(maps.isEmpty()) {
                    terminal=true;
                    break;
                }
                ladderWorked=true;
                int added=0;
                List<String> pageKeys=new ArrayList<>();
                String kit=FlowPvpProvider.normalizeMode(ladder);
                for(Map<String, Object> m:maps) {
                    PlayerIdentity p=flowIdentity(m);
                    if(p==null) {
                        s.reject("Flow leaderboard row has no UUID/name", String.valueOf(m));
                        continue;
                    }
                    String key=identityKey(p);
                    pageKeys.add(key);
                    if(!ladderSeen.add(p.uuid()))s.duplicateIdentities.incrementAndGet();
                    if(players.putIfAbsent(p.uuid(), p)==null)added++;
                    if(!"Global".equalsIgnoreCase(kit)) {
                        String rawTier=strAny(m, "currentRank", "tier", "tierTag", "rank", "grantedTier");
                        int rating=intAny(m, "sr", "skillRating", "rating", "elo");
                        String current=FlowPvpProvider.normalizeFlowRank(rawTier, rating);
                        if(current!=null) {
                            boolean retired=explicitRetired(m, rawTier);
                            leaderboardTiers.computeIfAbsent(p.uuid(),
                                x->new LinkedHashMap<>()).put(kit.toLowerCase(Locale.ROOT),
                                new TierEntry(kit,
                                current,
                                current,
                                retired,
                                null));
                        }
                    }
                }
                if(pageKeys.isEmpty()) {
                    s.fullRoster=false;
                    s.terminationReason="FLOW_UNPARSEABLE_PAGE";
                    s.note=joinNotes(s.note, ladder+" page "+page+" had no valid identities");
                    break;
                }
                String fp=fingerprint(pageKeys), boundary=pageKeys.get(0)+" -> "+pageKeys.get(pageKeys.size()-1);
                if(!fingerprints.add(fp)) {
                    s.duplicatePages.incrementAndGet();
                    s.fullRoster=false;
                    s.terminationReason="REPEATED_PAGE";
                    s.note=joinNotes(s.note, "Flow "+ladder+" repeated page "+page);
                    break;
                }
                if(!boundaries.add(boundary)) {
                    s.duplicatePages.incrementAndGet();
                    s.fullRoster=false;
                    s.terminationReason="REPEATED_BOUNDARY";
                    s.note=joinNotes(s.note, "Flow "+ladder+" repeated boundary at page "+page);
                    break;
                }
                if(page%10==0)emit(progress, "FlowPVP "+ladder+": page "+page+" · unique "+players.size());
                if(page==500) {
                    s.fullRoster=false;
                    s.terminationReason="FLOW_HARD_CAP_REACHED";
                    s.note=joinNotes(s.note, ladder+" safety cap 500 pages reached");
                }
            }
            if(ladderWorked)workingLadders++;
            if(ladderWorked&&!terminal&&s.fullRoster) {
                s.fullRoster=false;
                s.terminationReason="FLOW_TERMINAL_PAGE_UNPROVEN";
            }
            emit(progress, "FlowPVP "+ladder+": +"+(players.size()-before)+" · всего "+players.size());
        }
        if(players.isEmpty())return stagePreserve("flowpvp", "FlowPVP", "LEADERBOARD_UNAVAILABLE", "leaderboard returned no usable players");
        if(workingLadders==0)s.fullRoster=false;
        s.uniqueIdentities.set(players.size());
        if(s.terminationReason==null)s.terminationReason=s.fullRoster?"LADDERS_EMPTY_PAGE_TERMINATED":"ROSTER_UNPROVEN";
        int directRows=0;
        for(var en:players.entrySet()) {
            checkCancelled();
            LinkedHashMap<String, TierEntry> direct=leaderboardTiers.get(en.getKey());
            if(direct==null||direct.isEmpty())continue;
            PlayerIdentity p=en.getValue();
            List<TierEntry> merged=mergeFlowPeaksFromCache(p, direct.values());
            s.add(p, new ProviderResult("flowpvp", "FlowPVP", ProviderResult.Status.OK, merged, null, System.currentTimeMillis()));
            directRows++;
        }
        if(directRows==0)return stagePreserve("flowpvp", "FlowPVP", "LEADERBOARD_TIER_ROWS_UNPARSED", "leaderboard identities loaded but tier fields were not recognized");
        s.note=joinNotes(s.note, "bulk tiers use explicit leaderboard rank first, Flow SR fallback only when rank is absent; no per-player profile fan-out");
        
        return s;
    }
    private List<TierEntry> mergeFlowPeaksFromCache(PlayerIdentity player, Collection<TierEntry> current) {
        PlayerProfile old=profiles.cached(player.uuid());
        if(old==null)old=profiles.cachedByName(player.name());
        Map<String, TierEntry> oldByKit=new HashMap<>();
        if(old!=null) {
            ProviderResult prev=old.providers().get("flowpvp");
            if(prev!=null&&prev.tiers()!=null)for(TierEntry t:prev.tiers())if(t!=null&&t.gamemode()!=null)oldByKit.put(FlowPvpProvider.normalizeMode(t.gamemode()).toLowerCase(Locale.ROOT),
                t);
        }
        ArrayList<TierEntry> out=new ArrayList<>();
        for(TierEntry now:current) {
            TierEntry prev=oldByKit.get(FlowPvpProvider.normalizeMode(now.gamemode()).toLowerCase(Locale.ROOT));
            String peak=now.currentTier();
            if(prev!=null) {
                String oldPeak=TierRank.normalize(prev.peakTier());
                if(oldPeak!=null&&tierQuality(oldPeak)<tierQuality(peak))peak=oldPeak;
            }
            out.add(new TierEntry(now.gamemode(), now.currentTier(), peak, now.retired(), now.lastTest()));
        }
        return List.copyOf(out);
    }
    private static int tierQuality(String tier) {
        String t=TierRank.normalize(tier);
        if(t==null)return Integer.MAX_VALUE;
        int n=t.charAt(2)-'0';
        int band=t.startsWith("HT")?0:t.startsWith("MT")?1:2;
        return (n-1)*3+band;
    }
    private Snapshot stagePagedCommon(String id, String display, List<String> bases, Consumer<String> progress) {
        Throwable last=null;
        for(String base:bases) {
            Snapshot s=new Snapshot(id, display, true);
            int offset=0;
            try {
                Set<String> globalSeen=new HashSet<>(), pageFingerprints=new HashSet<>(), boundaryFingerprints=new HashSet<>();
                while(offset<COMMON_HARD_CAP) {
                    checkCancelled();
                    int remainingPages=(COMMON_HARD_CAP-offset+COMMON_PAGE_SIZE-1)/COMMON_PAGE_SIZE;
                    int window=Math.min(COMMON_MAX_WINDOW, remainingPages);
                    ArrayList<Integer> offsets=new ArrayList<>(window);
                    ArrayList<CompletableFuture<String>> futures=new ArrayList<>(window);
                    for(int i=0; i<window; i++) {
                        int off=offset+i*COMMON_PAGE_SIZE;
                        offsets.add(off);
                        futures.add(asyncGetWithRetry(base+off, 8, 3));
                    }
                    
                    boolean terminal=false;
                    try {
                        for(int i=0; i<futures.size(); i++) {
                            checkCancelled();
                            int off=offsets.get(i);
                            offset=off;
                            String body=await(futures.get(i));
                            if(processCommonPage(s, id, display, off, body, globalSeen, pageFingerprints, boundaryFingerprints, progress)) {
                                terminal=true;
                                for(int j=i+1; j<futures.size(); j++)futures.get(j).cancel(true);
                                break;
                            }
                            offset=off+COMMON_PAGE_SIZE;
                        }
                    } finally {
                        if(terminal||cancelRequested.get())for(CompletableFuture<String> f:futures)if(!f.isDone())f.cancel(true);
                    }
                    if(terminal)break;
                }
                if(s.terminationReason==null) {
                    s.fullRoster=false;
                    s.terminationReason="HARD_CAP_REACHED";
                    s.note=joinNotes(s.note, "safety cap "+COMMON_HARD_CAP+" reached without terminal proof");
                }
                if("HARD_CAP_REACHED".equals(s.terminationReason))s.fullRoster=false;
                if(s.parsed.get()==0)throw new IllegalStateException(display+" no bulk rows");
                return s;
            } catch (Throwable t) {
                if(isCancelled(t))throw new CancellationException("bulk sync cancelled");
                last=t;
                
                if(s.parsed.get()>0) {
                    s.fullRoster=false;
                    s.failedPage("offset "+offset);
                    s.terminationReason="PAGINATION_INTERRUPTED";
                    s.note=joinNotes(s.note, "pagination interrupted at offset "+offset+": "+Http.rootMessage(t));
                    return s;
                }
            }
        }
        throw new CompletionException(last==null?new IllegalStateException(display+" roster unavailable"):last);
    }
    private boolean processCommonPage(Snapshot s,
        String id,
        String display,
        int offset,
        String body,
        Set<String> globalSeen,
        Set<String> pageFingerprints,
        Set<String> boundaryFingerprints,
        Consumer<String> progress) {
        s.pages.incrementAndGet();
        Object root=MiniJson.parse(body);
        List<Map<String, Object>> maps=new ArrayList<>();
        collectPlayerMaps(root, maps, 0);
        s.rawReceived.addAndGet(maps.size());
        LinkedHashMap<String, Map<String, Object>> pageUnique=new LinkedHashMap<>();
        List<String> orderedKeys=new ArrayList<>();
        for(Map<String, Object> m:maps) {
            PlayerIdentity p=identityFromMap(m);
            if(p==null) {
                s.reject("player row missing UUID/name", String.valueOf(m));
                continue;
            }
            String key=identityKey(p);
            orderedKeys.add(key);
            if(pageUnique.putIfAbsent(key, m)!=null)s.duplicateIdentities.incrementAndGet();
        }
        s.received.addAndGet(pageUnique.size());
        if(pageUnique.isEmpty()) {
            if(offset==0)throw new IllegalStateException(display+" roster endpoint returned no players");
            s.terminationReason="EMPTY_PAGE";
            return true;
        }
        String pageFingerprint=fingerprint(orderedKeys);
        String first=orderedKeys.isEmpty()?"":orderedKeys.get(0), lastKey=orderedKeys.isEmpty()?"":orderedKeys.get(orderedKeys.size()-1);
        String boundary=first+" -> "+lastKey;
        if(!pageFingerprints.add(pageFingerprint)) {
            s.duplicatePages.incrementAndGet();
            s.fullRoster=false;
            s.terminationReason="REPEATED_PAGE";
            s.note=joinNotes(s.note, "repeated page at offset "+offset);
            return true;
        }
        if(!boundaryFingerprints.add(boundary)) {
            s.duplicatePages.incrementAndGet();
            s.fullRoster=false;
            s.terminationReason="REPEATED_BOUNDARY";
            s.note=joinNotes(s.note, "repeated first/last identity boundary at offset "+offset);
            return true;
        }
        int newIdentities=0;
        for(var en:pageUnique.entrySet()) {
            checkCancelled();
            String key=en.getKey();
            if(!globalSeen.add(key)) {
                s.duplicateIdentities.incrementAndGet();
                continue;
            }
            s.uniqueIdentities.incrementAndGet();
            newIdentities++;
            Map<String, Object> row=en.getValue();
            PlayerIdentity p=identityFromMap(row);
            if(p==null)continue;
            ProviderResult r=CommonTierProfileProvider.parseProfileBody(id, display, MiniJson.stringify(row));
            if(r.status()==ProviderResult.Status.ERROR) {
                s.reject("profile parser ERROR", MiniJson.stringify(row));
                continue;
            }
            s.add(p, r);
            int rank=officialOverallRank(row);
            if(rank>0&&rank<=20)s.addNotable(p.uuid(), new NotableStatus(NotableStatus.Type.WORLD_LEGEND, rank, display, System.currentTimeMillis()));
            if(authoritativeCreator(row))s.addNotable(p.uuid(), new NotableStatus(NotableStatus.Type.CREATOR, 0, display, System.currentTimeMillis()));
        }
        if(s.pages.get()%16==0||pageUnique.size()<COMMON_PAGE_SIZE)emit(progress,
            display+" staging: parsed "+s.parsed.get()+" · raw "+s.rawReceived.get()+" · unique "+s.uniqueIdentities.get()+" · dup "+s.duplicateIdentities.get()+" · pages "+s.pages.get());
        Integer explicitTotal=explicitRosterTotal(root);
        if(explicitTotal!=null&&explicitTotal>=0&&s.uniqueIdentities.get()>=explicitTotal) {
            s.terminationReason="API_TOTAL_REACHED";
            s.note=joinNotes(s.note, "API total="+explicitTotal);
            return true;
        }
        if(pageUnique.size()<COMMON_PAGE_SIZE) {
            s.terminationReason="SHORT_PAGE";
            return true;
        }
        if(newIdentities==0) {
            s.fullRoster=false;
            s.terminationReason="NO_NEW_IDENTITIES";
            s.note=joinNotes(s.note, "full page produced no new identities at offset "+offset);
            return true;
        }
        return false;
    }
    private Snapshot stagePreserve(String id, String display, String termination, String note) {
        Snapshot s=new Snapshot(id, display, false);
        s.terminationReason=termination;
        s.note=note;
        return s;
    }
    private Snapshot stageExisting(String id, String display, String reason, Consumer<String> progress) {
        Snapshot s=new Snapshot(id, display, false);
        s.note=reason;
        s.terminationReason="FALLBACK_EXISTING_ROWS";
        TierProvider provider=requireProvider(id);
        ArrayList<PlayerIdentity> known=new ArrayList<>();
        for(PlayerProfile cached:profiles.allCachedProfiles()) {
            ProviderResult old=cached.providers().get(id);
            if(old!=null&&old.status()==ProviderResult.Status.OK)known.add(cached.player());
        }
        if(known.isEmpty())throw new IllegalStateException(display+" full roster unavailable and no previous local rows exist");
        s.received.set(known.size());
        stageLookups(s, provider, known, progress);
        return s;
    }
    private void stageLookups(Snapshot s, TierProvider provider, List<PlayerIdentity> identities, Consumer<String> progress) {
        int total=identities.size();
        AtomicInteger done=new AtomicInteger();
        int batchSize=Math.max(1, fastParallelism);
        for(int from=0; from<identities.size(); from+=batchSize) {
            checkCancelled();
            List<CompletableFuture<Void>> batch=new ArrayList<>();
            for(int i=from; i<Math.min(identities.size(), from+batchSize); i++) {
                PlayerIdentity p=identities.get(i);
                batch.add(lookupForBulk(provider, p).handle((r, e)-> {
                    if(e!=null||r==null||r.status()==ProviderResult.Status.ERROR)s.reject("lookup failed "+p.name()+": "+(e==null?(r==null?"null":r.message()):Http.rootMessage(e)),
                        p.name());
                    else s.add(p, r);
                    int d=done.incrementAndGet();
                    if(d%100==0||d==total)emit(progress, provider.displayName()+" staging: "+d+"/"+total);
                    return null;
                }
                ));
            }
            CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new)).join();
        }
    }
    /**
    * Verification checks players that existed in the previous mirror but are absent from the new roster.
    * If an individual endpoint still says they are ranked, the new roster cannot be called COMPLETE.
    */ private Verification verifySnapshot(Snapshot s, Consumer<String> progress) {
        if(!s.fullRoster||s.parsed.get()==0)return new Verification(0, 0, false, "roster completeness not proven");
        TierProvider provider=byId.get(s.id);
        if(provider==null)return new Verification(0, 0, false, null);
        ArrayList<PlayerIdentity> missing=new ArrayList<>();
        for(PlayerProfile p:profiles.allCachedProfiles()) {
            ProviderResult old=p.providers().get(s.id);
            if(old==null||old.status()!=ProviderResult.Status.OK)continue;
            if(!s.contains(p.player()))missing.add(p.player());
        }
        if(missing.isEmpty())return new Verification(0, 0, false, null);
        int sample=Math.min(25, missing.size()), checked=0, gaps=0, errors=0;
        for(int i=0; i<sample; i++) {
            checkCancelled();
            int idx=sample==1?0:(int)Math.round(i*(missing.size()-1.0)/(sample-1.0));
            PlayerIdentity p=missing.get(idx);
            try {
                ProviderResult r=lookupForBulk(provider, p).join();
                if(r==null||r.status()==ProviderResult.Status.ERROR) {
                    errors++;
                    continue;
                }
                checked++;
                if(r.status()==ProviderResult.Status.OK&&!r.tiers().isEmpty())gaps++;
            } catch (Throwable t) {
                errors++;
            }
        }
        if(gaps>0)emit(progress, s.display+": verification нашёл "+gaps+" пропущенных ranked игроков из "+checked);
        boolean uncertain=errors>Math.max(2, sample/3);
        String msg=gaps>0?"verification gaps="+gaps+" of "+checked:(uncertain?"verification unavailable for "+errors+" samples":null);
        return new Verification(checked, gaps, uncertain, msg);
    }
    private String awaitGet(String url, int timeout, int attempts) {
        Throwable last=null;
        for(int i=1; i<=attempts; i++) {
            checkCancelled();
            try {
                return await(bulkRequest(()->Http.get(url, timeout)));
            } catch (Throwable t) {
                last=t;
                if(i<attempts) {
                    
                    sleepRetry(i);
                }
            }
        }
        throw new CompletionException(last==null?new IllegalStateException("request failed"):last);
    }
    private CompletableFuture<String> asyncGetWithRetry(String url, int timeout, int attempts) {
        CompletableFuture<String> out=new CompletableFuture<>();
        asyncGetAttempt(url, timeout, attempts, 1, out);
        return track(out);
    }
    private void asyncGetAttempt(String url, int timeout, int attempts, int attempt, CompletableFuture<String> out) {
        if(out.isDone())return;
        if(cancelRequested.get()) {
            out.completeExceptionally(new CancellationException("bulk sync cancelled"));
            return;
        }
        CompletableFuture<String> request=bulkRequest(()->Http.get(url, timeout));
        activeRequests.add(request);
        request.whenComplete((body, error)-> {
            activeRequests.remove(request); if(out.isDone())return; if(error==null) {
                out.complete(body); return;
            }
            if(attempt>=attempts) {
                out.completeExceptionally(error); return;
            }
            
            CompletableFuture.delayedExecutor(250L*attempt, TimeUnit.MILLISECONDS).execute(()->asyncGetAttempt(url, timeout, attempts, attempt+1, out));
        }
        );
    }
    private Http.Response awaitResponseWithRetry(String url, int timeout, Map<String, String> headers, int attempts) {
        Throwable last=null;
        for(int i=1; i<=attempts; i++) {
            checkCancelled();
            try {
                Http.Response r=await(bulkRequest(()->Http.getResponse(url, timeout, headers)));
                if((r.statusCode()==429||r.statusCode()>=500)&&i<attempts) {
                    
                    if(r.statusCode()==429)sleepRateLimit(i);
                    else sleepRetry(i);
                    continue;
                }
                return r;
            } catch (Throwable t) {
                last=t;
                if(i<attempts) {
                    
                    sleepRetry(i);
                }
            }
        }
        throw new CompletionException(last==null?new IllegalStateException("request failed"):last);
    }
    private static void sleepRetry(int attempt) {
        sleepMillis(200L*attempt);
    }
    private static void sleepRateLimit(int attempt) {
        sleepMillis(Math.min(8_000L, 750L*(1L<<Math.min(4, Math.max(0, attempt-1)))));
    }
    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("interrupted");
        }
    }
    private TierProvider requireProvider(String id) {
        TierProvider p=byId.get(id);
        if(p==null)throw new IllegalStateException("provider not found: "+id);
        return p;
    }
    private PlayerIdentity knownOrSynthetic(String name) {
        PlayerProfile cached=profiles.cachedByName(name);
        return cached==null?new PlayerIdentity(ProfileService.syntheticUuid(name), name):cached.player();
    }
    private CompletableFuture<ProviderResult> lookupForBulk(TierProvider provider, PlayerIdentity player) {
        try {
            return bulkRequest(()-> {
                CompletableFuture<ProviderResult> raw=provider.lookup(player);
                if(raw==null)return CompletableFuture.completedFuture(ProviderResult.error(provider.id(), provider.displayName(), "empty provider future"));
                CompletableFuture<ProviderResult> guarded=raw.copy().orTimeout(12, TimeUnit.SECONDS);
                guarded.whenComplete((r, e)-> {
                    if(e!=null&&!raw.isDone())raw.cancel(true);
                }
                ); return guarded;
            }
            );
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }
    private <T> CompletableFuture<T> bulkRequest(Supplier<CompletableFuture<T>> start) {
        acquireBulkPermit();
        CompletableFuture<T> f;
        try {
            f=start.get();
            if(f==null)throw new IllegalStateException("null bulk request future");
        } catch (Throwable t) {
            bulkNetworkPermits.release();
            throw t;
        }
        f.whenComplete((r, e)->bulkNetworkPermits.release());
        return track(f);
    }
    private void acquireBulkPermit() {
        try {
            while(!bulkNetworkPermits.tryAcquire(100, TimeUnit.MILLISECONDS))checkCancelled();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("interrupted");
        }
    }
    private static PlayerIdentity flowIdentity(Map<String, Object> m) {
        Object u=getAny(m, "uuid", "_id", "id", "minecraftUuid", "minecraft_uuid");
        Object n=getAny(m, "lastKnownName", "name", "username", "nickname", "ign");
        if(u==null||n==null||!validName(String.valueOf(n)))return null;
        UUID id=parseUuid(String.valueOf(u));
        return id==null?null:new PlayerIdentity(id, String.valueOf(n));
    }
    @SuppressWarnings("unchecked") private static void collectPlayerMaps(Object node, List<Map<String, Object>> out, int depth) {
        if(node==null||depth>10)return;
        if(node instanceof Map<?, ?> raw) {
            Map<String, Object> m=(Map<String, Object>)raw;
            if(identityFromMap(m)!=null) {
                out.add(m);
                return;
            }
            for(Object v:m.values())collectPlayerMaps(v, out, depth+1);
        } else if(node instanceof List<?> l)for(Object v:l)collectPlayerMaps(v, out, depth+1);
    }
    private static PlayerIdentity identityFromMap(Map<String, Object> m) {
        Object u=getAny(m, "uuid", "minecraft_uuid", "minecraftUuid", "id", "_id");
        Object n=getAny(m, "name", "username", "minecraft_username", "ign", "nickname", "lastKnownName");
        if(u==null||n==null||!validName(String.valueOf(n)))return null;
        UUID id=parseUuid(String.valueOf(u));
        return id==null?null:new PlayerIdentity(id, String.valueOf(n));
    }
    private static String identityKey(PlayerIdentity p) {
        return p==null?"":p.uuid()+"|"+p.name().toLowerCase(Locale.ROOT);
    }
    private static String fingerprint(Collection<String> keys) {
        StringBuilder b=new StringBuilder();
        for(String k:keys)b.append(k).append('\n');
        return UUID.nameUUIDFromBytes(b.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static Integer explicitRosterTotal(Object root) {
        if(!(root instanceof Map<?, ?> m))return null;
        Integer direct=explicitTotalFromMap(m);
        if(direct!=null)return direct;
        for(String k:List.of("meta", "metadata", "pagination", "pageInfo", "page_info")) {
            Object v=getAny(m, k);
            if(v instanceof Map<?, ?> nested) {
                Integer n=explicitTotalFromMap(nested);
                if(n!=null)return n;
            }
        }
        return null;
    }
    private static Integer explicitTotalFromMap(Map<?, ?> m) {
        Object v=getAny(m, "total", "total_count", "totalCount", "total_players", "totalPlayers", "player_count", "playerCount");
        if(v instanceof Number n) {
            int x=n.intValue();
            return x>=0?x:null;
        }
        try {
            int x=Integer.parseInt(String.valueOf(v));
            return x>=0?x:null;
        } catch (Exception e) {
            return null;
        }
    }
    private static UUID parseUuid(String value) {
        if(value==null)return null;
        String raw=value.trim().replace("-", "");
        if(raw.length()!=32)return null;
        try {
            return UUID.fromString(raw.substring(0, 8)+"-"+raw.substring(8, 12)+"-"+raw.substring(12, 16)+"-"+raw.substring(16, 20)+"-"+raw.substring(20));
        } catch (Exception e) {
            return null;
        }
    }
    private static int officialOverallRank(Map<?, ?> m) {
        Object v=getAny(m, "overall", "overall_rank", "overallRank", "position", "position_overall", "overallPosition", "rank", "rank_no", "rankNo", "place");
        if(v instanceof Number n)return n.intValue()>0?n.intValue():0;
        try {
            int x=Integer.parseInt(String.valueOf(v));
            return x>0?x:0;
        } catch (Exception e) {
            return 0;
        }
    }
    private static boolean authoritativeCreator(Map<?, ?> m) {
        Object explicit=getAny(m, "creator", "is_creator", "isCreator", "content_creator", "contentCreator", "is_content_creator", "isContentCreator");
        if(Boolean.TRUE.equals(explicit)||explicit!=null&&"true".equalsIgnoreCase(String.valueOf(explicit)))return true;
        Object role=getAny(m, "role", "roles", "badge", "badges", "account_type", "accountType");
        if(role!=null) {
            String x=String.valueOf(role).toLowerCase(Locale.ROOT);
            if(x.contains("creator")||x.contains("youtuber")||x.contains("youtube"))return true;
        }
        Object youtube=getAny(m, "youtube", "youtube_url", "youtubeUrl");
        return youtube!=null&&!String.valueOf(youtube).isBlank()&&!"null".equalsIgnoreCase(String.valueOf(youtube));
    }
    @SuppressWarnings("unchecked") private static void collectNamedMaps(Object node, List<Map<String, Object>> out, int depth) {
        if(node==null||depth>10)return;
        if(node instanceof Map<?, ?> raw) {
            Map<String, Object> m=(Map<String, Object>)raw;
            String n=strAny(m, "nickname", "name", "username", "minecraft_username", "ign", "lastKnownName");
            if(validName(n))out.add(m);
            for(Object v:m.values())collectNamedMaps(v, out, depth+1);
        } else if(node instanceof List<?> l)for(Object v:l)collectNamedMaps(v, out, depth+1);
    }
    @SuppressWarnings("unchecked") private static void collectNames(Object node, List<String>out, int depth) {
        if(node==null||depth>8)return;
        if(node instanceof String s) {
            if(validName(s))out.add(s);
            return;
        }
        if(node instanceof List<?> l) {
            for(Object v:l)collectNames(v, out, depth+1);
            return;
        }
        if(node instanceof Map<?, ?> m) {
            String n=strAny(m, "nickname", "name", "username", "minecraft_username", "ign", "lastKnownName");
            if(validName(n))out.add(n);
            else for(Object v:m.values())collectNames(v, out, depth+1);
        }
    }
    private static boolean validName(String s) {
        return s!=null&&s.matches("[A-Za-z0-9_]{1,16}");
    }
    private static Object getAny(Map<?, ?>m, String...ks) {
        for(String k:ks)for(var e:m.entrySet())if(String.valueOf(e.getKey()).equalsIgnoreCase(k))return e.getValue();
        return null;
    }
    private static String strAny(Map<?, ?>m, String...ks) {
        Object v=getAny(m, ks);
        return v==null?null:String.valueOf(v);
    }
    private static int intAny(Map<?, ?>m, String...ks) {
        Object v=getAny(m, ks);
        if(v instanceof Number n)return n.intValue();
        try {
            return v==null?0:Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0;
        }
    }
    private static boolean explicitRetired(Map<?, ?> m, String rawTier) {
        if(rawTier!=null&&rawTier.trim().toUpperCase(Locale.ROOT).startsWith("R")&&TierRank.normalize(rawTier)!=null)return true;
        Object flag=getAny(m, "retired", "isRetired", "is_retired");
        if(Boolean.TRUE.equals(flag)||flag!=null&&"true".equalsIgnoreCase(String.valueOf(flag).trim()))return true;
        Object status=getAny(m, "status", "state", "tierStatus", "tier_status", "rankStatus", "rank_status");
        return status!=null&&"retired".equalsIgnoreCase(String.valueOf(status).trim());
    }
    private <T> CompletableFuture<T> track(CompletableFuture<T> f) {
        activeRequests.add(f);
        f.whenComplete((r, e)->activeRequests.remove(f));
        if(cancelRequested.get())f.cancel(true);
        return f;
    }
    private <T> T await(CompletableFuture<T> f) {
        checkCancelled();
        try {
            return track(f).join();
        } catch (CompletionException e) {
            if(isCancelled(e)||cancelRequested.get())throw new CancellationException("bulk sync cancelled");
            throw e;
        } finally {
            activeRequests.remove(f);
            checkCancelled();
        }
    }
    private void emit(Consumer<String> c, String s) {
        if(cancelRequested.get())return;
        
        if(c!=null)try {
            c.accept(s);
        } catch (Throwable ignored) {
        }
    }
    private void checkCancelled() {
        if(cancelRequested.get())throw new CancellationException("bulk sync cancelled");
    }
    private static boolean isCancelled(Throwable t) {
        Throwable x=t;
        while(x!=null) {
            if(x instanceof CancellationException)return true;
            x=x.getCause();
        }
        return false;
    }
    private static String joinNotes(String... notes) {
        StringBuilder b=new StringBuilder();
        for(String n:notes) {
            if(n==null||n.isBlank())continue;
            if(b.length()>0)b.append("; ");
            b.append(n);
        }
        return b.toString();
    }
    public static boolean selfTestRepeatedFullPageDetection() {
        List<String> page=new ArrayList<>();
        for(int i=0; i<COMMON_PAGE_SIZE; i++)page.add("uuid-"+i+"|player"+i);
        Set<String> fingerprints=new HashSet<>(), boundaries=new HashSet<>();
        String fp=fingerprint(page), boundary=page.get(0)+" -> "+page.get(page.size()-1);
        boolean first=fingerprints.add(fp)&&boundaries.add(boundary);
        boolean repeated=!fingerprints.add(fingerprint(page))||!boundaries.add(boundary);
        return first&&repeated;
    }
    public static boolean selfTestHardCapTermination() {
        int offset=0;
        String reason=null;
        while(offset<COMMON_HARD_CAP)offset+=COMMON_PAGE_SIZE;
        if(reason==null&&offset>=COMMON_HARD_CAP)reason="HARD_CAP_REACHED";
        return offset==COMMON_HARD_CAP&&"HARD_CAP_REACHED".equals(reason);
    }
    private record SourceJob(String id, String display, Supplier<Snapshot> action) {
    }
    private record Verification(int checked, int gaps, boolean uncertain, String message) {
    }
    private static final class Counters {
        final AtomicInteger touched=new AtomicInteger(), skipped=new AtomicInteger(), complete=new AtomicInteger(), partial=new AtomicInteger(), failed=new AtomicInteger();
    }
    private static final class Snapshot {
        final String id, display;
        volatile boolean fullRoster;
        volatile String note, terminationReason;
        final ConcurrentLinkedQueue<ProfileService.MirrorRow> rowQueue=new ConcurrentLinkedQueue<>();
        final List<ProfileService.MirrorReject> rejects=Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger received=new AtomicInteger(),
            rawReceived=new AtomicInteger(),
            uniqueIdentities=new AtomicInteger(),
            duplicateIdentities=new AtomicInteger(),
            duplicatePages=new AtomicInteger(),
            parsed=new AtomicInteger(),
            rejected=new AtomicInteger(),
            pages=new AtomicInteger(),
            failedPages=new AtomicInteger();
        final Set<String> uuids=ConcurrentHashMap.newKeySet(), names=ConcurrentHashMap.newKeySet(), observedIdentities=ConcurrentHashMap.newKeySet();
        final Set<String> failedPageSet=Collections.synchronizedSet(new LinkedHashSet<>());
        final ConcurrentHashMap<UUID, List<NotableStatus>> notable=new ConcurrentHashMap<>();
        Snapshot(String id, String display, boolean full) {
            this.id=id;
            this.display=display;
            this.fullRoster=full;
        }
        void add(PlayerIdentity p, ProviderResult r) {
            if(p==null||r==null)return;
            rowQueue.add(new ProfileService.MirrorRow(p, r));
            uuids.add(p.uuid().toString());
            names.add(p.name().toLowerCase(Locale.ROOT));
            parsed.incrementAndGet();
        }
        void observeIdentity(String key) {
            if(key==null||key.isBlank())return;
            if(observedIdentities.add(key))uniqueIdentities.incrementAndGet();
            else duplicateIdentities.incrementAndGet();
        }
        void reject(String reason, String raw) {
            rejected.incrementAndGet();
            if(rejects.size()<REJECT_LIMIT)rejects.add(new ProfileService.MirrorReject(reason, raw));
        }
        void failedPage(String page) {
            failedPages.incrementAndGet();
            if(page!=null&&!page.isBlank()&&failedPageSet.size()<32)failedPageSet.add(page);
        }
        String failedPageDetails() {
            synchronized(failedPageSet) {
                return failedPageSet.isEmpty()?null:String.join(", ", failedPageSet);
            }
        }
        void addNotable(UUID id, NotableStatus n) {
            notable.compute(id, (k, v)-> {
                ArrayList<NotableStatus> x=v==null?new ArrayList<>():new ArrayList<>(v); x.add(n); return List.copyOf(x);
            }
            );
        }
        boolean contains(PlayerIdentity p) {
            return p!=null&&(uuids.contains(p.uuid().toString())||names.contains(p.name().toLowerCase(Locale.ROOT)));
        }
        List<ProfileService.MirrorRow> rows() {
            return List.copyOf(rowQueue);
        }
        List<ProfileService.MirrorReject> rejects() {
            synchronized(rejects) {
                return List.copyOf(rejects);
            }
        }
        void finalizeCoverage() {
            if(rawReceived.get()==0)rawReceived.set(received.get());
            if(received.get()==0)received.set(rawReceived.get());
            if(uniqueIdentities.get()==0)uniqueIdentities.set(Math.max(parsed.get(), Math.max(0, received.get()-duplicateIdentities.get())));
            if(terminationReason==null)terminationReason=fullRoster?"SNAPSHOT_RETURNED":"ROSTER_UNPROVEN";
        }
    }
}
