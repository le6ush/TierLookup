package com.tierlookup.client;

import java.lang.reflect.*;
import java.util.*;

import com.tierlookup.TierLookupClient;
import com.tierlookup.client.runtime.MinecraftRuntime;
import com.tierlookup.client.runtime.MinecraftRuntimeAdapter;
import com.tierlookup.model.PlayerIdentity;
import com.tierlookup.model.PlayerProfile;
import com.tierlookup.service.ProfileService;
import com.tierlookup.service.TierLookupConfig;

/**
* Mixin-free tier-sorted custom TAB renderer.
*
* Custom TAB keeps rows compact and grows sideways before it grows downward. The custom TAB
* supports left/center/right anchoring; frozen preview stays separate from the TAB itself.
*/ public final class TabOverlayRenderer {
    public record Row(MinecraftRuntimeAdapter.TabEntry entry, TierSelection.Best best, int score) {
    }
    public record HitRow(int x, int y, int w, int h, PlayerIdentity player) {
        boolean contains(double mx, double my) {
            return mx>=x&&my>=y&&mx<x+w&&my<y+h;
        }
    }
    private record Frozen(List<MinecraftRuntimeAdapter.TabEntry> entries, Map<UUID, PlayerProfile> profiles, String mode) {
    }
    private static final int LOGICAL_ROW_H=15, TARGET_ROWS=12;
    private static volatile Method fillMethod, stringTextMethod, stringWidthMethod;
    private static volatile Method matricesMethod, matrixPushMethod, matrixPopMethod, matrixTranslateMethod, matrixScaleMethod;
    private static volatile Class<?> matrixClass;
    private static volatile Field textRendererField;
    private static volatile boolean loggedFailure;
    private volatile Frozen frozen;
    private volatile List<HitRow> interactiveHits=List.of();
    private volatile String frozenKit="max";
    private volatile PlayerIdentity stickyPreviewPlayer;
    private volatile OverlayRenderer.TabPreviewBounds stickyPreviewBounds;
    private volatile boolean previewLocked;
    private volatile String lastRenderStatus="never";
    public boolean render(Object ctx, Object client) {
        TierLookupConfig cfg=TierLookupClient.configInstance();
        ProfileService service=TierLookupClient.profileServiceInstance();
        if(ctx==null||client==null||cfg==null||service==null||!ClientFeatures.ENHANCED_TAB_AVAILABLE||!cfg.customTabEnabled())return false;
        if(!MinecraftBridge.hasWorld(client)||!MinecraftRuntime.active().playerListPressed(client))return false;
        List<Row> rows=model(MinecraftRuntime.active().tabEntries(client), service, cfg, cfg.tabMode(), cfg.tabDisplayedKit());
        try {
            boolean ok=renderRows(ctx, client, rows, cfg.tabMode(), false, -1, -1, cfg.tabDisplayedKit());
            if(ok)lastRenderStatus="scaled";
            return ok;
        } catch (Throwable t) {
            logFailure("TAB custom render", t);
            try {
                boolean ok=renderRowsFallback(ctx, client, rows, cfg.tabMode(), false, -1, -1, cfg.tabDisplayedKit());
                if(ok)lastRenderStatus="fallback:"+unwrap(t).getClass().getSimpleName();
                return ok;
            } catch (Throwable fallback) {
                lastRenderStatus="failed:"+unwrap(fallback).getClass().getSimpleName();
                return false;
            }
        }
    }
    /** Freeze roster and current local tier snapshots before the cursor is released. */
    public synchronized boolean beginInteraction(Object client) {
        TierLookupConfig cfg=TierLookupClient.configInstance();
        ProfileService service=TierLookupClient.profileServiceInstance();
        if(client==null||cfg==null||service==null||!cfg.customTabEnabled()||!MinecraftBridge.hasWorld(client))return false;
        try {
            List<MinecraftRuntimeAdapter.TabEntry> entries=MinecraftRuntime.active().tabEntries(client);
            if(entries.isEmpty())return false;
            LinkedHashMap<UUID, PlayerProfile> profiles=new LinkedHashMap<>();
            for(var e:entries) {
                PlayerProfile p=service.profileForDisplay(e.player().uuid(), e.player().name());
                if(p!=null)profiles.put(e.player().uuid(), p);
            }
            frozen=new Frozen(List.copyOf(entries), Map.copyOf(profiles), cfg.tabMode());
            frozenKit=cfg.tabDisplayedKit();
            interactiveHits=List.of();
            stickyPreviewPlayer=null;
            stickyPreviewBounds=null;
            previewLocked=false;
            
            return true;
        } catch (Throwable t) {
            logFailure("TAB interaction freeze", t);
            return false;
        }
    }
    public synchronized void endInteraction() {
        frozen=null;
        interactiveHits=List.of();
        frozenKit="max";
        stickyPreviewPlayer=null;
        stickyPreviewBounds=null;
        previewLocked=false;
    }
    public boolean interactionFrozen() {
        return frozen!=null;
    }
    public int frozenRowCount() {
        Frozen f=frozen;
        return f==null?0:f.entries().size();
    }
    public String frozenKit() {
        return frozenKit;
    }
    /** Temporary filter only for the frozen TAB; it never changes the persistent TAB-kit setting. */
    public synchronized void cycleFrozenKit(int direction) {
        Frozen f=frozen;
        TierLookupConfig cfg=TierLookupClient.configInstance();
        if(f==null||cfg==null||direction==0)return;
        ArrayList<String> kits=new ArrayList<>();
        kits.add("max");
        for(String k:TierLookupConfig.KNOWN_KITS)if(cfg.kitEnabled(k))kits.add(k);
        if(kits.size()<2)return;
        int at=kits.indexOf(frozenKit);
        if(at<0)at=0;
        at=Math.floorMod(at+(direction>0?1:-1), kits.size());
        frozenKit=kits.get(at);
    }
    public boolean renderFrozen(Object ctx, Object client, int mouseX, int mouseY) {
        Frozen f=frozen;
        TierLookupConfig cfg=TierLookupClient.configInstance();
        if(f==null||ctx==null||client==null||cfg==null)return false;
        List<Row> rows=modelFrozen(f, cfg, frozenKit);
        try {
            boolean ok=renderRows(ctx, client, rows, f.mode(), true, mouseX, mouseY, frozenKit);
            if(ok)lastRenderStatus="frozen-scaled";
            return ok;
        } catch (Throwable t) {
            logFailure("TAB frozen render", t);
            try {
                boolean ok=renderRowsFallback(ctx, client, rows, f.mode(), true, mouseX, mouseY, frozenKit);
                if(ok)lastRenderStatus="frozen-fallback:"+unwrap(t).getClass().getSimpleName();
                return ok;
            } catch (Throwable fallback) {
                lastRenderStatus="frozen-failed:"+unwrap(fallback).getClass().getSimpleName();
                return false;
            }
        }
    }
    public PlayerIdentity playerAt(double mouseX, double mouseY) {
        List<HitRow> hits=interactiveHits;
        for(int i=hits.size()-1; i>=0; i--) {
            HitRow h=hits.get(i);
            if(h.contains(mouseX, mouseY))return h.player();
        }
        return null;
    }
    public synchronized void lockPreview(PlayerIdentity p) {
        previewLocked=p!=null;
        stickyPreviewPlayer=p;
        if(p==null)stickyPreviewBounds=null;
    }
    public synchronized void unlockPreview() {
        previewLocked=false;
    }
    public OverlayRenderer.TabPreviewBounds previewBounds() {
        return stickyPreviewBounds;
    }
    public PlayerIdentity notePlayerAt(double mouseX, double mouseY) {
        return null;
    }
    public String lastRenderStatus() {
        return lastRenderStatus;
    }
    private boolean renderRows(Object ctx, Object client, List<Row> rows, String mode, boolean interactive, int mouseX, int mouseY, String displayedKit)throws Exception {
        if(rows==null||rows.isEmpty())return false;
        Object tr=textRenderer(client);
        if(tr==null)return false;
        Method fill=fill(ctx), stringText=stringText(ctx, tr), stringWidth=stringWidth(tr);
        TierLookupConfig cfg=TierLookupClient.configInstance();
        float scale=cfg==null?0.90f:cfg.tabScale();
        int sw=MinecraftBridge.scaledWidth(client);
        int colW=columnWidth(rows, tr, stringWidth, mode);
        int rowsPerCol=Math.min(TARGET_ROWS, Math.max(1, rows.size()));
        int cols=Math.max(1, (rows.size()+rowsPerCol-1)/rowsPerCol), logicalW=cols*colW+6, logicalH=6+Math.min(rowsPerCol, rows.size())*LOGICAL_ROW_H;
        int physicalW=Math.min(sw-12, Math.round(logicalW*scale));
        int x0=tabX(cfg==null?"left":cfg.tabPosition(), sw, physicalW), y0=4;
        ArrayList<HitRow> hits=interactive?new ArrayList<>(rows.size()):null;
        PlayerIdentity hovered=null;
        Object matrices=pushScaled(ctx, x0, y0, scale);
        try {
            fill.invoke(ctx, 0, 0, logicalW, logicalH, 0xE20E1419);
            fill.invoke(ctx, 0, 0, logicalW, 2, 0xFF62D8E8);
            for(int i=0; i<rows.size(); i++) {
                int col=i/rowsPerCol, row=i%rowsPerCol;
                if(col>=cols)break;
                int rx=3+col*colW, ry=3+row*LOGICAL_ROW_H;
                Row r=rows.get(i);
                TierSelection.Best b=r.best();
                int phx=x0+Math.round(rx*scale), phy=y0+Math.round(ry*scale), phw=Math.max(1, Math.round((colW-2)*scale)), phh=Math.max(1, Math.round(LOGICAL_ROW_H*scale));
                boolean over=interactive&&mouseX>=phx&&mouseY>=phy&&mouseX<phx+phw&&mouseY<phy+phh;
                if(over) {
                    fill.invoke(ctx, rx, ry, rx+colW-2, ry+LOGICAL_ROW_H, 0x332A3941);
                    hovered=r.entry().player();
                }
                if(b!=null) {
                    drawSmallIcon(ctx, b.kit(), rx, ry-1);
                    String rank=(b.retired()?"R":"")+b.tier();
                    stringText.invoke(ctx, tr, rank, rx+15, ry+3, 0xFFB8F3FF);
                } else stringText.invoke(ctx, tr, "-", rx+16, ry+3, 0xFF89949D);
                int nameX=rx+39, available=Math.max(12, colW-(nameX-rx)-4);
                drawCompactName(ctx, tr, r.entry(), nameX, ry+3, available, stringText, stringWidth);
                if(interactive)hits.add(new HitRow(phx, phy, phw, phh, r.entry().player()));
            }
        } finally {
            popScaled(matrices);
        }
        if(interactive) {
            interactiveHits=List.copyOf(hits);
            if(!previewLocked) {
                if(hovered!=null) {
                    stickyPreviewPlayer=hovered;
                    stickyPreviewBounds=null;
                } else if(stickyPreviewBounds==null||!stickyPreviewBounds.contains(mouseX, mouseY)) {
                    stickyPreviewPlayer=null;
                    stickyPreviewBounds=null;
                }
            }
            PlayerIdentity preview=stickyPreviewPlayer;
            if(preview!=null) {
                ProfileService service=TierLookupClient.profileServiceInstance();
                PlayerProfile p=service==null?null:service.profileForDisplay(preview.uuid(), preview.name());
                OverlayRenderer overlay=TierLookupClient.overlayInstance();
                if(p!=null&&overlay!=null) {
                    int px=previewX(cfg==null?"left":cfg.tabPosition(), sw, x0, physicalW);
                    stickyPreviewBounds=overlay.renderTabPreview(ctx, client, p, px, 6);
                }
            }
        }
        return true;
    }
    private static int tabX(String position, int screenW, int physicalW) {
        return switch(position) {
            case "center"->Math.max(6, (screenW-physicalW)/2);
            case "right"->Math.max(6, screenW-physicalW-6);
            default->6;
        };
    }
    private static int previewX(String position, int screenW, int tabX, int tabW) {
        if("right".equals(position))return 6;
        if("center".equals(position)) {
            int right=tabX+tabW+8;
            if(right+220<screenW)return right;
            return 6;
        }
        return Math.min(screenW-220, tabX+tabW+8);
    }
    private static void drawSmallIcon(Object ctx, String kit, int x, int y)throws Exception {
        Object m=pushLocal(ctx, x+2, y+2, 0.80f);
        try {
            KitIconRenderer.draw(ctx, kit, 0, 0);
        } finally {
            popScaled(m);
        }
    }
    private static Object pushLocal(Object ctx, float x, float y, float scale)throws Exception {
        Method gm=matricesMethod;
        if(gm==null||!gm.getDeclaringClass().isAssignableFrom(ctx.getClass()))matricesMethod=gm=ctx.getClass().getMethod("method_51448");
        Object matrices=gm.invoke(ctx);
        Class<?> mc=matrices.getClass();
        if(matrixClass!=mc) {
            matrixClass=mc;
            matrixPushMethod=mc.getMethod("pushMatrix");
            matrixPopMethod=mc.getMethod("popMatrix");
            matrixTranslateMethod=mc.getMethod("translate", float.class, float.class);
            matrixScaleMethod=mc.getMethod("scale", float.class, float.class);
        }
        matrixPushMethod.invoke(matrices);
        matrixTranslateMethod.invoke(matrices, x, y);
        matrixScaleMethod.invoke(matrices, scale, scale);
        return matrices;
    }
    /** Every custom renderer is tier-sorted; equal tiers keep the original server/vanilla order. */
    public static List<Row> model(List<MinecraftRuntimeAdapter.TabEntry> entries, ProfileService service, TierLookupConfig cfg, String mode, String kit) {
        if(entries==null||entries.isEmpty())return List.of();
        ArrayList<Row> out=new ArrayList<>(entries.size());
        for(MinecraftRuntimeAdapter.TabEntry e:entries) {
            if(e==null||e.player()==null)continue;
            PlayerProfile p=service==null?null:service.profileForDisplay(e.player().uuid(), e.player().name());
            TierSelection.Best b=TierSelection.displayed(p, cfg, kit);
            out.add(new Row(e, b, b==null?-1:TierSelection.tierScore(b.tier())));
        }
        sortStable(out);
        return List.copyOf(out);
    }
    public static List<Row> model(List<MinecraftRuntimeAdapter.TabEntry> entries, ProfileService service, TierLookupConfig cfg, String mode) {
        return model(entries, service, cfg, mode, cfg==null?"max":cfg.tabDisplayedKit());
    }
    public static List<Row> model(List<MinecraftRuntimeAdapter.TabEntry> entries, ProfileService service, TierLookupConfig cfg) {
        return model(entries, service, cfg, cfg==null?"custom":cfg.tabMode());
    }
    private static List<Row> modelFrozen(Frozen f, TierLookupConfig cfg, String kit) {
        ArrayList<Row> out=new ArrayList<>();
        for(var e:f.entries()) {
            PlayerProfile p=f.profiles().get(e.player().uuid());
            TierSelection.Best b=TierSelection.displayed(p, cfg, kit);
            out.add(new Row(e, b, b==null?-1:TierSelection.tierScore(b.tier())));
        }
        sortStable(out);
        return List.copyOf(out);
    }
    private static void sortStable(List<Row> out) {
        out.sort((a, b)-> {
            int c=Integer.compare(b.score(), a.score()); if(c!=0)return c; return Integer.compare(a.entry().vanillaIndex(), b.entry().vanillaIndex());
        }
        );
    }
    private boolean renderRowsFallback(Object ctx, Object client, List<Row> rows, String mode, boolean interactive, int mouseX, int mouseY, String displayedKit)throws Exception {
        if(rows==null||rows.isEmpty())return false;
        Object tr=textRenderer(client);
        if(tr==null)return false;
        Method fill=fill(ctx), text=stringText(ctx, tr), width=stringWidth(tr);
        TierLookupConfig cfg=TierLookupClient.configInstance();
        int sw=MinecraftBridge.scaledWidth(client);
        int rowH=11, rowsPerCol=Math.min(TARGET_ROWS, Math.max(1, rows.size())), colW=Math.min(150, columnWidth(rows, tr, width, mode));
        int cols=Math.max(1, (rows.size()+rowsPerCol-1)/rowsPerCol);
        int totalW=Math.min(sw-12, cols*colW+6), x0=tabX(cfg==null?"left":cfg.tabPosition(), sw, totalW), y0=4, h=6+Math.min(rowsPerCol, rows.size())*rowH;
        fill.invoke(ctx, x0, y0, x0+totalW, y0+h, 0xE20E1419);
        fill.invoke(ctx, x0, y0, x0+totalW, y0+2, 0xFF62D8E8);
        ArrayList<HitRow> hits=interactive?new ArrayList<>(rows.size()):null;
        PlayerIdentity hovered=null;
        for(int i=0; i<rows.size(); i++) {
            int col=i/rowsPerCol, row=i%rowsPerCol, rx=x0+3+col*colW, ry=y0+3+row*rowH;
            Row r=rows.get(i);
            if(interactive&&mouseX>=rx&&mouseY>=ry&&mouseX<rx+colW-2&&mouseY<ry+rowH) {
                fill.invoke(ctx, rx, ry, rx+colW-2, ry+rowH, 0x332A3941);
                hovered=r.entry().player();
            }
            TierSelection.Best b=r.best();
            String rank=b==null?"-":(b.retired()?"R":"")+b.tier();
            text.invoke(ctx, tr, rank, rx+2, ry+1, b==null?0xFF89949D:0xFFB8F3FF);
            drawCompactName(ctx, tr, r.entry(), rx+31, ry+1, Math.max(12, colW-35), text, width);
            if(interactive)hits.add(new HitRow(rx, ry, colW-2, rowH, r.entry().player()));
        }
        if(interactive) {
            interactiveHits=List.copyOf(hits);
            if(!previewLocked) {
                if(hovered!=null) {
                    stickyPreviewPlayer=hovered;
                    stickyPreviewBounds=null;
                } else if(stickyPreviewBounds==null||!stickyPreviewBounds.contains(mouseX, mouseY)) {
                    stickyPreviewPlayer=null;
                    stickyPreviewBounds=null;
                }
            }
            PlayerIdentity preview=stickyPreviewPlayer;
            if(preview!=null) {
                ProfileService svc=TierLookupClient.profileServiceInstance();
                PlayerProfile p=svc==null?null:svc.profileForDisplay(preview.uuid(), preview.name());
                OverlayRenderer overlay=TierLookupClient.overlayInstance();
                if(p!=null&&overlay!=null)stickyPreviewBounds=overlay.renderTabPreview(ctx, client, p, previewX(cfg==null?"left":cfg.tabPosition(), sw, x0, totalW), 6);
            }
        }
        return true;
    }
    public static boolean matrixPipelineAvailable() {
        try {
            Class<?> draw=Class.forName("net.minecraft.class_332"), stack=Class.forName("org.joml.Matrix3x2fStack");
            Method get=draw.getMethod("method_51448");
            if(!stack.isAssignableFrom(get.getReturnType()))return false;
            stack.getMethod("pushMatrix");
            stack.getMethod("popMatrix");
            stack.getMethod("translate", float.class, float.class);
            stack.getMethod("scale", float.class, float.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
    private static int columnWidth(List<Row> rows, Object tr, Method stringWidth, String mode)throws Exception {
        int max=112;
        for(Row r:rows) {
            int w=((Number)stringWidth.invoke(tr, r.entry().player().name())).intValue();
            max=Math.max(max, 47+w);
        }
        return Math.min(170, max);
    }
    private static void drawCompactName(Object ctx, Object tr, MinecraftRuntimeAdapter.TabEntry e, int x, int y, int maxPx, Method stringText, Method stringWidth)throws Exception {
        String plain=ellipsizePx(e.player().name(), maxPx, tr, stringWidth);
        stringText.invoke(ctx, tr, plain, x, y, 0xFFE7EDF2);
    }
    private static Object pushScaled(Object ctx, int x, int y, float scale)throws Exception {
        Method gm=matricesMethod;
        if(gm==null||!gm.getDeclaringClass().isAssignableFrom(ctx.getClass()))matricesMethod=gm=ctx.getClass().getMethod("method_51448");
        Object matrices=gm.invoke(ctx);
        Class<?> mc=matrices.getClass();
        // Minecraft 1.21.8+ DrawContext uses JOML Matrix3x2fStack. It is NOT the old Minecraft MatrixStack,
        // so its methods keep their JOML names rather than intermediary method_2290x names.
        if(matrixClass!=mc) {
            matrixClass=mc;
            matrixPushMethod=mc.getMethod("pushMatrix");
            matrixPopMethod=mc.getMethod("popMatrix");
            matrixTranslateMethod=mc.getMethod("translate", float.class, float.class);
            matrixScaleMethod=mc.getMethod("scale", float.class, float.class);
        }
        matrixPushMethod.invoke(matrices);
        matrixTranslateMethod.invoke(matrices, (float)x, (float)y);
        matrixScaleMethod.invoke(matrices, scale, scale);
        return matrices;
    }
    private static void popScaled(Object matrices) {
        if(matrices==null)return;
        try {
            matrixPopMethod.invoke(matrices);
        } catch (Throwable ignored) {
        }
    }
    private static Object textRenderer(Object client)throws Exception {
        Field f=textRendererField;
        if(f==null||!f.getDeclaringClass().isAssignableFrom(client.getClass())) {
            f=findField(client.getClass(), "field_1772");
            if(f==null)throw new NoSuchFieldException("field_1772");
            f.setAccessible(true);
            textRendererField=f;
        }
        return f.get(client);
    }
    private static Method fill(Object ctx)throws Exception {
        Method m=fillMethod;
        if(m==null||!m.getDeclaringClass().isAssignableFrom(ctx.getClass()))fillMethod=m=ctx.getClass().getMethod("method_25294",
            int.class,
            int.class,
            int.class,
            int.class,
            int.class);
        return m;
    }
    private static Method stringText(Object ctx, Object tr)throws Exception {
        Method m=stringTextMethod;
        if(m==null||!m.getDeclaringClass().isAssignableFrom(ctx.getClass()))stringTextMethod=m=ctx.getClass().getMethod("method_25303",
            tr.getClass(),
            String.class,
            int.class,
            int.class,
            int.class);
        return m;
    }
    private static Method stringWidth(Object tr)throws Exception {
        Method m=stringWidthMethod;
        if(m==null||!m.getDeclaringClass().isAssignableFrom(tr.getClass()))stringWidthMethod=m=tr.getClass().getMethod("method_1727", String.class);
        return m;
    }
    private static Field findField(Class<?> c, String name) {
        for(Class<?> x=c; x!=null; x=x.getSuperclass())try {
            return x.getDeclaredField(name);
        } catch (NoSuchFieldException ignored) {
        }
        return null;
    }
    private static String ellipsizePx(String s, int maxPx, Object tr, Method width)throws Exception {
        if(s==null||s.isEmpty())return "";
        if(((Number)width.invoke(tr, s)).intValue()<=maxPx)return s;
        String ell="…";
        int ew=((Number)width.invoke(tr, ell)).intValue();
        if(ew>=maxPx)return ell;
        int lo=0, hi=s.length();
        while(lo<hi) {
            int mid=(lo+hi+1)>>>1;
            String q=s.substring(0, mid)+ell;
            int w=((Number)width.invoke(tr, q)).intValue();
            if(w<=maxPx)lo=mid;
            else hi=mid-1;
        }
        return s.substring(0, lo)+ell;
    }
    private static void logFailure(String where, Throwable t) {
        if(!loggedFailure) {
            loggedFailure=true;
            BootstrapLog.error(where, unwrap(t));
        }
    }
    private static Throwable unwrap(Throwable t) {
        Throwable x=t;
        while(x!=null&&x.getCause()!=null&&x instanceof InvocationTargetException)x=x.getCause();
        return x==null?t:x;
    }
}
