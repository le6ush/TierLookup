package com.tierlookup.client.runtime;

import com.tierlookup.client.BootstrapLog;
import java.lang.reflect.*;
import java.util.*;

import com.tierlookup.model.PlayerIdentity;

/** All hard 1.21.11 intermediary access used by new client integrations lives here. */
public final class Minecraft12111Adapter implements MinecraftRuntimeAdapter {
    @Override
    public String runtimeId() {
        return "minecraft-1.21.11-intermediary";
    }
    @Override
    public List<String> supportedVersions() {
        return List.of("1.21.11");
    }
    @Override
    public boolean playerListPressed(Object client) {
        try {
            if(client==null)return false;
            Object options=field(client, "field_1690");
            if(options==null)return false;
            Object key=field(options, "field_1907");
            if(key==null)return false;
            Object v=key.getClass().getMethod("method_1434").invoke(key);
            return Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            return false;
        }
    }
    @Override
    public List<TabEntry> tabEntries(Object client) {
        try {
            if(client==null)return List.of();
            Object handler=client.getClass().getMethod("method_1562").invoke(client);
            if(handler==null)return List.of();
            Object raw=handler.getClass().getMethod("method_2880").invoke(handler);
            if(!(raw instanceof Iterable<?> it))return List.of();
            Object listHud=null;
            try {
                Object hud=field(client, "field_1705");
                if(hud!=null)listHud=hud.getClass().getMethod("method_1750").invoke(hud);
            } catch (Throwable ignored) {
            }
            LinkedHashMap<UUID, TabEntry> out=new LinkedHashMap<>();
            int index=0;
            for(Object entry:it) {
                PlayerIdentity p=identity(entry);
                if(p==null) {
                    index++;
                    continue;
                }
                Object styled=richestDisplayText(listHud, entry, p);
                String display=textString(styled);
                if(display==null||display.isBlank())display=p.name();
                out.put(p.uuid(), new TabEntry(p, display, styled, index++));
            }
            return List.copyOf(out.values());
        } catch (Throwable t) {
            BootstrapLog.error("RUNTIME 1.21.11 tabEntries", unwrap(t));
            return List.of();
        }
    }
    @Override
    public boolean openPrefilledChat(Object client, String initial) {
        try {
            if(client==null)return false;
            Class<?> screen=Class.forName("net.minecraft.class_437"), chat=Class.forName("net.minecraft.class_408");
            Object chatScreen=chat.getConstructor(String.class, boolean.class).newInstance(initial==null?"":initial, false);
            client.getClass().getMethod("method_1507", screen).invoke(client, chatScreen);
            return true;
        } catch (Throwable t) {
            BootstrapLog.error("RUNTIME 1.21.11 open chat", unwrap(t));
            return false;
        }
    }
    @Override
    public boolean restoreGameplayControl(Object client) {
        try {
            if(client==null)return false;
            Object mouse=field(client, "field_1729");
            if(mouse==null)return false;
            mouse.getClass().getMethod("method_1612").invoke(mouse);
            return true;
        } catch (Throwable t) {
            BootstrapLog.error("RUNTIME 1.21.11 restore gameplay mouse", unwrap(t));
            return false;
        }
    }
    @Override
    public boolean keyBindingInControls(Object client, Object key, String bindingId) {
        if(client==null||key==null)return false;
        try {
            Object options=field(client, "field_1690");
            if(options==null)return false;
            Object all=field(options, "field_1839");
            if(all==null)return false;
            if(all.getClass().isArray()) {
                int n=Array.getLength(all);
                for(int i=0; i<n; i++) {
                    Object k=Array.get(all, i);
                    if(k==key||bindingId.equals(keyId(k)))return true;
                }
            } else if(all instanceof Iterable<?> it)for(Object k:it)if(k==key||bindingId.equals(keyId(k)))return true;
        } catch (Throwable ignored) {
        }
        return false;
    }
    @Override
    public RuntimeCapabilities capabilities() {
        try {
            Class.forName("net.minecraft.class_355");
            Class.forName("net.minecraft.class_640");
            Class.forName("net.minecraft.class_408");
            Class.forName("net.minecraft.class_11908");
            Class.forName("net.minecraft.class_11905");
            return new RuntimeCapabilities(true, true, true, true, true);
        } catch (Throwable t) {
            return new RuntimeCapabilities(false, false, false, false, false);
        }
    }
    private static PlayerIdentity identity(Object entry)throws Exception {
        Object gp=entry.getClass().getMethod("method_2966").invoke(entry);
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
        return id==null||name==null?null:new PlayerIdentity(id, name);
    }
    private static Object richestDisplayText(Object listHud, Object entry, PlayerIdentity player) {
        ArrayList<Object> candidates=new ArrayList<>(6);
        Object vanilla=null, direct=null, team=null;
        try {
            if(listHud!=null) {
                Method m=findCompatibleOneArg(listHud.getClass(), "method_1918", entry);
                if(m!=null) {
                    vanilla=m.invoke(listHud, entry);
                    if(vanilla!=null)candidates.add(vanilla);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            direct=entry.getClass().getMethod("method_2971").invoke(entry);
            if(direct!=null)candidates.add(direct);
        } catch (Throwable ignored) {
        }
        try {
            team=entry.getClass().getMethod("method_2955").invoke(entry);
        } catch (Throwable ignored) {
        }
        if(team!=null) {
            try {
                Class<?> text=Class.forName("net.minecraft.class_2561");
                Object literal=text.getMethod("method_43470", String.class).invoke(null, player.name());
                Method decorate=findCompatibleOneArg(team.getClass(), "method_1198", literal);
                if(decorate!=null) {
                    Object teamRaw=decorate.invoke(team, literal);
                    if(teamRaw!=null)candidates.add(teamRaw);
                    // Some servers split TAB decoration: a plugin supplies displayName while the scoreboard
                    // team supplies a different rank/clan prefix or suffix. Vanilla getPlayerName normally
                    // chooses one path, so explicitly compose the two when team decorations are still absent.
                    if(direct!=null&&missingTeamDecoration(team, direct)) {
                        Object combined=decorate.invoke(team, direct);
                        if(combined!=null)candidates.add(combined);
                    }
                    if(vanilla!=null&&vanilla!=direct&&missingTeamDecoration(team, vanilla)) {
                        Object combined=decorate.invoke(team, vanilla);
                        if(combined!=null)candidates.add(combined);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        Object best=null;
        int bestScore=Integer.MIN_VALUE;
        for(Object c:candidates) {
            String plain=textString(c);
            if(plain==null||plain.isBlank())continue;
            int score=decorationScore(plain, player.name(), team);
            if(score>bestScore) {
                bestScore=score;
                best=c;
            }
        }
        return best;
    }
    private static boolean missingTeamDecoration(Object team, Object base) {
        String visible=textString(base);
        if(visible==null)return false;
        String prefix=teamPart(team, "method_1144"), suffix=teamPart(team, "method_1136");
        return (!prefix.isBlank()&&!containsDecoration(visible, prefix))||(!suffix.isBlank()&&!containsDecoration(visible, suffix));
    }
    private static String teamPart(Object team, String method) {
        try {
            Object text=team.getClass().getMethod(method).invoke(team);
            String s=textString(text);
            return s==null?"":s.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }
    private static boolean containsDecoration(String whole, String part) {
        if(part==null||part.isBlank())return true;
        String w=normalizeVisible(whole), p=normalizeVisible(part);
        return !p.isBlank()&&w.contains(p);
    }
    private static String normalizeVisible(String s) {
        return s==null?"":s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
    private static int decorationScore(String visible, String rawName, Object team) {
        if(visible==null)return Integer.MIN_VALUE;
        String v=visible.trim(), n=rawName==null?"":rawName;
        int extra=Math.max(0, v.length()-n.length());
        int contains=n.isBlank()?0:(v.toLowerCase(Locale.ROOT).contains(n.toLowerCase(Locale.ROOT))?1000:0);
        String prefix=team==null?"":teamPart(team, "method_1144"), suffix=team==null?"":teamPart(team, "method_1136");
        int teamScore=0;
        if(!prefix.isBlank()&&containsDecoration(v, prefix))teamScore+=240;
        if(!suffix.isBlank()&&containsDecoration(v, suffix))teamScore+=120;
        return contains+teamScore+extra*8+v.length();
    }
    private static String textString(Object text) {
        if(text==null)return null;
        try {
            Object v=text.getClass().getMethod("method_10851").invoke(text);
            if(v instanceof String s)return s;
        } catch (Throwable ignored) {
        }
        return null;
    }
    private static String keyId(Object key) {
        if(key==null)return "";
        try {
            return String.valueOf(key.getClass().getMethod("method_1431").invoke(key));
        } catch (Throwable ignored) {
        }
        return "";
    }
    private static Method findCompatibleOneArg(Class<?> owner, String name, Object arg) {
        for(Class<?> c=owner; c!=null; c=c.getSuperclass())for(Method m:c.getDeclaredMethods()) {
            if(!m.getName().equals(name)||m.getParameterCount()!=1)continue;
            Class<?> p=m.getParameterTypes()[0];
            if(arg==null||p.isInstance(arg)||p.isAssignableFrom(arg.getClass())) {
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                }
                return m;
            }
        }
        return null;
    }
    private static Object field(Object owner, String name)throws Exception {
        Field f=findField(owner.getClass(), name);
        if(f==null)throw new NoSuchFieldException(name);
        f.setAccessible(true);
        return f.get(owner);
    }
    private static Field findField(Class<?> c, String name) {
        for(Class<?> x=c; x!=null; x=x.getSuperclass())try {
            return x.getDeclaredField(name);
        } catch (NoSuchFieldException ignored) {
        }
        return null;
    }
    private static Throwable unwrap(Throwable t) {
        Throwable x=t;
        while(x!=null&&x.getCause()!=null&&x instanceof InvocationTargetException)x=x.getCause();
        return x==null?t:x;
    }
}
