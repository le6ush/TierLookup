package com.tierlookup.service;

import com.tierlookup.client.BootstrapLog;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import com.tierlookup.model.*;

/** Persistent relational storage. Gameplay hover never calls this class. */
final class SqliteProfileStore implements AutoCloseable {
    static final int SCHEMA=4;
    record Loaded(PlayerProfile profile,
        Set<String> aliases,
        long lastSeenAt,
        String lastSeenServer,
        boolean favorite,
        long favoriteAddedAt,
        long favoriteLastUsedAt,
        long watchSeenAt,
        long historyLatestAt,
        Set<String> sourceHistoryLoaded,
        List<NotableStatus> notable,
        String note) {
    }
    record Bundle(PlayerProfile profile,
        List<String> aliases,
        long lastSeenAt,
        String lastSeenServer,
        boolean favorite,
        long favoriteAddedAt,
        long favoriteLastUsedAt,
        long watchSeenAt,
        List<String> sourceHistoryLoaded,
        List<TierHistoryEvent> history,
        List<NotableStatus> notable,
        String note) {
    }
    private final Path dbFile, libDir;
    private final ConcurrentHashMap<UUID, Bundle> pending=new ConcurrentHashMap<>();
    private record SeenUpdate(UUID id, String name, long lastSeenAt, String server) {
    }
    private final ConcurrentHashMap<UUID, SeenUpdate> pendingSeen=new ConcurrentHashMap<>();
    private final Set<UUID> pendingDelete=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService writer=Executors.newSingleThreadScheduledExecutor(r-> {
        Thread t=new Thread(r, "TierLookup-SQLite"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t;
    }
    );
    private final Object flushLock=new Object();
    private ScheduledFuture<?> scheduled;
    private volatile boolean closed;
    SqliteProfileStore(Path dbFile) throws Exception {
        this.dbFile=dbFile;
        this.libDir=dbFile.getParent().resolve("lib");
        Files.createDirectories(dbFile.getParent());
        try(Connection c=open()) {
            configure(c, false);
            createSchema(c);
        }
    }
    Path dbFile() {
        return dbFile;
    }
    private Connection open()throws Exception {
        return SqliteDriverBootstrap.connect(dbFile, libDir);
    }
    private static void configure(Connection c, boolean bulk)throws SQLException {
        try(Statement s=c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            // NORMAL keeps WAL durable against ordinary process/OS crashes while avoiding the unsafe zero-sync mode.
            try {
                s.execute("PRAGMA synchronous=NORMAL");
            } catch (SQLException e) {
                
            }
            s.execute("PRAGMA temp_store=MEMORY");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=30000");
            s.execute("PRAGMA wal_autocheckpoint=1000");
        }
    }
    private static void createSchema(Connection c)throws SQLException {
        try(Statement s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS players(uuid TEXT PRIMARY KEY,current_name TEXT NOT NULL,name_lower TEXT NOT NULL,fetched_at INTEGER NOT NULL DEFAULT 0,last_seen_at INTEGER NOT NULL DEFAULT 0,last_seen_server TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS aliases(uuid TEXT NOT NULL,alias TEXT NOT NULL,alias_lower TEXT NOT NULL,seen_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(uuid,alias_lower))");
            s.execute("CREATE TABLE IF NOT EXISTS provider_results(uuid TEXT NOT NULL,provider_id TEXT NOT NULL,display_name TEXT NOT NULL,status TEXT NOT NULL,message TEXT,fetched_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(uuid,provider_id))");
            s.execute("CREATE TABLE IF NOT EXISTS tiers(uuid TEXT NOT NULL,provider_id TEXT NOT NULL,gamemode TEXT NOT NULL,current_tier TEXT,peak_tier TEXT,retired INTEGER NOT NULL DEFAULT 0,last_test TEXT,PRIMARY KEY(uuid,provider_id,gamemode))");
            s.execute("CREATE TABLE IF NOT EXISTS tier_history(id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL,at INTEGER NOT NULL,provider_id TEXT NOT NULL,gamemode TEXT NOT NULL,old_tier TEXT,new_tier TEXT,old_retired INTEGER NOT NULL DEFAULT 0,new_retired INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS favorites(uuid TEXT PRIMARY KEY,added_at INTEGER NOT NULL,last_used_at INTEGER NOT NULL DEFAULT 0,watch_seen_at INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS source_history(uuid TEXT NOT NULL,provider_id TEXT NOT NULL,loaded_at INTEGER NOT NULL,PRIMARY KEY(uuid,provider_id))");
            s.execute("CREATE TABLE IF NOT EXISTS notable_status(uuid TEXT NOT NULL,type TEXT NOT NULL,rank_no INTEGER NOT NULL DEFAULT 0,source TEXT NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(uuid,type,source))");
            s.execute("CREATE TABLE IF NOT EXISTS player_notes(uuid TEXT PRIMARY KEY,note TEXT NOT NULL,updated_at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_players_name ON players(name_lower)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_players_server ON players(last_seen_server)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_players_seen ON players(last_seen_at DESC)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_alias_name ON aliases(alias_lower)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_history_player ON tier_history(uuid,at DESC)");
            // The semantic migration is deliberately conservative. Earlier schemas could infer Retired
            // from inactive/stale metadata and persisted a regional flag. Provenance was not stored, so the only
            // safe way to guarantee source-exact semantics is to clear those legacy derived flags once. Explicit
            // Retired values will be restored by the next provider refresh/sync from the source itself.
            boolean retiredExact=false;
            try(ResultSet r=s.executeQuery("SELECT value FROM meta WHERE key='retired_source_explicit_v1'")) {
                retiredExact=r.next()&&"1".equals(r.getString(1));
            }
            if(!retiredExact) {
                s.executeUpdate("UPDATE tiers SET retired=0 WHERE retired<>0");
                s.executeUpdate("UPDATE tier_history SET old_retired=0,new_retired=0 WHERE old_retired<>0 OR new_retired<>0");
                s.execute("INSERT INTO meta(key,value) VALUES('retired_source_explicit_v1','1') ON CONFLICT(key) DO UPDATE SET value='1'");
                
            }
            if(hasColumn(c, "provider_results", "cis")) {
                s.execute("ALTER TABLE provider_results RENAME TO provider_results_region_legacy");
                s.execute("CREATE TABLE provider_results(uuid TEXT NOT NULL,provider_id TEXT NOT NULL,display_name TEXT NOT NULL,status TEXT NOT NULL,message TEXT,fetched_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(uuid,provider_id))");
                s.execute("INSERT INTO provider_results(uuid,provider_id,display_name,status,message,fetched_at) SELECT uuid,provider_id,display_name,status,message,fetched_at FROM provider_results_region_legacy");
                s.execute("DROP TABLE provider_results_region_legacy");
                
            }
            s.execute("INSERT INTO meta(key,value) VALUES('schema_version','"+SCHEMA+"') ON CONFLICT(key) DO UPDATE SET value=excluded.value");
        }
    }
    int playerCount() {
        try(Connection c=open(); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT COUNT(*) FROM players")) {
            return r.next()?r.getInt(1):0;
        } catch (Exception e) {
            BootstrapLog.error("SQLITE count", e);
            return 0;
        }
    }
    List<Loaded> loadAll() throws Exception {
        long started=System.nanoTime();
        LinkedHashMap<UUID, MutableLoaded> map=new LinkedHashMap<>();
        try(Connection c=open()) {
            configure(c, false);
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT uuid,current_name,fetched_at,last_seen_at,last_seen_server FROM players")) {
                while(r.next()) {
                    UUID id=UUID.fromString(r.getString(1));
                    map.put(id, new MutableLoaded(id, r.getString(2), r.getLong(3), r.getLong(4), r.getString(5)));
                }
            }
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT uuid,provider_id,display_name,status,message,fetched_at FROM provider_results")) {
                while(r.next()) {
                    MutableLoaded m=map.get(parseUuid(r.getString(1)));
                    if(m==null)continue;
                    ProviderResult.Status st;
                    try {
                        st=ProviderResult.Status.valueOf(r.getString(4));
                    } catch (Exception e) {
                        st=ProviderResult.Status.NOT_RANKED;
                    }
                    m.providers.put(r.getString(2), new MutableProvider(r.getString(2), r.getString(3), st, r.getString(5), r.getLong(6)));
                }
            }
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT uuid,provider_id,gamemode,current_tier,peak_tier,retired,last_test FROM tiers")) {
                while(r.next()) {
                    MutableLoaded m=map.get(parseUuid(r.getString(1)));
                    if(m==null)continue;
                    MutableProvider p=m.providers.get(r.getString(2));
                    if(p==null)continue;
                    p.tiers.add(new TierEntry(r.getString(3), r.getString(4), r.getString(5), r.getInt(6)!=0, r.getString(7)));
                }
            }
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT uuid,alias FROM aliases")) {
                while(r.next()) {
                    MutableLoaded m=map.get(parseUuid(r.getString(1)));
                    if(m!=null)m.aliases.add(r.getString(2));
                }
            }
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT uuid,added_at,last_used_at,watch_seen_at FROM favorites")) {
                while(r.next()) {
                    MutableLoaded m=map.get(parseUuid(r.getString(1)));
                    if(m!=null) {
                        m.favorite=true;
                        m.favoriteAdded=r.getLong(2);
                        m.favoriteUsed=r.getLong(3);
                        m.watchSeen=r.getLong(4);
                    }
                }
            }
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT uuid,provider_id FROM source_history")) {
                while(r.next()) {
                    MutableLoaded m=map.get(parseUuid(r.getString(1)));
                    if(m!=null)m.sourceHistory.add(r.getString(2));
                }
            }
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT uuid,MAX(at) FROM tier_history GROUP BY uuid")) {
                while(r.next()) {
                    MutableLoaded m=map.get(parseUuid(r.getString(1)));
                    if(m!=null)m.historyLatest=r.getLong(2);
                }
            }
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT uuid,type,rank_no,source,updated_at FROM notable_status")) {
                while(r.next()) {
                    MutableLoaded m=map.get(parseUuid(r.getString(1)));
                    if(m==null)continue;
                    try {
                        m.notable.add(new NotableStatus(NotableStatus.Type.valueOf(r.getString(2)), r.getInt(3), r.getString(4), r.getLong(5)));
                    } catch (Exception ignored) {
                    }
                }
            }
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT uuid,note FROM player_notes")) {
                while(r.next()) {
                    MutableLoaded m=map.get(parseUuid(r.getString(1)));
                    if(m!=null)m.note=r.getString(2);
                }
            }
        }
        ArrayList<Loaded> out=new ArrayList<>(map.size());
        for(MutableLoaded m:map.values())out.add(m.freeze());
        
        return out;
    }
    Loaded loadByUuid(UUID id) {
        if(id==null)return null;
        try(Connection c=open()) {
            configure(c, false);
            return loadOne(c, id);
        } catch (Exception e) {
            BootstrapLog.error("SQLITE profile load uuid="+id, e);
            return null;
        }
    }
    Loaded loadByName(String name) {
        String n=norm(name);
        if(n.isBlank())return null;
        try(Connection c=open()) {
            configure(c, false);
            UUID id=null;
            try(PreparedStatement p=c.prepareStatement("SELECT uuid FROM players WHERE name_lower=? LIMIT 1")) {
                p.setString(1, n);
                try(ResultSet r=p.executeQuery()) {
                    if(r.next())id=parseUuid(r.getString(1));
                }
            }
            if(id==null)try(PreparedStatement p=c.prepareStatement("SELECT uuid FROM aliases WHERE alias_lower=? ORDER BY seen_at DESC LIMIT 1")) {
                p.setString(1, n);
                try(ResultSet r=p.executeQuery()) {
                    if(r.next())id=parseUuid(r.getString(1));
                }
            }
            return id==null?null:loadOne(c, id);
        } catch (Exception e) {
            BootstrapLog.error("SQLITE profile load name="+name, e);
            return null;
        }
    }
    private static Loaded loadOne(Connection c, UUID id)throws SQLException {
        MutableLoaded m=null;
        String u=id.toString();
        try(PreparedStatement p=c.prepareStatement("SELECT current_name,fetched_at,last_seen_at,last_seen_server FROM players WHERE uuid=?")) {
            p.setString(1, u);
            try(ResultSet r=p.executeQuery()) {
                if(r.next())m=new MutableLoaded(id, r.getString(1), r.getLong(2), r.getLong(3), r.getString(4));
            }
        }
        if(m==null)return null;
        try(PreparedStatement p=c.prepareStatement("SELECT provider_id,display_name,status,message,fetched_at FROM provider_results WHERE uuid=?")) {
            p.setString(1, u);
            try(ResultSet r=p.executeQuery()) {
                while(r.next()) {
                    ProviderResult.Status st;
                    try {
                        st=ProviderResult.Status.valueOf(r.getString(3));
                    } catch (Exception e) {
                        st=ProviderResult.Status.NOT_RANKED;
                    }
                    m.providers.put(r.getString(1), new MutableProvider(r.getString(1), r.getString(2), st, r.getString(4), r.getLong(5)));
                }
            }
        }
        try(PreparedStatement p=c.prepareStatement("SELECT provider_id,gamemode,current_tier,peak_tier,retired,last_test FROM tiers WHERE uuid=?")) {
            p.setString(1, u);
            try(ResultSet r=p.executeQuery()) {
                while(r.next()) {
                    MutableProvider pr=m.providers.get(r.getString(1));
                    if(pr!=null)pr.tiers.add(new TierEntry(r.getString(2), r.getString(3), r.getString(4), r.getInt(5)!=0, r.getString(6)));
                }
            }
        }
        try(PreparedStatement p=c.prepareStatement("SELECT alias FROM aliases WHERE uuid=?")) {
            p.setString(1, u);
            try(ResultSet r=p.executeQuery()) {
                while(r.next())m.aliases.add(r.getString(1));
            }
        }
        try(PreparedStatement p=c.prepareStatement("SELECT added_at,last_used_at,watch_seen_at FROM favorites WHERE uuid=?")) {
            p.setString(1, u);
            try(ResultSet r=p.executeQuery()) {
                if(r.next()) {
                    m.favorite=true;
                    m.favoriteAdded=r.getLong(1);
                    m.favoriteUsed=r.getLong(2);
                    m.watchSeen=r.getLong(3);
                }
            }
        }
        try(PreparedStatement p=c.prepareStatement("SELECT provider_id FROM source_history WHERE uuid=?")) {
            p.setString(1, u);
            try(ResultSet r=p.executeQuery()) {
                while(r.next())m.sourceHistory.add(r.getString(1));
            }
        }
        try(PreparedStatement p=c.prepareStatement("SELECT MAX(at) FROM tier_history WHERE uuid=?")) {
            p.setString(1, u);
            try(ResultSet r=p.executeQuery()) {
                if(r.next())m.historyLatest=r.getLong(1);
            }
        }
        try(PreparedStatement p=c.prepareStatement("SELECT type,rank_no,source,updated_at FROM notable_status WHERE uuid=?")) {
            p.setString(1, u);
            try(ResultSet r=p.executeQuery()) {
                while(r.next())try {
                    m.notable.add(new NotableStatus(NotableStatus.Type.valueOf(r.getString(1)), r.getInt(2), r.getString(3), r.getLong(4)));
                } catch (Exception ignored) {
                }
            }
        }
        try(PreparedStatement p=c.prepareStatement("SELECT note FROM player_notes WHERE uuid=?")) {
            p.setString(1, u);
            try(ResultSet r=p.executeQuery()) {
                if(r.next())m.note=r.getString(1);
            }
        }
        return m.freeze();
    }
    List<TierHistoryEvent> loadHistory(UUID id) {
        if(id==null)return List.of();
        ArrayList<TierHistoryEvent> out=new ArrayList<>();
        try(Connection c=open(); PreparedStatement p=c.prepareStatement("SELECT at,provider_id,gamemode,old_tier,new_tier,old_retired,new_retired FROM tier_history WHERE uuid=? ORDER BY at")) {
            p.setString(1, id.toString());
            try(ResultSet r=p.executeQuery()) {
                while(r.next())out.add(new TierHistoryEvent(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getInt(6)!=0, r.getInt(7)!=0));
            }
        } catch (Exception e) {
            BootstrapLog.error("SQLITE history load "+id, e);
        }
        return List.copyOf(out);
    }
    void queue(Bundle b) {
        if(closed||b==null||b.profile()==null)return;
        pendingDelete.remove(b.profile().player().uuid());
        pending.put(b.profile().player().uuid(), b);
        schedule();
    }
    /** Updates encounter identity/last-seen metadata without touching provider/tier rows. */ void queueSeen(UUID id, String name, long lastSeenAt, String server) {
        if(closed||id==null||name==null||name.isBlank())return;
        pendingDelete.remove(id);
        pendingSeen.put(id, new SeenUpdate(id, name, lastSeenAt, server));
        schedule();
    }
    void delete(UUID id) {
        if(closed||id==null)return;
        pending.remove(id);
        pendingSeen.remove(id);
        pendingDelete.add(id);
        schedule();
    }
    void clearAll() {
        if(closed)return;
        pending.clear();
        pendingSeen.clear();
        pendingDelete.clear();
        cancelSchedule();
        Future<?> f=submitWriter(()-> {
            synchronized(flushLock) {
                try(Connection c=open()) {
                    configure(c, false); c.setAutoCommit(false); try {
                        try(Statement s=c.createStatement()) {
                            for(String t:List.of("tiers",
                                "provider_results",
                                "aliases",
                                "tier_history",
                                "favorites",
                                "source_history",
                                "notable_status",
                                "player_notes",
                                "players"))s.executeUpdate("DELETE FROM "+t);
                        }
                        c.commit();
                    } catch (Exception e) {
                        rollbackQuietly(c, "clear"); throw e;
                    }
                } catch (Exception e) {
                    BootstrapLog.error("SQLITE clear", e);
                }
            }
        }
        );
        waitFuture(f);
    }
    void flush() {
        if(closed)return;
        cancelSchedule();
        Future<?> f=submitWriter(()->drain(false));
        waitFuture(f);
    }
    void flushBulk() {
        if(closed)return;
        cancelSchedule();
        Future<?> f=submitWriter(()->drain(true));
        waitFutureStrict(f);
    }
    boolean checkpoint() {
        if(closed)return false;
        flush();
        synchronized(flushLock) {
            try(Connection c=open(); Statement s=c.createStatement()) {
                configure(c, false);
                s.execute("PRAGMA wal_checkpoint(FULL)");
                return true;
            } catch (Exception e) {
                BootstrapLog.error("SQLITE checkpoint", e);
                return false;
            }
        }
    }
    /** Fast path for the one-time legacy migration into an empty DB: clear once, then insert in one transaction. */ void importFreshBulk(Collection<Bundle> bundles) {
        if(closed||bundles==null||bundles.isEmpty())return;
        cancelSchedule();
        pending.clear();
        pendingSeen.clear();
        pendingDelete.clear();
        Future<?> f=submitWriter(()-> {
            synchronized(flushLock) {
                long started=System.nanoTime(); try(Connection c=open()) {
                    configure(c, true); c.setAutoCommit(false); try {
                        try(Statement st=c.createStatement()) {
                            for(String table:List.of("tiers",
                                "provider_results",
                                "aliases",
                                "tier_history",
                                "favorites",
                                "source_history",
                                "notable_status",
                                "player_notes",
                                "players"))st.executeUpdate("DELETE FROM "+table);
                        }
                        try(PreparedOps ops=new PreparedOps(c)) {
                            for(Bundle b:bundles)if(b!=null&&b.profile()!=null)ops.insertFresh(b);
                        }
                        c.commit(); 
                    } catch (Exception e) {
                        rollbackQuietly(c, "fresh import"); throw e;
                    }
                } catch (Exception e) {
                    BootstrapLog.error("SQLITE fresh import", e); throw new CompletionException(e);
                }
            }
        }
        );
        waitFutureStrict(f);
    }
    private Future<?> submitWriter(Runnable task) {
        if(closed)return CompletableFuture.completedFuture(null);
        try {
            return writer.submit(task);
        } catch (RejectedExecutionException e) {
            if(!closed)BootstrapLog.error("SQLITE writer rejected task", e);
            return CompletableFuture.completedFuture(null);
        }
    }
    private void schedule() {
        synchronized(this) {
            if(closed||writer.isShutdown())return;
            if(scheduled!=null&&!scheduled.isDone())return;
            try {
                scheduled=writer.schedule(()->drain(false), 1200, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                if(!closed)BootstrapLog.error("SQLITE schedule rejected", e);
            }
        }
    }
    private void cancelSchedule() {
        synchronized(this) {
            if(scheduled!=null) {
                scheduled.cancel(false);
                scheduled=null;
            }
        }
    }
    private void drain(boolean bulk) {
        if(closed)return;
        LinkedHashMap<UUID, Bundle> batch=new LinkedHashMap<>();
        for(var e:pending.entrySet())if(pending.remove(e.getKey(), e.getValue()))batch.put(e.getKey(), e.getValue());
        LinkedHashMap<UUID, SeenUpdate> seenBatch=new LinkedHashMap<>();
        for(var e:pendingSeen.entrySet())if(pendingSeen.remove(e.getKey(), e.getValue()))seenBatch.put(e.getKey(), e.getValue());
        HashSet<UUID> deletes=new HashSet<>();
        for(UUID id:pendingDelete)if(pendingDelete.remove(id))deletes.add(id);
        if(batch.isEmpty()&&seenBatch.isEmpty()&&deletes.isEmpty())return;
        synchronized(flushLock) {
            long started=System.nanoTime();
            try(Connection c=open()) {
                configure(c, bulk);
                c.setAutoCommit(false);
                try {
                    try(PreparedOps ops=new PreparedOps(c)) {
                        for(UUID id:deletes)ops.deleteNow(id);
                        for(Bundle b:batch.values())if(b!=null&&b.profile()!=null)ops.upsertNow(b);
                        for(SeenUpdate seen:seenBatch.values())if(seen!=null&&!deletes.contains(seen.id()))ops.seenNow(seen);
                    }
                    c.commit();
                    
                } catch (Exception e) {
                    rollbackQuietly(c, "flush");
                    throw e;
                }
            } catch (Exception e) {
                BootstrapLog.error("SQLITE flush", e);
                for(var e2:batch.entrySet())pending.putIfAbsent(e2.getKey(), e2.getValue());
                for(var e2:seenBatch.entrySet())pendingSeen.putIfAbsent(e2.getKey(), e2.getValue());
                pendingDelete.addAll(deletes);
            }
        }
    }
    private static void rollbackQuietly(Connection c, String where) {
        try {
            if(c!=null&&!c.getAutoCommit())c.rollback();
        } catch (Exception rollback) {
            BootstrapLog.error("SQLITE rollback "+where, rollback);
        }
    }
    /** Reuses prepared statements across the entire transaction; essential for large migrations and bulk sync. */
    private static final class PreparedOps implements AutoCloseable {
        private final PreparedStatement player,
            seenPlayer,
            delAlias,
            insAlias,
            delProvider,
            delTier,
            insProvider,
            insTier,
            delFavorite,
            insFavorite,
            delSource,
            insSource,
            delHistory,
            insHistory,
            delNotable,
            insNotable,
            delNote,
            insNote,
            delPlayer;
        private final List<PreparedStatement> all;
        PreparedOps(Connection c)throws SQLException {
            player=c.prepareStatement("INSERT INTO players(uuid,current_name,name_lower,fetched_at,last_seen_at,last_seen_server,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET current_name=excluded.current_name,name_lower=excluded.name_lower,fetched_at=excluded.fetched_at,last_seen_at=excluded.last_seen_at,last_seen_server=excluded.last_seen_server,updated_at=excluded.updated_at");
            seenPlayer=c.prepareStatement("INSERT INTO players(uuid,current_name,name_lower,fetched_at,last_seen_at,last_seen_server,created_at,updated_at) VALUES(?,?,?,0,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET current_name=excluded.current_name,name_lower=excluded.name_lower,last_seen_at=MAX(players.last_seen_at,excluded.last_seen_at),last_seen_server=COALESCE(excluded.last_seen_server,players.last_seen_server),updated_at=excluded.updated_at");
            delAlias=c.prepareStatement("DELETE FROM aliases WHERE uuid=?");
            insAlias=c.prepareStatement("INSERT OR REPLACE INTO aliases(uuid,alias,alias_lower,seen_at) VALUES(?,?,?,?)");
            delProvider=c.prepareStatement("DELETE FROM provider_results WHERE uuid=?");
            delTier=c.prepareStatement("DELETE FROM tiers WHERE uuid=?");
            insProvider=c.prepareStatement("INSERT INTO provider_results(uuid,provider_id,display_name,status,message,fetched_at) VALUES(?,?,?,?,?,?) ON CONFLICT(uuid,provider_id) DO UPDATE SET display_name=excluded.display_name,status=excluded.status,message=excluded.message,fetched_at=excluded.fetched_at");
            insTier=c.prepareStatement("INSERT INTO tiers(uuid,provider_id,gamemode,current_tier,peak_tier,retired,last_test) VALUES(?,?,?,?,?,?,?) ON CONFLICT(uuid,provider_id,gamemode) DO UPDATE SET current_tier=excluded.current_tier,peak_tier=excluded.peak_tier,retired=excluded.retired,last_test=excluded.last_test");
            delFavorite=c.prepareStatement("DELETE FROM favorites WHERE uuid=?");
            insFavorite=c.prepareStatement("INSERT INTO favorites(uuid,added_at,last_used_at,watch_seen_at) VALUES(?,?,?,?)");
            delSource=c.prepareStatement("DELETE FROM source_history WHERE uuid=?");
            insSource=c.prepareStatement("INSERT OR REPLACE INTO source_history(uuid,provider_id,loaded_at) VALUES(?,?,?)");
            delHistory=c.prepareStatement("DELETE FROM tier_history WHERE uuid=?");
            insHistory=c.prepareStatement("INSERT INTO tier_history(uuid,at,provider_id,gamemode,old_tier,new_tier,old_retired,new_retired) VALUES(?,?,?,?,?,?,?,?)");
            delNotable=c.prepareStatement("DELETE FROM notable_status WHERE uuid=?");
            insNotable=c.prepareStatement("INSERT INTO notable_status(uuid,type,rank_no,source,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(uuid,type,source) DO UPDATE SET rank_no=excluded.rank_no,updated_at=excluded.updated_at");
            delNote=c.prepareStatement("DELETE FROM player_notes WHERE uuid=?");
            insNote=c.prepareStatement("INSERT INTO player_notes(uuid,note,updated_at) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET note=excluded.note,updated_at=excluded.updated_at");
            delPlayer=c.prepareStatement("DELETE FROM players WHERE uuid=?");
            all=List.of(player,
                seenPlayer,
                delAlias,
                insAlias,
                delProvider,
                delTier,
                insProvider,
                insTier,
                delFavorite,
                insFavorite,
                delSource,
                insSource,
                delHistory,
                insHistory,
                delNotable,
                insNotable,
                delNote,
                insNote,
                delPlayer);
        }
        private static void del(PreparedStatement p, String u)throws SQLException {
            p.clearParameters();
            p.setString(1, u);
            p.executeUpdate();
        }
        void deleteNow(UUID id)throws SQLException {
            String u=id.toString();
            del(delTier, u);
            del(delProvider, u);
            del(delAlias, u);
            del(delHistory, u);
            del(delFavorite, u);
            del(delSource, u);
            del(delNotable, u);
            del(delNote, u);
            del(delPlayer, u);
        }
        void upsertNow(Bundle b)throws SQLException {
            write(b, true);
        }
        void insertFresh(Bundle b)throws SQLException {
            write(b, false);
        }
        void seenNow(SeenUpdate s)throws SQLException {
            long now=System.currentTimeMillis();
            seenPlayer.clearParameters();
            seenPlayer.setString(1, s.id().toString());
            seenPlayer.setString(2, s.name());
            seenPlayer.setString(3, norm(s.name()));
            seenPlayer.setLong(4, s.lastSeenAt());
            seenPlayer.setString(5, s.server());
            seenPlayer.setLong(6, now);
            seenPlayer.setLong(7, now);
            seenPlayer.executeUpdate();
        }
        private void write(Bundle b, boolean deleteExisting)throws SQLException {
            PlayerProfile p=b.profile();
            String u=p.player().uuid().toString();
            long now=System.currentTimeMillis();
            player.clearParameters();
            player.setString(1, u);
            player.setString(2, p.player().name());
            player.setString(3, norm(p.player().name()));
            player.setLong(4, p.fetchedAt());
            player.setLong(5, b.lastSeenAt());
            player.setString(6, b.lastSeenServer());
            player.setLong(7, now);
            player.setLong(8, now);
            player.executeUpdate();
            if(deleteExisting)del(delAlias, u);
            if(b.aliases()!=null&&!b.aliases().isEmpty()) {
                insAlias.clearBatch();
                for(String a:b.aliases()) {
                    if(a==null||a.isBlank())continue;
                    insAlias.setString(1, u);
                    insAlias.setString(2, a);
                    insAlias.setString(3, norm(a));
                    insAlias.setLong(4, now);
                    insAlias.addBatch();
                }
                insAlias.executeBatch();
            }
            if(deleteExisting) {
                del(delProvider, u);
                del(delTier, u);
            }
            insProvider.clearBatch();
            insTier.clearBatch();
            for(ProviderResult r:p.providers().values()) {
                insProvider.setString(1, u);
                insProvider.setString(2, r.providerId());
                insProvider.setString(3, r.displayName());
                insProvider.setString(4, r.status().name());
                insProvider.setString(5, r.message());
                insProvider.setLong(6, r.fetchedAt());
                insProvider.addBatch();
                for(TierEntry t:r.tiers()) {
                    insTier.setString(1, u);
                    insTier.setString(2, r.providerId());
                    insTier.setString(3, t.gamemode());
                    insTier.setString(4, t.currentTier());
                    insTier.setString(5, t.peakTier());
                    insTier.setInt(6, t.retired()?1:0);
                    insTier.setString(7, t.lastTest());
                    insTier.addBatch();
                }
            }
            insProvider.executeBatch();
            insTier.executeBatch();
            if(deleteExisting)del(delFavorite, u);
            if(b.favorite()) {
                insFavorite.clearParameters();
                insFavorite.setString(1, u);
                insFavorite.setLong(2, b.favoriteAddedAt()>0?b.favoriteAddedAt():now);
                insFavorite.setLong(3, b.favoriteLastUsedAt());
                insFavorite.setLong(4, b.watchSeenAt());
                insFavorite.executeUpdate();
            }
            if(deleteExisting)del(delSource, u);
            if(b.sourceHistoryLoaded()!=null&&!b.sourceHistoryLoaded().isEmpty()) {
                insSource.clearBatch();
                for(String sh:b.sourceHistoryLoaded()) {
                    insSource.setString(1, u);
                    insSource.setString(2, sh);
                    insSource.setLong(3, now);
                    insSource.addBatch();
                }
                insSource.executeBatch();
            }
            // null means lazy history was not loaded in RAM: preserve already persisted history.
            if(b.history()!=null) {
                if(deleteExisting)del(delHistory, u);
                if(!b.history().isEmpty()) {
                    insHistory.clearBatch();
                    for(TierHistoryEvent e:b.history()) {
                        insHistory.setString(1, u);
                        insHistory.setLong(2, e.at());
                        insHistory.setString(3, e.providerId());
                        insHistory.setString(4, e.gamemode());
                        insHistory.setString(5, e.oldTier());
                        insHistory.setString(6, e.newTier());
                        insHistory.setInt(7, e.oldRetired()?1:0);
                        insHistory.setInt(8, e.newRetired()?1:0);
                        insHistory.addBatch();
                    }
                    insHistory.executeBatch();
                }
            }
            if(deleteExisting)del(delNotable, u);
            if(b.notable()!=null&&!b.notable().isEmpty()) {
                insNotable.clearBatch();
                for(NotableStatus n:b.notable()) {
                    insNotable.setString(1, u);
                    insNotable.setString(2, n.type().name());
                    insNotable.setInt(3, n.rank());
                    insNotable.setString(4, n.source()==null?"":n.source());
                    insNotable.setLong(5, n.updatedAt());
                    insNotable.addBatch();
                }
                insNotable.executeBatch();
            }
            if(deleteExisting)del(delNote, u);
            String note=b.note();
            if(note!=null&&!note.isBlank()) {
                insNote.clearParameters();
                insNote.setString(1, u);
                insNote.setString(2, note);
                insNote.setLong(3, now);
                insNote.executeUpdate();
            }
        }
        @Override
        public void close() {
            for(PreparedStatement p:all)try {
                p.close();
            } catch (Exception ignored) {
            }
        }
    }
    private static boolean hasColumn(Connection c, String table, String column)throws SQLException {
        try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")) {
            while(r.next())if(column.equalsIgnoreCase(r.getString("name")))return true;
        }
        return false;
    }
    private static String norm(String s) {
        return s==null?"":s.toLowerCase(Locale.ROOT);
    }
    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }
    private static void waitFuture(Future<?> f) {
        try {
            f.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            BootstrapLog.error("SQLITE writer wait", e);
        }
    }
    private static void waitFutureStrict(Future<?> f) {
        try {
            f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        } catch (ExecutionException e) {
            throw new CompletionException(e.getCause()==null?e:e.getCause());
        }
    }
    @Override
    public void close() {
        synchronized(this) {
            if(closed)return;
        }
        flush();
        synchronized(this) {
            if(closed)return;
            closed=true;
            cancelSchedule();
            writer.shutdown();
        }
        try {
            if(!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }
    private static final class MutableProvider {
        final String id, name;
        final ProviderResult.Status status;
        final String message;
        final long fetched;
        final List<TierEntry> tiers=new ArrayList<>();
        MutableProvider(String i, String n, ProviderResult.Status s, String m, long f) {
            id=i;
            name=n;
            status=s;
            message=m;
            fetched=f;
        }
        ProviderResult freeze() {
            return new ProviderResult(id, name, status, List.copyOf(tiers), message, fetched);
        }
    }
    private static final class MutableLoaded {
        final UUID id;
        final String name;
        final long fetched, lastSeen;
        final String server;
        final LinkedHashMap<String, MutableProvider> providers=new LinkedHashMap<>();
        final LinkedHashSet<String> aliases=new LinkedHashSet<>(), sourceHistory=new LinkedHashSet<>();
        final ArrayList<NotableStatus> notable=new ArrayList<>();
        boolean favorite;
        long favoriteAdded, favoriteUsed, watchSeen, historyLatest;
        String note;
        MutableLoaded(UUID i, String n, long f, long l, String s) {
            id=i;
            name=n;
            fetched=f;
            lastSeen=l;
            server=s;
        }
        Loaded freeze() {
            LinkedHashMap<String, ProviderResult> p=new LinkedHashMap<>();
            for(var e:providers.entrySet())p.put(e.getKey(), e.getValue().freeze());
            return new Loaded(new PlayerProfile(new PlayerIdentity(id,
                name),
                p,
                fetched),
                Set.copyOf(aliases),
                lastSeen,
                server,
                favorite,
                favoriteAdded,
                favoriteUsed,
                watchSeen,
                historyLatest,
                Set.copyOf(sourceHistory),
                List.copyOf(notable),
                note);
        }
    }
}
