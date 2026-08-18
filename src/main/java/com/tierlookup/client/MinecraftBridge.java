package com.tierlookup.client;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.tierlookup.model.*;

public final class MinecraftBridge {
    private static Class<?> MC;
    private static volatile Method CLIENT_INSTANCE_METHOD,
        CLIENT_RUN_METHOD,
        CLIENT_WINDOW_METHOD,
        WINDOW_HANDLE_METHOD,
        WINDOW_WIDTH_METHOD,
        WINDOW_HEIGHT_METHOD,
        CLIENT_SERVER_INFO_METHOD;
    private static volatile Method CLIENT_NETWORK_HANDLER_METHOD, NETWORK_PLAYER_LIST_METHOD, PLAYER_LIST_ENTRY_PROFILE_METHOD;
    private static volatile Field CLIENT_PLAYER_FIELD,
        CLIENT_WORLD_FIELD,
        CLIENT_TARGET_FIELD,
        CLIENT_TEXT_RENDERER_FIELD,
        CLIENT_OPTIONS_FIELD,
        OPTIONS_LANGUAGE_FIELD,
        SERVER_INFO_ADDRESS_FIELD,
        CLIENT_SCREEN_FIELD;
    private static volatile Method PLAYER_INVENTORY_METHOD, INVENTORY_CHANGE_METHOD, PLAYER_HAS_EFFECT_METHOD, PLAYER_UUID_METHOD, PLAYER_SCORE_NAME_METHOD;
    private static volatile Object EFFECT_SPEED, EFFECT_STRENGTH, EFFECT_REGEN;
    private static volatile Method WORLD_SCOREBOARD_METHOD, SCOREBOARD_OBJECTIVE_FOR_SLOT_METHOD;
    private static volatile Object SIDEBAR_SLOT;
    private static volatile long SIDEBAR_CACHE_AT;
    private static volatile boolean SIDEBAR_CACHE_VALUE;
    private static volatile Class<?> EFFECT_ENTRY_CLASS, PLAYER_CLASS;
    private static final Object HOT_ACCESS_LOCK=new Object();
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    private MinecraftBridge() {
    }
    public static void logOnce(String where, Throwable t) {
        if (LOGGED.add(where + ":" + t.getClass().getName() + ":" + String.valueOf(t.getMessage()))) BootstrapLog.error("MinecraftBridge." + where, t);
    }
    public static Object client() {
        try {
            if(MC==null) {
                MC=Class.forName("net.minecraft.class_310");
                
            }
            Method im=CLIENT_INSTANCE_METHOD;
            if(im==null) {
                synchronized(HOT_ACCESS_LOCK) {
                    im=CLIENT_INSTANCE_METHOD;
                    if(im==null)CLIENT_INSTANCE_METHOD=im=MC.getMethod("method_1551");
                }
            }
            Object c=im.invoke(null);
            return c;
        } catch (Throwable e) {
            logOnce("client", e);
            return null;
        }
    }
    /** Schedule lightweight UI work back on the Minecraft client thread without adding a hot-path dependency. */
    public static void runOnClientThread(Runnable task) {
        if(task==null)return;
        Object c=client();
        if(c==null) {
            task.run();
            return;
        }
        try {
            Method m=CLIENT_RUN_METHOD;
            if(m==null) {
                synchronized(HOT_ACCESS_LOCK) {
                    m=CLIENT_RUN_METHOD;
                    if(m==null)CLIENT_RUN_METHOD=m=c.getClass().getMethod("method_18859", Runnable.class);
                }
            }
            m.invoke(c, task);
        } catch (Throwable e) {
            logOnce("runOnClientThread", e);
            task.run();
        }
    }
    private static Object hotWindow(Object client)throws Exception {
        if(client==null)return null;
        Method m=CLIENT_WINDOW_METHOD;
        if(m==null) {
            synchronized(HOT_ACCESS_LOCK) {
                m=CLIENT_WINDOW_METHOD;
                if(m==null)CLIENT_WINDOW_METHOD=m=client.getClass().getMethod("method_22683");
            }
        }
        return m.invoke(client);
    }
    public static long windowHandle(Object client) {
        try {
            Object w=hotWindow(client);
            if(w==null)return 0;
            Method m=WINDOW_HANDLE_METHOD;
            if(m==null||!m.getDeclaringClass().isAssignableFrom(w.getClass())) {
                synchronized(HOT_ACCESS_LOCK) {
                    m=WINDOW_HANDLE_METHOD;
                    if(m==null||!m.getDeclaringClass().isAssignableFrom(w.getClass()))WINDOW_HANDLE_METHOD=m=w.getClass().getMethod("method_4490");
                }
            }
            return ((Number)m.invoke(w)).longValue();
        } catch (Throwable e) {
            logOnce("windowHandle", e);
            return 0;
        }
    }
    public static int scaledWidth(Object client) {
        try {
            Object w=hotWindow(client);
            if(w==null)return 854;
            Method m=WINDOW_WIDTH_METHOD;
            if(m==null||!m.getDeclaringClass().isAssignableFrom(w.getClass())) {
                synchronized(HOT_ACCESS_LOCK) {
                    m=WINDOW_WIDTH_METHOD;
                    if(m==null||!m.getDeclaringClass().isAssignableFrom(w.getClass()))WINDOW_WIDTH_METHOD=m=w.getClass().getMethod("method_4486");
                }
            }
            return ((Number)m.invoke(w)).intValue();
        } catch (Throwable e) {
            logOnce("scaledWidth", e);
            return 854;
        }
    }
    public static int scaledHeight(Object client) {
        try {
            Object w=hotWindow(client);
            if(w==null)return 480;
            Method m=WINDOW_HEIGHT_METHOD;
            if(m==null||!m.getDeclaringClass().isAssignableFrom(w.getClass())) {
                synchronized(HOT_ACCESS_LOCK) {
                    m=WINDOW_HEIGHT_METHOD;
                    if(m==null||!m.getDeclaringClass().isAssignableFrom(w.getClass()))WINDOW_HEIGHT_METHOD=m=w.getClass().getMethod("method_4502");
                }
            }
            return ((Number)m.invoke(w)).intValue();
        } catch (Throwable e) {
            logOnce("scaledHeight", e);
            return 480;
        }
    }
    public static Object textRenderer(Object client) {
        try {
            if(client==null)return null;
            Field f=CLIENT_TEXT_RENDERER_FIELD;
            if(f==null) {
                synchronized(HOT_ACCESS_LOCK) {
                    f=CLIENT_TEXT_RENDERER_FIELD;
                    if(f==null)CLIENT_TEXT_RENDERER_FIELD=f=client.getClass().getField("field_1772");
                }
            }
            return f.get(client);
        } catch (Throwable e) {
            logOnce("textRenderer", e);
            return null;
        }
    }
    public static boolean gameplayScreenClear(Object client) {
        return currentScreenObject(client)==null&&client!=null;
    }
    public static Object currentScreenObject(Object client) {
        try {
            if(client==null)return null;
            Field f=CLIENT_SCREEN_FIELD;
            if(f==null) {
                synchronized(HOT_ACCESS_LOCK) {
                    f=CLIENT_SCREEN_FIELD;
                    if(f==null)CLIENT_SCREEN_FIELD=f=client.getClass().getField("field_1755");
                }
            }
            return f.get(client);
        } catch (Throwable e) {
            logOnce("currentScreenObject", e);
            return new Object();
        }
    }
    public static PlayerIdentity localPlayer(Object client) {
        try {
            if(client==null)return null;
            Object entity=client.getClass().getField("field_1724").get(client);
            return entity==null?null:identity(entity);
        } catch (Throwable e) {
            logOnce("localPlayer", e);
            return null;
        }
    }
    public static PlayerIdentity targetPlayer(Object client) {
        try {
            if(client==null)return null;
            Field f=CLIENT_TARGET_FIELD;
            if(f==null) {
                synchronized(HOT_ACCESS_LOCK) {
                    f=CLIENT_TARGET_FIELD;
                    if(f==null)CLIENT_TARGET_FIELD=f=client.getClass().getField("field_1692");
                }
            }
            Object entity=f.get(client);
            if(entity==null)return null;
            return identity(entity);
        } catch (Throwable e) {
            logOnce("targetPlayer", e);
            return null;
        }
    }
    /**
    * Snapshot of the local player's own inventory. No remote player inventory is inspected.
    * Besides registry ids we retain custom-name presence and the potion effects required by the
    * kit detector. Active strength/speed/regeneration effects are read from the local player only.
    */ public static LocalInventorySnapshot localInventorySnapshot(Object client) {
        try {
            if(client==null)return new LocalInventorySnapshot(List.of(), false, false, false);
            Object player=client.getClass().getField("field_1724").get(client);
            if(player==null)return new LocalInventorySnapshot(List.of(), false, false, false);
            Object inventory=player.getClass().getMethod("method_31548").invoke(player);
            if(inventory==null)return new LocalInventorySnapshot(List.of(), false, false, false);
            int size=((Number)inventory.getClass().getMethod("method_5439").invoke(inventory)).intValue();
            Object itemRegistry=Class.forName("net.minecraft.class_7923").getField("field_41178").get(null);
            Method getId=Class.forName("net.minecraft.class_2378").getMethod("method_10221", Object.class);
            Method getStack=inventory.getClass().getMethod("method_5438", int.class);
            Class<?> componentType=Class.forName("net.minecraft.class_9331");
            Object potionComponentType=Class.forName("net.minecraft.class_9334").getField("field_49651").get(null);
            Object effectsClass=Class.forName("net.minecraft.class_1294");
            Object speed=((Class<?>)effectsClass).getField("field_5904").get(null);
            Object strength=((Class<?>)effectsClass).getField("field_5910").get(null);
            Object regen=((Class<?>)effectsClass).getField("field_5924").get(null);
            Object instantHealth=((Class<?>)effectsClass).getField("field_5915").get(null);
            ArrayList<LocalInventorySnapshot.Item> out=new ArrayList<>(Math.max(0, size));
            for(int i=0; i<size; i++) {
                Object stack=getStack.invoke(inventory, i);
                if(stack==null||Boolean.TRUE.equals(stack.getClass().getMethod("method_7960").invoke(stack)))continue;
                Object item=stack.getClass().getMethod("method_7909").invoke(stack);
                if(item==null)continue;
                Object idObj=getId.invoke(itemRegistry, item);
                if(idObj==null)continue;
                String id=String.valueOf(idObj);
                int count=1;
                try {
                    count=((Number)stack.getClass().getMethod("method_7947").invoke(stack)).intValue();
                } catch (Throwable ignored) {
                }
                boolean customNamed=false;
                try {
                    customNamed=stack.getClass().getMethod("method_65130").invoke(stack)!=null;
                } catch (Throwable ignored) {
                }
                LinkedHashSet<String> potionEffects=new LinkedHashSet<>();
                try {
                    Object contents=stack.getClass().getMethod("method_58694", componentType).invoke(stack, potionComponentType);
                    if(contents!=null) {
                        Object rawEffects=contents.getClass().getMethod("method_57397").invoke(contents);
                        if(rawEffects instanceof Iterable<?> iterable)for(Object effect:iterable) {
                            if(effect==null)continue;
                            Object type=effect.getClass().getMethod("method_5579").invoke(effect);
                            if(Objects.equals(type, instantHealth))potionEffects.add("instant_health");
                            if(Objects.equals(type, speed))potionEffects.add("speed");
                            if(Objects.equals(type, strength))potionEffects.add("strength");
                            if(Objects.equals(type, regen))potionEffects.add("regeneration");
                        }
                    }
                } catch (Throwable ignored) {
                }
                out.add(new LocalInventorySnapshot.Item(id, count, customNamed, potionEffects));
            }
            Class<?> registryEntry=Class.forName("net.minecraft.class_6880");
            Method hasEffect=player.getClass().getMethod("method_6059", registryEntry);
            boolean activeSpeed=Boolean.TRUE.equals(hasEffect.invoke(player, speed));
            boolean activeStrength=Boolean.TRUE.equals(hasEffect.invoke(player, strength));
            boolean activeRegen=Boolean.TRUE.equals(hasEffect.invoke(player, regen));
            return new LocalInventorySnapshot(out, activeSpeed, activeStrength, activeRegen);
        } catch (Throwable e) {
            logOnce("localInventorySnapshot", e);
            return new LocalInventorySnapshot(List.of(), false, false, false);
        }
    }
    private static Object hotLocalPlayer(Object client) throws Exception {
        if(client==null)return null;
        Field f=CLIENT_PLAYER_FIELD;
        if(f==null) {
            synchronized(HOT_ACCESS_LOCK) {
                f=CLIENT_PLAYER_FIELD;
                if(f==null)CLIENT_PLAYER_FIELD=f=client.getClass().getField("field_1724");
            }
        }
        return f.get(client);
    }
    private static Object hotInventory(Object player) throws Exception {
        if(player==null)return null;
        Method m=PLAYER_INVENTORY_METHOD;
        if(m==null) {
            synchronized(HOT_ACCESS_LOCK) {
                m=PLAYER_INVENTORY_METHOD;
                if(m==null)PLAYER_INVENTORY_METHOD=m=player.getClass().getMethod("method_31548");
            }
        }
        return m.invoke(player);
    }
    private static void ensureHotEffects(Object player, Object inventory) throws Exception {
        if(INVENTORY_CHANGE_METHOD!=null&&PLAYER_HAS_EFFECT_METHOD!=null&&EFFECT_SPEED!=null)return;
        synchronized(HOT_ACCESS_LOCK) {
            if(INVENTORY_CHANGE_METHOD==null&&inventory!=null)INVENTORY_CHANGE_METHOD=inventory.getClass().getMethod("method_7364");
            if(EFFECT_ENTRY_CLASS==null)EFFECT_ENTRY_CLASS=Class.forName("net.minecraft.class_6880");
            if(PLAYER_HAS_EFFECT_METHOD==null&&player!=null)PLAYER_HAS_EFFECT_METHOD=player.getClass().getMethod("method_6059", EFFECT_ENTRY_CLASS);
            if(EFFECT_SPEED==null) {
                Class<?> effects=Class.forName("net.minecraft.class_1294");
                EFFECT_SPEED=effects.getField("field_5904").get(null);
                EFFECT_STRENGTH=effects.getField("field_5910").get(null);
                EFFECT_REGEN=effects.getField("field_5924").get(null);
            }
        }
    }
    /** Cheap PlayerInventory revision used to avoid rescanning 40+ slots when nothing changed. Reflection metadata is cached. */
    public static int localInventoryRevision(Object client) {
        try {
            Object player=hotLocalPlayer(client);
            if(player==null)return Integer.MIN_VALUE;
            Object inventory=hotInventory(player);
            if(inventory==null)return Integer.MIN_VALUE;
            ensureHotEffects(player, inventory);
            return ((Number)INVENTORY_CHANGE_METHOD.invoke(inventory)).intValue();
        } catch (Throwable e) {
            logOnce("localInventoryRevision", e);
            return Integer.MIN_VALUE;
        }
    }
    /** Three active potion effects that influence DPot detection, encoded as bits. Reflection metadata is cached. */
    public static int localRelevantEffectBits(Object client) {
        try {
            Object player=hotLocalPlayer(client);
            if(player==null)return 0;
            Object inventory=hotInventory(player);
            ensureHotEffects(player, inventory);
            Method has=PLAYER_HAS_EFFECT_METHOD;
            int bits=0;
            if(Boolean.TRUE.equals(has.invoke(player, EFFECT_SPEED)))bits|=1;
            if(Boolean.TRUE.equals(has.invoke(player, EFFECT_STRENGTH)))bits|=2;
            if(Boolean.TRUE.equals(has.invoke(player, EFFECT_REGEN)))bits|=4;
            return bits;
        } catch (Throwable e) {
            logOnce("localRelevantEffectBits", e);
            return 0;
        }
    }
    /** Backward-compatible id-only view used by older diagnostics. */
    public static List<String> localInventoryItemIds(Object client) {
        ArrayList<String> ids=new ArrayList<>();
        for(LocalInventorySnapshot.Item item:localInventorySnapshot(client).items())ids.add(item.id());
        return ids;
    }
    /**
    * Reads the player identity without depending on Authlib internals:
    * authlib 7.x changed its accessor surface, while the Minecraft entity API already exposes
    * a stable UUID and a scoreboard name. For player entities getNameForScoreboard is the username.
    */ public static PlayerIdentity identity(Object entity) {
        try {
            if(entity==null)return null;
            Class<?> pc=PLAYER_CLASS;
            Method uuid=PLAYER_UUID_METHOD, nameMethod=PLAYER_SCORE_NAME_METHOD;
            if(pc==null||uuid==null||nameMethod==null) {
                synchronized(HOT_ACCESS_LOCK) {
                    if(PLAYER_CLASS==null)PLAYER_CLASS=Class.forName("net.minecraft.class_1657");
                    pc=PLAYER_CLASS;
                    if(PLAYER_UUID_METHOD==null)PLAYER_UUID_METHOD=pc.getMethod("method_5667");
                    if(PLAYER_SCORE_NAME_METHOD==null)PLAYER_SCORE_NAME_METHOD=pc.getMethod("method_5820");
                    uuid=PLAYER_UUID_METHOD;
                    nameMethod=PLAYER_SCORE_NAME_METHOD;
                }
            }
            if(!pc.isInstance(entity))return null;
            UUID id=(UUID)uuid.invoke(entity);
            String name=(String)nameMethod.invoke(entity);
            if(id==null||name==null||name.isBlank()) return null;
            return new PlayerIdentity(id, name);
        } catch (Throwable primary) {
            // Fallback for unusual modded player implementations: inspect GameProfile accessors by signature.
            try {
                Class<?> pc=Class.forName("net.minecraft.class_1657");
                Object gp=pc.getMethod("method_7334").invoke(entity);
                UUID id=null;
                String name=null;
                for(String n:new String[] {
                    "id", "getId"
                }
                ) try {
                    Object v=gp.getClass().getMethod(n).invoke(gp);
                    if(v instanceof UUID u) {
                        id=u;
                        break;
                    }
                } catch (Throwable ignored) {
                }
                for(String n:new String[] {
                    "name", "getName"
                }
                ) try {
                    Object v=gp.getClass().getMethod(n).invoke(gp);
                    if(v instanceof String s) {
                        name=s;
                        break;
                    }
                } catch (Throwable ignored) {
                }
                if(id!=null&&name!=null&&!name.isBlank()) return new PlayerIdentity(id, name);
            } catch (Throwable fallback) {
                logOnce("identity.fallback", fallback);
            }
            logOnce("identity", primary);
            return null;
        }
    }
    public static List<PlayerIdentity> players(Object client) {
        try {
            if(client==null)return List.of();
            Object world=client.getClass().getField("field_1687").get(client);
            if(world==null)return List.of();
            Object raw=world.getClass().getMethod("method_18456").invoke(world);
            ArrayList<PlayerIdentity> r=new ArrayList<>();
            if(raw instanceof Iterable<?> it)for(Object e:it) {
                PlayerIdentity p=identity(e);
                if(p!=null)r.add(p);
            }
            return r;
        } catch (Throwable e) {
            logOnce("players", e);
            return List.of();
        }
    }
    /**
    * Snapshot of Minecraft's actual Tab/player-list roster. Unlike world entity iteration this includes
    * players outside render distance. It is intentionally read-only and performs no network request.
    */ public static List<PlayerIdentity> tabPlayers(Object client) {
        try {
            if(client==null)return List.of();
            Method nh=CLIENT_NETWORK_HANDLER_METHOD;
            if(nh==null||!nh.getDeclaringClass().isAssignableFrom(client.getClass())) {
                synchronized(HOT_ACCESS_LOCK) {
                    nh=CLIENT_NETWORK_HANDLER_METHOD;
                    if(nh==null||!nh.getDeclaringClass().isAssignableFrom(client.getClass()))CLIENT_NETWORK_HANDLER_METHOD=nh=client.getClass().getMethod("method_1562");
                }
            }
            Object handler=nh.invoke(client);
            if(handler==null)return List.of();
            Method list=NETWORK_PLAYER_LIST_METHOD;
            if(list==null||!list.getDeclaringClass().isAssignableFrom(handler.getClass())) {
                synchronized(HOT_ACCESS_LOCK) {
                    list=NETWORK_PLAYER_LIST_METHOD;
                    if(list==null||!list.getDeclaringClass().isAssignableFrom(handler.getClass()))NETWORK_PLAYER_LIST_METHOD=list=handler.getClass().getMethod("method_2880");
                }
            }
            Object raw=list.invoke(handler);
            if(!(raw instanceof Iterable<?> it))return List.of();
            LinkedHashMap<UUID, PlayerIdentity> out=new LinkedHashMap<>();
            for(Object entry:it) {
                if(entry==null)continue;
                Method profile=PLAYER_LIST_ENTRY_PROFILE_METHOD;
                if(profile==null||!profile.getDeclaringClass().isAssignableFrom(entry.getClass())) {
                    synchronized(HOT_ACCESS_LOCK) {
                        profile=PLAYER_LIST_ENTRY_PROFILE_METHOD;
                        if(profile==null||!profile.getDeclaringClass().isAssignableFrom(entry.getClass()))PLAYER_LIST_ENTRY_PROFILE_METHOD=profile=entry.getClass().getMethod("method_2966");
                    }
                }
                PlayerIdentity p=gameProfileIdentity(profile.invoke(entry));
                if(p!=null)out.put(p.uuid(), p);
            }
            return List.copyOf(out.values());
        } catch (Throwable e) {
            logOnce("tabPlayers", e);
            return List.of();
        }
    }
    private static PlayerIdentity gameProfileIdentity(Object gp) {
        if(gp==null)return null;
        UUID id=null;
        String name=null;
        for(String n:new String[] {
            "id", "getId"
        }
        )try {
            Object v=gp.getClass().getMethod(n).invoke(gp);
            if(v instanceof UUID u) {
                id=u;
                break;
            }
        } catch (Throwable ignored) {
        }
        for(String n:new String[] {
            "name", "getName"
        }
        )try {
            Object v=gp.getClass().getMethod(n).invoke(gp);
            if(v instanceof String s&&!s.isBlank()) {
                name=s;
                break;
            }
        } catch (Throwable ignored) {
        }
        if(id==null||name==null) {
            for(Field f:gp.getClass().getDeclaredFields())try {
                f.setAccessible(true);
                Object v=f.get(gp);
                if(id==null&&v instanceof UUID u)id=u;
                else if(name==null&&v instanceof String s&&!s.isBlank())name=s;
            } catch (Throwable ignored) {
            }
        }
        return id==null||name==null||name.isBlank()?null:new PlayerIdentity(id, name);
    }
    /** Runtime mapping smoke-test for the 1.21.11 Tab roster path; no server connection is required. */
    /** Raw GLFW code of Minecraft's currently bound chat key (T by default). */
    public static int chatKeyCode(Object client) {
        try {
            if(client==null)return 84;
            Object options=client.getClass().getField("field_1690").get(client);
            Object chat=options.getClass().getField("field_1890").get(options);
            Field bound=chat.getClass().getDeclaredField("field_1655");
            bound.setAccessible(true);
            Object key=bound.get(chat);
            Object code=key.getClass().getMethod("method_1444").invoke(key);
            return code instanceof Number n?n.intValue():84;
        } catch (Throwable e) {
            logOnce("chatKeyCode", e);
            return 84;
        }
    }
    public static boolean russian(Object client) {
        try {
            if(client==null)return Locale.getDefault().getLanguage().equals("ru");
            Field of=CLIENT_OPTIONS_FIELD;
            if(of==null) {
                synchronized(HOT_ACCESS_LOCK) {
                    of=CLIENT_OPTIONS_FIELD;
                    if(of==null)CLIENT_OPTIONS_FIELD=of=client.getClass().getField("field_1690");
                }
            }
            Object options=of.get(client);
            if(options==null)return Locale.getDefault().getLanguage().equals("ru");
            Field lf=OPTIONS_LANGUAGE_FIELD;
            if(lf==null) {
                synchronized(HOT_ACCESS_LOCK) {
                    lf=OPTIONS_LANGUAGE_FIELD;
                    if(lf==null) {
                        for(Field f:options.getClass().getDeclaredFields()) {
                            if(f.getType()!=String.class)continue;
                            f.setAccessible(true);
                            Object v=f.get(options);
                            if(v instanceof String x&&x.matches("[a-z]{2}_[a-z]{2}")) {
                                OPTIONS_LANGUAGE_FIELD=lf=f;
                                break;
                            }
                        }
                    }
                }
            }
            if(lf!=null) {
                Object v=lf.get(options);
                if(v instanceof String x)return x.toLowerCase(Locale.ROOT).startsWith("ru_");
            }
        } catch (Throwable e) {
            logOnce("russian", e);
        }
        return Locale.getDefault().getLanguage().equals("ru");
    }
    public static Path configDir() {
        try {
            Class<?> fl=Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object inst=fl.getMethod("getInstance").invoke(null);
            Path p=(Path)fl.getMethod("getConfigDir").invoke(inst);
            
            return p;
        } catch (Throwable e) {
            logOnce("configDir", e);
            return Path.of("config");
        }
    }
    public static Path gameDir() {
        try {
            Class<?> fl=Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object inst=fl.getMethod("getInstance").invoke(null);
            Path p=(Path)fl.getMethod("getGameDir").invoke(inst);
            
            return p;
        } catch (Throwable e) {
            logOnce("gameDir", e);
            return Path.of(".");
        }
    }
    public static String entityName(Object entity) {
        PlayerIdentity p=identity(entity);
        return p==null?null:p.name();
    }
    /** Stable-ish server tag used only for local search prioritization; never sent anywhere. */
    public static String currentServerKey(Object client) {
        try {
            if(client==null||!hasWorld(client))return null;
            Method sm=CLIENT_SERVER_INFO_METHOD;
            if(sm==null) {
                synchronized(HOT_ACCESS_LOCK) {
                    sm=CLIENT_SERVER_INFO_METHOD;
                    if(sm==null) {
                        for(Method m:client.getClass().getMethods()) {
                            if(m.getParameterCount()!=0)continue;
                            String rt=m.getReturnType().getName();
                            if(rt.equals("net.minecraft.class_642")||rt.endsWith("ServerInfo")) {
                                CLIENT_SERVER_INFO_METHOD=sm=m;
                                break;
                            }
                        }
                    }
                }
            }
            Object info=sm==null?null:sm.invoke(client);
            if(info!=null) {
                Field af=SERVER_INFO_ADDRESS_FIELD;
                if(af!=null&&af.getDeclaringClass().isAssignableFrom(info.getClass())) {
                    Object v=af.get(info);
                    if(v instanceof String x&&!x.isBlank())return x.toLowerCase(Locale.ROOT);
                }
                String fallback=null;
                Field chosen=null;
                for(Field f:info.getClass().getDeclaredFields()) {
                    if(f.getType()!=String.class)continue;
                    try {
                        f.setAccessible(true);
                        Object v=f.get(info);
                        if(v instanceof String x&&!x.isBlank()) {
                            if(fallback==null) {
                                fallback=x;
                                chosen=f;
                            }
                            if(x.contains(".")||x.contains(":")||x.equalsIgnoreCase("localhost")) {
                                SERVER_INFO_ADDRESS_FIELD=f;
                                return x.toLowerCase(Locale.ROOT);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if(fallback!=null) {
                    if(chosen!=null)SERVER_INFO_ADDRESS_FIELD=chosen;
                    return fallback.toLowerCase(Locale.ROOT);
                }
            }
            return "local-world";
        } catch (Throwable e) {
            logOnce("currentServerKey", e);
            return null;
        }
    }
    public static boolean hasWorld(Object client) {
        try {
            if(client==null)return false;
            Field f=CLIENT_WORLD_FIELD;
            if(f==null) {
                synchronized(HOT_ACCESS_LOCK) {
                    f=CLIENT_WORLD_FIELD;
                    if(f==null)CLIENT_WORLD_FIELD=f=client.getClass().getField("field_1687");
                }
            }
            return f.get(client)!=null;
        } catch (Throwable e) {
            logOnce("hasWorld", e);
            return false;
        }
    }
    /**
    * Lightweight vanilla sidebar detection for top-right HUD collision avoidance. The reflective
    * members are resolved once and the result is sampled at most twice per second, so this is not
    * a per-frame reflection hot path. Unknown/modded scoreboard implementations fail open.
    */ public static boolean hasSidebarScoreboard(Object client) {
        long now=System.currentTimeMillis();
        if(now-SIDEBAR_CACHE_AT<500L)return SIDEBAR_CACHE_VALUE;
        synchronized(HOT_ACCESS_LOCK) {
            now=System.currentTimeMillis();
            if(now-SIDEBAR_CACHE_AT<500L)return SIDEBAR_CACHE_VALUE;
            boolean value=false;
            try {
                if(client!=null) {
                    Field wf=CLIENT_WORLD_FIELD;
                    if(wf==null)CLIENT_WORLD_FIELD=wf=client.getClass().getField("field_1687");
                    Object world=wf.get(client);
                    if(world!=null) {
                        Method wm=WORLD_SCOREBOARD_METHOD;
                        if(wm==null)WORLD_SCOREBOARD_METHOD=wm=world.getClass().getMethod("method_8428");
                        Object scoreboard=wm.invoke(world);
                        if(scoreboard!=null) {
                            if(SIDEBAR_SLOT==null) {
                                Class<?> slot=Class.forName("net.minecraft.class_8646");
                                SIDEBAR_SLOT=slot.getField("field_45157").get(null);
                            }
                            Method om=SCOREBOARD_OBJECTIVE_FOR_SLOT_METHOD;
                            if(om==null)SCOREBOARD_OBJECTIVE_FOR_SLOT_METHOD=om=scoreboard.getClass().getMethod("method_1189", SIDEBAR_SLOT.getClass());
                            value=om.invoke(scoreboard, SIDEBAR_SLOT)!=null;
                        }
                    }
                }
            } catch (Throwable e) {
                logOnce("hasSidebarScoreboard", e);
                value=false;
            }
            SIDEBAR_CACHE_VALUE=value;
            SIDEBAR_CACHE_AT=now;
            return value;
        }
    }
    public static String currentScreenDiagnostics(Object client) {
        if(client==null)return "client=null";
        try {
            Object screen=client.getClass().getField("field_1755").get(client);
            if(screen==null)return "screen=null";
            StringBuilder b=new StringBuilder("screen=").append(screen.getClass().getName());
            if(screen.getClass().getName().equals("net.minecraft.class_419")) {
                try {
                    Field infoF=screen.getClass().getDeclaredField("field_52131");
                    infoF.setAccessible(true);
                    Object info=infoF.get(screen);
                    b.append(" info=").append(String.valueOf(info));
                    try {
                        Object report=info.getClass().getMethod("comp_2854").invoke(info);
                        b.append(" report=").append(String.valueOf(report));
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable t) {
                    b.append(" infoReadError=").append(t.getClass().getSimpleName()).append(':').append(String.valueOf(t.getMessage()));
                }
            }
            return b.toString();
        } catch (Throwable e) {
            logOnce("currentScreenDiagnostics", e);
            return "screen-error="+e;
        }
    }
}
