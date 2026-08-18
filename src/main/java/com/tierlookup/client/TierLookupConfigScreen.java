package com.tierlookup.client;

import java.util.*;

import com.tierlookup.TierLookupClient;
import com.tierlookup.api.ProviderCapabilities;
import com.tierlookup.provider.Providers;
import com.tierlookup.provider.TierProvider;
import com.tierlookup.service.BulkSyncService;
import com.tierlookup.service.ProfileService;
import com.tierlookup.service.TierLookupConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

/** Fully custom TierLookup settings UI. No vanilla ButtonWidget controls are used. */
public final class TierLookupConfigScreen extends Screen {
    private enum Page {
        MAIN, CONTENT, SEARCH, EXPERIMENTAL, SYNC, STATUS
    }
    private record Hit(int x, int y, int w, int h, String action, String value, String tooltip) {
        boolean contains(double mx, double my) {
            return mx>=x&&my>=y&&mx<x+w&&my<y+h;
        }
    }
    private record Palette(int bg, int panel, int rowA, int rowB, int control, int controlHover, int accent, int text, int muted, int on, int off, int section, int outline) {
    }
    private final Screen parent;
    private Page page=Page.MAIN;
    private final ArrayList<Hit> hits=new ArrayList<>();
    private final LinkedHashSet<String> syncSelected=new LinkedHashSet<>();
    private boolean syncInitialized=false, syncFullReset=false;
    private int mouseX, mouseY, statusPage=0;
    private String hoverTip="";
    public TierLookupConfigScreen(Screen parent) {
        super(Text.literal("TierLookup"));
        this.parent=parent;
    }
    @Override
    protected void init() {
        hits.clear();
        initSyncSelection();
    }
    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        this.mouseX=mouseX;
        this.mouseY=mouseY;
        this.hoverTip="";
        hits.clear();
        TierLookupConfig cfg=TierLookupClient.configInstance();
        boolean ru=ru();
        Palette p=palette(cfg==null?"midnight":cfg.theme());
        c.fill(0, 0, width, height, alpha(p.bg, 0x88));
        if(cfg==null) {
            drawPanel(c, Math.max(8, width/2-180), Math.max(8, height/2-40), 360, 80, p);
            c.drawCenteredTextWithShadow(textRenderer, "TierLookup unavailable", width/2, height/2-8, p.off());
            return;
        }
        int px=Math.max(10, (width-Math.min(980, width-20))/2), pw=Math.min(980, width-20), py=8, ph=Math.max(130, height-16);
        drawPanel(c, px, py, pw, ph, p);
        drawTabs(c, px, py, pw, ru, p);
        int bodyY=py+38, bodyBottom=py+ph-30;
        switch(page) {
            case MAIN -> drawMain(c, cfg, px+12, bodyY, pw-24, bodyBottom, ru, p);
            case CONTENT -> drawContent(c, cfg, px+12, bodyY, pw-24, bodyBottom, ru, p);
            case SEARCH -> drawSearch(c, cfg, px+12, bodyY, pw-24, bodyBottom, ru, p);
            case EXPERIMENTAL -> drawExperimental(c, cfg, px+12, bodyY, pw-24, bodyBottom, ru, p);
            case SYNC -> drawSync(c, cfg, px+12, bodyY, pw-24, bodyBottom, ru, p);
            case STATUS -> drawStatus(c, px+12, bodyY, pw-24, bodyBottom, ru, p);
        }
        drawControl(c, px+pw-105, py+ph-24, 93, 18, ru?"Готово":"Done", "CLOSE", "", false, p, p.text());
        for(int i=hits.size()-1; i>=0; i--) {
            Hit h=hits.get(i);
            if(h.contains(mouseX, mouseY)&&h.tooltip()!=null&&!h.tooltip().isBlank()) {
                hoverTip=h.tooltip();
                break;
            }
        }
        if(!hoverTip.isBlank())drawTooltip(c, mouseX, mouseY, hoverTip, p);
    }
    private Palette palette(String theme) {
        return switch(theme) {
            case "classic" -> new Palette(0x88141218,
                0xEE171E25,
                0xCC1E252C,
                0xCC222A32,
                0xE02C343D,
                0xF0384450,
                0xFFD7C45A,
                0xFFF3F0E7,
                0xFFAAA79D,
                0xFF8FE09C,
                0xFFF07474,
                0xD6242D35,
                0x66394550);
            case "glass" -> new Palette(0x6610171D,
                0xB8172028,
                0x86202A33,
                0x8E25303A,
                0xAA2A3944,
                0xCC314451,
                0xFF8DE8FF,
                0xFFF2FBFF,
                0xFFB6C7D0,
                0xFFA4EEB2,
                0xFFFF8C93,
                0xAE233240,
                0x66485F70);
            case "warm" -> new Palette(0x88191410,
                0xEE231B16,
                0xCC2A201A,
                0xCC30241D,
                0xE03B2E27,
                0xF045362E,
                0xFFFFB36B,
                0xFFFFF4EA,
                0xFFC7B8AA,
                0xFF9BE0A6,
                0xFFFF8D8D,
                0xD633271F,
                0x66503A2D);
            default -> new Palette(0x88070A0D,
                0xEC11161B,
                0xCC1D242A,
                0xCC20282F,
                0xE029333B,
                0xF0334049,
                0xFF66D9EF,
                0xFFF0F4F6,
                0xFF95A2AC,
                0xFF8EEA9A,
                0xFFF06E78,
                0xD3222C33,
                0x663B4A54);
        };
    }
    private void drawPanel(DrawContext c, int x, int y, int w, int h, Palette p) {
        c.fill(x, y, x+w, y+h, p.panel());
        c.fill(x, y, x+w, y+2, p.accent());
        c.fill(x+1, y+2, x+w-1, y+h-1, p.outline());
    }
    private void drawTabs(DrawContext c, int x, int y, int w, boolean ru, Palette p) {
        String[] labels=ru?new String[] {
            "Основное", "Тирлисты + киты", "Поиск + сеть", "Эксп.", "Синхронизация"
        }
        :new String[] {
            "Main", "Tierlists + kits", "Search + network", "Experimental", "Synchronization"
        };
        Page[] pages= {
            Page.MAIN, Page.CONTENT, Page.SEARCH, Page.EXPERIMENTAL, Page.SYNC
        };
        int gap=3, tw=Math.max(72, Math.min(160, (w-20-gap*4)/5)), total=tw*5+gap*4, sx=x+(w-total)/2, ty=y+7;
        for(int i=0; i<5; i++) {
            boolean active=(page==pages[i])||(page==Page.STATUS&&pages[i]==Page.SYNC);
            int bx=sx+i*(tw+gap);
            int bg=active?p.controlHover():p.control();
            if(hover(bx, ty, tw, 24))bg=active?brighten(p.controlHover(), 18):brighten(p.control(), 18);
            c.fill(bx, ty, bx+tw, ty+24, bg);
            c.fill(bx, ty+22, bx+tw, ty+24, active?p.accent():p.outline());
            c.drawCenteredTextWithShadow(textRenderer, fit(labels[i], tw-8), bx+tw/2, ty+8, active?p.text():0xFFC0C8CE);
            hit(bx, ty, tw, 24, "PAGE", pages[i].name(), "");
        }
    }
    private void drawMain(DrawContext c, TierLookupConfig cfg, int x, int y, int w, int bottom, boolean ru, Palette p) {
        int row=27;
        y=settingToggle(c,
            x,
            y,
            w,
            ru?"TierLookup включён":"TierLookup enabled",
            cfg.masterEnabled(),
            "TOGGLE_MASTER",
            row,
            p,
            ru?"Экстренное отключение мода в случае лагов":"Emergency mod shutdown in case of lag");
        y=settingToggle(c, x, y, w, ru?"Динамическая таблица при наведении на игрока":"Dynamic table on player hover", cfg.targetCardEnabled(), "TOGGLE_HOVER", row, p, null);
        y=settingStepper(c,
            x,
            y,
            w,
            ru?"Таймер отображения динамической таблицы":"Dynamic table display timer",
            cfg.tableHoldSeconds()+" s",
            "HOLD_MINUS",
            "HOLD_PLUS",
            row,
            p,
            null);
        y=settingToggle(c,
            x,
            y,
            w,
            ru?"Умное отображение":"Smart display",
            cfg.filterCurrentKit(),
            "TOGGLE_CURRENT_KIT",
            row,
            p,
            ru?"Включите чтобы отображать тиры игрока основываясь на играемом ките. Выключите чтобы видеть полный список":"Enable to show player tiers based on the kit being played. Disable to see the full list");
        y=settingCycle(c, x, y, w, ru?"Выбор темы":"Theme selection", themeName(cfg.theme(), ru), "CYCLE_THEME", row, p, null);
        if(y+row<bottom)settingAction(c, x, y, w, ru?"Сбросить настройки интерфейса":"Reset interface settings", ru?"Сбросить":"Reset", "RESET_UI", row, p, p.off(), null);
    }
    private void drawSearch(DrawContext c, TierLookupConfig cfg, int x, int y, int w, int bottom, boolean ru, Palette p) {
        int row=31;
        y=settingCycle(c, x, y, w, ru?"Основной способ подгрузки данных":"Primary data loading method", dataModeName(cfg, ru), "CYCLE_DATA_MODE", row, p, dataModeTooltip(cfg, ru));
        y=settingCycle(c,
            x,
            y,
            w,
            ru?"Подгрузка данных":"Data loading",
            interval(cfg,
            ru),
            "CYCLE_CACHE",
            row,
            p,
            ru?"Как долго считать уже загруженные данные актуальными":"How long already loaded data stays current");
        y=settingStepper(c, x, y, w, ru?"Лимит RAM-кэша":"RAM cache limit", cfg.ramCacheMb()+" MB", "RAM_MB_MINUS", "RAM_MB_PLUS", row, p, null);
        y=settingToggle(c, x, y, w, ru?"Заметки":"Notes", cfg.notesEnabled(), "TOGGLE_NOTES", row, p, null);
    }
    private void drawExperimental(DrawContext c, TierLookupConfig cfg, int x, int y, int w, int bottom, boolean ru, Palette p) {
        int row=31;
        y=settingCycle(c, x, y, w, "Tab", tabModeName(cfg.tabMode(), ru), "CYCLE_TAB_MODE", row, p, null);
        y=settingStepper(c, x, y, w, ru?"Размер кастомного Tab":"Custom Tab size", cfg.tabScalePercent()+"%", "TAB_SCALE_MINUS", "TAB_SCALE_PLUS", row, p, null);
        y=settingCycle(c, x, y, w, ru?"Положение TAB":"TAB position", tabPositionName(cfg.tabPosition(), ru), "CYCLE_TAB_POSITION", row, p, null);
        y=settingCycle(c, x, y, w, ru?"Вход в TAB-инспектор":"TAB inspector trigger", tabInteractionName(cfg.tabInteractionTrigger(), ru), "CYCLE_TAB_INTERACTION", row, p, null);
        y=drawTabKitSelector(c, cfg, x, y, w, ru, p);
        y=settingCycle(c, x, y, w, ru?"Метод сравнения":"Comparison method", compareMode(cfg.experimentalCompareMode(), ru), "CYCLE_COMPARE", row, p, null);
    }
    private int drawTabKitSelector(DrawContext c, TierLookupConfig cfg, int x, int y, int w, boolean ru, Palette p) {
        List<String> values=new ArrayList<>();
        values.add("max");
        values.addAll(TierLookupConfig.KNOWN_KITS);
        int cell=22, gap=2, labelW=Math.min(170, Math.max(118, w/4)), available=Math.max(cell, w-labelW-12), cols=Math.max(1, available/(cell+gap));
        cols=Math.min(cols, values.size());
        int rows=(values.size()+cols-1)/cols, h=20+rows*24+3;
        int bg=hover(x, y, w, h)?p.rowB():p.rowA();
        c.fill(x, y, x+w, y+h-1, bg);
        c.drawTextWithShadow(textRenderer, ru?"Предпочитаемый кит в Tab":"Preferred kit in Tab", x+7, y+6, p.text());
        int start=x+labelW, cy=y+17;
        for(int i=0; i<values.size(); i++) {
            String v=values.get(i);
            int cx=start+(i%cols)*(cell+gap), iy=cy+(i/cols)*24;
            boolean selected=v.equals(cfg.tabDisplayedKit());
            int cbg=selected?alpha(p.accent(), 0x50):(hover(cx, iy, cell, 22)?p.controlHover():p.control());
            c.fill(cx, iy, cx+cell, iy+22, cbg);
            c.fill(cx, iy, cx+cell, iy+2, selected?p.accent():p.outline());
            if("max".equals(v))c.drawCenteredTextWithShadow(textRenderer, "∞", cx+cell/2, iy+7, selected?p.text():p.muted());
            else if(!KitIconRenderer.draw(c, v, cx+3, iy+3))c.drawCenteredTextWithShadow(textRenderer, "?", cx+cell/2, iy+7, p.muted());
            hit(cx, iy, cell, 22, "TAB_KIT", v, "");
        }
        return y+h;
    }
    private void drawContent(DrawContext c, TierLookupConfig cfg, int x, int y, int w, int bottom, boolean ru, Palette p) {
        int gap=12, colW=(w-gap)/2, lx=x, rx=x+colW+gap;
        drawSectionTitle(c, lx, y, colW, ru?"Тирлисты":"Tierlists", p);
        drawSectionTitle(c, rx, y, colW, ru?"Киты":"Kits", p);
        int py=y+24;
        List<TierProvider> providers=new ArrayList<>(TierLookupClient.providersInstance());
        int pillH=24, pillGap=6, pillW=Math.max(120, (colW-pillGap)/2), col2x=lx+pillW+pillGap;
        for(int i=0; i<providers.size(); i++) {
            TierProvider pr=providers.get(i);
            int bx=(i%2==0)?lx:col2x, by=py+(i/2)*(pillH+6);
            drawTogglePill(c, bx, by, pillW, pillH, pr.displayName(), cfg.enabled(pr.id()), "PROVIDER_TOGGLE", pr.id(), p, "");
        }
        int providerBottom=py+((providers.size()+1)/2)*(pillH+6);
        List<String> kits=cfg.kitOrder();
        int cell=34, kgap=8, cols=Math.max(4, Math.min(6, (colW-8)/(cell+kgap)));
        int totalW=cols*cell+(cols-1)*kgap;
        int kx=rx+(colW-totalW)/2, ky=y+28;
        for(int i=0; i<kits.size(); i++) {
            String id=kits.get(i);
            int cx=kx+(i%cols)*(cell+kgap), cy=ky+(i/cols)*(cell+kgap);
            drawKitToggle(c, cx, cy, cell, cell, id, cfg.kitEnabled(id), p);
        }
        int kitBottom=ky+((kits.size()+cols-1)/cols)*(cell+kgap);
    }
    private void drawSync(DrawContext c, TierLookupConfig cfg, int x, int y, int w, int bottom, boolean ru, Palette p) {
        initSyncSelection();
        boolean running=TierLookupClient.bulkSyncRunning();
        drawSectionTitle(c, x, y, w, ru?"Выбор синхронизации":"Synchronization selection", p);
        y+=25;
        int modeW=54, modeH=26;
        String modeGlyph=syncFullReset?"↻":"↓";
        drawControl(c, x, y, modeW, modeH, modeGlyph, "SYNC_MODE", "", false, p, p.text());
        c.drawTextWithShadow(textRenderer,
            syncFullReset?(ru?"Полный ресет выбранных тирлистов":"Full reset of selected tierlists"):(ru?"Подгрузка данных":"Data refresh"),
            x+modeW+10,
            y+8,
            p.text());
        y+=34;
        int smallW=74;
        drawControl(c, x, y, smallW, 18, ru?"Все":"All", "SYNC_ALL", "", false, p, p.text());
        drawControl(c, x+smallW+6, y, smallW+8, 18, ru?"Снять":"None", "SYNC_NONE", "", false, p, p.text());
        y+=26;
        List<TierProvider> providers=orderedSyncProviders();
        int gap=8, colW=(w-gap)/2, rowH=24;
        for(int i=0; i<providers.size(); i++) {
            TierProvider pr=providers.get(i);
            int bx=x+(i%2)*(colW+gap), by=y+(i/2)*rowH;
            boolean sel=syncSelected.contains(pr.id());
            drawTogglePill(c, bx, by, colW, 20, pr.displayName(), sel, "SYNC_TOGGLE", pr.id(), p, "");
        }
        y+=((providers.size()+1)/2)*rowH+6;
        if(running) {
            c.drawTextWithShadow(textRenderer, fit(ru?"Синхронизация выполняется…":"Synchronization is running…", w-4), x+2, y+1, p.muted());
            y+=16;
            drawControl(c, x, y, 170, 22, ru?"■ Прервать":"■ Cancel", "SYNC_CANCEL", "", false, p, p.off());
        } else {
            String start=syncFullReset?(ru?"Запустить ресет":"Start reset"):(ru?"Запустить подгрузку":"Start refresh");
            drawControl(c, x, y, 170, 22, start, "SYNC_START", "", false, p, syncSelected.isEmpty()?p.muted():p.on());
        }
        drawControl(c, x+w-115, bottom-20, 115, 18, ru?"Покрытие базы →":"Coverage →", "STATUS_OPEN", "", false, p, p.text());
    }
    private void drawStatus(DrawContext c, int x, int y, int w, int bottom, boolean ru, Palette p) {
        drawSectionTitle(c, x, y, w, ru?"Покрытие базы":"Coverage", p);
        y+=22;
        List<ProfileService.SyncManifest> manifests=new ArrayList<>(TierLookupClient.syncManifests());
        Map<String, ProfileService.SyncManifest> by=new HashMap<>();
        for(ProfileService.SyncManifest m:manifests)by.put(m.providerId(), m);
        List<TierProvider> providers=TierLookupClient.providersInstance();
        int rows=Math.max(2, Math.min(5, (bottom-y-28)/52)), pages=Math.max(1, (providers.size()+rows-1)/rows);
        statusPage=Math.max(0, Math.min(statusPage, pages-1));
        for(int i=statusPage*rows; i<Math.min(providers.size(), (statusPage+1)*rows); i++) {
            TierProvider pr=providers.get(i);
            ProfileService.SyncManifest m=by.get(pr.id());
            ProviderCapabilities cap=Providers.capabilities(pr);
            int byy=y+(i-statusPage*rows)*52;
            int bg=hover(x, byy, w, 48)?p.rowB():p.rowA();
            c.fill(x, byy, x+w, byy+48, bg);
            String head=pr.displayName()+" · "+shownStatus(m);
            if(m!=null)head+=" · live "+m.liveRows()+" · staged "+m.snapshotRows();
            c.drawTextWithShadow(textRenderer, fit(head, w-10), x+6, byy+5, p.text());
            String capability=(ru?"Источник: ":"Source: ")+cap.shortLabel()+(cap.incrementalSync()?" · incremental":"");
            c.drawTextWithShadow(textRenderer, fit(capability, w-10), x+6, byy+18, p.accent());
            if(m!=null) {
                String d="raw "+m.rawReceived()+" · unique "+m.uniqueIdentities()+" · parsed "+m.parsed()+" · pages "+m.pages()+" · "+reason(m.terminationReason(), ru);
                c.drawTextWithShadow(textRenderer, fit(d, w-10), x+6, byy+32, p.muted());
            }
        }
        if(pages>1)drawPager(c, x, bottom-18, w, statusPage, pages, "STATUS_PREV", "STATUS_NEXT", p);
        drawControl(c, x+w-72, y-20, 72, 18, ru?"← Назад":"← Back", "PAGE", "SYNC", false, p, p.text());
    }
    private int settingToggle(DrawContext c, int x, int y, int w, String label, boolean value, String action, int row, Palette p, String tooltip) {
        drawSettingBase(c, x, y, w, row, label, p, tooltip);
        int cw=188;
        drawControl(c, x+w-cw, y+3, cw, row-6, value?(ru()?"Да":"On"):(ru()?"Нет":"Off"), action, "", false, p, value?p.on():p.off());
        return y+row;
    }
    private int settingCycle(DrawContext c, int x, int y, int w, String label, String value, String action, int row, Palette p, String tooltip) {
        drawSettingBase(c, x, y, w, row, label, p, tooltip);
        drawControl(c, x+w-188, y+3, 188, row-6, value, action, "", false, p, p.text());
        return y+row;
    }
    private int settingStepper(DrawContext c, int x, int y, int w, String label, String value, String minus, String plus, int row, Palette p, String tooltip) {
        drawSettingBase(c, x, y, w, row, label, p, tooltip);
        int bx=x+w-188;
        drawControl(c, bx, y+3, 34, row-6, "−", minus, "", false, p, p.text());
        drawControl(c, bx+37, y+3, 80, row-6, value, "NONE", "", false, p, p.text());
        drawControl(c, bx+120, y+3, 68, row-6, "+", plus, "", false, p, p.text());
        return y+row;
    }
    private void settingAction(DrawContext c, int x, int y, int w, String label, String value, String action, int row, Palette p, int color, String tooltip) {
        drawSettingBase(c, x, y, w, row, label, p, tooltip);
        drawControl(c, x+w-188, y+3, 188, row-6, value, action, "", false, p, color);
    }
    private void drawSettingBase(DrawContext c, int x, int y, int w, int h, String label, Palette p, String tooltip) {
        int bg=hover(x, y, w, h)?p.rowB():p.rowA();
        c.fill(x, y, x+w, y+h-1, bg);
        c.drawTextWithShadow(textRenderer, fit(label, w-210), x+7, y+(h-9)/2, p.text());
        if(tooltip!=null&&!tooltip.isBlank())hit(x, y, w, h, "NONE", "", tooltip);
    }
    private void infoRow(DrawContext c, int x, int y, int w, String label, String tooltip, Palette p) {
        int h=26, bg=hover(x, y, w, h)?p.rowB():p.rowA();
        c.fill(x, y, x+w, y+h-1, bg);
        c.drawTextWithShadow(textRenderer, fit(label, w-18), x+7, y+8, p.text());
        hit(x, y, w, h, "NONE", "", tooltip);
    }
    private void drawSectionTitle(DrawContext c, int x, int y, int w, String label, Palette p) {
        c.fill(x, y, x+w, y+18, p.section());
        c.fill(x, y, x+3, y+18, p.accent());
        c.drawTextWithShadow(textRenderer, fit(label, w-12), x+8, y+5, p.text());
    }
    private void drawTogglePill(DrawContext c, int x, int y, int w, int h, String label, boolean enabled, String action, String value, Palette p, String tooltip) {
        int bg=enabled?alpha(p.on(), 0x38):alpha(p.off(), 0x26);
        if(hover(x, y, w, h))bg=enabled?alpha(p.on(), 0x52):alpha(p.off(), 0x3E);
        c.fill(x, y, x+w, y+h, bg);
        c.fill(x, y, x+3, y+h, enabled?p.on():p.off());
        c.drawCenteredTextWithShadow(textRenderer, fit(label, w-10), x+w/2, y+(h-9)/2, enabled?p.text():p.muted());
        hit(x, y, w, h, action, value, tooltip);
    }
    private void drawKitToggle(DrawContext c, int x, int y, int w, int h, String kitId, boolean enabled, Palette p) {
        int bg=hover(x, y, w, h)?p.controlHover():p.control();
        if(!enabled)bg=alpha(bg, 0xA8);
        c.fill(x, y, x+w, y+h, bg);
        c.fill(x, y, x+w, y+2, enabled?p.accent():p.off());
        int ix=x+(w-16)/2, iy=y+(h-16)/2;
        boolean ok=KitIconRenderer.draw(c, kitId, ix, iy);
        if(!ok)c.drawCenteredTextWithShadow(textRenderer, "?", x+w/2, y+(h-9)/2, enabled?p.text():p.muted());
        if(!enabled) {
            c.fill(x+1, y+1, x+w-1, y+h-1, 0x660B0E10);
        }
        hit(x, y, w, h, "KIT_TOGGLE", kitId, kitName(kitId));
    }
    private void drawControl(DrawContext c, int x, int y, int w, int h, String label, String action, String value, boolean accent, Palette p, int textColor) {
        int bg=hover(x, y, w, h)?p.controlHover():p.control();
        c.fill(x, y, x+w, y+h, bg);
        c.fill(x, y, x+w, y+1, accent?p.accent():p.outline());
        c.drawCenteredTextWithShadow(textRenderer, fit(label, w-6), x+w/2, y+(h-9)/2, textColor);
        if(!"NONE".equals(action))hit(x, y, w, h, action, value, "");
    }
    private void drawPager(DrawContext c, int x, int y, int w, int page, int pages, String prev, String next, Palette p) {
        drawControl(c, x, y, 36, 18, "‹", prev, "", false, p, p.text());
        drawControl(c, x+39, y, w-78, 18, (page+1)+" / "+pages, "NONE", "", false, p, p.text());
        drawControl(c, x+w-36, y, 36, 18, "›", next, "", false, p, p.text());
    }
    private void drawTooltip(DrawContext c, int mx, int my, String s, Palette p) {
        int maxW=Math.min(320, width-20);
        List<String> lines=wrap(s, maxW-12);
        int tw=0;
        for(String line:lines)tw=Math.max(tw, textRenderer.getWidth(line));
        int th=lines.size()*11+8;
        int tx=Math.min(width-tw-16, mx+12), ty=Math.max(8, Math.min(height-th-8, my+12));
        c.fill(tx, ty, tx+tw+12, ty+th, 0xF012171C);
        c.fill(tx, ty, tx+tw+12, ty+2, p.accent());
        int ly=ty+4;
        for(String line:lines) {
            c.drawTextWithShadow(textRenderer, line, tx+6, ly, p.text());
            ly+=11;
        }
    }
    private List<String> wrap(String s, int maxWidth) {
        ArrayList<String> out=new ArrayList<>();
        StringBuilder line=new StringBuilder();
        for(String word:s.split(" ")) {
            String test=line.isEmpty()?word:line+" "+word;
            if(textRenderer.getWidth(test)<=maxWidth) {
                if(!line.isEmpty())line.append(' ');
                line.append(word);
            } else {
                if(!line.isEmpty())out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if(!line.isEmpty())out.add(line.toString());
        if(out.isEmpty())out.add(s);
        return out;
    }
    private void hit(int x, int y, int w, int h, String action, String value, String tooltip) {
        hits.add(new Hit(x, y, w, h, action, value==null?"":value, tooltip==null?"":tooltip));
    }
    private boolean hover(int x, int y, int w, int h) {
        return mouseX>=x&&mouseY>=y&&mouseX<x+w&&mouseY<y+h;
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
    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if(click.button()!=0)return true;
        for(int i=hits.size()-1; i>=0; i--) {
            Hit h=hits.get(i);
            if(h.contains(click.x(), click.y())) {
                handle(h);
                return true;
            }
        }
        return true;
    }
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int d=verticalAmount>0?-1:verticalAmount<0?1:0;
        if(d==0)return true;
        if(page==Page.STATUS)statusPage=Math.max(0, statusPage+d);
        return true;
    }
    @Override
    public boolean keyPressed(KeyInput input) {
        if(input.getKeycode()==256) {
            close();
            return true;
        }
        return true;
    }
    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }
    private void handle(Hit h) {
        TierLookupConfig c=TierLookupClient.configInstance();
        if(c==null)return;
        switch(h.action()) {
            case "PAGE"-> {
                try {
                    page=Page.valueOf(h.value());
                    statusPage=0;
                } catch (Exception ignored) {
                }
            }
            case "STATUS_OPEN"->page=Page.STATUS;
            case "CLOSE"->close();
            case "TOGGLE_MASTER"-> {
                c.setMasterEnabled(!c.masterEnabled());
                TierLookupClient.onMasterSettingChanged();
            }
            case "TOGGLE_HOVER"->c.setTargetCardEnabled(!c.targetCardEnabled());
            case "HOLD_MINUS"->c.setTableHoldSeconds(c.tableHoldSeconds()-1);
            case "HOLD_PLUS"->c.setTableHoldSeconds(c.tableHoldSeconds()+1);
            case "TOGGLE_CURRENT_KIT"->c.setFilterCurrentKit(!c.filterCurrentKit());
            case "CYCLE_THEME"->c.cycleTheme();
            case "CYCLE_COMPARE"->c.cycleExperimentalCompareMode();
            case "RESET_UI"->c.resetUiDefaults();
            case "CYCLE_DATA_MODE"-> {
                c.cycleDataMode();
                TierLookupClient.onDataModeChanged();
            }
            case "TOGGLE_NOTES"->c.setNotesEnabled(!c.notesEnabled());
            case "RAM_MB_MINUS"-> {
                c.adjustRamCacheMb(-32);
                TierLookupClient.onRamCacheLimitChanged();
            }
            case "RAM_MB_PLUS"-> {
                c.adjustRamCacheMb(32);
                TierLookupClient.onRamCacheLimitChanged();
            }
            case "CYCLE_TAB_MODE"-> {
                String old=c.tabMode();
                c.cycleTabMode();
                String now=c.tabMode();
                if("legacy".equals(now))TabNameDecorator.refreshVanillaTab(MinecraftBridge.client());
                else if(!Objects.equals(old, now))TabNameDecorator.restoreVanillaTab(MinecraftBridge.client());
            }
            case "TAB_SCALE_MINUS"->c.adjustTabScale(-5);
            case "TAB_SCALE_PLUS"->c.adjustTabScale(5);
            case "CYCLE_TAB_INTERACTION"->c.cycleTabInteractionTrigger();
            case "CYCLE_TAB_POSITION"->c.cycleTabPosition();
            case "TAB_KIT"-> {
                c.setTabDisplayedKit(h.value());
                if(c.legacyTabEnabled())TabNameDecorator.refreshVanillaTab(MinecraftBridge.client());
            }
            case "CYCLE_CACHE"->c.cycleRecacheInterval();
            case "PROVIDER_TOGGLE"-> {
                c.setEnabled(h.value(), !c.enabled(h.value()));
                TierLookupClient.onProviderSettingsChanged();
            }
            case "KIT_TOGGLE"->c.setKitEnabled(h.value(), !c.kitEnabled(h.value()));
            case "SYNC_MODE"-> {
                if(!TierLookupClient.bulkSyncRunning())syncFullReset=!syncFullReset;
            }
            case "SYNC_TOGGLE"-> {
                if(!TierLookupClient.bulkSyncRunning()) {
                    if(!syncSelected.add(h.value()))syncSelected.remove(h.value());
                }
            }
            case "SYNC_ALL"-> {
                if(!TierLookupClient.bulkSyncRunning()) {
                    syncSelected.clear();
                    for(TierProvider p:orderedSyncProviders())syncSelected.add(p.id());
                }
            }
            case "SYNC_NONE"-> {
                if(!TierLookupClient.bulkSyncRunning())syncSelected.clear();
            }
            case "SYNC_START"-> {
                if(!TierLookupClient.bulkSyncRunning()&&!syncSelected.isEmpty())TierLookupClient.startSelectedSyncFromSettings(new ArrayList<>(syncSelected), syncFullReset);
            }
            case "SYNC_CANCEL"->TierLookupClient.cancelBulkSyncFromSettings();
            case "STATUS_PREV"->statusPage=Math.max(0, statusPage-1);
            case "STATUS_NEXT"->statusPage++;
            default -> {
            }
        }
    }
    private void initSyncSelection() {
        if(syncInitialized)return;
        syncInitialized=true;
        syncSelected.clear();
        for(TierProvider p:orderedSyncProviders())syncSelected.add(p.id());
    }
    private List<TierProvider> orderedSyncProviders() {
        TierLookupConfig c=TierLookupClient.configInstance();
        List<TierProvider> all=TierLookupClient.providersInstance();
        Map<String, TierProvider> by=new LinkedHashMap<>();
        for(TierProvider p:all)by.put(p.id(), p);
        ArrayList<TierProvider> out=new ArrayList<>();
        List<String> order=c==null?new ArrayList<>(by.keySet()):c.providerOrder();
        for(String id:order) {
            TierProvider p=by.get(id);
            if(p!=null&&BulkSyncService.supportsSingleProvider(id))out.add(p);
        }
        return out;
    }
    private static String shownStatus(ProfileService.SyncManifest m) {
        if(m==null)return "NEVER";
        if("COMPLETE".equals(m.status())||"FAILED".equals(m.status())||"RUNNING".equals(m.status()))return m.status();
        if(m.liveRows()>0||m.snapshotRows()>0)return "LOADED / PARTIAL";
        return m.status();
    }
    private static String reason(String term, boolean ru) {
        if(term==null||term.isBlank())return "—";
        return switch(term) {
            case "HARD_CAP_REACHED"->ru?"лимит безопасности":"safety cap";
            case "EMPTY_PAGE"->ru?"пустая конечная страница":"terminal empty page";
            case "SHORT_PAGE"->ru?"короткая конечная страница":"short terminal page";
            case "PAGINATION_INTERRUPTED"->ru?"сеть оборвала пагинацию":"network interrupted pagination";
            case "UNPROVEN_API_ROSTER"->ru?"полнота API не доказана":"API completeness unproven";
            default->term;
        };
    }
    private String fit(String s, int maxWidth) {
        if(s==null)return "";
        String v=s;
        while(v.length()>3&&textRenderer.getWidth(v)>Math.max(10, maxWidth))v=v.substring(0, v.length()-2)+"…";
        return v;
    }
    private boolean ru() {
        return MinecraftBridge.russian(MinecraftBridge.client());
    }
    private static String dataModeName(TierLookupConfig c, boolean ru) {
        return c.internetMode()?(ru?"Интернет":"Internet"):(ru?"Оффлайн":"Offline");
    }
    private static String dataModeTooltip(TierLookupConfig c, boolean ru) {
        return c.internetMode()?(ru?"RAM → интернет → локальная БД":"RAM → internet → local database"):(ru?"RAM → локальная БД, без обычной сети":"RAM → local database, no normal network");
    }
    private static String interval(TierLookupConfig c, boolean ru) {
        return switch(c.recacheInterval()) {
            case "hour"->ru?"1 час":"1 hour";
            case "day"->ru?"1 день":"1 day";
            default->ru?"1 неделя":"1 week";
        };
    }
    private static String tabModeName(String p, boolean ru) {
        return "legacy".equals(p)?"Legacy":"Custom";
    }
    private static String tabPositionName(String p, boolean ru) {
        return switch(p) {
            case "center"->ru?"По центру":"Center";
            case "right"->ru?"Справа":"Right";
            default->ru?"Слева":"Left";
        };
    }
    private static String tabInteractionName(String p, boolean ru) {
        return "hotkey".equals(p)?"TAB + K":(ru?"TAB + ПКМ":"TAB + RMB");
    }
    private static String compareMode(String p, boolean ru) {
        return switch(p) {
            case "max"->ru?"Лучший тир":"Best tier";
            case "union"->ru?"Полное объединение":"Full union";
            default->ru?"По игроку 1":"Player 1 mask";
        };
    }
    private static String themeName(String p, boolean ru) {
        return switch(p) {
            case "classic"->ru?"Классическая":"Classic";
            case "glass"->ru?"Стекло":"Glass";
            case "warm"->ru?"Тёплая":"Warm";
            default->"Midnight";
        };
    }
    private static String kitName(String k) {
        return switch(k) {
            case "vanilla"->"Vanilla / Crystal";
            case "dpot"->"DPot";
            case "npot"->"NETHPOT";
            case "uhc"->"UHC";
            case "suhc"->"SUHC";
            case "sword"->"Sword / Beast";
            case "smp"->"SMP";
            case "op"->"OP";
            case "dsmp"->"DSMP";
            case "minecart"->"TNT Cart";
            default->k.isEmpty()?k:k.substring(0, 1).toUpperCase(Locale.ROOT)+k.substring(1);
        };
    }
    private static int alpha(int color, int a) {
        return (color&0x00FFFFFF)|((a&0xFF)<<24);
    }
    private static int brighten(int color, int add) {
        int a=(color>>>24)&0xFF, r=Math.min(255, ((color>>>16)&0xFF)+add), g=Math.min(255, ((color>>>8)&0xFF)+add), b=Math.min(255, (color&0xFF)+add);
        return (a<<24)|(r<<16)|(g<<8)|b;
    }
}
