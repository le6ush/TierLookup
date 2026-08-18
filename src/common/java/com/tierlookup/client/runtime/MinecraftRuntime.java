package com.tierlookup.client.runtime;

import java.lang.reflect.Constructor;
import java.util.*;


/**
* Runtime adapter registry. Adapter implementations are loaded reflectively from version source sets, so the
* common source tree has no compile-time dependency on Minecraft 1.21.11 intermediary classes/implementation.
*/ public final class MinecraftRuntime {
    private static final List<String> ADAPTER_CLASSES=List.of("com.tierlookup.client.runtime.Minecraft12111Adapter");
    private static final List<MinecraftRuntimeAdapter> ADAPTERS=loadAdapters();
    private static final String DETECTED_VERSION=detectMinecraftVersion();
    private static final MinecraftRuntimeAdapter ACTIVE=select();
    private MinecraftRuntime() {
    }
    public static MinecraftRuntimeAdapter active() {
        return ACTIVE;
    }
    public static String detectedVersion() {
        return DETECTED_VERSION;
    }
    public static List<String> supportedVersions() {
        ArrayList<String> out=new ArrayList<>();
        for(MinecraftRuntimeAdapter a:ADAPTERS)out.addAll(a.supportedVersions());
        return List.copyOf(out);
    }
    private static List<MinecraftRuntimeAdapter> loadAdapters() {
        ArrayList<MinecraftRuntimeAdapter> out=new ArrayList<>();
        for(String name:ADAPTER_CLASSES)try {
            Class<?> c=Class.forName(name);
            Constructor<?> ctor=c.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object v=ctor.newInstance();
            if(v instanceof MinecraftRuntimeAdapter a)out.add(a);
        } catch (Throwable t) {
            
        }
        if(out.isEmpty())throw new IllegalStateException("No TierLookup Minecraft runtime adapters packaged");
        return List.copyOf(out);
    }
    private static MinecraftRuntimeAdapter select() {
        String v=DETECTED_VERSION;
        for(MinecraftRuntimeAdapter a:ADAPTERS)if(a.supportsVersion(v)) {
            
            return a;
        }
        MinecraftRuntimeAdapter fallback=ADAPTERS.get(0);
        
        return fallback;
    }
    private static String detectMinecraftVersion() {
        try {
            Class<?> loader=Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object inst=loader.getMethod("getInstance").invoke(null);
            Object opt=loader.getMethod("getModContainer", String.class).invoke(inst, "minecraft");
            if(opt instanceof Optional<?> o&&o.isPresent()) {
                Object container=o.get();
                Object meta=container.getClass().getMethod("getMetadata").invoke(container);
                Object version=meta.getClass().getMethod("getVersion").invoke(meta);
                Object friendly=version.getClass().getMethod("getFriendlyString").invoke(version);
                if(friendly!=null&&!String.valueOf(friendly).isBlank())return String.valueOf(friendly);
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }
}
