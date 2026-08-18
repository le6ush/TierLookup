package com.tierlookup.client;

import java.lang.reflect.*;
import java.util.*;

import com.tierlookup.TierLookupClient;
import com.tierlookup.model.*;
import com.tierlookup.service.ProfileService;
import com.tierlookup.service.TierLookupConfig;

/**
* Decorates vanilla Tab-list names with a compact "kit icon + strongest exact tier" prefix.
* The tierlist itself is intentionally omitted. No source/region weighting is applied.
*/ public final class TabNameDecorator {
    public record Badge(String kit, String tier, boolean retired, char glyph) {
    }
    private static final Map<String, Character> GLYPHS;
    static {
        String[] kits= {
            "sword",
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
                "bow"
        };
        LinkedHashMap<String, Character> m=new LinkedHashMap<>();
        for(int i=0; i<kits.length; i++)m.put(kits[i], (char)(0xE100+i));
        GLYPHS=Map.copyOf(m);
    }
    private static volatile Method entryProfileMethod,
        gameProfileIdMethod,
        gameProfileNameMethod,
        textLiteralMethod,
        textGetStyleMethod,
        styleWithFontMethod,
        textSetStyleMethod,
        textAppendMethod,
        textWithColorMethod;
    private static volatile Object iconFont;
    private static volatile Class<?> gameProfileClass, textClass, mutableTextClass, styleClass, fontInterfaceClass;
    private static volatile boolean initErrorLogged;
    private static final ThreadLocal<Boolean> BYPASS=ThreadLocal.withInitial(()->false);
    private static final Map<Object, EntryState> ENTRY_STATES=Collections.synchronizedMap(new WeakHashMap<>());
    /** Legacy-only client sort state. Server order is restored whenever Legacy is disabled. */
    private static final Map<Object, OrderState> ORDER_STATES=Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Method clientNetworkHandlerMethod,
        networkPlayerListMethod,
        entryDisplayNameMethod,
        entrySetDisplayNameMethod,
        entryGetListOrderMethod,
        entrySetListOrderMethod,
        hudPlayerListMethod,
        hudGetPlayerNameMethod;
    private static volatile Field clientInGameHudField;
    private static final class EntryState {
        final Object base, decorated;
        final long stamp;
        EntryState(Object base, Object decorated, long stamp) {
            this.base=base;
            this.decorated=decorated;
            this.stamp=stamp;
        }
    }
    private static final class OrderState {
        int serverOrder;
        int appliedOrder;
        boolean applied;
        OrderState(int serverOrder) {
            this.serverOrder=serverOrder;
            this.appliedOrder=serverOrder;
        }
    }
    private static final int LEGACY_SORT_BASE=1_000_000;
    private record CachedBadge(long fetchedAt, long configRevision, Badge badge) {
    }
    private static final java.util.concurrent.ConcurrentHashMap<UUID, CachedBadge> BADGE_CACHE=new java.util.concurrent.ConcurrentHashMap<>();
    private TabNameDecorator() {
    }
    public static Object decorate(Object entry, Object originalText) {
        if(originalText==null)return null;
        if(Boolean.TRUE.equals(BYPASS.get()))return originalText;
        try {
            EntryState st=ENTRY_STATES.get(entry);
            if(st!=null&&originalText==st.decorated)return originalText;
            PlayerIdentity id=identity(entry);
            if(id==null)return originalText;
            TierLookupConfig cfg=TierLookupClient.configInstance();
            if(cfg!=null&&!cfg.masterEnabled())return originalText;
            ProfileService service=TierLookupClient.profileServiceInstance();
            PlayerProfile p=service==null?null:service.profileForDisplay(id.uuid(), id.name());
            long fetched=p==null?0:p.fetchedAt(), revision=cfg==null?0:cfg.revision();
            CachedBadge cached=BADGE_CACHE.get(id.uuid());
            Badge badge;
            if(cached!=null&&cached.fetchedAt()==fetched&&cached.configRevision()==revision)badge=cached.badge();
            else {
                badge=bestBadge(p, cfg);
                if(BADGE_CACHE.size()>2048)BADGE_CACHE.clear();
                BADGE_CACHE.put(id.uuid(), new CachedBadge(fetched, revision, badge));
            }
            return compose(originalText, badge);
        } catch (Throwable t) {
            if(!initErrorLogged) {
                initErrorLogged=true;
                BootstrapLog.error("TAB BADGE decorate", unwrap(t));
            }
            return originalText;
        }
    }
    /**
    * Primary crash-safe Tab path: decorate PlayerListEntry.displayName directly while preserving the
    * server/team-formatted base Text. No HTTP/SQLite work is performed here; only RAM snapshots are read.
    */ public static void refreshVanillaTab(Object client) {
        if(client==null)return;
        TierLookupConfig activeCfg=TierLookupClient.configInstance();
        if(activeCfg!=null&&!activeCfg.masterEnabled()) {
            restoreVanillaTab(client);
            return;
        }
        try {
            Method nh=clientNetworkHandlerMethod;
            if(nh==null||!nh.getDeclaringClass().isAssignableFrom(client.getClass()))clientNetworkHandlerMethod=nh=client.getClass().getMethod("method_1562");
            Object handler=nh.invoke(client);
            if(handler==null)return;
            Method list=networkPlayerListMethod;
            if(list==null||!list.getDeclaringClass().isAssignableFrom(handler.getClass()))networkPlayerListMethod=list=handler.getClass().getMethod("method_2880");
            Object raw=list.invoke(handler);
            if(!(raw instanceof Iterable<?> iterable))return;
            ArrayList<Object> entries=new ArrayList<>();
            for(Object entry:iterable)if(entry!=null)entries.add(entry);
            Object playerListHud=playerListHud(client);
            for(Object entry:entries) {
                ensureEntryDisplayMethods(entry.getClass());
                captureServerOrder(entry);
                Object current=entryDisplayNameMethod.invoke(entry);
                EntryState state=ENTRY_STATES.get(entry);
                Object base;
                long stamp=decorationStamp(entry);
                if(state!=null&&current==state.decorated&&state.stamp==stamp)continue;
                if(state==null||current!=state.decorated) {
                    if(current!=null)base=current;
                    else base=vanillaPlayerName(playerListHud, entry);
                    if(base==null) {
                        PlayerIdentity id=identity(entry);
                        base=literal(id==null?"?":id.name());
                    }
                } else base=state.base;
                if(base==null)continue;
                Object decorated=decorate(entry, base);
                if(decorated==null)continue;
                entrySetDisplayNameMethod.invoke(entry, decorated);
                ENTRY_STATES.put(entry, new EntryState(base, decorated, stamp));
            }
            applyLegacySort(entries);
        } catch (Throwable t) {
            if(!initErrorLogged) {
                initErrorLogged=true;
                BootstrapLog.error("TAB BADGE fallback", unwrap(t));
            }
        }
    }
    /** Restore the server/vanilla Text captured before fallback decoration, used when TAB 2.0 is enabled. */
    public static void restoreVanillaTab(Object client) {
        synchronized(ENTRY_STATES) {
            for(var e:new ArrayList<>(ENTRY_STATES.entrySet())) {
                Object entry=e.getKey(), stateObj=e.getValue();
                if(entry==null||stateObj==null)continue;
                try {
                    EntryState st=(EntryState)stateObj;
                    ensureEntryDisplayMethods(entry.getClass());
                    Object current=entryDisplayNameMethod.invoke(entry);
                    if(current==st.decorated)entrySetDisplayNameMethod.invoke(entry, st.base);
                } catch (Throwable ignored) {
                }
            }
            ENTRY_STATES.clear();
        }
        restoreLegacyOrder();
        BADGE_CACHE.clear();
    }
    public static void resetVanillaTabState() {
        ENTRY_STATES.clear();
        ORDER_STATES.clear();
        BADGE_CACHE.clear();
    }
    private static long decorationStamp(Object entry) {
        try {
            PlayerIdentity id=identity(entry);
            if(id==null)return 0L;
            TierLookupConfig cfg=TierLookupClient.configInstance();
            ProfileService service=TierLookupClient.profileServiceInstance();
            PlayerProfile p=service==null?null:service.profileForDisplay(id.uuid(), id.name());
            long fetched=p==null?0L:p.fetchedAt(), revision=cfg==null?0L:cfg.revision();
            return fetched*31L+revision;
        } catch (Throwable ignored) {
            return 0L;
        }
    }
    private static void ensureEntryDisplayMethods(Class<?> ec)throws Exception {
        if(entryDisplayNameMethod!=null&&entryDisplayNameMethod.getDeclaringClass().isAssignableFrom(ec)&&entrySetDisplayNameMethod!=null&&entryGetListOrderMethod!=null&&entrySetListOrderMethod!=null)return;
        synchronized(TabNameDecorator.class) {
            if(entryDisplayNameMethod==null||!entryDisplayNameMethod.getDeclaringClass().isAssignableFrom(ec))entryDisplayNameMethod=ec.getMethod("method_2971");
            if(entrySetDisplayNameMethod==null||!entrySetDisplayNameMethod.getDeclaringClass().isAssignableFrom(ec)) {
                ensureTextReflection();
                entrySetDisplayNameMethod=ec.getMethod("method_2962", textClass);
            }
            if(entryGetListOrderMethod==null||!entryGetListOrderMethod.getDeclaringClass().isAssignableFrom(ec))entryGetListOrderMethod=ec.getMethod("method_62154");
            if(entrySetListOrderMethod==null||!entrySetListOrderMethod.getDeclaringClass().isAssignableFrom(ec))entrySetListOrderMethod=ec.getMethod("method_62153", int.class);
        }
    }
    /** Capture the server's latest listOrder before TierLookup overwrites it. */
    private static void captureServerOrder(Object entry)throws Exception {
        ensureEntryDisplayMethods(entry.getClass());
        int current=((Number)entryGetListOrderMethod.invoke(entry)).intValue();
        synchronized(ORDER_STATES) {
            OrderState st=ORDER_STATES.get(entry);
            if(st==null) {
                ORDER_STATES.put(entry, new OrderState(current));
                return;
            }
            // If the value no longer equals what TierLookup applied, a packet/server-side TAB plugin changed it.
            if(!st.applied||current!=st.appliedOrder)st.serverOrder=current;
        }
    }
    /**
    * Best-effort Legacy sorting without a render/mixin hook. Minecraft 1.21.11 exposes listOrder on
    * PlayerListEntry; higher values are assigned to stronger TierLookup rows while preserving the
    * server order as a stable tie-break. The original/latest observed server value is restored later.
    */ private static void applyLegacySort(List<Object> entries) {
        if(entries==null||entries.isEmpty())return;
        try {
            List<Object> sorted=sortEntries(entries);
            int n=sorted.size();
            for(int i=0; i<n; i++) {
                Object entry=sorted.get(i);
                ensureEntryDisplayMethods(entry.getClass());
                OrderState st;
                synchronized(ORDER_STATES) {
                    st=ORDER_STATES.computeIfAbsent(entry, e-> {
                        try {
                            return new OrderState(((Number)entryGetListOrderMethod.invoke(e)).intValue());
                        } catch (Throwable t) {
                            return new OrderState(0);
                        }
                    }
                    );
                }
                // Vanilla's 1.21.x player-list comparator gives larger listOrder values precedence.
                // Leave generous spacing so server/team secondary ordering cannot interleave tier groups.
                int applied=LEGACY_SORT_BASE-i;
                entrySetListOrderMethod.invoke(entry, applied);
                st.appliedOrder=applied;
                st.applied=true;
            }
        } catch (Throwable t) {
            if(!initErrorLogged) {
                initErrorLogged=true;
                BootstrapLog.error("TAB LEGACY sort", unwrap(t));
            }
        }
    }
    private static void restoreLegacyOrder() {
        synchronized(ORDER_STATES) {
            for(var e:new ArrayList<>(ORDER_STATES.entrySet())) {
                Object entry=e.getKey();
                OrderState st=e.getValue();
                if(entry==null||st==null)continue;
                try {
                    ensureEntryDisplayMethods(entry.getClass());
                    int current=((Number)entryGetListOrderMethod.invoke(entry)).intValue();
                    if(!st.applied||current==st.appliedOrder)entrySetListOrderMethod.invoke(entry, st.serverOrder);
                } catch (Throwable ignored) {
                }
            }
            ORDER_STATES.clear();
        }
    }
    private static Object playerListHud(Object client)throws Exception {
        Field f=clientInGameHudField;
        if(f==null||!f.getDeclaringClass().isAssignableFrom(client.getClass())) {
            f=client.getClass().getField("field_1705");
            clientInGameHudField=f;
        }
        Object hud=f.get(client);
        if(hud==null)return null;
        Method m=hudPlayerListMethod;
        if(m==null||!m.getDeclaringClass().isAssignableFrom(hud.getClass())) {
            m=hud.getClass().getMethod("method_1750");
            hudPlayerListMethod=m;
        }
        return m.invoke(hud);
    }
    private static Object vanillaPlayerName(Object playerListHud, Object entry)throws Exception {
        if(playerListHud==null)return null;
        Method m=hudGetPlayerNameMethod;
        if(m==null||!m.getDeclaringClass().isAssignableFrom(playerListHud.getClass())) {
            m=playerListHud.getClass().getMethod("method_1918", entry.getClass());
            hudGetPlayerNameMethod=m;
        }
        Boolean old=BYPASS.get();
        BYPASS.set(true);
        try {
            return m.invoke(playerListHud, entry);
        } finally {
            BYPASS.set(old);
        }
    }
    private static Object literal(String s)throws Exception {
        ensureTextReflection();
        return textLiteralMethod.invoke(null, s==null?"":s);
    }
    public static boolean fallbackPipelineAvailable() {
        try {
            Class<?> client=Class.forName("net.minecraft.class_310"),
                handler=Class.forName("net.minecraft.class_634"),
                entry=Class.forName("net.minecraft.class_640"),
                text=Class.forName("net.minecraft.class_2561"),
                hud=Class.forName("net.minecraft.class_329"),
                listHud=Class.forName("net.minecraft.class_355");
            client.getMethod("method_1562");
            client.getField("field_1705");
            handler.getMethod("method_2880");
            entry.getMethod("method_2966");
            entry.getMethod("method_2971");
            entry.getMethod("method_2962", text);
            entry.getMethod("method_62154");
            entry.getMethod("method_62153", int.class);
            hud.getMethod("method_1750");
            listHud.getMethod("method_1918", entry);
            ensureTextReflection();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
    public static boolean legacyListOrderPipelineAvailable() {
        try {
            Class<?> entry=Class.forName("net.minecraft.class_640");
            entry.getMethod("method_62154");
            entry.getMethod("method_62153", int.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
    public static Badge bestBadge(PlayerProfile p, TierLookupConfig cfg) {
        TierSelection.Best best=TierSelection.displayed(p, cfg);
        if(best==null)return null;
        char glyph=GLYPHS.getOrDefault(best.kit(), '\0');
        return new Badge(best.kit(), best.tier(), best.retired(), glyph);
    }
    /** Pure sort helper kept for a future safe Tab-order integration; it is not installed through a runtime mixin. */
    public static <T> List<T> sortEntries(List<T> source) {
        if(source==null||source.size()<2)return source;
        TierLookupConfig cfg=TierLookupClient.configInstance();
        ProfileService service=TierLookupClient.profileServiceInstance();
        if(service==null)return source;
        ArrayList<T> out=new ArrayList<>(source);
        IdentityHashMap<T, SortKey> keys=new IdentityHashMap<>();
        for(T e:out) {
            try {
                PlayerIdentity id=identity(e);
                PlayerProfile p=id==null?null:service.profileForDisplay(id.uuid(), id.name());
                Badge b=bestBadge(p, cfg);
                keys.put(e, new SortKey(b==null?-1:TierSelection.tierScore(b.tier()), b!=null&&b.retired(), id==null?"":id.name()));
            } catch (Throwable ignored) {
                keys.put(e, new SortKey(-1, false, ""));
            }
        }
        out.sort((a, b)-> {
            SortKey aa=keys.get(a), bb=keys.get(b);
            int c=Integer.compare(bb.score(), aa.score());
            if(c!=0)return c;
            if(aa.score()>=0&&aa.retired()!=bb.retired())return aa.retired()?1:-1;
            return aa.name().compareToIgnoreCase(bb.name());
        }
        );
        return out;
    }
    private record SortKey(int score, boolean retired, String name) {
    }
    private static PlayerIdentity identity(Object entry)throws Exception {
        if(entry==null)return null;
        Method m=entryProfileMethod;
        if(m==null||!m.getDeclaringClass().isAssignableFrom(entry.getClass())) {
            m=entry.getClass().getMethod("method_2966");
            entryProfileMethod=m;
        }
        Object gp=m.invoke(entry);
        if(gp==null)return null;
        Class<?> gc=gp.getClass();
        if(gameProfileClass!=gc||gameProfileIdMethod==null||gameProfileNameMethod==null) {
            synchronized(TabNameDecorator.class) {
                if(gameProfileClass!=gc||gameProfileIdMethod==null||gameProfileNameMethod==null) {
                    gameProfileClass=gc;
                    gameProfileIdMethod=findNoArg(gc, "getId", "id");
                    gameProfileNameMethod=findNoArg(gc, "getName", "name");
                }
            }
        }
        Object iv=gameProfileIdMethod==null?null:gameProfileIdMethod.invoke(gp), nv=gameProfileNameMethod==null?null:gameProfileNameMethod.invoke(gp);
        UUID uuid=iv instanceof UUID u?u:null;
        String name=nv instanceof String s?s:null;
        if(uuid==null||name==null||name.isBlank())return null;
        return new PlayerIdentity(uuid, name);
    }
    private static Object compose(Object original, Badge badge)throws Exception {
        ensureTextReflection();
        Object root=textLiteralMethod.invoke(null, "");
        if(badge==null||badge.glyph()==0) {
            Object dash=textLiteralMethod.invoke(null, "- ");
            textAppendMethod.invoke(root, dash);
            textAppendMethod.invoke(root, original);
            return root;
        }
        Object icon=textLiteralMethod.invoke(null, String.valueOf(badge.glyph()));
        Object style=textGetStyleMethod.invoke(icon);
        Object font=iconFont;
        if(font!=null) {
            style=styleWithFontMethod.invoke(style, font);
            textSetStyleMethod.invoke(icon, style);
        }
        textAppendMethod.invoke(root, icon);
        String rank=(badge.retired()?" R":" ")+badge.tier()+" ";
        Object rt=textLiteralMethod.invoke(null, rank);
        textWithColorMethod.invoke(rt, 0xB8F3FF);
        textAppendMethod.invoke(root, rt);
        textAppendMethod.invoke(root, original);
        return root;
    }
    private static void ensureTextReflection()throws Exception {
        if(textLiteralMethod!=null&&textAppendMethod!=null)return;
        synchronized(TabNameDecorator.class) {
            if(textLiteralMethod!=null&&textAppendMethod!=null)return;
            textClass=Class.forName("net.minecraft.class_2561");
            mutableTextClass=Class.forName("net.minecraft.class_5250");
            styleClass=Class.forName("net.minecraft.class_2583");
            fontInterfaceClass=Class.forName("net.minecraft.class_11719");
            textLiteralMethod=textClass.getMethod("method_43470", String.class);
            textGetStyleMethod=textClass.getMethod("method_10866");
            styleWithFontMethod=styleClass.getMethod("method_27704", fontInterfaceClass);
            textSetStyleMethod=mutableTextClass.getMethod("method_10862", styleClass);
            textAppendMethod=mutableTextClass.getMethod("method_10852", textClass);
            textWithColorMethod=mutableTextClass.getMethod("method_54663", int.class);
            try {
                Class<?> identifier=Class.forName("net.minecraft.class_2960"), fontClass=Class.forName("net.minecraft.class_11719$class_11721");
                Object id=identifier.getMethod("method_60654", String.class).invoke(null, "tierlookup:kit_icons");
                iconFont=fontClass.getConstructor(identifier).newInstance(id);
            } catch (Throwable fontError) {
                iconFont=null;
                BootstrapLog.error("TAB BADGE custom font", unwrap(fontError));
            }
        }
    }
    private static Method findNoArg(Class<?> type, String... names) {
        for(String name:names)try {
            return type.getMethod(name);
        } catch (Throwable ignored) {
        }
        return null;
    }
    private static Throwable unwrap(Throwable t) {
        Throwable x=t;
        while(x!=null&&x.getCause()!=null&&(x instanceof InvocationTargetException||x instanceof java.util.concurrent.CompletionException))x=x.getCause();
        return x==null?t:x;
    }
}
