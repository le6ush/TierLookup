package com.tierlookup.client;

import java.lang.reflect.*;
import java.util.*;

import com.tierlookup.client.runtime.MinecraftRuntime;
import com.tierlookup.service.TierLookupConfig;

/** Runtime bridge to Minecraft/Fabric key bindings. The user-facing key is registered in Minecraft Controls. */
public final class KeyBindingBridge {
    private static final String OPEN_ID="key.tierlookup.open";
    private static final int DEFAULT_OPEN_KEY=75;
    private static Object openKey;
    private static String status = "not registered";
    private static volatile boolean controlsRegistered=false;
    private KeyBindingBridge() {
    }
    public static String register(TierLookupConfig config) throws Exception {
        
        try {
            Class<?> keyBinding = Class.forName("net.minecraft.class_304");
            Class<?> category = Class.forName("net.minecraft.class_304$class_11900");
            Class<?> inputType = Class.forName("net.minecraft.class_3675$class_307");
            Object categoryValue=createTierLookupCategory(category);
            Object keysym=inputType.getField("field_1668").get(null);
            // InputUtil.Type.KEYSYM, never SCANCODE
            Constructor<?> ctor = keyBinding.getConstructor(String.class, inputType, int.class, category);
            openKey = ctor.newInstance(OPEN_ID, keysym, DEFAULT_OPEN_KEY, categoryValue);
            String fabricPath=registerWithFabric(keyBinding, openKey);
            // Fabric owns GameOptions.allKeys registration. Do not mutate the array ourselves: manual insertion can
            // desynchronize the Controls screen's capture state on 1.21.11.
            controlsRegistered=controlsContains(openKey);
            if(!controlsRegistered)throw new IllegalStateException("key binding not present in GameOptions.allKeys after Fabric registration; registry="+keyBindingRegistryContains());
            status = "OK controls="+fabricPath+" type=KEYSYM";
            
            
            return status;
        } catch (Throwable t) {
            controlsRegistered=false;
            status = "ERROR " + root(t).getClass().getSimpleName()+": "+String.valueOf(root(t).getMessage());
            BootstrapLog.error("KeyBindingBridge.register", root(t));
            
            if (t instanceof Exception e) throw e;
            throw new RuntimeException(t);
        }
    }
    private static Object createTierLookupCategory(Class<?> category)throws Exception {
        try {
            Class<?> identifier=Class.forName("net.minecraft.class_2960");
            Object id=identifier.getMethod("method_60654", String.class).invoke(null, "tierlookup:controls");
            return category.getMethod("method_74698", identifier).invoke(null, id);
        } catch (Throwable custom) {
            
            return category.getField("field_62556").get(null);
        }
    }
    /** Register through whichever public Fabric helper method exists in the installed Fabric API. */
    private static String registerWithFabric(Class<?> keyBinding, Object key)throws Exception {
        Class<?> helper=Class.forName("net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper");
        for(String name:List.of("registerKeyBinding", "registerKeyMapping")) {
            try {
                Method m=helper.getMethod(name, keyBinding);
                Object registered=m.invoke(null, key);
                if(registered!=null)openKey=registered;
                return name;
            } catch (NoSuchMethodException ignored) {
            }
        }
        for(Method m:helper.getMethods()) {
            if(!Modifier.isStatic(m.getModifiers())||m.getParameterCount()!=1)continue;
            if(!m.getParameterTypes()[0].isAssignableFrom(keyBinding))continue;
            String n=m.getName().toLowerCase(Locale.ROOT);
            if(!n.contains("register"))continue;
            Object registered=m.invoke(null, key);
            if(registered!=null)openKey=registered;
            return m.getName();
        }
        throw new NoSuchMethodException("Fabric KeyBindingHelper registration method not found");
    }
    /** GameOptions.allKeys is what vanilla Controls enumerates. Reflection accepts public/private mapping drift. */
    private static boolean controlsContains(Object key) {
        if(key==null)return false;
        try {
            Object client=Class.forName("net.minecraft.class_310").getMethod("method_1551").invoke(null);
            if(client!=null&&MinecraftRuntime.active().keyBindingInControls(client, key, OPEN_ID))return true;
        } catch (Throwable ignored) {
        }
        try {
            Object client=Class.forName("net.minecraft.class_310").getMethod("method_1551").invoke(null);
            if(client==null)return false;
            Object options=readField(client, "field_1690");
            if(options==null)return false;
            Object arr=readField(options, "field_1839");
            if(arr!=null&&arr.getClass().isArray()) {
                int n=Array.getLength(arr);
                for(int i=0; i<n; i++) {
                    Object k=Array.get(arr, i);
                    if(k==key||sameId(k, OPEN_ID))return true;
                }
            }
            if(arr instanceof Iterable<?> it)for(Object k:it)if(k==key||sameId(k, OPEN_ID))return true;
            return false;
        } catch (Throwable t) {
            BootstrapLog.error("KEYBIND controls verification", root(t));
            return false;
        }
    }
    /** Mapping-independent secondary verification: registered KeyBinding ids live in one of its static maps. */
    private static boolean keyBindingRegistryContains() {
        try {
            Class<?> kb=Class.forName("net.minecraft.class_304");
            for(Field f:kb.getDeclaredFields()) {
                if(!Modifier.isStatic(f.getModifiers())||!Map.class.isAssignableFrom(f.getType()))continue;
                f.setAccessible(true);
                Object raw=f.get(null);
                if(!(raw instanceof Map<?, ?> m))continue;
                if(m.containsKey(OPEN_ID))return true;
                for(Object v:m.values())if(sameId(v, OPEN_ID))return true;
            }
        } catch (Throwable t) {
            BootstrapLog.error("KEYBIND registry verification", root(t));
        }
        return false;
    }
    private static Object readField(Object owner, String name)throws Exception {
        Field f=findField(owner.getClass(), name);
        if(f==null)throw new NoSuchFieldException(name);
        f.setAccessible(true);
        return f.get(owner);
    }
    private static Field findField(Class<?> type, String name) {
        for(Class<?> c=type; c!=null; c=c.getSuperclass())try {
            return c.getDeclaredField(name);
        } catch (NoSuchFieldException ignored) {
        }
        return null;
    }
    private static boolean sameId(Object key, String expected) {
        if(key==null)return false;
        try {
            Object v=key.getClass().getMethod("method_1431").invoke(key);
            return expected.equals(String.valueOf(v));
        } catch (Throwable ignored) {
        }
        try {
            Field f=key.getClass().getDeclaredField("field_1660");
            f.setAccessible(true);
            return expected.equals(String.valueOf(f.get(key)));
        } catch (Throwable ignored) {
        }
        return false;
    }
    public static String register() throws Exception {
        return register(new TierLookupConfig(MinecraftBridge.configDir().resolve("tierlookup.json"), java.util.List.of()));
    }
    public static boolean consumeOpen() {
        return consume("open", openKey);
    }
    public static boolean openPressed() {
        return pressed(openKey);
    }
    public static int openKeyCode() {
        return keyCode(openKey, DEFAULT_OPEN_KEY);
    }
    public static boolean ready() {
        return openKey != null;
    }
    public static boolean controlsRegistered() {
        boolean verified=controlsContains(openKey);
        if(verified)controlsRegistered=true;
        return openKey!=null&&controlsRegistered&&verified;
    }
    /** Controls may legitimately rebind to KEYSYM or MOUSE; SCANCODE is not used by TierLookup. */
    public static boolean bindingIsNotScancode() {
        if(openKey==null)return false;
        try {
            Field f=openKey.getClass().getDeclaredField("field_1655");
            f.setAccessible(true);
            Object bound=f.get(openKey);
            if(bound==null)return false;
            Object type=bound.getClass().getMethod("method_1442").invoke(bound);
            Class<?> inputType=Class.forName("net.minecraft.class_3675$class_307");
            Object scancode=inputType.getField("field_1671").get(null);
            return type!=scancode;
        } catch (Throwable t) {
            MinecraftBridge.logOnce("key.type", t);
            return false;
        }
    }
    public static String status() {
        return status;
    }
    public static String keyName(int code) {
        if(code<0)return "Unbound";
        if(code>=65&&code<=90)return String.valueOf((char)code);
        if(code>=48&&code<=57)return String.valueOf((char)code);
        if(code>=290&&code<=314)return "F"+(code-289);
        return switch(code) {
            case 32->"Space";
            case 39->"'";
            case 44->",";
            case 45->"-";
            case 46->".";
            case 47->"/";
            case 59->";";
            case 61->"=";
            case 91->"[";
            case 92->"\\";
            case 93->"]";
            case 256->"Esc";
            case 257->"Enter";
            case 258->"Tab";
            case 259->"Backspace";
            case 260->"Insert";
            case 261->"Delete";
            case 262->"Right";
            case 263->"Left";
            case 264->"Down";
            case 265->"Up";
            case 266->"Page Up";
            case 267->"Page Down";
            case 268->"Home";
            case 269->"End";
            case 280->"Caps Lock";
            case 281->"Scroll Lock";
            case 282->"Num Lock";
            case 340->"Left Shift";
            case 341->"Left Ctrl";
            case 342->"Left Alt";
            case 344->"Right Shift";
            case 345->"Right Ctrl";
            case 346->"Right Alt";
            default->"Key "+code;
        };
    }
    private static boolean consume(String label, Object key) {
        if (key == null) return false;
        try {
            return (boolean) key.getClass().getMethod("method_1436").invoke(key);
        } catch (Throwable t) {
            MinecraftBridge.logOnce("key.consume."+label, t);
            return false;
        }
    }
    private static int keyCode(Object key, int fallback) {
        if(key==null)return fallback;
        try {
            Field f=key.getClass().getDeclaredField("field_1655");
            f.setAccessible(true);
            Object k=f.get(key);
            Object v=k.getClass().getMethod("method_1444").invoke(k);
            return v instanceof Number n?n.intValue():fallback;
        } catch (Throwable t) {
            MinecraftBridge.logOnce("key.code", t);
            return fallback;
        }
    }
    private static boolean pressed(Object key) {
        if (key == null) return false;
        try {
            return (boolean) key.getClass().getMethod("method_1434").invoke(key);
        } catch (Throwable t) {
            MinecraftBridge.logOnce("key.pressed", t);
            return false;
        }
    }
    private static Throwable root(Throwable t) {
        while(t instanceof InvocationTargetException i&&i.getCause()!=null)t=i.getCause();
        return t;
    }
}
