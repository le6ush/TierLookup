package com.tierlookup.client.dormant;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

import com.tierlookup.TierLookupClient;
import com.tierlookup.client.*;
import com.tierlookup.model.*;
import com.tierlookup.provider.TierProvider;
import com.tierlookup.service.ProfileService;
import com.tierlookup.service.TierLookupConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

/**
* Full Mode 2.0: one compact custom TierLookup window. No vanilla widgets, no pagination buttons.
* Wheel over the kit header scrolls kits horizontally; wheel over matrix rows scrolls tierlists vertically.
* History is a lazy drawer on wide GUIs and a focused page on narrow GUIs.
*/ public final class FullModeScreen extends Screen {
    private record ActionRegion(int x, int y, int w, int h, String type, String value, String tooltip) {
        boolean contains(double mx, double my) {
            return mx>=x&&my>=y&&mx<x+w&&my<y+h;
        }
    }
    private record HoverRegion(int x, int y, int w, int h, List<String> lines) {
        boolean contains(int mx, int my) {
            return mx>=x&&my>=y&&mx<x+w&&my<y+h;
        }
    }
    private record Cell(TierEntry entry, ProviderResult result) {
    }
    private record Theme(int bg, int accent, int panel, int rowA, int rowB, int muted, int text) {
    }
    private record Layout(int x,
        int y,
        int panelW,
        int panelH,
        int headerH,
        int chipsH,
        int nameW,
        int cellW,
        int rowH,
        int kitHeaderY,
        int matrixY,
        int visibleKits,
        int visibleRows,
        int drawerX,
        int drawerW,
        int drawerH,
        boolean historyStandalone) {
    }
    private PlayerProfile profile;
    private String providerFilter=null, kitFilter=null;
    private String historyProvider=null, historyKit=null;
    private boolean historyOpen=false;
    private int providerOffset=0, kitOffset=0, historyOffset=0;
    private final List<ActionRegion> actions=new ArrayList<>();
    private final List<HoverRegion> hovers=new ArrayList<>();
    private Layout lastLayout;
    private boolean returning=false;
    public FullModeScreen(PlayerProfile focus) {
        super(Text.literal("TierLookup Full"));
        this.profile=focus;
    }
    public static void open() {
        if(!ClientFeatures.FULL_MODE_VISIBLE)return;
        OverlayRenderer o=TierLookupClient.overlayInstance();
        if(o!=null&&o.primaryProfile()!=null)open(o.primaryProfile());
    }
    public static void open(PlayerProfile focus) {
        if(!ClientFeatures.FULL_MODE_VISIBLE)return;
        OverlayRenderer o=TierLookupClient.overlayInstance();
        if(o==null||focus==null)return;
        TierLookupClient.enterFullModeState();
        o.setFullMode(true);
        MinecraftClient.getInstance().setScreen(new FullModeScreen(focus));
    }
    @Override
    protected void init() {
        actions.clear();
        hovers.clear();
    }
    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float deltaTicks) {
        actions.clear();
        hovers.clear();
        refreshProfile();
        boolean ru=MinecraftBridge.russian(MinecraftBridge.client());
        TierLookupConfig cfg=TierLookupClient.configInstance();
        Theme t=theme();
        List<String> providers=visibleProviders(), kits=visibleKits(providers);
        if(providers.isEmpty()||kits.isEmpty()) {
            providerOffset=kitOffset=0;
            lastLayout=emptyLayout();
            drawEmpty(c, t, ru, mouseX, mouseY);
            drawTooltip(c, mouseX, mouseY, cfg);
            return;
        }
        Layout l=computeLayout(providers, kits);
        lastLayout=l;
        if(l.historyStandalone())drawStandaloneHistory(c, t, ru, l, mouseX, mouseY);
        else {
            drawMatrixWindow(c, t, ru, l, providers, kits, mouseX, mouseY);
            if(historyOpen&&l.drawerW()>0)drawHistoryDrawer(c, t, ru, l, mouseX, mouseY);
        }
        drawTooltip(c, mouseX, mouseY, cfg);
    }
    private Layout emptyLayout() {
        int w=Math.min(320, Math.max(210, width-20)), h=70;
        return new Layout((width-w)/2, (height-h)/2, w, h, 24, 0, 70, 26, 16, 0, 0, 0, 0, 0, 0, 0, false);
    }
    private void drawEmpty(DrawContext c, Theme t, boolean ru, int mx, int my) {
        Layout l=lastLayout;
        c.fill(l.x(), l.y(), l.x()+l.panelW(), l.y()+l.panelH(), t.bg());
        c.fill(l.x(), l.y(), l.x()+l.panelW(), l.y()+2, accent());
        drawHeader(c, t, ru, l.x(), l.y(), l.panelW());
        c.drawCenteredTextWithShadow(textRenderer, ru?"Нет видимых тиров для текущих настроек":"No visible tiers for current settings", l.x()+l.panelW()/2, l.y()+43, t.muted());
    }
    private Layout computeLayout(List<String> providers, List<String> kits) {
        int margin=8, headerH=24, chipsH=(providerFilter!=null||kitFilter!=null)?17:0, cellW=26, rowH=16, nameW=70;
        for(String id:providers)nameW=Math.max(nameW, Math.min(100, textRenderer.getWidth(providerLabel(id))+10));
        int outerW=Math.max(220, width-margin*2), outerH=Math.max(100, height-margin*2);
        boolean historyStandalone=historyOpen&&width<620;
        if(historyStandalone) {
            int pw=Math.min(410, outerW), ph=Math.min(270, outerH);
            return new Layout((width-pw)/2, (height-ph)/2, pw, ph, 24, 0, nameW, cellW, rowH, 0, 0, 0, 0, 0, 0, 0, true);
        }
        int drawerW=historyOpen?Math.min(205, Math.max(170, outerW/3)):0, gap=drawerW>0?4:0;
        int matrixAvail=Math.max(220, outerW-drawerW-gap), desired=nameW+Math.max(1, kits.size())*cellW+8;
        int panelW=Math.min(matrixAvail, Math.max(220, Math.min(520, desired))), visibleKits=Math.max(1, (panelW-nameW-8)/cellW);
        kitOffset=Math.max(0, Math.min(kitOffset, Math.max(0, kits.size()-visibleKits)));
        int fixed=headerH+chipsH+18+4, rowsFit=Math.max(1, (outerH-fixed)/rowH), visibleRows=Math.min(rowsFit, providers.size());
        providerOffset=Math.max(0, Math.min(providerOffset, Math.max(0, providers.size()-visibleRows)));
        int panelH=fixed+visibleRows*rowH, drawerH=panelH, totalW=panelW+gap+drawerW, x=(width-totalW)/2, y=(height-panelH)/2;
        int kitHeaderY=y+headerH+chipsH, matrixY=kitHeaderY+18;
        return new Layout(x,
            y,
            panelW,
            panelH,
            headerH,
            chipsH,
            nameW,
            cellW,
            rowH,
            kitHeaderY,
            matrixY,
            visibleKits,
            visibleRows,
            drawerW>0?x+panelW+gap:0,
            drawerW,
            drawerH,
            false);
    }
    private void drawMatrixWindow(DrawContext c, Theme t, boolean ru, Layout l, List<String> providers, List<String> kits, int mx, int my) {
        int x=l.x(), y=l.y(), w=l.panelW();
        c.fill(x, y, x+w, y+l.panelH(), t.bg());
        c.fill(x, y, x+w, y+2, accent());
        drawHeader(c, t, ru, x, y, w);
        int cy=y+l.headerH();
        if(l.chipsH()>0) {
            drawFilterChips(c, t, ru, x, cy, w);
            cy+=l.chipsH();
        }
        drawKitHeader(c, t, ru, l, kits);
        int ry=l.matrixY();
        for(int ri=0; ri<l.visibleRows()&&providerOffset+ri<providers.size(); ri++) {
            String pid=providers.get(providerOffset+ri);
            ProviderResult pr=profile.providers().get(pid);
            int rowY=ry+ri*l.rowH(), bg=(ri&1)==0?t.rowA():t.rowB();
            c.fill(x, rowY, x+w, rowY+l.rowH(), bg);
            c.fill(x, rowY, x+l.nameW(), rowY+l.rowH(), 0x55242A30);
            int ncol=pid.equals(providerFilter)?t.text():0xFFE4E9ED;
            c.drawTextWithShadow(textRenderer, ellipsize(providerLabel(pid), Math.max(6, (l.nameW()-8)/6)), x+4, rowY+4, ncol);
            action(x, rowY, l.nameW(), l.rowH(), "PROVIDER_FILTER", pid, (ru?"Фильтр: ":"Filter: ")+providerLabel(pid));
            hover(x, rowY, l.nameW(), l.rowH(), providerTooltip(pid, pr, ru));
            for(int ci=0; ci<l.visibleKits()&&kitOffset+ci<kits.size(); ci++) {
                String kit=kits.get(kitOffset+ci);
                int cx=x+l.nameW()+ci*l.cellW();
                Cell cell=findCell(kit, pr);
                if(cell.entry()!=null) {
                    drawCell(c, t, ru, cx, rowY, l.cellW(), l.rowH(), pid, kit, cell.entry(), pr);
                } else c.drawCenteredTextWithShadow(textRenderer, "—", cx+l.cellW()/2, rowY+4, 0xFF6F7982);
            }
        }
    }
    private void drawHeader(DrawContext c, Theme t, boolean ru, int x, int y, int w) {
        ProfileService s=TierLookupClient.profileServiceInstance();
        TierLookupConfig cfg=TierLookupClient.configInstance();
        boolean head=cfg!=null&&"head".equals(cfg.skinMode()), fav=s!=null&&s.watchlisted(profile.player().uuid());
        OverlayRenderer ov=TierLookupClient.overlayInstance();
        boolean pinned=ov!=null&&ov.tablePinned(profile.player().uuid());
        c.drawTextWithShadow(textRenderer, "‹", x+5, y+7, t.text());
        action(x+2, y+2, 15, 18, "BACK", "", ru?"Назад в K":"Back to K");
        int infoX=x+20;
        if(head) {
            KitIconRenderer.drawPlayerHead(c, profile.player(), infoX, y+4);
            infoX+=19;
        }
        c.drawTextWithShadow(textRenderer, fav?"★":"☆", infoX, y+7, fav?0xFFFFD76A:t.muted());
        action(infoX-2, y+3, 14, 17, "STAR", "", fav?(ru?"Убрать из избранного":"Remove from favorites"):(ru?"Добавить в избранное":"Add to favorites"));
        infoX+=14;
        int controls=38, nameMax=Math.max(42, (w-(infoX-x)-controls)/6);
        c.drawTextWithShadow(textRenderer, ellipsize(profile.player().name(), nameMax), infoX, y+7, nameColor());
        action(infoX, y+3, Math.max(30, w-(infoX-x)-controls-2), 17, "HISTORY_ALL", "", ru?"История игрока":"Player history");
        int pinX=x+w-36, closeX=x+w-18;
        if(pinned)c.fill(pinX-1, y+3, pinX+17, y+21, 0x553C7A8C);
        drawPinGlyph(c, pinX, y+3, pinned?0xFFFFFFFF:0xFFD5DDE3);
        drawCloseGlyph(c, closeX, y+3, 0xFFAAB4BC);
        action(pinX, y+3, 16, 16, "PIN", "", "");
        action(closeX, y+3, 16, 16, "GAME", "", "");
    }
    private void drawFilterChips(DrawContext c, Theme t, boolean ru, int x, int y, int w) {
        int cx=x+5;
        String p=providerFilter==null?null:providerLabel(providerFilter), k=kitFilter==null?null:kitLabel(kitFilter);
        if(p!=null) {
            int cw=Math.min(130, textRenderer.getWidth(p)+24);
            chip(c, t, cx, y+2, cw, p, "CLEAR_PROVIDER", ru?"Сбросить фильтр тирлиста":"Clear tierlist filter");
            cx+=cw+4;
        }
        if(k!=null) {
            int cw=Math.min(110, textRenderer.getWidth(k)+24);
            chip(c, t, cx, y+2, cw, k, "CLEAR_KIT", ru?"Сбросить фильтр кита":"Clear kit filter");
        }
    }
    private void chip(DrawContext c, Theme t, int x, int y, int w, String label, String type, String tip) {
        c.fill(x, y, x+w, y+15, 0xAA27313A);
        c.fill(x, y, x+2, y+15, t.accent());
        c.drawTextWithShadow(textRenderer, ellipsize(label, Math.max(4, (w-18)/6)), x+5, y+4, 0xFFE8EDF0);
        c.drawTextWithShadow(textRenderer, "×", x+w-11, y+4, t.muted());
        action(x, y, w, 15, type, "", tip);
    }
    private void drawKitHeader(DrawContext c, Theme t, boolean ru, Layout l, List<String> kits) {
        int x=l.x(), y=l.kitHeaderY();
        c.fill(x, y, x+l.panelW(), y+18, t.panel());
        c.fill(x, y, x+l.nameW(), y+18, 0xEE252C33);
        for(int ci=0; ci<l.visibleKits()&&kitOffset+ci<kits.size(); ci++) {
            String kit=kits.get(kitOffset+ci);
            int cx=x+l.nameW()+ci*l.cellW();
            if(!KitIconRenderer.draw(c, kit, cx+(l.cellW()-16)/2, y+1))c.drawCenteredTextWithShadow(textRenderer, "?", cx+l.cellW()/2, y+6, t.muted());
            if(kit.equals(kitFilter))c.fill(cx, y+16, cx+l.cellW(), y+18, t.accent());
            action(cx, y, l.cellW(), 18, "KIT_FILTER", kit, (ru?"Фильтр кита: ":"Kit filter: ")+kitLabel(kit));
            hover(cx, y, l.cellW(), 18, List.of(kitLabel(kit), ru?"Колесо здесь — прокрутка китов":"Wheel here scrolls kits"));
        }
    }
    private void drawCell(DrawContext c, Theme t, boolean ru, int x, int y, int w, int h, String provider, String kit, TierEntry e, ProviderResult pr) {
        String tier=TierRank.normalize(e.currentTier());
        if(tier==null)return;
        String label=retiredText(tier, e.retired());
        if(e.retired())c.fill(x+2, y+2, x+w-2, y+h-2, 0x553F3430);
        c.drawCenteredTextWithShadow(textRenderer, label, x+w/2, y+4, e.retired()?0xFFD6CBC4:0xFFB8F3FF);
        action(x, y, w, h, "CELL_HISTORY", provider+"|"+kit, ru?"Открыть историю этой клетки":"Open this cell history");
        hover(x, y, w, h, cellTooltip(provider, kit, e, pr, ru));
    }
    private void drawHistoryDrawer(DrawContext c, Theme t, boolean ru, Layout l, int mx, int my) {
        int x=l.drawerX(), y=l.y(), w=l.drawerW(), h=l.drawerH();
        c.fill(x, y, x+w, y+h, t.bg());
        c.fill(x, y, x+w, y+2, 0xFFF08CBD);
        String title=historyTitle(ru);
        c.drawTextWithShadow(textRenderer, ellipsize(title, Math.max(8, (w-26)/6)), x+7, y+8, t.text());
        c.drawTextWithShadow(textRenderer, "×", x+w-14, y+8, t.muted());
        action(x+w-18, y+3, 17, 20, "HISTORY_CLOSE", "", ru?"Закрыть историю":"Close history");
        drawHistoryRows(c, t, ru, x, y+27, w, h-30);
    }
    private void drawStandaloneHistory(DrawContext c, Theme t, boolean ru, Layout l, int mx, int my) {
        int x=l.x(), y=l.y(), w=l.panelW(), h=l.panelH();
        c.fill(x, y, x+w, y+h, t.bg());
        c.fill(x, y, x+w, y+2, 0xFFF08CBD);
        c.drawTextWithShadow(textRenderer, "‹", x+6, y+8, t.text());
        action(x+3, y+3, 15, 20, "HISTORY_CLOSE", "", ru?"Назад к таблице":"Back to table");
        c.drawTextWithShadow(textRenderer, ellipsize(historyTitle(ru), Math.max(8, (w-50)/6)), x+23, y+8, t.text());
        c.drawTextWithShadow(textRenderer, "×", x+w-14, y+8, t.muted());
        action(x+w-18, y+3, 17, 20, "GAME", "", ru?"В игру":"Game");
        drawHistoryRows(c, t, ru, x, y+27, w, h-30);
    }
    private void drawHistoryRows(DrawContext c, Theme t, boolean ru, int x, int y, int w, int h) {
        ProfileService s=TierLookupClient.profileServiceInstance();
        if(s==null)return;
        List<TierHistoryEvent> ev=filteredHistory(s.history(profile.player().uuid()));
        int rows=Math.max(1, h/13);
        historyOffset=Math.max(0, Math.min(historyOffset, Math.max(0, ev.size()-rows)));
        if(ev.isEmpty()) {
            c.drawTextWithShadow(textRenderer, historyEmptyText(s, ru), x+7, y+5, t.muted());
            return;
        }
        for(int i=0; i<rows&&historyOffset+i<ev.size(); i++) {
            TierHistoryEvent e=ev.get(historyOffset+i);
            int yy=y+i*13;
            if((i&1)==0)c.fill(x+4, yy-1, x+w-4, yy+11, 0x441E252B);
            String date=DateTimeFormatter.ofPattern("dd.MM.yy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(e.at()));
            String old=e.oldTier()==null?"—":TierRank.normalize(e.oldTier()), nw=e.newTier()==null?"—":TierRank.normalize(e.newTier());
            String line=date+"  "+providerLabel(e.providerId())+" / "+kitLabel(OverlayRenderer.canonicalKit(e.gamemode()))+"  "+old+" → "+nw;
            c.drawTextWithShadow(textRenderer, ellipsize(line, Math.max(8, (w-14)/6)), x+7, yy+1, 0xFFD8DFE5);
        }
    }
    private String historyEmptyText(ProfileService s, boolean ru) {
        if("cistiers".equals(historyProvider)) {
            ProfileService.SourceHistoryState st=s.sourceHistoryState(profile.player().uuid(), "cistiers");
            return switch(st) {
                case LOADING->ru?"Загрузка истории CISTiers…":"Loading CISTiers history…";
                case ERROR->ru?"Источник истории недоступен":"History source unavailable";
                default->ru?"Истории пока нет":"No history yet";
            };
        }
        if("atiers".equals(historyProvider))return ru?"ATiers: доступна только локально замеченная история":"ATiers: only locally observed history is available";
        return ru?"Истории пока нет":"No history yet";
    }
    private void drawTooltip(DrawContext c, int mx, int my, TierLookupConfig cfg) {
        if(cfg==null||!ClientFeatures.FULL_MODE_VISIBLE)return;
        HoverRegion hit=null;
        for(HoverRegion r:hovers)if(r.contains(mx, my)&&!r.lines().isEmpty()) {
            hit=r;
            break;
        }
        if(hit==null)return;
        int tw=0;
        for(String line:hit.lines())tw=Math.max(tw, textRenderer.getWidth(line));
        int x=Math.max(4, Math.min(width-tw-12, mx+9)), y=Math.max(4, Math.min(height-hit.lines().size()*11-10, my+10));
        c.fill(x-4, y-4, x+tw+5, y+hit.lines().size()*11+3, 0xF510151C);
        int yy=y;
        for(String line:hit.lines()) {
            c.drawTextWithShadow(textRenderer, line, x, yy, 0xFFF1F5F8);
            yy+=11;
        }
    }
    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if(click.button()!=0)return true;
        for(int i=actions.size()-1; i>=0; i--) {
            ActionRegion a=actions.get(i);
            if(a.contains(click.x(), click.y())) {
                handleAction(a);
                return true;
            }
        }
        return true;
    }
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        Layout l=lastLayout;
        if(l==null)return true;
        int step=(verticalAmount>0||horizontalAmount>0)?-1:1;
        if(verticalAmount==0&&horizontalAmount==0)return true;
        if(l.historyStandalone()) {
            historyOffset=Math.max(0, historyOffset+step);
            return true;
        }
        if(historyOpen&&l.drawerW()>0&&mouseX>=l.drawerX()) {
            historyOffset=Math.max(0, historyOffset+step);
            return true;
        }
        if(mouseY>=l.kitHeaderY()&&mouseY<l.matrixY()) {
            kitOffset=Math.max(0, kitOffset+step);
            return true;
        }
        if(mouseY>=l.matrixY()&&mouseY<l.y()+l.panelH()) {
            providerOffset=Math.max(0, providerOffset+step);
            return true;
        }
        if(Math.abs(horizontalAmount)>0) {
            kitOffset=Math.max(0, kitOffset+step);
            return true;
        }
        return true;
    }
    private void handleAction(ActionRegion a) {
        ProfileService s=TierLookupClient.profileServiceInstance();
        switch(a.type()) {
            case "BACK"->returnToK();
            case "GAME"->exitToGame();
            case "PIN"-> {
                TierLookupClient.pinFromFullMode(profile);
            }
            case "STAR"-> {
                if(s!=null) {
                    boolean next=!s.watchlisted(profile.player().uuid());
                    s.setWatchlisted(profile.player().uuid(), next);
                    if(next)s.markWatchViewed(profile.player().uuid());
                }
            }
            case "PROVIDER_FILTER"-> {
                providerFilter=Objects.equals(providerFilter, a.value())?null:a.value();
                providerOffset=0;
                historyOffset=0;
            }
            case "KIT_FILTER"-> {
                kitFilter=Objects.equals(kitFilter, a.value())?null:a.value();
                kitOffset=0;
                historyOffset=0;
            }
            case "CLEAR_PROVIDER"-> {
                providerFilter=null;
                providerOffset=0;
            }
            case "CLEAR_KIT"-> {
                kitFilter=null;
                kitOffset=0;
            }
            case "HISTORY_ALL"-> {
                historyOpen=true;
                historyProvider=historyKit=null;
                historyOffset=0;
                if(s!=null)s.markWatchViewed(profile.player().uuid());
            }
            case "CELL_HISTORY"-> {
                String[] v=a.value().split("\\|", 2);
                if(v.length==2) {
                    historyOpen=true;
                    historyProvider=v[0];
                    historyKit=v[1];
                    historyOffset=0;
                    requestSourceHistoryIfSupported();
                }
            }
            case "HISTORY_CLOSE"-> {
                historyOpen=false;
                historyProvider=historyKit=null;
                historyOffset=0;
            }
        }
    }
    private void requestSourceHistoryIfSupported() {
        if(!"cistiers".equals(historyProvider))return;
        ProfileService s=TierLookupClient.profileServiceInstance();
        if(s==null)return;
        s.ensureSourceHistory(profile.player().uuid(), "cistiers").whenComplete((ok, err)->MinecraftBridge.runOnClientThread(this::refreshProfile));
    }
    private void refreshProfile() {
        ProfileService s=TierLookupClient.profileServiceInstance();
        if(s==null||profile==null)return;
        PlayerProfile p=s.profileForDisplay(profile.player().uuid(), profile.player().name());
        if(p!=null)profile=p;
    }
    private List<String> visibleProviders() {
        TierLookupConfig cfg=TierLookupClient.configInstance();
        ArrayList<String> out=new ArrayList<>();
        List<String> order=cfg==null?TierLookupClient.providersInstance().stream().map(TierProvider::id).toList():cfg.providerOrder();
        for(String id:order) {
            if(providerFilter!=null&&!providerFilter.equals(id))continue;
            if(cfg!=null&&!cfg.enabled(id))continue;
            ProviderResult pr=profile.providers().get(id);
            if(pr==null||pr.status()!=ProviderResult.Status.OK)continue;
            boolean any=false;
            for(TierEntry e:pr.tiers()) {
                String kit=OverlayRenderer.canonicalKit(e.gamemode());
                if(kit==null||TierRank.normalize(e.currentTier())==null)continue;
                if(cfg!=null&&!cfg.kitEnabled(kit))continue;
                if(kitFilter!=null&&!kitFilter.equals(kit))continue;
                any=true;
                break;
            }
            if(any)out.add(id);
        }
        return out;
    }
    private List<String> visibleKits(List<String> providers) {
        TierLookupConfig cfg=TierLookupClient.configInstance();
        LinkedHashSet<String> found=new LinkedHashSet<>();
        for(String id:providers) {
            ProviderResult pr=profile.providers().get(id);
            if(pr==null)continue;
            for(TierEntry e:pr.tiers()) {
                String kit=OverlayRenderer.canonicalKit(e.gamemode());
                if(kit==null||TierRank.normalize(e.currentTier())==null)continue;
                if(cfg!=null&&!cfg.kitEnabled(kit))continue;
                if(kitFilter!=null&&!kitFilter.equals(kit))continue;
                found.add(kit);
            }
        }
        ArrayList<String> ordered=new ArrayList<>();
        if(cfg!=null)for(String k:cfg.kitOrder())if(found.contains(k))ordered.add(k);
        for(String k:found)if(!ordered.contains(k))ordered.add(k);
        return ordered;
    }
    private Cell findCell(String kit, ProviderResult pr) {
        if(pr!=null&&pr.status()==ProviderResult.Status.OK)for(TierEntry t:pr.tiers())if(kit.equals(OverlayRenderer.canonicalKit(t.gamemode()))&&TierRank.normalize(t.currentTier())!=null)return new Cell(t,
            pr);
        return new Cell(null, pr);
    }
    private List<TierHistoryEvent> filteredHistory(List<TierHistoryEvent> src) {
        ArrayList<TierHistoryEvent> out=new ArrayList<>();
        for(TierHistoryEvent e:src) {
            if(historyProvider!=null&&!historyProvider.equals(e.providerId()))continue;
            String k=OverlayRenderer.canonicalKit(e.gamemode());
            if(historyKit!=null&&!historyKit.equals(k))continue;
            out.add(e);
        }
        out.sort(Comparator.comparingLong(TierHistoryEvent::at).reversed());
        return out;
    }
    private List<String> cellTooltip(String provider, String kit, TierEntry t, ProviderResult pr, boolean ru) {
        ArrayList<String> lines=new ArrayList<>();
        lines.add(providerLabel(provider)+" / "+kitLabel(kit));
        lines.add((ru?"Тир: ":"Tier: ")+TierRank.normalize(t.currentTier())+(t.retired()?" · retired":""));
        long test=OverlayRenderer.lastTestMillis(t.lastTest(), pr==null?0:pr.fetchedAt());
        lines.add((ru?"Последний тиртест: ":"Last tier test: ")+(test>0?ageText(test, ru):(ru?"неизвестно":"unknown")));
        if(pr!=null)lines.add((ru?"Данные тирлиста: ":"Tierlist data: ")+ageText(pr.fetchedAt(), ru));
        lines.add(ru?"Клик — история тира":"Open — tier history");
        return lines;
    }
    private List<String> providerTooltip(String provider, ProviderResult pr, boolean ru) {
        ArrayList<String> lines=new ArrayList<>();
        lines.add(providerLabel(provider));
        if(pr!=null)lines.add((ru?"Данные загружены: ":"Data loaded: ")+ageText(pr.fetchedAt(), ru));
        lines.add(ru?"Клик — временный фильтр":"Open — temporary filter");
        return lines;
    }
    private String historyTitle(boolean ru) {
        String base=ru?"История":"History";
        if(historyProvider!=null)base+=" · "+providerLabel(historyProvider);
        if(historyKit!=null)base+=" / "+kitLabel(historyKit);
        return base;
    }
    private String retiredText(String tier, boolean retired) {
        return (retired?"R":"")+tier;
    }
    private int nameColor() {
        ProfileService s=TierLookupClient.profileServiceInstance();
        NotableStatus n=s==null?null:s.primaryNotable(profile.player().uuid());
        if(n==null)return 0xFFF0F3F5;
        return n.type()==NotableStatus.Type.CREATOR?0xFFFF8BCB:0xFFFFD36B;
    }
    private int accent() {
        ProfileService s=TierLookupClient.profileServiceInstance();
        NotableStatus n=s==null?null:s.primaryNotable(profile.player().uuid());
        if(n==null)return theme().accent();
        return n.type()==NotableStatus.Type.CREATOR?0xFFFF70BE:0xFFFFC857;
    }
    private Theme theme() {
        TierLookupConfig cfg=TierLookupClient.configInstance();
        String v=cfg==null?"midnight":cfg.theme();
        return switch(v) {
            case "classic"->new Theme(0xF7191919, 0xFFDDDDDD, 0xFA252525, 0xE71D1D1D, 0xE7232323, 0xFF9A9A9A, 0xFFF0F0F0);
            case "glass"->new Theme(0xF3151B22, 0xFF79D7FF, 0xF825303A, 0xE51A222A, 0xE51F2932, 0xFFAAB7C2, 0xFFF2F6F8);
            case "warm"->new Theme(0xF71D1714, 0xFFFFB36B, 0xFA30241F, 0xE5231A16, 0xE5291F19, 0xFFC7A98D, 0xFFFFF0E6);
            default->new Theme(0xF711151A, 0xFF66D9EF, 0xFA20262D, 0xE7191F24, 0xE71D242A, 0xFF89949E, 0xFFF0F4F6);
        };
    }
    private static void drawPinGlyph(DrawContext c, int x, int y, int color) {
        c.fill(x+5, y+3, x+11, y+5, color);
        c.fill(x+6, y+5, x+10, y+9, color);
        c.fill(x+4, y+8, x+12, y+10, color);
        c.fill(x+7, y+10, x+9, y+14, color);
        c.fill(x+8, y+14, x+9, y+15, color);
    }
    private static void drawCloseGlyph(DrawContext c, int x, int y, int color) {
        for(int i=0; i<8; i++) {
            c.fill(x+4+i, y+4+i, x+5+i, y+5+i, color);
            c.fill(x+11-i, y+4+i, x+12-i, y+5+i, color);
        }
    }
    private void action(int x, int y, int w, int h, String type, String value, String tip) {
        if(w<=0||h<=0)return;
        String t=tip==null?"":tip;
        actions.add(new ActionRegion(x, y, w, h, type, value==null?"":value, t));
        if(!t.isBlank())hovers.add(new HoverRegion(x, y, w, h, List.of(t)));
    }
    private void hover(int x, int y, int w, int h, List<String> lines) {
        if(lines!=null&&!lines.isEmpty())hovers.add(new HoverRegion(x, y, w, h, List.copyOf(lines)));
    }
    private static String ellipsize(String s, int max) {
        if(s==null)return "";
        if(max<2)return s;
        return s.length()<=max?s:s.substring(0, Math.max(1, max-1))+"…";
    }
    private static TierProvider providerById(String id) {
        for(TierProvider p:TierLookupClient.providersInstance())if(p.id().equals(id))return p;
        return null;
    }
    private static String providerLabel(String id) {
        TierProvider p=providerById(id);
        return p==null?id:p.displayName();
    }
    private static String kitLabel(String k) {
        if(k==null)return "—";
        return switch(k) {
            case "vanilla"->"Vanilla / Crystal";
            case "sword"->"Sword / Beast";
            case "npot"->"NETHPOT";
            default->k.toUpperCase(Locale.ROOT);
        };
    }
    private static String ageText(long epoch, boolean ru) {
        if(epoch<=0)return ru?"неизвестно":"unknown";
        long d=Math.max(0, Duration.between(Instant.ofEpochMilli(epoch), Instant.now()).toDays());
        if(d==0)return ru?"сегодня":"today";
        return d+(ru?" д. назад":"d ago");
    }
    @Override
    public void tick() {
    }
    @Override
    public boolean shouldPause() {
        return false;
    }
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
    @Override
    public boolean keyPressed(KeyInput input) {
        int code=input.getKeycode();
        if(code==256) {
            returnToK();
            return true;
        }
        return true;
    }
    @Override
    public void close() {
        returnToK();
    }
    private void returnToK() {
        if(returning)return;
        returning=true;
        TierLookupClient.returnToKFromFullMode();
    }
    private void exitToGame() {
        if(returning)return;
        returning=true;
        TierLookupClient.exitFullModeToGame();
    }
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
    }
    @Override
    protected void applyBlur(DrawContext context) {
    }
    @Override
    public void renderInGameBackground(DrawContext context) {
    }
}
