package com.tierlookup.client;

import java.lang.reflect.*;
import java.util.function.Function;

import com.tierlookup.TierLookupClient;
import com.tierlookup.client.runtime.MinecraftRuntime;

/** Uses the modern Fabric HUD API, falling back to the deprecated callback if necessary. */
public final class HudBridge {
    private static final TabOverlayRenderer TAB2=new TabOverlayRenderer();
    private static volatile boolean tabReplacementInstalled;
    private static volatile String tabReplacementStatus="not attempted";
    private static volatile boolean replacementRenderFailureLogged;
    private HudBridge() {
    }
    public static String register(OverlayRenderer overlay) throws Exception {
        
        try {
            registerModern(overlay);
            
            
            return tabReplacementInstalled?"HudElementRegistry+PlayerListReplace":"HudElementRegistry";
        } catch (Throwable modern) {
            BootstrapLog.error("HudBridge modern API", modern);
            registerLegacy(overlay);
            
            
            return "HudRenderCallback(fallback)";
        }
    }
    private static void registerModern(OverlayRenderer overlay) throws Exception {
        Class<?> registry = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry");
        
        Class<?> element = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement");
        Class<?> identifier = Class.forName("net.minecraft.class_2960");
        Object id = identifier.getMethod("method_60655", String.class, String.class).invoke(null, "tierlookup", "overlay");
        Object proxy = Proxy.newProxyInstance(element.getClassLoader(), new Class<?>[] {
            element
        }, (p, m, a) -> {
            if (ProxySupport.isObjectMethod(m)) return ProxySupport.invokeObjectMethod(p, m, a, "TierLookupHudElement");
            if (m.getName().equals("render") && a != null && a.length > 0 && !SearchCaptureScreen.isOpen() && !TabInteractionScreen.isOpen()) {
                Object client=MinecraftBridge.client(); var cfg=TierLookupClient.configInstance(); if(cfg==null||!cfg.masterEnabled())return null; overlay.render(a[0], client);
            }
            return null;
        }
        );
        Method addLast=registry.getMethod("addLast", identifier, element);
        
        addLast.invoke(null, id, proxy);
        // Prefer replacing the vanilla player-list element. This gives TAB 2.0 ownership of the actual TAB layer
        // without a Minecraft/Sponge Mixin. If Fabric changes this API, keep the normal HUD and use an addLast fallback.
        if(!installPlayerListReplacement(registry, element)) {
            Object tabId = identifier.getMethod("method_60655", String.class, String.class).invoke(null, "tierlookup", "tab2_fallback");
            Object tabProxy = Proxy.newProxyInstance(element.getClassLoader(), new Class<?>[] {
                element
            }, (p, m, a) -> {
                if (ProxySupport.isObjectMethod(m)) return ProxySupport.invokeObjectMethod(p, m, a, "TierLookupTab2Fallback");
                if (m.getName().equals("render") && a != null && a.length > 0 && !SearchCaptureScreen.isOpen() && !TabInteractionScreen.isOpen()) {
                    var cfg=TierLookupClient.configInstance(); if(cfg!=null&&cfg.masterEnabled())TAB2.render(a[0], MinecraftBridge.client());
                }
                return null;
            }
            );
            addLast.invoke(null, tabId, tabProxy);
            
        }
    }
    @SuppressWarnings( {
        "unchecked", "rawtypes"
    }
    ) private static boolean installPlayerListReplacement(Class<?> registry, Class<?> element) {
        try {
            Class<?> vanilla=Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements");
            Field playerList=vanilla.getField("PLAYER_LIST");
            Object playerListId=playerList.get(null);
            Method replace=null;
            for(Method m:registry.getMethods()) {
                if(Modifier.isStatic(m.getModifiers())&&m.getName().equals("replaceElement")&&m.getParameterCount()==2&&Function.class.isAssignableFrom(m.getParameterTypes()[1])) {
                    replace=m;
                    break;
                }
            }
            if(replace==null)throw new NoSuchMethodException("HudElementRegistry.replaceElement(Identifier, Function)");
            Function replacer=(Object oldElement)->Proxy.newProxyInstance(element.getClassLoader(), new Class<?>[] {
                element
            },(p, m, a)-> {
                if(ProxySupport.isObjectMethod(m))return ProxySupport.invokeObjectMethod(p, m, a, "TierLookupPlayerListElement");
                if(!m.getName().equals("render")||a==null||a.length==0)return invokeOriginal(oldElement, m, a);
                try {
                    Object client=MinecraftBridge.client();
                    if(client==null)return invokeOriginal(oldElement, m, a);
                    if(TabInteractionScreen.isOpen())return null;
                    if(SearchCaptureScreen.isOpen())return invokeOriginal(oldElement, m, a);
                    var cfg=TierLookupClient.configInstance();
                    boolean custom=ClientFeatures.ENHANCED_TAB_AVAILABLE&&cfg!=null&&cfg.masterEnabled()&&cfg.customTabEnabled();
                    if(!custom)return invokeOriginal(oldElement, m, a);
                    // Once enabled, TAB2 owns this vanilla layer. If TAB is held but custom drawing fails/has no model,
                    // fall back to the old vanilla renderer for that frame. If TAB is not held, neither should draw.
                    if(!MinecraftRuntime.active().playerListPressed(client))return null; if(!TAB2.render(a[0], client))return invokeOriginal(oldElement, m, a); return null;
                } catch (Throwable t) {
                    if(!replacementRenderFailureLogged) {
                        replacementRenderFailureLogged=true; BootstrapLog.error("TAB2 replacement render", unwrap(t));
                    }
                    return invokeOriginal(oldElement, m, a);
                }
            }
            );
            replace.invoke(null, playerListId, replacer);
            tabReplacementInstalled=true;
            tabReplacementStatus="installed";
            return true;
        } catch (Throwable t) {
            tabReplacementInstalled=false;
            tabReplacementStatus=unwrap(t).getClass().getSimpleName()+": "+String.valueOf(unwrap(t).getMessage());
            
            return false;
        }
    }
    private static Object invokeOriginal(Object oldElement, Method m, Object[] args)throws Throwable {
        if(oldElement==null)return null;
        try {
            return m.invoke(oldElement, args==null?new Object[0]:args);
        } catch (InvocationTargetException e) {
            throw e.getCause()==null?e:e.getCause();
        }
    }
    public static boolean tabReplacementInstalled() {
        return tabReplacementInstalled;
    }
    public static String tabReplacementStatus() {
        return tabReplacementStatus;
    }
    public static boolean tabReplacementApiAvailable() {
        try {
            Class<?> registry=Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry");
            Class<?> vanilla=Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements");
            vanilla.getField("PLAYER_LIST").get(null);
            for(Method m:registry.getMethods())if(Modifier.isStatic(m.getModifiers())&&m.getName().equals("replaceElement")&&m.getParameterCount()==2&&Function.class.isAssignableFrom(m.getParameterTypes()[1]))return true;
        } catch (Throwable ignored) {
        }
        return false;
    }
    private static void registerLegacy(OverlayRenderer overlay) throws Exception {
        Class<?> cb = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback");
        
        Object event = cb.getField("EVENT").get(null);
        Object proxy = Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[] {
            cb
        }, (p, m, a) -> {
            if (ProxySupport.isObjectMethod(m)) return ProxySupport.invokeObjectMethod(p, m, a, "TierLookupHudRenderCallback");
            if (m.getName().equals("onHudRender") && a != null && a.length > 0 && !SearchCaptureScreen.isOpen() && !TabInteractionScreen.isOpen()) {
                Object client=MinecraftBridge.client(); var cfg=TierLookupClient.configInstance(); if(cfg!=null&&cfg.masterEnabled()) {
                    overlay.render(a[0], client); TAB2.render(a[0], client);
                }
            }
            return null;
        }
        );
        EventBridge.register(event, proxy);
    }
    public static boolean beginTabInteraction(Object client) {
        return TAB2.beginInteraction(client);
    }
    public static void endTabInteraction() {
        TAB2.endInteraction();
    }
    public static boolean tabInteractionFrozen() {
        return TAB2.interactionFrozen();
    }
    public static int frozenTabRowCount() {
        return TAB2.frozenRowCount();
    }
    public static boolean renderFrozenTab(Object ctx, Object client, int mouseX, int mouseY) {
        return TAB2.renderFrozen(ctx, client, mouseX, mouseY);
    }
    public static com.tierlookup.model.PlayerIdentity frozenPlayerAt(double x, double y) {
        return TAB2.playerAt(x, y);
    }
    public static com.tierlookup.model.PlayerIdentity frozenNoteAt(double x, double y) {
        return TAB2.notePlayerAt(x, y);
    }
    public static String tabRenderStatus() {
        return TAB2.lastRenderStatus();
    }
    public static void cycleFrozenTabKit(int direction) {
        TAB2.cycleFrozenKit(direction);
    }
    public static String frozenTabKit() {
        return TAB2.frozenKit();
    }
    public static void lockFrozenPreview(com.tierlookup.model.PlayerIdentity p) {
        TAB2.lockPreview(p);
    }
    public static void unlockFrozenPreview() {
        TAB2.unlockPreview();
    }
    public static OverlayRenderer.TabPreviewBounds frozenPreviewBounds() {
        return TAB2.previewBounds();
    }
    private static Throwable unwrap(Throwable t) {
        Throwable x=t;
        while(x instanceof InvocationTargetException&&x.getCause()!=null)x=x.getCause();
        return x==null?t:x;
    }
}
