package com.tierlookup.service;

import com.tierlookup.client.BootstrapLog;
import java.nio.file.*;
import java.util.*;

import com.tierlookup.net.MiniJson;

/** Persistent TierLookup settings. All user-facing settings are surfaced through ModMenu. */
public final class TierLookupConfig {
    public static final int SCHEMA_VERSION=28;
    public static final int HOT_RECENT_SIZE=300;
    public static final int RECENT_SEEN_SIZE=1500;
    public static final List<String> KNOWN_KITS=List.of( "sword",
        "dpot",
        "npot",
        "uhc",
        "smp",
        "op",
        "vanilla",
        "minecart",
        "mace",
        "axe",
        "dsmp",
        "suhc",
        "shield",
        "trident",
        "spear",
        "speed",
        "debuff",
        "manhunt",
        "creeper",
        "elytra",
        "bed",
        "bow");
    /** Primary normal-runtime data source. Bulk sync remains an explicit maintenance action in every mode. */
    public enum DataMode {
        INTERNET, OFFLINE
    }
    private final Path file;
    private final List<String> canonicalProviderOrder;
    private final LinkedHashMap<String, Boolean> enabled=new LinkedHashMap<>();
    private final LinkedHashMap<String, Boolean> enabledKits=new LinkedHashMap<>();
    private final ArrayList<String> providerOrder=new ArrayList<>();
    private final ArrayList<String> kitOrder=new ArrayList<>();
    private boolean hideUnavailable=true;
    private boolean targetCardEnabled=true;
    private boolean filterCurrentKit=false;
    private String recacheInterval="week";
    private int tableHoldSeconds=5;
    private String theme="midnight";
    private String skinMode="head";
    private double animationSpeed=1.0;
    private boolean masterEnabled=true;
    private DataMode dataMode=DataMode.INTERNET;
    private boolean notesEnabled=true;
    /** Approximate hot profile-cache budget. Lightweight indexes/metadata are outside this soft limit. */
    private int ramCacheMb=256;
    /** TAB presentation: legacy = vanilla list + compact badge; custom = tier-sorted compact TierLookup list. */
    private String tabMode="custom";
    private String tabDisplayedKit="max";
    /** Custom TAB visual scale in percent. Legacy TAB keeps vanilla Minecraft sizing. */
    private int tabScalePercent=90;
    /** Frozen Custom TAB entry: right mouse (default) or the TierLookup hotkey. */
    private String tabInteractionTrigger="right_mouse";
    /** Custom TAB anchor. */
    private String tabPosition="left";
    /** Compare layout: player1 masks cells, max compares the best source tier per kit, union is the complete union. */
    private String experimentalCompareMode="player1";
    private long revision=1;
    public TierLookupConfig(Path file, List<String> ids) {
        this.file=file;
        LinkedHashSet<String> canonicalIds=new LinkedHashSet<>();
        if(ids!=null)for(String id:ids)if(id!=null&&!id.isBlank())canonicalIds.add(id);
        this.canonicalProviderOrder=List.copyOf(canonicalIds);
        Set<String> defaultVisible=Set.of("mctiers", "pvptiers", "subtiers", "flowpvp", "cistiers", "atiers", "mytiers", "centraltierlist");
        for(String id:canonicalProviderOrder) {
            enabled.put(id, defaultVisible.contains(id));
            providerOrder.add(id);
        }
        for(String kit:KNOWN_KITS) {
            enabledKits.put(kit, true);
            kitOrder.add(kit);
        }
        load();
        normalizeOrders();
    }
    public synchronized boolean enabled(String id) {
        return enabled.getOrDefault(id, true);
    }
    public synchronized void toggle(String id) {
        setEnabled(id, !enabled(id));
    }
    public synchronized void setEnabled(String id, boolean on) {
        if(enabled.containsKey(id)) {
            enabled.put(id, on);
            save();
        }
    }
    public synchronized Map<String, Boolean> snapshot() {
        return new LinkedHashMap<>(enabled);
    }
    public synchronized boolean kitEnabled(String id) {
        return enabledKits.getOrDefault(id, false);
    }
    public synchronized void setKitEnabled(String id, boolean on) {
        if(enabledKits.containsKey(id)) {
            enabledKits.put(id, on);
            if(!on&&id.equals(tabDisplayedKit))tabDisplayedKit="max";
            save();
        }
    }
    public synchronized Map<String, Boolean> kitSnapshot() {
        return new LinkedHashMap<>(enabledKits);
    }
    public synchronized long revision() {
        return revision;
    }
    public synchronized List<String> providerOrder() {
        return List.copyOf(providerOrder);
    }
    public synchronized List<String> kitOrder() {
        return List.copyOf(kitOrder);
    }
    public synchronized boolean hideUnavailable() {
        return hideUnavailable;
    }
    public synchronized void setHideUnavailable(boolean value) {
        hideUnavailable=value;
        save();
    }
    public synchronized boolean targetCardEnabled() {
        return targetCardEnabled;
    }
    public synchronized void setTargetCardEnabled(boolean value) {
        targetCardEnabled=value;
        save();
    }
    public synchronized boolean filterCurrentKit() {
        return filterCurrentKit;
    }
    public synchronized void setFilterCurrentKit(boolean value) {
        filterCurrentKit=value;
        save();
    }
    /** Unknown detector result always falls back to the complete enabled-kit matrix. */
    public synchronized boolean showAllWhenKitUnknown() {
        return true;
    }
    public synchronized String recacheInterval() {
        return recacheInterval;
    }
    public synchronized long recacheIntervalMs() {
        return switch(recacheInterval) {
            case "hour"->60L*60_000L;
            case "week"->7L*24L*60L*60_000L;
            default->24L*60L*60_000L;
        };
    }
    public synchronized void cycleRecacheInterval() {
        recacheInterval=switch(recacheInterval) {
            case "hour"->"day";
            case "day"->"week";
            default->"hour";
        };
        save();
    }
    public synchronized int tableHoldSeconds() {
        return tableHoldSeconds;
    }
    public synchronized void setTableHoldSeconds(int value) {
        tableHoldSeconds=Math.max(0, Math.min(120, value));
        save();
    }
    /** One recent-player queue is used internally; the first 300 entries form the autocomplete hot window. */
    public int hotRecentSize() {
        return HOT_RECENT_SIZE;
    }
    public int recentSeenSize() {
        return RECENT_SEEN_SIZE;
    }
    public synchronized String theme() {
        return theme;
    }
    public synchronized void cycleTheme() {
        theme=cycle(theme, "midnight", "classic", "glass", "warm");
        save();
    }
    public synchronized String skinMode() {
        return skinMode;
    }
    public synchronized void cycleSkinMode() {
        skinMode=cycle(skinMode, "head", "off");
        save();
    }
    public synchronized double animationSpeed() {
        return animationSpeed;
    }
    public synchronized void cycleAnimationSpeed() {
        animationSpeed=animationSpeed<0.75?1.0:animationSpeed<1.25?1.5:animationSpeed<1.75?0.5:1.0;
        save();
    }
    public synchronized boolean syncResumeAfterDisconnect() {
        return true;
    }
    public synchronized boolean masterEnabled() {
        return masterEnabled;
    }
    public synchronized void setMasterEnabled(boolean v) {
        if(masterEnabled!=v) {
            masterEnabled=v;
            save();
        }
    }
    public synchronized DataMode dataMode() {
        return dataMode;
    }
    public synchronized String dataModeId() {
        return dataMode.name().toLowerCase(Locale.ROOT);
    }
    public synchronized boolean internetMode() {
        return dataMode==DataMode.INTERNET;
    }
    public synchronized boolean offlineMode() {
        return dataMode==DataMode.OFFLINE;
    }
    public synchronized boolean normalRuntimeNetworkAllowed() {
        return dataMode==DataMode.INTERNET;
    }
    public synchronized void setDataMode(String value) {
        DataMode n=parseDataMode(value, dataMode);
        if(n!=dataMode) {
            dataMode=n;
            save();
        }
    }
    public synchronized void cycleDataMode() {
        dataMode=dataMode==DataMode.INTERNET?DataMode.OFFLINE:DataMode.INTERNET;
        save();
    }
    /** TAB roster observation is a fixed runtime feature; only INTERNET mode may turn a join into provider traffic. */
    public boolean backgroundTabObservationEnabled() {
        return true;
    }
    public synchronized boolean notesEnabled() {
        return notesEnabled;
    }
    public synchronized void setNotesEnabled(boolean value) {
        if(notesEnabled!=value) {
            notesEnabled=value;
            save();
        }
    }
    public synchronized int ramCacheMb() {
        return ramCacheMb;
    }
    public synchronized long ramCacheBytes() {
        return Math.max(16L, ramCacheMb)*1024L*1024L;
    }
    public synchronized void setRamCacheMb(int value) {
        int n=Math.max(16, Math.min(2048, value));
        if(n!=ramCacheMb) {
            ramCacheMb=n;
            save();
        }
    }
    public synchronized void adjustRamCacheMb(int delta) {
        setRamCacheMb(ramCacheMb+delta);
    }
    public synchronized String tabMode() {
        return tabMode;
    }
    public synchronized boolean customTabEnabled() {
        return "custom".equals(tabMode);
    }
    public synchronized boolean legacyTabEnabled() {
        return "legacy".equals(tabMode);
    }
    public synchronized void setTabMode(String v) {
        String raw=v==null?tabMode:v;
        if("prefix".equals(raw)||"hybrid".equals(raw))raw="custom";
        String n=oneOf(raw, tabMode, "legacy", "custom");
        if(!Objects.equals(n, tabMode)) {
            tabMode=n;
            save();
        }
    }
    public synchronized void cycleTabMode() {
        setTabMode(cycle(tabMode, "legacy", "custom"));
    }
    public synchronized int tabScalePercent() {
        return tabScalePercent;
    }
    public synchronized float tabScale() {
        return tabScalePercent/100.0f;
    }
    public synchronized void setTabScalePercent(int value) {
        int n=Math.max(35, Math.min(100, value));
        if(n!=tabScalePercent) {
            tabScalePercent=n;
            save();
        }
    }
    public synchronized void adjustTabScale(int delta) {
        setTabScalePercent(tabScalePercent+delta);
    }
    public synchronized String tabInteractionTrigger() {
        return tabInteractionTrigger;
    }
    public synchronized void cycleTabInteractionTrigger() {
        tabInteractionTrigger=cycle(tabInteractionTrigger, "right_mouse", "hotkey");
        save();
    }
    public synchronized String tabPosition() {
        return tabPosition;
    }
    public synchronized void cycleTabPosition() {
        tabPosition=cycle(tabPosition, "left", "center", "right");
        save();
    }
    public synchronized String tabDisplayedKit() {
        return tabDisplayedKit;
    }
    public synchronized void setTabDisplayedKit(String v) {
        if(v==null)return;
        String n=v.toLowerCase(Locale.ROOT);
        if("max".equals(n)||KNOWN_KITS.contains(n)) {
            tabDisplayedKit=n;
            save();
        }
    }
    public synchronized String experimentalCompareMode() {
        return experimentalCompareMode;
    }
    public synchronized void cycleExperimentalCompareMode() {
        experimentalCompareMode=cycle(experimentalCompareMode, "player1", "max", "union");
        save();
    }
    /** Profile-in-corner is the fixed normal layout. */
    public synchronized boolean experimentalProfileCorner() {
        return true;
    }
    @SuppressWarnings("unchecked") private void load() {
        int sourceSchema=1;
        Boolean oldHotOnly=null, oldInternetFirst=null;
        try {
            if(!Files.exists(file))return;
            Object o=MiniJson.parse(Files.readString(file));
            if(!(o instanceof Map<?, ?> m))return;
            sourceSchema=asInt(m.get("schemaVersion"), 1);
            Object master=m.get("enabled");
            if(master instanceof Boolean b)masterEnabled=b;
            Object p=m.get("providers");
            if(p instanceof Map<?,
                ?> pm)for(var e:pm.entrySet())if(e.getValue() instanceof Boolean b&&enabled.containsKey(String.valueOf(e.getKey())))enabled.put(String.valueOf(e.getKey()),
                b);
            Object kits=m.get("kits");
            if(kits instanceof Map<?,
                ?> km)for(var e:km.entrySet())if(e.getValue() instanceof Boolean b&&enabledKits.containsKey(String.valueOf(e.getKey())))enabledKits.put(String.valueOf(e.getKey()),
                b);
            Object hide=m.get("hideUnavailable");
            if(hide instanceof Boolean b)hideUnavailable=b;
            Object target=m.get("targetCard");
            if(target instanceof Map<?, ?> tm) {
                Object en=tm.get("enabled");
                if(en instanceof Boolean b)targetCardEnabled=b;
                Object hold=tm.get("holdSeconds");
                if(hold!=null)tableHoldSeconds=asInt(hold, tableHoldSeconds);
            }
            Object filter=m.get("filterCurrentKit");
            if(filter instanceof Boolean b)filterCurrentKit=b;
            Object ri=m.get("recacheInterval");
            if(ri!=null) {
                String v=String.valueOf(ri);
                if(v.equals("hour")||v.equals("day")||v.equals("week"))recacheInterval=v;
            }
            Object mode=m.get("dataMode");
            if(mode!=null)dataMode=parseDataMode(mode, dataMode);
            Object notes=m.get("notesEnabled");
            if(notes instanceof Boolean b)notesEnabled=b;
            Object ramMb=m.get("ramCacheMb");
            if(ramMb!=null)ramCacheMb=asInt(ramMb, ramCacheMb);
            Object search=m.get("searchCache");
            if(search instanceof Map<?, ?> sm) {
                Object hot=sm.get("hotOnly");
                if(hot instanceof Boolean b)oldHotOnly=b;
                Object dm=sm.get("dataMode");
                if(dm!=null)dataMode=parseDataMode(dm, dataMode);
            }
            Object visual=m.get("visual");
            if(visual instanceof Map<?, ?> vm) {
                theme=oneOf(vm.get("theme"), theme, "midnight", "classic", "glass", "warm");
                skinMode=oneOf(vm.get("skinMode"), skinMode, "off", "head");
                Object as=vm.get("animationSpeed");
                if(as instanceof Number n)animationSpeed=Math.max(0.5, Math.min(2.0, n.doubleValue()));
            }
            Object sync=m.get("sync");
            if(sync instanceof Map<?, ?> sm) {
                Object inf=sm.get("internetFirst");
                if(inf instanceof Boolean b)oldInternetFirst=b;
            }
            Object tab=m.get("tab");
            if(tab instanceof Map<?, ?> tm) {
                String dk=String.valueOf(tm.get("displayedKit"));
                if("max".equals(dk)||KNOWN_KITS.contains(dk))tabDisplayedKit=dk;
                Object sc=tm.get("scalePercent");
                if(sc!=null)tabScalePercent=asInt(sc, tabScalePercent);
                Object tmMode=tm.get("mode");
                if(tmMode!=null) {
                    String rawMode=String.valueOf(tmMode);
                    if("hybrid".equals(rawMode)||"prefix".equals(rawMode))rawMode="custom";
                    tabMode=oneOf(rawMode, tabMode, "legacy", "custom");
                } else {
                    Object et=tm.get("enhanced");
                    if(et instanceof Boolean b)tabMode=b?"custom":"legacy";
                }
                tabInteractionTrigger=oneOf(tm.get("interactionTrigger"), tabInteractionTrigger, "right_mouse", "hotkey");
                tabPosition=oneOf(tm.get("position"), tabPosition, "left", "center", "right");
            }
            Object experimental=m.get("experimental");
            if(experimental instanceof Map<?, ?> em) {
                experimentalCompareMode=oneOf(em.get("compareMode"), experimentalCompareMode, "player1", "max", "union");
            }
        } catch (Exception e) {
            BootstrapLog.error("CONFIG load "+file, e);
        }
        if(sourceSchema<9)experimentalCompareMode="player1";
        if(sourceSchema<17)tabDisplayedKit="max";
        if(sourceSchema<19)tabMode="custom";
        if(sourceSchema<20&&!"legacy".equals(tabMode))tabMode="custom";
        if("prefix".equals(tabMode)||"hybrid".equals(tabMode))tabMode="custom";
        if(sourceSchema<23)masterEnabled=true;
        if(sourceSchema<24)tabScalePercent=60;
        if(sourceSchema<25) {
            tabScalePercent=90;
            tabInteractionTrigger="right_mouse";
            tabPosition="left";
            tabMode="legacy".equals(tabMode)?"legacy":"custom";
        }
        if(sourceSchema<26) {
            // Old booleans collapse into the single primary-source mode.
            if(Boolean.TRUE.equals(oldHotOnly))dataMode=DataMode.OFFLINE;
            else if(Boolean.FALSE.equals(oldInternetFirst))dataMode=DataMode.OFFLINE;
            else dataMode=DataMode.INTERNET;
            notesEnabled=true;
        }
        if(sourceSchema<27)ramCacheMb=256;
        if(!"max".equals(tabDisplayedKit)&&!KNOWN_KITS.contains(tabDisplayedKit))tabDisplayedKit="max";
        tabScalePercent=Math.max(35, Math.min(100, tabScalePercent));
        ramCacheMb=Math.max(16, Math.min(2048, ramCacheMb));
    }
    /** Reset presentation defaults only. Database, provider/kit choices, favorites and notes are untouched. */
    public synchronized void resetUiDefaults() {
        hideUnavailable=true;
        targetCardEnabled=true;
        filterCurrentKit=false;
        tableHoldSeconds=5;
        theme="midnight";
        skinMode="head";
        animationSpeed=1.0;
        experimentalCompareMode="player1";
        dataMode=DataMode.INTERNET;
        notesEnabled=true;
        tabMode="custom";
        tabDisplayedKit="max";
        tabScalePercent=90;
        tabInteractionTrigger="right_mouse";
        tabPosition="left";
        save();
    }
    public synchronized void save() {
        revision++;
        Path tmp=file.resolveSibling(file.getFileName().toString()+".tmp");
        try {
            if(file.getParent()!=null)Files.createDirectories(file.getParent());
            Map<String, Object> root=new LinkedHashMap<>();
            root.put("enabled", masterEnabled);
            root.put("providers", snapshot());
            root.put("kits", kitSnapshot());
            root.put("hideUnavailable", hideUnavailable);
            root.put("targetCard", map("enabled", targetCardEnabled, "holdSeconds", tableHoldSeconds));
            root.put("filterCurrentKit", filterCurrentKit);
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("recacheInterval", recacheInterval);
            root.put("dataMode", dataModeId());
            root.put("notesEnabled", notesEnabled);
            root.put("ramCacheMb", ramCacheMb);
            root.put("visual", map("theme", theme, "skinMode", skinMode, "animationSpeed", animationSpeed));
            root.put("sync", map("mode", "fast", "resumeAfterDisconnect", true));
            root.put("tab",
                map("displayedKit",
                tabDisplayedKit,
                "mode",
                tabMode,
                "scalePercent",
                tabScalePercent,
                "interactionTrigger",
                tabInteractionTrigger,
                "position",
                tabPosition));
            root.put("experimental", map("compareMode", experimentalCompareMode));
            root.put("settingsUi", "tierlookup_custom");
            String json=MiniJson.stringify(root);
            Files.writeString(tmp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException noAtomic) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            BootstrapLog.error("CONFIG save "+file, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
            }
        }
    }
    private synchronized void normalizeOrders() {
        providerOrder.clear();
        providerOrder.addAll(canonicalProviderOrder);
        kitOrder.clear();
        kitOrder.addAll(KNOWN_KITS);
    }
    private static DataMode parseDataMode(Object value, DataMode fallback) {
        if(value==null)return fallback;
        String raw=String.valueOf(value).trim().toUpperCase(Locale.ROOT);
        if("RAM".equals(raw))return DataMode.OFFLINE;
        try {
            return DataMode.valueOf(raw);
        } catch (Exception e) {
            return fallback;
        }
    }
    private static int asInt(Object v, int d) {
        if(v instanceof Number n)return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return d;
        }
    }
    private static String cycle(String cur, String... values) {
        for(int i=0; i<values.length; i++)if(values[i].equals(cur))return values[(i+1)%values.length];
        return values[0];
    }
    private static String oneOf(Object value, String fallback, String... allowed) {
        if(value==null)return fallback;
        String s=String.valueOf(value);
        for(String a:allowed)if(a.equals(s))return s;
        return fallback;
    }
    private static LinkedHashMap<String, Object> map(Object... kv) {
        LinkedHashMap<String, Object> m=new LinkedHashMap<>();
        for(int i=0; i+1<kv.length; i+=2)m.put(String.valueOf(kv[i]), kv[i+1]);
        return m;
    }
}
