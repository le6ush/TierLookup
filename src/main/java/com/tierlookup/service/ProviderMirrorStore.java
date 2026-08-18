package com.tierlookup.service;

import com.tierlookup.client.BootstrapLog;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import com.tierlookup.model.*;

/**
* Provider snapshot metadata and atomic provider-level commits.
*
* Bulk sync never writes directly into live provider rows while it is still downloading.
* A source is assembled in memory first, then this class commits it in one SQLite transaction.
* COMPLETE replaces the previous provider mirror; PARTIAL only merges successful rows and leaves
* all missing old rows untouched. Gameplay hover never calls this class.
*/ final class ProviderMirrorStore {
    enum Status {
        NEVER, RUNNING, COMPLETE, PARTIAL, FAILED
    }
    record Manifest(String providerId,
        String displayName,
        Status status,
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
        String terminationReason,
        String failedPageDetails,
        String message) {
    }
    record Row(PlayerIdentity player, ProviderResult result) {
    }
    record Reject(String reason, String raw) {
    }
    private final Path dbFile, libDir;
    ProviderMirrorStore(Path dbFile) throws Exception {
        this.dbFile=dbFile;
        this.libDir=dbFile.getParent().resolve("lib");
        try(Connection c=open()) {
            createSchema(c);
        }
    }
    private Connection open() throws Exception {
        Connection c=SqliteDriverBootstrap.connect(dbFile, libDir);
        try(Statement s=c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA busy_timeout=30000");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA wal_autocheckpoint=1000");
        }
        return c;
    }
    private static void createSchema(Connection c) throws SQLException {
        try(Statement s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS provider_sync_manifest(provider_id TEXT PRIMARY KEY,display_name TEXT NOT NULL,status TEXT NOT NULL,generation INTEGER NOT NULL DEFAULT 0,mode TEXT NOT NULL DEFAULT 'UPDATE',started_at INTEGER NOT NULL DEFAULT 0,completed_at INTEGER NOT NULL DEFAULT 0,received INTEGER NOT NULL DEFAULT 0,parsed INTEGER NOT NULL DEFAULT 0,rejected INTEGER NOT NULL DEFAULT 0,pages INTEGER NOT NULL DEFAULT 0,failed_pages INTEGER NOT NULL DEFAULT 0,verified_checked INTEGER NOT NULL DEFAULT 0,verified_gaps INTEGER NOT NULL DEFAULT 0,snapshot_rows INTEGER NOT NULL DEFAULT 0,message TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS provider_sync_rejections(id INTEGER PRIMARY KEY AUTOINCREMENT,provider_id TEXT NOT NULL,generation INTEGER NOT NULL,reason TEXT NOT NULL,raw TEXT,created_at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_sync_reject_provider ON provider_sync_rejections(provider_id,generation)");
            s.execute("CREATE TABLE IF NOT EXISTS discovery_gap(provider_id TEXT NOT NULL,uuid TEXT NOT NULL,nickname TEXT NOT NULL,discovered_at INTEGER NOT NULL,manifest_generation INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(provider_id,uuid))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_discovery_gap_provider ON discovery_gap(provider_id,discovered_at DESC)");
        }
        // Add mirror-integrity columns idempotently so older databases migrate in place.
        ensureColumn(c, "provider_sync_manifest", "raw_received", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(c, "provider_sync_manifest", "unique_identities", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(c, "provider_sync_manifest", "duplicate_identities", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(c, "provider_sync_manifest", "duplicate_pages", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(c, "provider_sync_manifest", "live_rows", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(c, "provider_sync_manifest", "termination_reason", "TEXT");
        ensureColumn(c, "provider_sync_manifest", "failed_page_details", "TEXT");
    }
    private static void ensureColumn(Connection c, String table, String column, String definition) throws SQLException {
        boolean present=false;
        try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")) {
            while(r.next())if(column.equalsIgnoreCase(r.getString("name"))) {
                present=true;
                break;
            }
        }
        if(!present)try(Statement s=c.createStatement()) {
            s.execute("ALTER TABLE "+table+" ADD COLUMN "+column+" "+definition);
        }
    }
    synchronized void begin(String providerId, String displayName, long generation, String mode) {
        long now=System.currentTimeMillis();
        String sql="INSERT INTO provider_sync_manifest(provider_id,display_name,status,generation,mode,started_at,completed_at,received,raw_received,unique_identities,duplicate_identities,duplicate_pages,parsed,rejected,pages,failed_pages,verified_checked,verified_gaps,snapshot_rows,live_rows,termination_reason,failed_page_details,message) VALUES(?,?,?,?,?,?,0,0,0,0,0,0,0,0,0,0,0,0,0,0,NULL,NULL,NULL) ON CONFLICT(provider_id) DO UPDATE SET display_name=excluded.display_name,status=excluded.status,generation=excluded.generation,mode=excluded.mode,started_at=excluded.started_at,completed_at=0,received=0,raw_received=0,unique_identities=0,duplicate_identities=0,duplicate_pages=0,parsed=0,rejected=0,pages=0,failed_pages=0,verified_checked=0,verified_gaps=0,snapshot_rows=0,termination_reason=NULL,failed_page_details=NULL,message=NULL";
        try(Connection c=open(); PreparedStatement p=c.prepareStatement(sql)) {
            p.setString(1, providerId);
            p.setString(2, displayName);
            p.setString(3, Status.RUNNING.name());
            p.setLong(4, generation);
            p.setString(5, mode);
            p.setLong(6, now);
            p.executeUpdate();
        } catch (Exception e) {
            BootstrapLog.error("MIRROR manifest begin "+providerId, e);
        }
    }
    synchronized void fail(String providerId, String displayName, long generation, String mode, String message) {
        long now=System.currentTimeMillis();
        try(Connection c=open(); PreparedStatement p=c.prepareStatement("INSERT INTO provider_sync_manifest(provider_id,display_name,status,generation,mode,started_at,completed_at,message) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(provider_id) DO UPDATE SET display_name=excluded.display_name,status=excluded.status,generation=excluded.generation,mode=excluded.mode,completed_at=excluded.completed_at,message=excluded.message")) {
            p.setString(1, providerId);
            p.setString(2, displayName);
            p.setString(3, Status.FAILED.name());
            p.setLong(4, generation);
            p.setString(5, mode);
            p.setLong(6, now);
            p.setLong(7, now);
            p.setString(8, trim(message, 1800));
            p.executeUpdate();
        } catch (Exception e) {
            BootstrapLog.error("MIRROR manifest fail "+providerId, e);
        }
    }
    /** Atomic provider commit. */ synchronized void commit(String providerId,
        String displayName,
        long generation,
        String mode,
        Status status,
        Collection<Row> rows,
        Collection<Reject> rejects,
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
        List<NotableStatus>> notableByUuid,
        boolean replaceMissing) throws Exception {
        long now=System.currentTimeMillis();
        List<Row> safeRows=rows==null?List.of():List.copyOf(rows);
        List<Reject> safeRejects=rejects==null?List.of():List.copyOf(rejects);
        try(Connection c=open()) {
            c.setAutoCommit(false);
            try {
                createSchema(c);
                if(replaceMissing) {
                    try(PreparedStatement p=c.prepareStatement("DELETE FROM tiers WHERE provider_id=?")) {
                        p.setString(1, providerId);
                        p.executeUpdate();
                    }
                    try(PreparedStatement p=c.prepareStatement("DELETE FROM provider_results WHERE provider_id=?")) {
                        p.setString(1, providerId);
                        p.executeUpdate();
                    }
                    try(PreparedStatement p=c.prepareStatement("DELETE FROM notable_status WHERE source=?")) {
                        p.setString(1, displayName);
                        p.executeUpdate();
                    }
                    try(PreparedStatement p=c.prepareStatement("DELETE FROM discovery_gap WHERE provider_id=?")) {
                        p.setString(1, providerId);
                        p.executeUpdate();
                    }
                }
                PreparedStatement selectName=c.prepareStatement("SELECT current_name FROM players WHERE uuid=?");
                PreparedStatement player=c.prepareStatement("INSERT INTO players(uuid,current_name,name_lower,fetched_at,last_seen_at,last_seen_server,created_at,updated_at) VALUES(?,?,?,?,0,NULL,?,?) ON CONFLICT(uuid) DO UPDATE SET current_name=excluded.current_name,name_lower=excluded.name_lower,fetched_at=CASE WHEN players.fetched_at>excluded.fetched_at THEN players.fetched_at ELSE excluded.fetched_at END,updated_at=excluded.updated_at");
                PreparedStatement alias=c.prepareStatement("INSERT OR IGNORE INTO aliases(uuid,alias,alias_lower,seen_at) VALUES(?,?,?,?)");
                PreparedStatement delProvider=c.prepareStatement("DELETE FROM provider_results WHERE uuid=? AND provider_id=?");
                PreparedStatement delTiers=c.prepareStatement("DELETE FROM tiers WHERE uuid=? AND provider_id=?");
                PreparedStatement insProvider=c.prepareStatement("INSERT OR REPLACE INTO provider_results(uuid,provider_id,display_name,status,message,fetched_at) VALUES(?,?,?,?,?,?)");
                PreparedStatement insTier=c.prepareStatement("INSERT OR REPLACE INTO tiers(uuid,provider_id,gamemode,current_tier,peak_tier,retired,last_test) VALUES(?,?,?,?,?,?,?)");
                try(selectName; player; alias; delProvider; delTiers; insProvider; insTier) {
                    for(Row row:safeRows) {
                        if(row==null||row.player()==null||row.result()==null)continue;
                        PlayerIdentity id=row.player();
                        ProviderResult r=row.result();
                        String u=id.uuid().toString();
                        String oldName=null;
                        selectName.clearParameters();
                        selectName.setString(1, u);
                        try(ResultSet rs=selectName.executeQuery()) {
                            if(rs.next())oldName=rs.getString(1);
                        }
                        if(oldName!=null&&!oldName.equalsIgnoreCase(id.name())) {
                            alias.clearParameters();
                            alias.setString(1, u);
                            alias.setString(2, oldName);
                            alias.setString(3, norm(oldName));
                            alias.setLong(4, now);
                            alias.executeUpdate();
                        }
                        player.clearParameters();
                        player.setString(1, u);
                        player.setString(2, id.name());
                        player.setString(3, norm(id.name()));
                        player.setLong(4, Math.max(now, r.fetchedAt()));
                        player.setLong(5, now);
                        player.setLong(6, now);
                        player.executeUpdate();
                        if(!replaceMissing) {
                            delTiers.clearParameters();
                            delTiers.setString(1, u);
                            delTiers.setString(2, providerId);
                            delTiers.executeUpdate();
                            delProvider.clearParameters();
                            delProvider.setString(1, u);
                            delProvider.setString(2, providerId);
                            delProvider.executeUpdate();
                        }
                        insProvider.clearParameters();
                        insProvider.setString(1, u);
                        insProvider.setString(2, providerId);
                        insProvider.setString(3, displayName);
                        insProvider.setString(4, r.status().name());
                        insProvider.setString(5, r.message());
                        insProvider.setLong(6, r.fetchedAt());
                        insProvider.executeUpdate();
                        for(TierEntry t:r.tiers()) {
                            insTier.clearParameters();
                            insTier.setString(1, u);
                            insTier.setString(2, providerId);
                            insTier.setString(3, t.gamemode());
                            insTier.setString(4, t.currentTier());
                            insTier.setString(5, t.peakTier());
                            insTier.setInt(6, t.retired()?1:0);
                            insTier.setString(7, t.lastTest());
                            insTier.executeUpdate();
                        }
                    }
                }
                if(notableByUuid!=null&&!notableByUuid.isEmpty()) {
                    try(PreparedStatement p=c.prepareStatement("INSERT OR REPLACE INTO notable_status(uuid,type,rank_no,source,updated_at) VALUES(?,?,?,?,?)")) {
                        for(var en:notableByUuid.entrySet())for(NotableStatus n:en.getValue()) {
                            if(n==null)continue;
                            p.setString(1, en.getKey().toString());
                            p.setString(2, n.type().name());
                            p.setInt(3, n.rank());
                            p.setString(4, displayName);
                            p.setLong(5, now);
                            p.addBatch();
                        }
                        p.executeBatch();
                    }
                }
                try(PreparedStatement d=c.prepareStatement("DELETE FROM provider_sync_rejections WHERE provider_id=?")) {
                    d.setString(1, providerId);
                    d.executeUpdate();
                }
                if(!safeRejects.isEmpty())try(PreparedStatement p=c.prepareStatement("INSERT INTO provider_sync_rejections(provider_id,generation,reason,raw,created_at) VALUES(?,?,?,?,?)")) {
                    int n=0;
                    for(Reject r:safeRejects) {
                        if(r==null||n++>=250)break;
                        p.setString(1, providerId);
                        p.setLong(2, generation);
                        p.setString(3, trim(r.reason(), 500));
                        p.setString(4, trim(r.raw(), 1800));
                        p.setLong(5, now);
                        p.addBatch();
                    }
                    p.executeBatch();
                }
                int liveRows=liveRows(c, providerId);
                String sql="INSERT INTO provider_sync_manifest(provider_id,display_name,status,generation,mode,started_at,completed_at,received,raw_received,unique_identities,duplicate_identities,duplicate_pages,parsed,rejected,pages,failed_pages,verified_checked,verified_gaps,snapshot_rows,live_rows,termination_reason,failed_page_details,message) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(provider_id) DO UPDATE SET display_name=excluded.display_name,status=excluded.status,generation=excluded.generation,mode=excluded.mode,completed_at=excluded.completed_at,received=excluded.received,raw_received=excluded.raw_received,unique_identities=excluded.unique_identities,duplicate_identities=excluded.duplicate_identities,duplicate_pages=excluded.duplicate_pages,parsed=excluded.parsed,rejected=excluded.rejected,pages=excluded.pages,failed_pages=excluded.failed_pages,verified_checked=excluded.verified_checked,verified_gaps=excluded.verified_gaps,snapshot_rows=excluded.snapshot_rows,live_rows=excluded.live_rows,termination_reason=excluded.termination_reason,failed_page_details=excluded.failed_page_details,message=excluded.message";
                try(PreparedStatement p=c.prepareStatement(sql)) {
                    int i=1;
                    p.setString(i++, providerId);
                    p.setString(i++, displayName);
                    p.setString(i++, status.name());
                    p.setLong(i++, generation);
                    p.setString(i++, mode);
                    p.setLong(i++, now);
                    p.setLong(i++, now);
                    p.setInt(i++, received);
                    p.setInt(i++, rawReceived);
                    p.setInt(i++, uniqueIdentities);
                    p.setInt(i++, duplicateIdentities);
                    p.setInt(i++, duplicatePages);
                    p.setInt(i++, parsed);
                    p.setInt(i++, rejected);
                    p.setInt(i++, pages);
                    p.setInt(i++, failedPages);
                    p.setInt(i++, verifiedChecked);
                    p.setInt(i++, verifiedGaps);
                    p.setInt(i++, stagedRows);
                    p.setInt(i++, liveRows);
                    p.setString(i++, trim(terminationReason, 120));
                    p.setString(i++, trim(failedPageDetails, 700));
                    p.setString(i, trim(message, 1800));
                    p.executeUpdate();
                }
                c.commit();
                
            } catch (Throwable t) {
                try {
                    c.rollback();
                } catch (Exception ignored) {
                }
                throw t;
            }
        }
    }
    private static int liveRows(Connection c, String providerId) throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM provider_results WHERE provider_id=?")) {
            p.setString(1, providerId);
            try(ResultSet r=p.executeQuery()) {
                return r.next()?r.getInt(1):0;
            }
        }
    }
    List<Manifest> manifests() {
        ArrayList<Manifest> out=new ArrayList<>();
        String sql="SELECT provider_id,display_name,status,generation,mode,started_at,completed_at,received,raw_received,unique_identities,duplicate_identities,duplicate_pages,parsed,rejected,pages,failed_pages,verified_checked,verified_gaps,snapshot_rows,live_rows,termination_reason,failed_page_details,message FROM provider_sync_manifest ORDER BY display_name COLLATE NOCASE";
        try(Connection c=open()) {
            createSchema(c);
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery(sql)) {
                while(r.next()) {
                    Status st;
                    try {
                        st=Status.valueOf(r.getString(3));
                    } catch (Exception e) {
                        st=Status.NEVER;
                    }
                    out.add(new Manifest(r.getString(1),
                        r.getString(2),
                        st,
                        r.getLong(4),
                        r.getString(5),
                        r.getLong(6),
                        r.getLong(7),
                        r.getInt(8),
                        r.getInt(9),
                        r.getInt(10),
                        r.getInt(11),
                        r.getInt(12),
                        r.getInt(13),
                        r.getInt(14),
                        r.getInt(15),
                        r.getInt(16),
                        r.getInt(17),
                        r.getInt(18),
                        r.getInt(19),
                        r.getInt(20),
                        r.getString(21),
                        r.getString(22),
                        r.getString(23)));
                }
            }
        } catch (Exception e) {
            BootstrapLog.error("MIRROR manifests", e);
        }
        return List.copyOf(out);
    }
    String rejectionSummary(String providerId, int maxReasons) {
        if(providerId==null||maxReasons<=0)return null;
        ArrayList<String> parts=new ArrayList<>();
        String sql="SELECT reason,COUNT(*) AS n,MIN(id) AS first_id FROM provider_sync_rejections WHERE provider_id=? GROUP BY reason ORDER BY n DESC,first_id ASC LIMIT ?";
        try(Connection c=open(); PreparedStatement p=c.prepareStatement(sql)) {
            p.setString(1, providerId);
            p.setInt(2, maxReasons);
            try(ResultSet r=p.executeQuery()) {
                while(r.next())parts.add(r.getInt(2)+"× "+trim(r.getString(1), 140));
            }
        } catch (Exception e) {
            BootstrapLog.error("MIRROR rejection summary "+providerId, e);
        }
        return parts.isEmpty()?null:String.join("; ", parts);
    }
    void recordDiscoveryGap(String providerId, UUID uuid, String nickname) {
        if(providerId==null||uuid==null||nickname==null)return;
        try(Connection c=open()) {
            long generation=0;
            Status status=Status.NEVER;
            try(PreparedStatement q=c.prepareStatement("SELECT generation,status FROM provider_sync_manifest WHERE provider_id=?")) {
                q.setString(1, providerId);
                try(ResultSet r=q.executeQuery()) {
                    if(r.next()) {
                        generation=r.getLong(1);
                        try {
                            status=Status.valueOf(r.getString(2));
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            if(status==Status.NEVER||status==Status.RUNNING)return;
            try(PreparedStatement p=c.prepareStatement("INSERT INTO discovery_gap(provider_id,uuid,nickname,discovered_at,manifest_generation) VALUES(?,?,?,?,?) ON CONFLICT(provider_id,uuid) DO UPDATE SET nickname=excluded.nickname,discovered_at=excluded.discovered_at,manifest_generation=excluded.manifest_generation")) {
                p.setString(1, providerId);
                p.setString(2, uuid.toString());
                p.setString(3, nickname);
                p.setLong(4, System.currentTimeMillis());
                p.setLong(5, generation);
                p.executeUpdate();
            }
            
        } catch (Exception e) {
            BootstrapLog.error("MIRROR discovery gap "+providerId, e);
        }
    }
    int discoveryGapCount(String providerId) {
        if(providerId==null)return 0;
        try(Connection c=open(); PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM discovery_gap WHERE provider_id=?")) {
            p.setString(1, providerId);
            try(ResultSet r=p.executeQuery()) {
                return r.next()?r.getInt(1):0;
            }
        } catch (Exception e) {
            return 0;
        }
    }
    private static String norm(String s) {
        return s==null?"":s.toLowerCase(Locale.ROOT);
    }
    private static String trim(String s, int max) {
        if(s==null)return null;
        String v=s.replace('\r', ' ').replace('\n', ' ').trim();
        return v.length()<=max?v:v.substring(0, max)+"…";
    }
}
