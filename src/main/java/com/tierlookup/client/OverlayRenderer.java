package com.tierlookup.client;

import java.lang.reflect.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

import com.tierlookup.client.ui.PlayerNoteEditor;
import com.tierlookup.client.ui.PlayerNotePanel;
import com.tierlookup.client.ui.PlayerQuickMenu;
import com.tierlookup.client.ui.TierUi;
import com.tierlookup.model.*;
import com.tierlookup.service.*;

public final class OverlayRenderer {
    private static final int CELL_W=25;
    private static final int KIT_HEADER_H=18;
    private static final int STATIC_VISIBLE_KITS=9;
    private static final int DYNAMIC_VISIBLE_KITS=10;
    public record UiAction(String type, String value) {
    }
    public record TabPreviewBounds(int x, int y, int w, int h) {
        public boolean contains(double mx, double my) {
            return mx>=x&&my>=y&&mx<x+w&&my<y+h;
        }
    }
    private record ActionRegion(int x, int y, int w, int h, String type, String value, String tooltip) {
        boolean contains(int mx, int my) {
            return mx>=x&&my>=y&&mx<x+w&&my<y+h;
        }
    }
    private record ContextTarget(int x, int y, int w, int h, PlayerProfile profile) {
        boolean contains(int mx, int my) {
            return mx>=x&&my>=y&&mx<x+w&&my<y+h;
        }
    }
    private final TierLookupConfig config;
    private final ProfileService service;
    // Reuse reflection handles instead of resolving DrawContext methods every frame.
    private volatile Method hudFillMethod;
    private volatile Method hudTextMethod;
    private volatile Method hudTextWidthMethod;
    private volatile Class<?> hudContextClass;
    private volatile Class<?> hudTextRendererClass;
    private volatile PlayerProfile profile;
    private volatile PlayerProfile compareProfile;
    private volatile PlayerProfile hoverProfile;
    private volatile PlayerProfile pinnedSearchBase;
    // Pinning belongs to the current window, not to the player profile.
    private volatile boolean windowPinned=false;
    private volatile UUID hoverUuid;
    private volatile long hoverLastSeenAt;
    private volatile PlayerIdentity loadingPlayer;
    private volatile boolean open;
    private volatile boolean searchActive;
    private volatile boolean fullMode;
    private volatile long renderCount;
    private volatile String lastRenderError;
    private volatile boolean renderErrorLogged;
    private String searchText="";
    private int cursor=0;
    private int anchor=0;
    private List<String> suggestions=List.of();
    private int suggestionIndex=0;
    private volatile String searchMessage="";
    private volatile String detectedKit=KitDetector.UNKNOWN;
    private volatile long kitRevealUntil=0;
    private volatile boolean syncActive=false;
    private volatile long syncDoneUntil=0;
    private volatile String syncTitle="";
    private volatile String syncLine="";
    private long lastFrameNanos=System.nanoTime();
    private float panelAnim=0f;
    private float tableAnim=0f;
    private float syncAnim=0f;
    private float kitVisibleAnim=0f;
    private float kitExpandAnim=0f;
    private PlayerProfile lastCardA;
    private PlayerProfile lastCardB;
    private int lastMainHeight=58;
    // Small layout caches keep fast hover changes cheap.
    private record MatrixCacheEntry(PlayerProfile profile, long revision, String detected, TierMatrix matrix) {
    }
    private final LinkedHashMap<UUID, MatrixCacheEntry> matrixLru=new LinkedHashMap<>(512, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, MatrixCacheEntry> e) {
            return size()>384;
        }
    };
    private record SingleLayoutCacheEntry(PlayerProfile profile,
        long revision,
        String detected,
        int screenWidth,
        TierMatrix matrix,
        int rowNameW,
        int rowH,
        List<MatrixBand> bands,
        int width,
        int bodyHeight,
        boolean tierDots,
        boolean providerDots) {
    }
    private final LinkedHashMap<UUID, SingleLayoutCacheEntry> singleLayoutLru=new LinkedHashMap<>(512, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, SingleLayoutCacheEntry> e) {
            return size()>384;
        }
    };
    // Geometry from the last frame is reused for mouse selection.
    private int searchFieldX=-1;
    private int searchFieldY=-1;
    private int searchFieldW=0;
    private int searchFieldH=0;
    private int searchInputX=-1;
    private int[] searchCaretOffsets=new int[] {
        0
    };
    private boolean mouseSelecting=false;
    private final List<ActionRegion> actionRegions=new ArrayList<>();
    private final List<ContextTarget> contextTargets=new ArrayList<>();
    private volatile UiAction pendingUiAction;
    private boolean contextMenuOpen=false;
    private PlayerProfile contextPlayer;
    private int contextMenuX, contextMenuY;
    private final PlayerNotePanel notePanel = new PlayerNotePanel();
    private int lastMouseX=-1, lastMouseY=-1;
    // 0 = normal, 1 = choose first player, 2 = choose second player.
    private int comparePickerStage=0;
    private PlayerProfile compareDraftA;
    private boolean favoritesView=false;
    private boolean kitMenuOpen=false;
    private final FavoritesScroller favoritesScroller=new FavoritesScroller();
    private int staticKitOffset=0;
    private int staticScrollTrackX=-1, staticScrollTrackY=-1, staticScrollTrackW=0, staticScrollKnobX=-1, staticScrollKnobW=0, staticScrollTotalKits=0, staticScrollVisibleKits=0;
    private boolean staticKitDragging=false;
    private int staticKitDragGrab=0;
    public OverlayRenderer(TierLookupConfig config, ProfileService service) {
        this.config = config;
        this.service = service;
    }
    public synchronized void showLoading(PlayerIdentity p) {
        loadingPlayer=p;
        profile=null;
        compareProfile=null;
        searchMessage="";
    }
    public synchronized void showLoadingIfEmpty(PlayerIdentity p) {
        loadingPlayer=p;
        searchMessage="";
        if(profile==null||profile.player()==null||!profile.player().uuid().equals(p.uuid())) {
            profile=null;
            compareProfile=null;
        }
    }
    public synchronized void show(PlayerProfile p) {
        profile=p;
        compareProfile=null;
        loadingPlayer=p==null?null:p.player();
        searchMessage="";
        staticKitOffset=0;
    }
    public synchronized void showCompare(PlayerProfile a, PlayerProfile b) {
        if(a==null||b==null)return;
        profile=a;
        compareProfile=b;
        loadingPlayer=null;
        searchMessage="";
        staticKitOffset=0;
    }
    public synchronized void replaceProfile(PlayerProfile updated) {
        if(updated==null)return;
        UUID id=updated.player().uuid();
        if(profile!=null&&id.equals(profile.player().uuid()))profile=updated;
        if(compareProfile!=null&&id.equals(compareProfile.player().uuid()))compareProfile=updated;
        if(compareDraftA!=null&&id.equals(compareDraftA.player().uuid()))compareDraftA=updated;
    }
    public synchronized void clearCompareResult() {
        compareProfile = null;
        compareDraftA = null;
        comparePickerStage = 0;
    }
    public synchronized PlayerProfile comparisonBase() {
        return pinnedSearchBase;
    }
    public synchronized void pinComparisonBase(PlayerProfile p) {
        pinnedSearchBase=p;
    }
    public synchronized void clearComparisonBase() {
        pinnedSearchBase=null;
    }
    public synchronized String comparisonBaseName() {
        return pinnedSearchBase==null?"":pinnedSearchBase.player().name();
    }
    public synchronized boolean comparing() {
        return profile!=null&&compareProfile!=null;
    }
    public synchronized void openMain() {
        open=true;
        refreshSuggestions();
    }
    public synchronized void toggleMain() {
        if(open)close();
        else openMain();
    }
    public synchronized void close() {
        open=false;
        searchActive=false;
        searchMessage="";
        loadingPlayer=null;
        if(!windowPinned) {
            profile=null;
            compareProfile=null;
            lastCardA=null;
            lastCardB=null;
            tableAnim=0f;
        } else {
            lastCardA=profile;
            lastCardB=compareProfile;
            tableAnim=profile==null?0f:1f;
        }
        searchText="";
        cursor=anchor=0;
        suggestions=List.of();
        suggestionIndex=0;
        mouseSelecting=false;
        pinnedSearchBase=null;
        comparePickerStage=0;
        compareDraftA=null;
        favoritesView=false;
        kitMenuOpen=false;
        staticKitOffset=0;
        staticKitDragging=false;
        staticScrollTrackX=-1;
        contextMenuOpen=false;
        contextPlayer=null;
        notePanel.clear();
        clearHoverImmediate();
    }
    public synchronized void closeAll() {
        windowPinned=false;
        close();
        profile=null;
        compareProfile=null;
        loadingPlayer=null;
        lastCardA=null;
        lastCardB=null;
        tableAnim=0f;
        clearHoverImmediate();
        cancelSync();
    }
    public synchronized boolean noteEditing() {
        return notePanel.editing();
    }
    public synchronized void beginNoteEdit(PlayerProfile playerProfile) {
        if (playerProfile == null || service == null || !config.notesEnabled()) {
            return;
        }
        notePanel.begin(playerProfile.player(), service.playerNote(playerProfile.player().uuid()));
        contextMenuOpen = false;
        contextPlayer = null;
        mouseSelecting = false;
    }
    public synchronized boolean handleNoteChar(Object input) {
        return notePanel.handleChar(input);
    }
    public synchronized boolean handleNoteKey(Object input) {
        if (!notePanel.editing()) {
            return false;
        }
        PlayerNoteEditor.Result result = notePanel.handleKey(input);
        if (result == PlayerNoteEditor.Result.SAVE) {
            if (service != null && notePanel.player() != null) {
                service.setPlayerNote(notePanel.player().uuid(), notePanel.value());
            }
            notePanel.clear();
        } else if (result == PlayerNoteEditor.Result.CANCEL) {
            notePanel.clear();
        }
        return true;
    }
    public boolean searchActive() {
        return searchActive;
    }
    public boolean ownsKeyboard() {
        return open;
    }
    public boolean open() {
        return open;
    }
    public boolean fullMode() {
        return fullMode;
    }
    public synchronized void setFullMode(boolean v) {
        fullMode=v;
    }
    public synchronized PlayerProfile primaryProfile() {
        return profile;
    }
    public synchronized PlayerProfile secondaryProfile() {
        return compareProfile;
    }
    public synchronized PlayerProfile pinnedTableProfile() {
        return windowPinned?profile:null;
    }
    public synchronized boolean pinnedHudActive() {
        return !open&&windowPinned&&profile!=null;
    }
    public synchronized boolean tablePinned(UUID id) {
        return windowPinned&&id!=null&&((profile!=null&&id.equals(profile.player().uuid()))||(compareProfile!=null&&id.equals(compareProfile.player().uuid())));
    }
    public synchronized boolean toggleTablePin(PlayerProfile p) {
        if(p==null)return false;
        windowPinned=!windowPinned;
        if(!windowPinned&&!open) {
            profile=null;
            compareProfile=null;
            lastCardA=null;
            lastCardB=null;
            tableAnim=0f;
        } else if(windowPinned&&!open) {
            lastCardA=profile;
            lastCardB=compareProfile;
            tableAnim=profile==null?0f:1f;
        }
        return windowPinned;
    }
    public synchronized void closeWindow() {
        profile=null;
        compareProfile=null;
        compareDraftA=null;
        comparePickerStage=0;
        windowPinned=false;
        lastCardA=null;
        lastCardB=null;
        tableAnim=0f;
        loadingPlayer=null;
    }
    public synchronized void closeParticipant(UUID id) {
        if(id==null) {
            closeWindow();
            return;
        }
        if(compareProfile==null) {
            if(profile!=null&&id.equals(profile.player().uuid()))closeWindow();
            return;
        }
        if(id.equals(compareProfile.player().uuid())) {
            compareProfile=null;
            compareDraftA=profile;
            comparePickerStage=2;
            searchText="";
            cursor=anchor=0;
            refreshSuggestions();
            return;
        }
        if(profile!=null&&id.equals(profile.player().uuid())) {
            profile=compareProfile;
            compareProfile=null;
            compareDraftA=profile;
            comparePickerStage=2;
            searchText="";
            cursor=anchor=0;
            refreshSuggestions();
        }
    }
    public synchronized boolean windowPinned() {
        return windowPinned;
    }
    public synchronized PlayerProfile promoteHoverToWorkspace() {
        if(hoverProfile==null)return null;
        profile=hoverProfile;
        compareProfile=null;
        loadingPlayer=profile.player();
        windowPinned=false;
        hoverProfile=null;
        hoverUuid=null;
        hoverLastSeenAt=0;
        lastCardA=profile;
        lastCardB=null;
        tableAnim=1f;
        return profile;
    }
    public String detectedKit() {
        return detectedKit;
    }
    public PlayerIdentity player() {
        return loadingPlayer;
    }
    public synchronized UiAction pollUiAction() {
        UiAction a=pendingUiAction;
        pendingUiAction=null;
        return a;
    }
    public synchronized int comparePickerStage() {
        return comparePickerStage;
    }
    public synchronized boolean comparePickerActive() {
        return comparePickerStage>0;
    }
    public synchronized PlayerProfile compareDraftA() {
        return compareDraftA;
    }
    public synchronized void beginComparePicker() {
        favoritesView=false;
        kitMenuOpen=false;
        searchMessage="";
        if(profile!=null) {
            compareDraftA=profile;
            comparePickerStage=2;
        } else {
            compareDraftA=null;
            comparePickerStage=1;
        }
        searchText="";
        cursor=anchor=0;
        suggestions=List.of();
        activateSearch();
    }
    public synchronized void cancelComparePicker() {
        comparePickerStage=0;
        compareDraftA=null;
        favoritesView=false;
        searchMessage="";
        refreshSuggestions();
    }
    public synchronized void acceptComparePick(PlayerProfile p) {
        if(p==null||comparePickerStage==0)return;
        if(comparePickerStage==1) {
            profile=p;
            compareDraftA=p;
            comparePickerStage=2;
            searchText="";
            cursor=anchor=0;
            refreshSuggestions();
            searchMessage="";
        } else {
            if(compareDraftA!=null&&!compareDraftA.player().uuid().equals(p.player().uuid()))showCompare(compareDraftA, p);
            comparePickerStage=0;
            compareDraftA=null;
            searchMessage="";
            refreshSuggestions();
        }
    }
    public synchronized void toggleFavoritesView() {
        favoritesView=!favoritesView;
        favoritesScroller.reset();
        refreshSuggestions();
    }
    public synchronized void setFavoritesView(boolean value) {
        favoritesView=value;
        favoritesScroller.reset();
        refreshSuggestions();
    }
    public synchronized void scrollStaticKits(int delta) {
        staticKitOffset=Math.max(0, staticKitOffset+delta);
    }
    public synchronized boolean scrollFavorites(int delta) {
        if(!open||!favoritesView||!searchText.isBlank())return false;
        int size=service==null?0:service.watchlistedProfiles().size();
        return favoritesScroller.scroll(delta, size);
    }
    public synchronized void setKitMenuOpen(boolean v) {
        kitMenuOpen=v;
    }
    public synchronized boolean kitMenuOpen() {
        return kitMenuOpen;
    }
    public long renderCount() {
        return renderCount;
    }
    public String lastRenderError() {
        return lastRenderError;
    }
    public synchronized void activateSearch() {
        open=true;
        searchActive=true;
        cursor=searchText.length();
        anchor=cursor;
        searchMessage="";
        refreshSuggestions();
    }
    public synchronized void cancelSearch() {
        searchActive=false;
        anchor=cursor;
        mouseSelecting=false;
    }
    public synchronized String searchText() {
        return searchText;
    }
    public synchronized void setSearchText(String value) {
        searchText=value==null?"":value;
        cursor=anchor=searchText.length();
        searchMessage="";
        mouseSelecting=false;
        refreshSuggestions();
    }
    public synchronized void clearSearch() {
        searchText="";
        cursor=anchor=0;
        searchMessage="";
        mouseSelecting=false;
        refreshSuggestions();
    }
    public synchronized void setSearchMessage(String s) {
        searchMessage=s==null?"":s;
    }
    public synchronized void selectAll() {
        anchor=0;
        cursor=searchText.length();
    }
    public synchronized boolean hasSelection() {
        return cursor!=anchor;
    }
    public synchronized String selectedText() {
        int a=Math.min(cursor, anchor), b=Math.max(cursor, anchor);
        return searchText.substring(a, b);
    }
    public synchronized void insertText(String raw) {
        if(raw==null||raw.isEmpty())return;
        StringBuilder clean=new StringBuilder();
        for(int i=0; i<raw.length(); i++) {
            char c=raw.charAt(i);
            if(Character.isLetterOrDigit(c)||c=='_')clean.append(c);
        }
        replaceSelection(clean.toString());
        refreshSuggestions();
    }
    public synchronized void backspaceSearch() {
        if(deleteSelection()) {
            refreshSuggestions();
            return;
        }
        if(cursor<=0)return;
        searchText=searchText.substring(0, cursor-1)+searchText.substring(cursor);
        cursor--;
        anchor=cursor;
        searchMessage="";
        refreshSuggestions();
    }
    public synchronized void deleteForward() {
        if(deleteSelection()) {
            refreshSuggestions();
            return;
        }
        if(cursor>=searchText.length())return;
        searchText=searchText.substring(0, cursor)+searchText.substring(cursor+1);
        anchor=cursor;
        searchMessage="";
        refreshSuggestions();
    }
    public synchronized void cutSelection() {
        if(deleteSelection())refreshSuggestions();
    }
    public synchronized void moveCursor(int delta, boolean selecting) {
        int n=Math.max(0, Math.min(searchText.length(), cursor+delta));
        if(!selecting)anchor=n;
        cursor=n;
    }
    public synchronized void moveHome(boolean selecting) {
        cursor=0;
        if(!selecting)anchor=cursor;
    }
    public synchronized void moveEnd(boolean selecting) {
        cursor=searchText.length();
        if(!selecting)anchor=cursor;
    }
    public synchronized void moveSuggestion(int delta) {
        if(suggestions.isEmpty())return;
        suggestionIndex=(suggestionIndex+delta)%suggestions.size();
        if(suggestionIndex<0)suggestionIndex+=suggestions.size();
    }
    public synchronized boolean acceptSuggestion() {
        if(suggestions.isEmpty())return false;
        String s=suggestions.get(Math.max(0, Math.min(suggestionIndex, suggestions.size()-1)));
        searchText=s;
        cursor=anchor=s.length();
        refreshSuggestions();
        return true;
    }
    private void replaceSelection(String add) {
        int a=Math.min(cursor, anchor), b=Math.max(cursor, anchor);
        int room=16-(searchText.length()-(b-a));
        if(room<=0)return;
        if(add.length()>room)add=add.substring(0, room);
        searchText=searchText.substring(0, a)+add+searchText.substring(b);
        cursor=a+add.length();
        anchor=cursor;
        searchMessage="";
    }
    private boolean deleteSelection() {
        if(cursor==anchor)return false;
        int a=Math.min(cursor, anchor), b=Math.max(cursor, anchor);
        searchText=searchText.substring(0, a)+searchText.substring(b);
        cursor=anchor=a;
        searchMessage="";
        return true;
    }
    private void refreshSuggestions() {
        if(service==null||searchText.isBlank()) {
            suggestions=List.of();
            suggestionIndex=0;
            return;
        }
        if(favoritesView) {
            String q=searchText.toLowerCase(Locale.ROOT);
            ArrayList<String> out=new ArrayList<>();
            for(PlayerProfile p:service.watchlistedProfiles()) {
                String n=p.player().name();
                if(n.toLowerCase(Locale.ROOT).startsWith(q)||n.toLowerCase(Locale.ROOT).contains(q)) {
                    out.add(n);
                    if(out.size()>=8)break;
                }
            }
            suggestions=List.copyOf(out);
        } else suggestions=service.autocomplete(searchText, 8, MinecraftBridge.currentServerKey(MinecraftBridge.client()));
        if(suggestionIndex>=suggestions.size())suggestionIndex=Math.max(0, suggestions.size()-1);
    }
    public synchronized void handleSearchMouse(int mx, int my, boolean leftDown, boolean leftPressed, boolean rightPressed) {
        lastMouseX=mx;
        lastMouseY=my;
        if(!open)return;
        if(rightPressed) {
            for(int i=contextTargets.size()-1; i>=0; i--) {
                ContextTarget t=contextTargets.get(i);
                if(t.contains(mx, my)) {
                    contextMenuOpen=true;
                    contextPlayer=t.profile();
                    contextMenuX=mx;
                    contextMenuY=my;
                    mouseSelecting=false;
                    return;
                }
            }
            contextMenuOpen=false;
            contextPlayer=null;
        }
        if (leftPressed && notePanel.editing()) {
            Object textRenderer = MinecraftBridge.textRenderer(MinecraftBridge.client());
            if (notePanel.click(mx, my, textRenderer)) {
                mouseSelecting = false;
                return;
            }
        }
        if(staticKitDragging) {
            if(leftDown) {
                updateStaticKitDrag(mx);
                mouseSelecting=false;
                return;
            }
            staticKitDragging=false;
        }
        if(leftPressed) {
            // Popups get the click before controls underneath them.
            for(int i=actionRegions.size()-1; i>=0; i--) {
                ActionRegion r=actionRegions.get(i);
                if(r.contains(mx, my)) {
                    pendingUiAction=new UiAction(r.type(), r.value());
                    if(contextMenuOpen) {
                        contextMenuOpen=false;
                        contextPlayer=null;
                    }
                    mouseSelecting=false;
                    return;
                }
            }
            if(!contextMenuOpen&&staticScrollTrackX>=0&&my>=staticScrollTrackY-2&&my<staticScrollTrackY+12&&mx>=staticScrollTrackX-2&&mx<staticScrollTrackX+staticScrollTrackW+2) {
                staticKitDragGrab=(mx>=staticScrollKnobX&&mx<staticScrollKnobX+staticScrollKnobW)?mx-staticScrollKnobX:Math.max(0, staticScrollKnobW/2);
                staticKitDragging=true;
                updateStaticKitDrag(mx);
                mouseSelecting=false;
                return;
            }
        }
        if(searchFieldX<0) {
            if(!leftDown)mouseSelecting=false;
            return;
        }
        boolean inside=mx>=searchFieldX&&mx<searchFieldX+searchFieldW&&my>=searchFieldY&&my<searchFieldY+searchFieldH;
        if(leftPressed) {
            if(inside) {
                searchActive=true;
                int idx=mouseIndex(mx);
                cursor=anchor=idx;
                mouseSelecting=true;
                searchMessage="";
                refreshSuggestions();
            } else mouseSelecting=false;
        } else if(leftDown&&mouseSelecting) {
            cursor=mouseIndex(mx);
        }
        if(!leftDown)mouseSelecting=false;
    }
    private void updateStaticKitDrag(int mouseX) {
        int maxOffset=Math.max(0, staticScrollTotalKits-staticScrollVisibleKits), travel=Math.max(0, staticScrollTrackW-staticScrollKnobW);
        if(maxOffset<=0||travel<=0) {
            staticKitOffset=0;
            return;
        }
        int left=Math.max(staticScrollTrackX, Math.min(staticScrollTrackX+travel, mouseX-staticKitDragGrab));
        float f=(left-staticScrollTrackX)/(float)travel;
        staticKitOffset=Math.max(0, Math.min(maxOffset, Math.round(f*maxOffset)));
    }
    private int mouseIndex(int mx) {
        int rel=Math.max(0, mx-searchInputX), len=searchText.length();
        int[] pos=searchCaretOffsets;
        if(pos==null||pos.length!=len+1) {
            int idx=(rel+3)/6;
            return Math.max(0, Math.min(len, idx));
        }
        if(rel<=0)return 0;
        if(rel>=pos[len])return len;
        for(int i=0; i<len; i++) {
            int mid=(pos[i]+pos[i+1])/2;
            if(rel<mid)return i;
        }
        return len;
    }
    public synchronized void beginHover(PlayerIdentity p) {
        if(p==null)return;
        long now=System.currentTimeMillis();
        if(hoverUuid==null||!hoverUuid.equals(p.uuid())) {
            hoverUuid=p.uuid();
            hoverProfile=null;
        }
        hoverLastSeenAt=now;
    }
    public synchronized void touchHover(UUID id) {
        if(id!=null&&id.equals(hoverUuid))hoverLastSeenAt=System.currentTimeMillis();
    }
    public synchronized void showHover(PlayerProfile p) {
        if(p==null)return;
        hoverProfile=p;
    }
    public synchronized PlayerProfile currentHoverProfile() {
        return hoverProfile;
    }
    public synchronized UUID currentHoverObservedUuid() {
        return hoverUuid;
    }
    public synchronized void markHoverLost() {
        /* timer is intentionally allowed to run out */
    }
    public synchronized void clearHoverImmediate() {
        hoverProfile=null;
        hoverUuid=null;
        hoverLastSeenAt=0;
    }
    public synchronized void setDetectedKit(String kit) {
        String next=(kit==null||kit.isBlank())?KitDetector.UNKNOWN:kit;
        if(!Objects.equals(next, detectedKit)) {
            detectedKit=next;
            if(!KitDetector.UNKNOWN.equals(next))kitRevealUntil=System.currentTimeMillis()+2200;
            else kitRevealUntil=0;
        }
    }
    public synchronized void beginSync(String title) {
        syncTitle=title==null?"Sync":title;
        syncLine="starting...";
        syncActive=true;
        syncDoneUntil=0;
    }
    public synchronized void setSyncProgress(String line) {
        if(!syncActive)return;
        syncLine=line==null?"":line;
    }
    public synchronized void finishSync(String line) {
        syncLine=line==null?"":line;
        syncActive=false;
        syncDoneUntil=System.currentTimeMillis()+3000;
    }
    public synchronized void cancelSync() {
        syncActive=false;
        syncDoneUntil=0;
        syncTitle="";
        syncLine="";
    }
    public void renderFromScreen(Object ctx, Object client) {
        render(ctx, client);
    }
    public void render(Object ctx, Object client) {
        renderCount++;
        if(ctx==null||client==null)return;
        try {
            long nowNs=System.nanoTime();
            float dt=Math.min(0.1f, Math.max(0f, (nowNs-lastFrameNanos)/1_000_000_000f));
            lastFrameNanos=nowNs;
            float speed=(float)config.animationSpeed();
            panelAnim=approach(panelAnim, open?1f:0f, dt, 10f*speed);
            boolean syncVisible=syncActive||System.currentTimeMillis()<syncDoneUntil;
            syncAnim=approach(syncAnim, syncVisible?1f:0f, dt, 10f*speed);
            boolean kitKnown=!KitDetector.UNKNOWN.equals(detectedKit);
            kitVisibleAnim=approach(kitVisibleAnim, kitKnown?1f:0f, dt, 10f*speed);
            kitExpandAnim=approach(kitExpandAnim, kitKnown&&System.currentTimeMillis()<kitRevealUntil?1f:0f, dt, 8f*speed);
            Object tr=MinecraftBridge.textRenderer(client);
            if(tr==null)return;
            Method fill=hudFillMethod, text=hudTextMethod;
            Class<?> cc=ctx.getClass(), tc=tr.getClass();
            if(fill==null||text==null||hudContextClass!=cc||hudTextRendererClass!=tc) {
                synchronized(this) {
                    fill=hudFillMethod;
                    text=hudTextMethod;
                    if(fill==null||text==null||hudContextClass!=cc||hudTextRendererClass!=tc) {
                        hudFillMethod=fill=cc.getMethod("method_25294", int.class, int.class, int.class, int.class, int.class);
                        hudTextMethod=text=cc.getMethod("method_25303", tc, String.class, int.class, int.class, int.class);
                        try {
                            hudTextWidthMethod=tc.getMethod("method_1727", String.class);
                        } catch (Throwable ignored) {
                            hudTextWidthMethod=null;
                        }
                        hudContextClass=cc;
                        hudTextRendererClass=tc;
                    }
                }
            }
            boolean ru=MinecraftBridge.russian(client);
            synchronized(this) {
                actionRegions.clear();
                contextTargets.clear();
                searchFieldX=-1;
                searchFieldY=-1;
                searchFieldW=searchFieldH=0;
            }
            if(panelAnim>0.01f&&!fullMode&&!comparePickerActive())renderMain(ctx, client, tr, fill, text, ru, panelAnim);
            if(panelAnim>0.01f&&!fullMode&&comparePickerActive())renderComparePicker(ctx, client, tr, fill, text, ru, panelAnim);
            if(syncAnim>0.01f)renderSync(ctx, client, tr, fill, text, ru, syncAnim);
            long now=System.currentTimeMillis();
            PlayerProfile a=null, b=null;
            boolean staticWindow=false;
            // Static K/pinned windows are workspace state. Dynamic hover is a separate RAM-only transient view.
            if(open&&profile!=null) {
                a=profile;
                b=compareProfile;
                staticWindow=true;
            } else if(!open&&windowPinned&&profile!=null) {
                a=profile;
                b=compareProfile;
                staticWindow=true;
            } else if(!windowPinned&&hoverProfile!=null&&now-hoverLastSeenAt<=config.tableHoldSeconds()*1000L) {
                a=hoverProfile;
                staticWindow=false;
            }
            boolean wantsTable=a!=null&&config.targetCardEnabled();
            if(fullMode||(comparePickerStage==1))wantsTable=false;
            if(!wantsTable||!staticWindow) {
                staticScrollTrackX=-1;
                staticKitDragging=false;
            }
            if(wantsTable) {
                lastCardA=a;
                lastCardB=b;
            }
            tableAnim=approach(tableAnim, wantsTable?1f:0f, dt, 9f*speed);
            if(tableAnim>0.01f&&lastCardA!=null)renderTable(ctx, client, tr, fill, text, lastCardA, lastCardB, tableAnim, staticWindow);
            if(tableAnim<0.01f&&!wantsTable) {
                lastCardA=null;
                lastCardB=null;
            }
            if(kitVisibleAnim>0.01f)renderDetectedKit(ctx, client, tr, fill, text, ru, kitVisibleAnim, kitExpandAnim);
            if(open&&kitMenuOpen&&!fullMode)renderKitMenu(ctx, client, tr, fill, text, ru);
            if (open && contextMenuOpen && !fullMode) {
                renderContextMenu(ctx, client, tr, fill, text, ru);
            } else if (open && !fullMode) {
                renderUiTooltip(ctx, tr, fill, text);
            }
        } catch (Throwable t) {
            lastRenderError=t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
            if(!renderErrorLogged) {
                renderErrorLogged=true;
                BootstrapLog.error("HUD render", t);
            }
        }
    }
    private void renderMain(Object ctx, Object client, Object tr, Method fill, Method text, boolean ru, float anim)throws Exception {
        int sw=MinecraftBridge.scaledWidth(client);
        Theme theme=theme();
        List<String> sug;
        int sel, cur, anc;
        String q;
        boolean favOnly;
        int favScroll;
        synchronized(this) {
            sug=suggestions;
            sel=suggestionIndex;
            cur=cursor;
            anc=anchor;
            q=searchText;
            favOnly=favoritesView;
            favScroll=favoritesScroller.offset();
        }
        List<PlayerProfile> favs=service==null?List.of():service.watchlistedProfiles(), recent=service==null?List.of():service.recentProfiles(7);
        int maxFavScroll=FavoritesScroller.maxOffset(favs.size());
        favScroll=favoritesScroller.clamp(favs.size());
        int visibleRows=0;
        if(!q.isBlank())visibleRows=Math.min(6, sug.size());
        else if(favOnly)visibleRows=Math.min(7, Math.max(0, favs.size()-favScroll));
        else visibleRows=Math.min(7, recent.size());
        int w=Math.min(306, Math.max(246, sw/4)), h=50+Math.max(1, visibleRows)*13+(!searchMessage.isBlank()?13:0);
        int x=Math.round(-w-6+(w+11)*ease(anim)), y=6;
        lastMainHeight=h;
        fill.invoke(ctx, x, y, x+w, y+h, theme.bg());
        fill.invoke(ctx, x, y, x+w, y+2, theme.accent());
        text.invoke(ctx, tr, ru?"Игроки":"Players", x+8, y+8, 0xFFFFFFFF);
        drawSearchViewToggle(fill, text, ctx, tr, x+w-145, y+4, 118, 16, favOnly, ru, theme);
        text.invoke(ctx, tr, "×", x+w-16, y+7, 0xFFE6EBEF);
        addActionRegion(x+w-20, y+2, 18, 20, "WORKSPACE_CLOSE", "", ru?"Закрыть TierLookup":"Close TierLookup");
        int fy=y+25;
        fill.invoke(ctx, x+8, fy-2, x+w-8, fy+16, 0xCC20262D);
        String prefix=ru?"Ник: ":"Nick: ";
        int inputX=x+12+exactTextWidth(tr, prefix);
        text.invoke(ctx, tr, prefix, x+12, fy+2, 0xFFC6CDD3);
        int[] caret=caretOffsets(tr, q);
        synchronized(this) {
            searchFieldX=x+8;
            searchFieldY=fy-2;
            searchFieldW=w-16;
            searchFieldH=18;
            searchInputX=inputX;
            searchCaretOffsets=caret;
        }
        int sa=Math.max(0, Math.min(q.length(), Math.min(cur, anc))), sb=Math.max(0, Math.min(q.length(), Math.max(cur, anc)));
        if(searchActive&&sa!=sb)fill.invoke(ctx, inputX+caret[sa], fy, inputX+caret[sb], fy+12, 0xAA3974A8);
        text.invoke(ctx, tr, q, inputX, fy+2, 0xFFFFFFFF);
        if(cursorBlinkVisible(searchActive)) {
            int ci=Math.max(0, Math.min(q.length(), cur)), cx=inputX+caret[ci];
            fill.invoke(ctx, cx, fy, cx+1, fy+12, 0xFFFFFFFF);
        }
        int ty=fy+21;
        if(!q.isBlank()) {
            if(sug.isEmpty()) {
                text.invoke(ctx, tr, favOnly?(ru?"Нет совпадений":"No matches"):(ru?"Нет совпадений":"No matches"), x+11, ty+1, theme.muted());
                ty+=13;
            } else for(int i=0; i<Math.min(6, sug.size()); i++) {
                String name=sug.get(i);
                boolean selected=i==sel, over=lastMouseX>=x+8&&lastMouseY>=ty-1&&lastMouseX<x+w-8&&lastMouseY<ty+12;
                if(selected||over)fill.invoke(ctx, x+8, ty-1, x+w-8, ty+12, selected?0x4435444E:0x332A353D);
                int color=notableSearchColor(name, selected?0xFFFFFFFF:0xFFC3CBD2);
                text.invoke(ctx, tr, name, x+12, ty+2, color);
                addActionRegion(x+8, ty-1, w-16, 13, "SELECT_PLAYER", name, "");
                ty+=13;
            }
        } else if(favOnly) {
            int from=favScroll;
            if(favs.isEmpty()) {
                text.invoke(ctx, tr, ru?"Пусто":"Empty", x+11, ty, theme.muted());
                ty+=13;
            } else {
                int to=Math.min(favs.size(), from+7);
                for(PlayerProfile p:favs.subList(Math.min(from, favs.size()), to)) {
                    drawPlayerRow(ctx, tr, fill, text, p, x+8, ty, w-22, ru);
                    ty+=13;
                }
                if(favs.size()>7) {
                    int trackX=x+w-10,
                        trackY=fy+20,
                        trackH=Math.max(14,
                        Math.min(7,
                        favs.size())*13),
                        thumbH=Math.max(10,
                        Math.round(trackH*(7f/favs.size()))),
                        travel=Math.max(0,
                        trackH-thumbH),
                        thumbY=trackY+(maxFavScroll<=0?0:Math.round(travel*(favScroll/(float)maxFavScroll)));
                    fill.invoke(ctx, trackX, trackY, trackX+2, trackY+trackH, 0x88404A52);
                    fill.invoke(ctx, trackX-1, thumbY, trackX+3, thumbY+thumbH, theme.accent());
                }
            }
        } else {
            if(recent.isEmpty()) {
                text.invoke(ctx, tr, ru?"Пусто":"Empty", x+11, ty, theme.muted());
                ty+=13;
            } else for(PlayerProfile p:recent.subList(0, Math.min(7, recent.size()))) {
                drawPlayerRow(ctx, tr, fill, text, p, x+8, ty, w-16, ru);
                ty+=13;
            }
        }
        if(!searchMessage.isBlank())text.invoke(ctx, tr, ellipsize(searchMessage, 44), x+8, Math.min(y+h-13, ty), 0xFFB8F3FF);
    }
    private int notableSearchColor(String name, int fallback) {
        if(service==null||name==null)return fallback;
        PlayerProfile p=service.cachedByName(name);
        if(p==null)return fallback;
        return notableNameColor(p.player().uuid());
    }
    private static String historySummary(TierHistoryEvent e, boolean ru) {
        String kit=canonicalKit(e.gamemode());
        String old=e.oldTier()==null?"—":TierRank.normalize(e.oldTier()), nw=e.newTier()==null?"—":TierRank.normalize(e.newTier());
        long days=Math.max(0, Duration.between(Instant.ofEpochMilli(e.at()), Instant.now()).toDays());
        return (ru?"Последнее: ":"Latest: ")+kitMenuLabel(kit==null?"vanilla":kit)+"  "+old+" → "+nw+" · "+days+(ru?" д.":"d");
    }
    private void drawPlayerRow(Object ctx, Object tr, Method fill, Method text, PlayerProfile p, int x, int y, int w, boolean ru)throws Exception {
        if(p==null)return;
        boolean selected=profile!=null&&profile.player()!=null&&profile.player().uuid().equals(p.player().uuid());
        boolean over=lastMouseX>=x&&lastMouseY>=y-1&&lastMouseX<x+w&&lastMouseY<y+12;
        if(selected||over)fill.invoke(ctx, x, y-1, x+w, y+12, selected?0x4436444E:0x332A353D);
        if(selected) {
            int c=theme().accent();
            fill.invoke(ctx, x, y-1, x+2, y+12, c);
        }
        int base=notableNameColor(p.player().uuid());
        text.invoke(ctx, tr, ellipsize(p.player().name(), 24), x+8, y+2, base);
        addActionRegion(x, y-1, w, 13, "SELECT_PLAYER", p.player().name(), "");
    }
    private void drawSearchViewToggle(Method fill, Method text, Object ctx, Object tr, int x, int y, int w, int h, boolean favorites, boolean ru, Theme theme)throws Exception {
        int gap=2, allW=36, favW=Math.max(54, w-allW-gap);
        int allBg=favorites?0x66232B32:0xAA263843, favBg=favorites?0xAA433A22:0x66232B32;
        fill.invoke(ctx, x, y, x+allW, y+h, allBg);
        fill.invoke(ctx, x, y, x+2, y+h, favorites?theme.muted():theme.accent());
        drawCentered(text, ctx, tr, ru?"Все":"All", x, y, allW, h, favorites?0xFFB7C0C7:0xFFFFFFFF);
        addActionRegion(x, y, allW, h, "FAVORITES_VIEW", "search", ru?"Все игроки / недавние":"All players / recent");
        int fx=x+allW+gap;
        fill.invoke(ctx, fx, y, fx+favW, y+h, favBg);
        fill.invoke(ctx, fx, y, fx+2, y+h, favorites?0xFFFFD76A:theme.muted());
        drawCentered(text, ctx, tr, ru?"★ Избранные":"★ Favorites", fx, y, favW, h, favorites?0xFFFFD76A:0xFFC7CDD2);
        addActionRegion(fx, y, favW, h, "FAVORITES_VIEW", "favorites", ru?"Только избранные":"Favorites only");
    }
    private static boolean cursorBlinkVisible(boolean active) {
        return active&&((System.currentTimeMillis()/500L)&1L)==0L;
    }
    private int exactTextWidth(Object tr, String value) {
        if(value==null||value.isEmpty())return 0;
        Method m=hudTextWidthMethod;
        if(m!=null)try {
            Object v=m.invoke(tr, value);
            if(v instanceof Number n)return n.intValue();
        } catch (Throwable ignored) {
        }
        return approxTextWidth(value);
    }
    private int[] caretOffsets(Object tr, String value) {
        String s=value==null?"":value;
        int[] out=new int[s.length()+1];
        for(int i=1; i<=s.length(); i++)out[i]=exactTextWidth(tr, s.substring(0, i));
        return out;
    }
    private void drawAction(Method fill,
        Method text,
        Object ctx,
        Object tr,
        int x,
        int y,
        int w,
        int h,
        String label,
        String type,
        String value,
        String tooltip,
        Theme theme,
        boolean subtle)throws Exception {
        boolean over=lastMouseX>=x&&lastMouseY>=y&&lastMouseX<x+w&&lastMouseY<y+h;
        int bg=over?0x5535444E:(subtle?0x80232B32:0xAA27323B);
        fill.invoke(ctx, x, y, x+w, y+h, bg);
        fill.invoke(ctx, x, y, x+2, y+h, subtle?theme.muted():theme.accent());
        drawCentered(text, ctx, tr, ellipsize(label, Math.max(3, w/6)), x, y, w, h, 0xFFE9EEF2);
        addActionRegion(x, y, w, h, type, value, tooltip);
    }
    private synchronized void addActionRegion(int x, int y, int w, int h, String type, String value, String tooltip) {
        if(w>0&&h>0)actionRegions.add(new ActionRegion(x, y, w, h, type, value==null?"":value, tooltip==null?"":tooltip));
    }
    private synchronized void addContextTarget(int x, int y, int w, int h, PlayerProfile p) {
        if(p!=null&&w>0&&h>0)contextTargets.add(new ContextTarget(x, y, w, h, p));
    }
    private void renderContextMenu( Object context, Object client, Object textRenderer, Method fill, Method text, boolean russian) {
        PlayerProfile playerProfile;
        int anchorX;
        int anchorY;
        synchronized (this) {
            playerProfile = contextPlayer;
            anchorX = contextMenuX;
            anchorY = contextMenuY;
        }
        if (playerProfile == null) {
            return;
        }
        boolean notesEnabled = config != null && config.notesEnabled();
        boolean favorite = service != null && service.watchlisted(playerProfile.player().uuid());
        PlayerQuickMenu.Layout menu = PlayerQuickMenu.draw( context,
            textRenderer,
            anchorX,
            anchorY,
            MinecraftBridge.scaledWidth(client),
            MinecraftBridge.scaledHeight(client),
            playerProfile.player().name(),
            favorite,
            notesEnabled,
            russian,
            lastMouseX,
            lastMouseY,
            theme().accent());
        int rowY = menu.bodyY();
        for (PlayerQuickMenu.Action action : menu.actions()) {
            String type = switch (action) {
                case FAVORITE -> "WATCH_TOGGLE";
                case NOTE -> "NOTE_EDIT";
                case MESSAGE -> "MESSAGE_PLAYER";
                case COPY_NAME -> "COPY_NAME";
            };
            addActionRegion( menu.x() + 3, rowY, menu.width() - 6, PlayerQuickMenu.ROW_HEIGHT, type, playerProfile.player().uuid().toString(), "");
            rowY += PlayerQuickMenu.ROW_HEIGHT;
        }
    }
    private void renderComparePicker(Object ctx, Object client, Object tr, Method fill, Method text, boolean ru, float anim)throws Exception {
        int stage;
        String q;
        List<String>sug;
        int sel, cur, anc;
        PlayerProfile first;
        boolean favOnly;
        synchronized(this) {
            stage=comparePickerStage;
            q=searchText;
            sug=suggestions;
            sel=suggestionIndex;
            cur=cursor;
            anc=anchor;
            first=compareDraftA;
            favOnly=favoritesView;
        }
        if(stage==0)return;
        int sw=MinecraftBridge.scaledWidth(client), sh=MinecraftBridge.scaledHeight(client), w=Math.min(320, Math.max(250, sw/3)), y=6;
        Theme theme=theme();
        if(stage==2&&first!=null) {
            SingleLayoutCacheEntry pv=prepareSingleLayout(first, sw);
            if(!pv.matrix().kits().isEmpty()&&!pv.matrix().rows().isEmpty()) {
                w=pv.width();
                y=6+22+pv.bodyHeight()+5;
            }
        }
        w=Math.max(250, Math.min(w, Math.max(250, sw-12)));
        int x=Math.max(6, sw-w-6);
        List<PlayerProfile> favs=service==null?List.of():service.watchlistedProfiles(), recent=service==null?List.of():service.recentProfiles(6);
        int rows=!q.isBlank()?Math.min(6, sug.size()):Math.min(6, favOnly?favs.size():recent.size());
        int h=Math.min(sh-y-6, 61+Math.max(2, rows)*13+(searchMessage.isBlank()?0:13));
        fill.invoke(ctx, x, y, x+w, y+h, 0xF011151A);
        fill.invoke(ctx, x, y, x+w, y+2, stage==1?0xFF74C8F4:0xFFF08CBD);
        String title=stage==1?(ru?"Выбери первого игрока":"Choose first player"):(ru?"Сравнить с…":"Compare with…");
        text.invoke(ctx, tr, title, x+8, y+7, 0xFFFFFFFF);
        drawSearchViewToggle(fill, text, ctx, tr, x+w-145, y+3, 118, 16, favOnly, ru, theme);
        text.invoke(ctx, tr, "×", x+w-16, y+6, 0xFFE6EBEF);
        addActionRegion(x+w-20, y+1, 18, 20, "WORKSPACE_CLOSE", "", ru?"Закрыть TierLookup":"Close TierLookup");
        int fy=y+25;
        fill.invoke(ctx, x+8, fy-2, x+w-8, fy+16, 0xCC20262D);
        String prefix=ru?"Ник: ":"Nick: ";
        int inputX=x+12+exactTextWidth(tr, prefix);
        text.invoke(ctx, tr, prefix, x+12, fy+2, 0xFFC6CDD3);
        int[] caret=caretOffsets(tr, q);
        synchronized(this) {
            searchFieldX=x+8;
            searchFieldY=fy-2;
            searchFieldW=w-16;
            searchFieldH=18;
            searchInputX=inputX;
            searchCaretOffsets=caret;
        }
        int aa=Math.max(0, Math.min(q.length(), Math.min(cur, anc))), bb=Math.max(0, Math.min(q.length(), Math.max(cur, anc)));
        if(searchActive&&aa!=bb)fill.invoke(ctx, inputX+caret[aa], fy, inputX+caret[bb], fy+12, 0xAA3974A8);
        text.invoke(ctx, tr, q, inputX, fy+2, 0xFFFFFFFF);
        if(cursorBlinkVisible(searchActive)) {
            int ci=Math.max(0, Math.min(q.length(), cur)), cc=inputX+caret[ci];
            fill.invoke(ctx, cc, fy, cc+1, fy+12, 0xFFFFFFFF);
        }
        int ty=fy+21;
        if(!q.isBlank()) {
            if(sug.isEmpty()) {
                text.invoke(ctx, tr, ru?"Нет совпадений":"No matches", x+11, ty+1, theme.muted());
                ty+=13;
            } else for(int i=0; i<Math.min(6, sug.size()); i++) {
                String name=sug.get(i);
                boolean selected=i==sel, over=lastMouseX>=x+8&&lastMouseY>=ty-1&&lastMouseX<x+w-8&&lastMouseY<ty+12;
                if(selected||over)fill.invoke(ctx, x+8, ty-1, x+w-8, ty+12, selected?0x4435444E:0x332A353D);
                text.invoke(ctx, tr, name, x+12, ty+2, notableSearchColor(name, selected?0xFFFFFFFF:0xFFC3CBD2));
                addActionRegion(x+8, ty-1, w-16, 13, "SELECT_PLAYER", name, "");
                ty+=13;
            }
        } else {
            List<PlayerProfile> src=favOnly?favs:recent;
            if(src.isEmpty()) {
                text.invoke(ctx, tr, favOnly?(ru?"Избранное пусто":"Favorites empty"):(ru?"Недавних пока нет":"No recent players yet"), x+11, ty, theme.muted());
            } else for(PlayerProfile p:src.subList(0, Math.min(6, src.size()))) {
                drawPlayerRow(ctx, tr, fill, text, p, x+8, ty, w-16, ru);
                ty+=13;
            }
        }
        if(!searchMessage.isBlank())text.invoke(ctx, tr, ellipsize(searchMessage, 44), x+8, Math.min(y+h-14, ty), 0xFFB8F3FF);
    }
    private void renderSync(Object ctx, Object client, Object tr, Method fill, Method text, boolean ru, float anim)throws Exception {
        Theme theme=theme();
        if(!syncActive) {
            int w=Math.min(300, Math.max(220, MinecraftBridge.scaledWidth(client)/4)), h=44, sw=MinecraftBridge.scaledWidth(client), x=(sw-w)/2, y=8;
            fill.invoke(ctx, x, y, x+w, y+h, 0xEE11171C);
            fill.invoke(ctx, x, y, x+w, y+2, 0xFF8BD17C);
            text.invoke(ctx, tr, ru?"Синхронизация завершена":"Synchronization complete", x+8, y+7, 0xFFFFFFFF);
            text.invoke(ctx, tr, ellipsize(syncLine, Math.max(24, (w-16)/6)), x+8, y+21, 0xFFCFD7DE);
            return;
        }
        int w=210, h=64, targetY=panelAnim>0.15f?Math.min(MinecraftBridge.scaledHeight(client)-h-6, 12+lastMainHeight):6;
        int x=Math.round(-w-6+(w+11)*ease(anim)), y=targetY;
        fill.invoke(ctx, x, y, x+w, y+h, 0xD911151A);
        fill.invoke(ctx, x, y, x+w, y+2, 0xFF8BD17C);
        text.invoke(ctx, tr, syncTitle.isBlank()?(ru?"Синхронизация":"Sync"):syncTitle, x+7, y+7, 0xFFFFFFFF);
        String line=ellipsize(syncLine, 32);
        text.invoke(ctx, tr, line, x+7, y+20, 0xFFCFD7DE);
        String saved=(ru?"Сохранено: ":"Saved: ")+(service==null?0:service.cachedCount());
        text.invoke(ctx, tr, saved, x+7, y+33, 0xFF8FA0AD);
        drawAction(fill, text, ctx, tr, x+6, y+47, w-12, 13, ru?"■ Прервать":"■ Stop", "SYNC_CANCEL", "", ru?"Прервать синхронизацию":"Stop synchronization", theme, true);
    }
    private void renderTable(Object ctx, Object client, Object tr, Method fill, Method text, PlayerProfile a, PlayerProfile b, float anim, boolean staticWindow)throws Exception {
        if(b==null)renderSingleTable(ctx, client, tr, fill, text, a, anim, staticWindow);
        else renderCompareTable(ctx, client, tr, fill, text, a, b, anim, staticWindow);
    }
    private void renderSingleTable( Object context,
        Object client,
        Object textRenderer,
        Method fill,
        Method text,
        PlayerProfile playerProfile,
        float animation,
        boolean staticWindow) throws Exception {
        int screenWidth = MinecraftBridge.scaledWidth(client);
        int screenHeight = MinecraftBridge.scaledHeight(client);
        int columnWidth = CELL_W;
        SingleLayoutCacheEntry prepared = prepareSingleLayout(playerProfile, screenWidth);
        TierMatrix matrix = prepared.matrix();
        if (matrix.kits().isEmpty() || matrix.rows().isEmpty()) {
            return;
        }
        List<String> visibleKits = overlayVisibleKits(matrix.kits(), staticWindow);
        if (visibleKits.isEmpty()) {
            return;
        }
        boolean showScrollbar = staticWindow && visibleKits.size() < matrix.kits().size();
        int rowNameWidth = staticWindow ? Math.max(96, prepared.rowNameW()) : prepared.rowNameW();
        int rowHeight = 13;
        List<MatrixBand> bands = List.of( new MatrixBand(0, visibleKits.size(), visibleKits.size() * columnWidth));
        int headerHeight = staticWindow ? 18 : 16;
        int scrollbarHeight = showScrollbar ? 14 : 0;
        String note = config.notesEnabled() && service != null ? service.playerNoteForDisplay(playerProfile.player().uuid(), playerProfile.player().name()) : "";
        int noteHeight = notePanel.height(playerProfile.player().uuid(), note);
        int matrixHeight = matrixBodyHeight(bands, matrix.rows().size(), rowHeight);
        int width = matrixWidth(bands, rowNameWidth);
        int height = Math.min( headerHeight + matrixHeight + scrollbarHeight + noteHeight, Math.max(44, screenHeight - 12));
        int targetX = Math.max(6, screenWidth - width - 6);
        int x = Math.round(screenWidth + 4 - (screenWidth + 4 - targetX) * ease(animation));
        int y = topRightTableY(client, screenHeight, height, 6);
        Theme theme = theme();
        int accent = notableAccent(playerProfile.player().uuid(), theme.accent());
        int nameColor = notableNameColor(playerProfile.player().uuid());
        fill.invoke(context, x, y, x + width, y + height, theme.bg());
        drawLifetimeBar(fill, context, x, y, width, accent, staticWindow);
        if (staticWindow && open) {
            addContextTarget(x, y, width, height, playerProfile);
        }
        if (staticWindow) {
            int closeX = x + width - 18;
            int pinX = closeX - 18;
            int iconY = y + 2;
            boolean showCompare = open && compareProfile == null && comparePickerStage == 0;
            int compareX = pinX - 18;
            if (showCompare) {
                boolean hovered = lastMouseX >= compareX && lastMouseX < compareX + 16 && lastMouseY >= iconY && lastMouseY < iconY + 16;
                TierUi.hoverBackground(context, compareX, iconY, 16, 16, hovered);
                TierUi.compareGlyph(context, compareX + 3, iconY + 3, 0xFFD8E2E8);
                addActionRegion( compareX, iconY, 16, 16, "COMPARE_START", "", MinecraftBridge.russian(client) ? "Сравнить" : "Compare");
            }
            drawPinGlyph(fill, context, pinX, iconY, windowPinned ? 0xFFFFFFFF : 0xFFD5DDE3);
            drawCloseGlyph(fill, context, closeX, iconY, 0xFFAAB4BC);
            if (open) {
                addActionRegion(pinX, iconY, 16, 16, "PIN_TOGGLE", playerProfile.player().uuid().toString(), "");
                addActionRegion(closeX, iconY, 16, 16, "WINDOW_CLOSE", playerProfile.player().uuid().toString(), "");
            }
        } else {
            text.invoke(context, textRenderer, playerProfile.player().name(), x + 6, y + 5, nameColor);
        }
        int matrixY = y + headerHeight;
        int matrixBottom = y + height - scrollbarHeight - noteHeight;
        int nextY = renderMatrixBodySubset( context,
            client,
            textRenderer,
            fill,
            text,
            matrix,
            visibleKits,
            x,
            matrixY,
            matrixBottom,
            rowNameWidth,
            columnWidth,
            rowHeight,
            bands,
            true,
            false,
            0xFFB8F3FF,
            staticWindow ? playerProfile : null,
            theme);
        if (showScrollbar) {
            drawStaticKitScrollbar( context, textRenderer, fill, text, x, nextY, width, theme, accent, matrix.kits().size(), visibleKits.size());
        }
        if (noteHeight > 0) {
            notePanel.draw( context, textRenderer, x, y + height - noteHeight, width, playerProfile.player().uuid(), note);
        }
    }
    public TabPreviewBounds renderTabPreview(Object ctx, Object client, PlayerProfile p, int x, int y) {
        if(ctx==null||client==null||p==null)return null;
        try {
            Object tr=MinecraftBridge.textRenderer(client);
            if(tr==null)return null;
            Method fill=ctx.getClass().getMethod("method_25294",
                int.class,
                int.class,
                int.class,
                int.class,
                int.class),
                text=ctx.getClass().getMethod("method_25303",
                tr.getClass(),
                String.class,
                int.class,
                int.class,
                int.class);
            TierMatrix matrix=buildMatrixCached(p, false);
            if(matrix.kits().isEmpty()||matrix.rows().isEmpty())return null;
            List<String> kits=matrix.kits().subList(0, Math.min(8, matrix.kits().size()));
            int rowNameW=Math.max(58, matrixRowNameWidth(matrix)), rowH=13, colW=CELL_W;
            String note = (config.notesEnabled() && service != null) ? service.playerNoteForDisplay(p.player().uuid(), p.player().name()) : "";
            int noteH = notePanel.height(p.player().uuid(), note);
            List<MatrixBand> bands=List.of(new MatrixBand(0, kits.size(), kits.size()*colW));
            int body=matrixBodyHeight(bands, matrix.rows().size(), rowH);
            int w=matrixWidth(bands, rowNameW), h=18+body+noteH;
            int sw=MinecraftBridge.scaledWidth(client), sh=MinecraftBridge.scaledHeight(client);
            x=Math.max(4, Math.min(sw-w-4, x));
            y=Math.max(4, Math.min(sh-h-4, y));
            Theme theme=theme();
            fill.invoke(ctx, x, y, x+w, y+h, theme.bg());
            fill.invoke(ctx, x, y, x+w, y+2, notableAccent(p.player().uuid(), theme.accent()));
            text.invoke(ctx, tr, ellipsize(p.player().name(), Math.max(8, (w-10)/6)), x+5, y+6, notableNameColor(p.player().uuid()));
            int gy=y+18;
            renderMatrixBodySubset(ctx, client, tr, fill, text, matrix, kits, x, gy, y+h-noteH, rowNameW, colW, rowH, bands, true, false, 0xFFB8F3FF, null, theme);
            if (noteH > 0) {
                notePanel.draw(ctx, tr, x, y + h - noteH, w, p.player().uuid(), note);
            }
            return new TabPreviewBounds(x, y, w, h);
        } catch (Throwable t) {
            return null;
        }
    }
    private int renderMatrixBody(Object ctx,
        Object client,
        Object tr,
        Method fill,
        Method text,
        TierMatrix matrix,
        int x,
        int gy,
        int bottom,
        int rowNameW,
        int colW,
        int rowH,
        List<MatrixBand> bands,
        boolean tierDots,
        boolean providerDots,
        int tierColor,
        PlayerProfile cornerProfile,
        Theme theme)throws Exception {
        for(int bi=0; bi<bands.size(); bi++) {
            MatrixBand band=bands.get(bi);
            int bandW=rowNameW+band.width();
            if(gy+KIT_HEADER_H>bottom)break;
            fill.invoke(ctx, x, gy, x+bandW, gy+KIT_HEADER_H, 0xEE20262D);
            fill.invoke(ctx, x, gy, x+rowNameW, gy+KIT_HEADER_H, 0xEE252C33);
            if(bi==0&&cornerProfile!=null)drawProfileCorner(ctx, client, tr, text, cornerProfile, x, gy, rowNameW, KIT_HEADER_H, theme);
            int cx=x+rowNameW;
            for(int i=band.from(); i<band.to(); i++) {
                String kit=matrix.kits().get(i);
                if(!KitIconRenderer.draw(ctx, kit, cx+(colW-16)/2, gy+1))drawCentered(text, ctx, tr, "?", cx, gy, colW, KIT_HEADER_H, 0xFFCED6DD);
                cx+=colW;
            }
            gy+=KIT_HEADER_H;
            int rowIndex=0;
            for(MatrixRow row:matrix.rows()) {
                if(gy+rowH>bottom)break;
                int rowBg=(rowIndex++&1)==0?0xAA1B2025:0xAA1E242A;
                fill.invoke(ctx, x, gy, x+bandW, gy+rowH, rowBg);
                fill.invoke(ctx, x, gy, x+rowNameW, gy+rowH, 0x551E252B);
                if(providerDots)drawProviderDot(fill, ctx, x+rowNameW/2-1, gy+1, row.fetchedAt());
                int nameY=gy+(providerDots?6:Math.max(1, (rowH-9)/2));
                text.invoke(ctx, tr, row.name(), x+4, nameY, 0xFFE8ECEF);
                cx=x+rowNameW;
                for(int i=band.from(); i<band.to(); i++) {
                    TierCell cell=row.cells().get(matrix.kits().get(i));
                    if(cell!=null) {
                        if(tierDots)drawTierTestDot(fill, ctx, cx+colW-5, gy+2, cell.lastTestAt());
                        drawCentered(text, ctx, tr, cellText(cell), cx, gy, colW, rowH, tierColor);
                    }
                    cx+=colW;
                }
                gy+=rowH;
            }
            if(bi+1<bands.size())gy+=2;
        }
        return gy;
    }
    private int renderMatrixBodySubset(Object ctx,
        Object client,
        Object tr,
        Method fill,
        Method text,
        TierMatrix matrix,
        List<String> kits,
        int x,
        int gy,
        int bottom,
        int rowNameW,
        int colW,
        int rowH,
        List<MatrixBand> bands,
        boolean tierDots,
        boolean providerDots,
        int tierColor,
        PlayerProfile cornerProfile,
        Theme theme)throws Exception {
        for(int bi=0; bi<bands.size(); bi++) {
            MatrixBand band=bands.get(bi);
            int bandW=rowNameW+band.width();
            if(gy+KIT_HEADER_H>bottom)break;
            fill.invoke(ctx, x, gy, x+bandW, gy+KIT_HEADER_H, 0xEE20262D);
            fill.invoke(ctx, x, gy, x+rowNameW, gy+KIT_HEADER_H, 0xEE252C33);
            if(bi==0&&cornerProfile!=null)drawProfileCorner(ctx, client, tr, text, cornerProfile, x, gy, rowNameW, KIT_HEADER_H, theme);
            int cx=x+rowNameW;
            for(int i=band.from(); i<band.to(); i++) {
                String kit=kits.get(i);
                if(!KitIconRenderer.draw(ctx, kit, cx+(colW-16)/2, gy+1))drawCentered(text, ctx, tr, "?", cx, gy, colW, KIT_HEADER_H, 0xFFCED6DD);
                cx+=colW;
            }
            gy+=KIT_HEADER_H;
            int rowIndex=0;
            for(MatrixRow row:matrix.rows()) {
                if(gy+rowH>bottom)break;
                int rowBg=(rowIndex++&1)==0?0xAA1B2025:0xAA1E242A;
                fill.invoke(ctx, x, gy, x+bandW, gy+rowH, rowBg);
                fill.invoke(ctx, x, gy, x+rowNameW, gy+rowH, 0x551E252B);
                if(providerDots)drawProviderDot(fill, ctx, x+rowNameW/2-1, gy+1, row.fetchedAt());
                int nameY=gy+(providerDots?6:Math.max(1, (rowH-9)/2));
                text.invoke(ctx, tr, row.name(), x+4, nameY, 0xFFE8ECEF);
                cx=x+rowNameW;
                for(int i=band.from(); i<band.to(); i++) {
                    TierCell cell=row.cells().get(kits.get(i));
                    if(cell!=null) {
                        if(tierDots)drawTierTestDot(fill, ctx, cx+colW-5, gy+2, cell.lastTestAt());
                        drawCentered(text, ctx, tr, cellText(cell), cx, gy, colW, rowH, tierColor);
                    }
                    cx+=colW;
                }
                gy+=rowH;
            }
            if(bi+1<bands.size())gy+=2;
        }
        return gy;
    }
    private List<String> overlayVisibleKits(List<String> kits, boolean staticWindow) {
        if(kits==null||kits.isEmpty())return List.of();
        int limit=staticWindow?STATIC_VISIBLE_KITS:DYNAMIC_VISIBLE_KITS;
        if(kits.size()<=limit) {
            staticKitOffset=0;
            staticScrollTrackX=-1;
            staticKitDragging=false;
            return List.copyOf(kits);
        }
        if(!staticWindow) {
            staticScrollTrackX=-1;
            staticKitDragging=false;
            return List.copyOf(kits.subList(0, Math.min(limit, kits.size())));
        }
        int maxOffset=Math.max(0, kits.size()-limit);
        staticKitOffset=Math.max(0, Math.min(staticKitOffset, maxOffset));
        return List.copyOf(kits.subList(staticKitOffset, Math.min(kits.size(), staticKitOffset+limit)));
    }
    private void drawLifetimeBar(Method fill, Object ctx, int x, int y, int w, int accent, boolean staticWindow)throws Exception {
        int base=0xEE1A2026;
        fill.invoke(ctx, x, y, x+w, y+3, base);
        int barW=staticWindow?w:Math.max(0, Math.min(w, Math.round(w*dynamicTableLifeProgress())));
        if(barW>0)fill.invoke(ctx, x, y, x+barW, y+3, accent);
    }
    private float dynamicTableLifeProgress() {
        long holdMs=Math.max(1000L, config.tableHoldSeconds()*1000L);
        long now=System.currentTimeMillis();
        if(hoverUuid!=null&&hoverProfile!=null&&now-hoverLastSeenAt<150L)return 1f;
        float p=1f-((float)(now-hoverLastSeenAt)/(float)holdMs);
        return Math.max(0f, Math.min(1f, p));
    }
    private void drawStaticKitScrollbar(Object ctx,
        Object tr,
        Method fill,
        Method text,
        int x,
        int y,
        int w,
        Theme theme,
        int accent,
        int totalKits,
        int visibleKits)throws Exception {
        int sy=y+1, trackX=x+8, trackW=Math.max(32, w-16);
        fill.invoke(ctx, trackX, sy+3, trackX+trackW, sy+7, 0xCC141B21);
        int maxOffset=Math.max(0, totalKits-visibleKits), knobW=Math.max(20, Math.round(trackW*(visibleKits/(float)Math.max(1, totalKits))));
        knobW=Math.min(trackW, knobW);
        int travel=Math.max(0, trackW-knobW);
        int knobX=trackX+(maxOffset<=0?0:Math.round(travel*(staticKitOffset/(float)maxOffset)));
        fill.invoke(ctx, knobX, sy+1, knobX+knobW, sy+9, accent);
        staticScrollTrackX=trackX;
        staticScrollTrackY=sy;
        staticScrollTrackW=trackW;
        staticScrollKnobX=knobX;
        staticScrollKnobW=knobW;
        staticScrollTotalKits=totalKits;
        staticScrollVisibleKits=visibleKits;
    }
    private void drawProfileCorner(Object ctx, Object client, Object tr, Method text, PlayerProfile p, int x, int y, int w, int h, Theme theme)throws Exception {
        Method fill=hudFillMethod;
        int nx=x+3;
        if("head".equals(config.skinMode())) {
            KitIconRenderer.drawPlayerHead(ctx, p.player(), x+2, y+1);
            nx=x+21;
        }
        boolean fav=service!=null&&service.watchlisted(p.player().uuid());
        String star=fav?"★":"☆";
        text.invoke(ctx, tr, star, nx, y+Math.max(1, (h-9)/2), fav?0xFFFFD76A:theme.muted());
        if(open)addActionRegion(nx-1, y, 12, h, "WATCH_TOGGLE", p.player().uuid().toString(), "");
        nx+=12;
        int room=Math.max(18, w-(nx-x)-3);
        String name=ellipsize(p.player().name(), Math.max(3, room/6));
        int nw=Math.min(room, exactTextWidth(tr, name)+6);
        boolean over=open&&lastMouseX>=nx-2&&lastMouseY>=y&&lastMouseX<nx+nw&&lastMouseY<y+h;
        if(over&&fill!=null)fill.invoke(ctx, nx-2, y, nx+nw, y+h, 0x332A353D);
        text.invoke(ctx, tr, name, nx, y+Math.max(1, (h-9)/2), notableNameColor(p.player().uuid()));
        if(open)addActionRegion(nx-2, y, nw+2, h, "PROFILE_NAME_CLICK", p.player().uuid().toString(), "");
    }
    private static int matrixBodyHeight(List<MatrixBand> bands, int rows, int rowH) {
        return bands.size()*(KIT_HEADER_H+rows*rowH)+Math.max(0, bands.size()-1)*2+2;
    }
    private record BestTier(String providerId, String providerName, String kit, TierCell cell, int rawScore) {
    }
    private void renderCompareTable(Object ctx,
        Object client,
        Object tr,
        Method fill,
        Method text,
        PlayerProfile a,
        PlayerProfile b,
        float anim,
        boolean staticWindow)throws Exception {
        String mode=config.experimentalCompareMode();
        if("max".equals(mode)) {
            renderMaxTierCompare(ctx, client, tr, fill, text, a, b, anim, staticWindow);
            return;
        }
        TierMatrix left=buildMatrixCached(a, false), right=buildMatrixCached(b, true);
        LinkedHashSet<String> presentKits=new LinkedHashSet<>();
        ArrayList<String> rowIds=new ArrayList<>();
        Map<String, MatrixRow> leftRows=rowsById(left), rightRows=rowsById(right);
        boolean player1Mask="player1".equals(mode);
        if(player1Mask) {
            for(String k:left.kits())presentKits.add(k);
            for(String id:config.providerOrder())if(leftRows.containsKey(id))rowIds.add(id);
        } else {
            for(String k:left.kits())presentKits.add(k);
            for(String k:right.kits())presentKits.add(k);
            for(String id:config.providerOrder())if(leftRows.containsKey(id)||rightRows.containsKey(id))rowIds.add(id);
        }
        ArrayList<String> kits=new ArrayList<>();
        for(String k:config.kitOrder())if(presentKits.contains(k))kits.add(k);
        if(kits.isEmpty()||rowIds.isEmpty())return;
        boolean tierDots=true, providerDots=false;
        boolean corner=staticWindow, controls=staticWindow;
        int sw=MinecraftBridge.scaledWidth(client),
            sh=MinecraftBridge.scaledHeight(client),
            rowNameW=comparePairRowNameWidth(leftRows,
            rightRows,
            rowIds),
            colW=CELL_W,
            rowH=13,
            gap=3;
        if(corner)rowNameW=Math.max(96, rowNameW);
        int headerH=staticWindow?(corner?(controls?18:2):20):16;
        List<MatrixBand> bands=matrixBands(kits.size(), rowNameW, Math.max(120, Math.min(sw-12, 500)), colW);
        int panelW=matrixWidth(bands, rowNameW), bodyH=matrixBodyHeight(bands, rowIds.size(), rowH), panelH=Math.min(headerH+bodyH, Math.max(38, (sh-15)/2));
        int x=Math.round(sw+4-(sw+4-Math.max(6, sw-panelW-6))*ease(anim)), y=6;
        renderComparePlayerPanel(ctx,
            client,
            tr,
            fill,
            text,
            a,
            true,
            x,
            y,
            panelW,
            panelH,
            headerH,
            rowNameW,
            colW,
            rowH,
            bands,
            kits,
            rowIds,
            leftRows,
            rightRows,
            tierDots,
            providerDots,
            staticWindow,
            player1Mask,
            corner,
            controls);
        renderComparePlayerPanel(ctx,
            client,
            tr,
            fill,
            text,
            b,
            false,
            x,
            y+panelH+gap,
            panelW,
            panelH,
            headerH,
            rowNameW,
            colW,
            rowH,
            bands,
            kits,
            rowIds,
            rightRows,
            leftRows,
            tierDots,
            providerDots,
            staticWindow,
            player1Mask,
            corner,
            controls);
    }
    /** Player-1 mask keeps every visual signal, but player two is only allowed to answer cells that exist for player one. */
    private void renderComparePlayerPanel(Object ctx,
        Object client,
        Object tr,
        Method fill,
        Method text,
        PlayerProfile playerProfile,
        boolean first,
        int x,
        int y,
        int w,
        int h,
        int headerH,
        int rowNameW,
        int colW,
        int rowH,
        List<MatrixBand> bands,
        List<String> kits,
        List<String> rowIds,
        Map<String,
        MatrixRow> ownRows,
        Map<String,
        MatrixRow> otherRows,
        boolean tierDots,
        boolean providerDots,
        boolean staticWindow,
        boolean player1Mask,
        boolean corner,
        boolean controls)throws Exception {
        PlayerIdentity player=playerProfile.player();
        Theme theme=theme();
        int accent=notableAccent(player.uuid(), first?0xFF74C8F4:0xFFF08CBD), nameColor=notableNameColor(player.uuid());
        fill.invoke(ctx, x, y, x+w, y+h, 0xF011151A);
        fill.invoke(ctx, x, y, x+w, y+2, accent);
        if(staticWindow&&!corner) {
            int nameX=x+4;
            if("head".equals(config.skinMode())) {
                KitIconRenderer.drawPlayerHead(ctx, player, x+3, y+2);
                nameX=x+22;
            }
            boolean fav=service!=null&&service.watchlisted(player.uuid());
            text.invoke(ctx, tr, fav?"★":"☆", nameX, y+5, fav?0xFFFFD76A:theme.muted());
            if(open)addActionRegion(nameX-1,
                y+2,
                12,
                16,
                "WATCH_TOGGLE",
                player.uuid().toString(),
                fav?(MinecraftBridge.russian(client)?"Убрать из избранного":"Remove from favorites"):(MinecraftBridge.russian(client)?"Добавить в избранное":"Add to favorites"));
            nameX+=12;
            text.invoke(ctx, tr, ellipsize(player.name(), Math.max(6, (w-(nameX-x)-(controls?40:4))/6)), nameX, y+5, nameColor);
        } else if(!staticWindow)text.invoke(ctx, tr, ellipsize(player.name(), Math.max(6, (w-12)/6)), x+4, y+5, nameColor);
        if(controls) {
            int closeX=x+w-18, pinX=closeX-18, iy=y+2;
            drawPinGlyph(fill, ctx, pinX, iy, windowPinned?0xFFFFFFFF:0xFFD5DDE3);
            drawCloseGlyph(fill, ctx, closeX, iy, 0xFFAAB4BC);
            if(open) {
                addActionRegion(pinX, iy, 16, 16, "PIN_TOGGLE", player.uuid().toString(), "");
                addActionRegion(closeX, iy, 16, 16, "WINDOW_CLOSE", player.uuid().toString(), "");
            }
        }
        int gy=y+headerH;
        for(int bi=0; bi<bands.size(); bi++) {
            MatrixBand band=bands.get(bi);
            int bandW=rowNameW+band.width();
            if(gy+KIT_HEADER_H>y+h)break;
            fill.invoke(ctx, x, gy, x+bandW, gy+KIT_HEADER_H, 0xEE20262D);
            fill.invoke(ctx, x, gy, x+rowNameW, gy+KIT_HEADER_H, 0xEE252C33);
            if(corner&&bi==0)drawProfileCorner(ctx, client, tr, text, playerProfile, x, gy, rowNameW, KIT_HEADER_H, theme);
            int cx=x+rowNameW;
            for(int i=band.from(); i<band.to(); i++) {
                if(!KitIconRenderer.draw(ctx, kits.get(i), cx+(colW-16)/2, gy+1))drawCentered(text, ctx, tr, "?", cx, gy, colW, KIT_HEADER_H, 0xFFCED6DD);
                cx+=colW;
            }
            gy+=KIT_HEADER_H;
            int ri=0;
            for(String id:rowIds) {
                if(gy+rowH>y+h)break;
                MatrixRow own=ownRows.get(id), other=otherRows.get(id), label=own!=null?own:other;
                fill.invoke(ctx, x, gy, x+bandW, gy+rowH, (ri++&1)==0?0xB51A2025:0xB51D2329);
                if(providerDots&&own!=null)drawProviderDot(fill, ctx, x+rowNameW/2-1, gy+1, own.fetchedAt());
                text.invoke(ctx, tr, label==null?id:label.name(), x+4, gy+(providerDots?5:Math.max(1, (rowH-9)/2)), own==null?0xFF6F7880:0xFFE5EAEE);
                cx=x+rowNameW;
                for(int i=band.from(); i<band.to(); i++) {
                    String kit=kits.get(i);
                    TierCell oc=own==null?null:own.cells().get(kit);
                    MatrixRow otherRow=otherRows.get(id);
                    TierCell tc=otherRow==null?null:otherRow.cells().get(kit);
                    if(player1Mask&&!first&&tc==null)oc=null;
                    if(oc!=null||tc!=null)fill.invoke(ctx, cx+1, gy+1, cx+colW-1, gy+rowH-1, comparePlayerCellColor(oc, tc, first));
                    if(oc!=null) {
                        if(tierDots)drawTierTestDot(fill, ctx, cx+colW-5, gy+2, oc.lastTestAt());
                        drawCentered(text, ctx, tr, cellText(oc), cx, gy, colW, rowH, nameColor);
                    }
                    cx+=colW;
                }
                gy+=rowH;
            }
            if(bi+1<bands.size())gy+=2;
        }
    }
    /**
    * Categorical max compare: provider rows are collapsed independently for every kit.
    * For each kit we choose the strongest stored tier from any enabled tierlist, then compare the two players.
    */ private void renderMaxTierCompare(Object ctx,
        Object client,
        Object tr,
        Method fill,
        Method text,
        PlayerProfile a,
        PlayerProfile b,
        float anim,
        boolean staticWindow)throws Exception {
        Map<String, BestTier> aa=bestTiersByKit(a), bb=bestTiersByKit(b);
        LinkedHashSet<String> present=new LinkedHashSet<>();
        present.addAll(aa.keySet());
        present.addAll(bb.keySet());
        ArrayList<String> kits=new ArrayList<>();
        for(String k:config.kitOrder())if(present.contains(k))kits.add(k);
        for(String k:present)if(!kits.contains(k))kits.add(k);
        if(kits.isEmpty())return;
        boolean corner=staticWindow, controls=staticWindow;
        int sw=MinecraftBridge.scaledWidth(client),
            sh=MinecraftBridge.scaledHeight(client),
            colW=31,
            nameW=corner?96:54,
            headerH=staticWindow?(corner?(controls?18:2):20):16,
            sourceH=10,
            tierH=14,
            gap=3;
        List<MatrixBand> bands=matrixBands(kits.size(), nameW, Math.max(120, Math.min(sw-12, 520)), colW);
        int panelW=matrixWidth(bands, nameW), bodyH=0;
        for(MatrixBand ignored:bands)bodyH+=KIT_HEADER_H+sourceH+tierH+2;
        bodyH+=Math.max(0, bands.size()-1)*2;
        int panelH=Math.min(headerH+bodyH, Math.max(48, (sh-15)/2));
        int x=Math.round(sw+4-(sw+4-Math.max(6, sw-panelW-6))*ease(anim)), y=6;
        renderMaxByKitPanel(ctx, client, tr, fill, text, a, aa, bb, true, x, y, panelW, panelH, headerH, nameW, colW, bands, kits, sourceH, tierH, staticWindow, corner, controls);
        renderMaxByKitPanel(ctx,
            client,
            tr,
            fill,
            text,
            b,
            bb,
            aa,
            false,
            x,
            y+panelH+gap,
            panelW,
            panelH,
            headerH,
            nameW,
            colW,
            bands,
            kits,
            sourceH,
            tierH,
            staticWindow,
            corner,
            controls);
    }
    private void renderMaxByKitPanel(Object ctx,
        Object client,
        Object tr,
        Method fill,
        Method text,
        PlayerProfile p,
        Map<String,
        BestTier> own,
        Map<String,
        BestTier> other,
        boolean first,
        int x,
        int y,
        int w,
        int h,
        int headerH,
        int nameW,
        int colW,
        List<MatrixBand> bands,
        List<String> kits,
        int sourceH,
        int tierH,
        boolean staticWindow,
        boolean corner,
        boolean controls)throws Exception {
        Theme theme=theme();
        int accent=notableAccent(p.player().uuid(), first?0xFF74C8F4:0xFFF08CBD), nameColor=notableNameColor(p.player().uuid());
        fill.invoke(ctx, x, y, x+w, y+h, 0xF011151A);
        fill.invoke(ctx, x, y, x+w, y+2, accent);
        if(staticWindow&&open)addContextTarget(x, y, w, h, p);
        if(staticWindow&&!corner) {
            int nameX=x+4;
            if("head".equals(config.skinMode())) {
                KitIconRenderer.drawPlayerHead(ctx, p.player(), x+3, y+2);
                nameX=x+22;
            }
            boolean fav=service!=null&&service.watchlisted(p.player().uuid());
            text.invoke(ctx, tr, fav?"★":"☆", nameX, y+5, fav?0xFFFFD76A:theme.muted());
            if(open)addActionRegion(nameX-1, y+2, 12, 16, "WATCH_TOGGLE", p.player().uuid().toString(), fav?"Remove from favorites":"Add to favorites");
            nameX+=12;
            text.invoke(ctx, tr, ellipsize(p.player().name(), Math.max(6, (w-(nameX-x)-(controls?40:4))/6)), nameX, y+5, nameColor);
        } else if(!staticWindow)text.invoke(ctx, tr, ellipsize(p.player().name(), Math.max(6, (w-12)/6)), x+4, y+5, nameColor);
        if(controls) {
            int closeX=x+w-18, pinX=closeX-18, iy=y+2;
            drawPinGlyph(fill, ctx, pinX, iy, windowPinned?0xFFFFFFFF:0xFFD5DDE3);
            drawCloseGlyph(fill, ctx, closeX, iy, 0xFFAAB4BC);
            if(open) {
                addActionRegion(pinX, iy, 16, 16, "PIN_TOGGLE", p.player().uuid().toString(), "");
                addActionRegion(closeX, iy, 16, 16, "WINDOW_CLOSE", p.player().uuid().toString(), "");
            }
        }
        int gy=y+headerH;
        for(int bi=0; bi<bands.size(); bi++) {
            MatrixBand band=bands.get(bi);
            int bandW=nameW+band.width();
            if(gy+KIT_HEADER_H+sourceH+tierH>y+h)break;
            fill.invoke(ctx, x, gy, x+bandW, gy+KIT_HEADER_H, 0xEE20262D);
            fill.invoke(ctx, x, gy, x+nameW, gy+KIT_HEADER_H, 0xEE252C33);
            if(corner&&bi==0)drawProfileCorner(ctx, client, tr, text, p, x, gy, nameW, KIT_HEADER_H, theme);
            else text.invoke(ctx, tr, "MAX", x+5, gy+5, theme.muted());
            int cx=x+nameW;
            for(int i=band.from(); i<band.to(); i++) {
                String kit=kits.get(i);
                if(!KitIconRenderer.draw(ctx, kit, cx+(colW-16)/2, gy+1))drawCentered(text, ctx, tr, "?", cx, gy, colW, KIT_HEADER_H, 0xFFCED6DD);
                cx+=colW;
            }
            gy+=KIT_HEADER_H;
            fill.invoke(ctx, x, gy, x+bandW, gy+sourceH, 0xA51B2025);
            text.invoke(ctx, tr, "SRC", x+5, gy+1, 0xFF7E8992);
            cx=x+nameW;
            for(int i=band.from(); i<band.to(); i++) {
                BestTier v=own.get(kits.get(i));
                if(v!=null)drawCentered(text, ctx, tr, providerShort(v.providerId()), cx, gy, colW, sourceH, 0xFF8E9AA4);
                cx+=colW;
            }
            gy+=sourceH;
            fill.invoke(ctx, x, gy, x+bandW, gy+tierH, 0xB51E242A);
            text.invoke(ctx, tr, "TIER", x+5, gy+3, 0xFFB6C0C8);
            cx=x+nameW;
            for(int i=band.from(); i<band.to(); i++) {
                String kit=kits.get(i);
                BestTier v=own.get(kit), o=other.get(kit);
                if(v!=null||o!=null)fill.invoke(ctx, cx+1, gy+1, cx+colW-1, gy+tierH-1, maxCategoryColor(v, o, first));
                if(v!=null)drawCentered(text, ctx, tr, cellText(v.cell()), cx, gy, colW, tierH, nameColor);
                cx+=colW;
            }
            gy+=tierH+2;
            if(bi+1<bands.size())gy+=2;
        }
    }
    private int maxCategoryColor(BestTier own, BestTier other, boolean first) {
        if(own==null)return 0xA51B2025;
        if(other==null)return 0xB5333940;
        int cmp=TierComparison.compareTier(own.cell().tier(), other.cell().tier());
        if(cmp==0) {
            if(own.cell().retired()!=other.cell().retired())return 0xD08A7429;
            return first?0xB51B3544:0xB5452338;
        }
        return cmp>0?0xD02F7844:0xD07B3434;
    }
    private static String providerShort(String id) {
        return switch(id) {
            case "mctiers"->"MCT";
            case "pvptiers"->"PVP";
            case "subtiers"->"SUB";
            case "flowpvp"->"FLW";
            case "cistiers"->"CIS";
            case "atiers"->"ATI";
            case "mytiers"->"MYT";
            case "centraltierlist"->"CTL";
            default->id==null?"?":id.substring(0, Math.min(3, id.length())).toUpperCase(Locale.ROOT);
        };
    }
    private Map<String, BestTier> bestTiersByKit(PlayerProfile p) {
        LinkedHashMap<String, BestTier> out=new LinkedHashMap<>();
        for(var e:TierSelection.bestByKit(p, config).entrySet()) {
            TierSelection.Best b=e.getValue();
            TierCell cell=new TierCell(b.tier(), b.retired(), b.fetchedAt(), b.lastTestAt());
            out.put(e.getKey(), new BestTier(b.providerId(), b.providerName(), b.kit(), cell, TierSelection.tierScore(b.tier())));
        }
        return out;
    }
    private static Map<String, MatrixRow> rowsById(TierMatrix matrix) {
        LinkedHashMap<String, MatrixRow> out=new LinkedHashMap<>();
        for(MatrixRow r:matrix.rows())out.put(r.id(), r);
        return out;
    }
    private static int comparePairRowNameWidth(Map<String, MatrixRow> a, Map<String, MatrixRow> b, List<String> ids) {
        int w=48;
        for(String id:ids) {
            MatrixRow r=a.get(id);
            if(r==null)r=b.get(id);
            if(r!=null)w=Math.max(w, 8+approxTextWidth(r.name()));
        }
        return Math.min(110, w);
    }
    /** Green/red is only a per-cell comparison hint; it never hides either player's actual tier. */
    private int comparePlayerCellColor(TierCell own, TierCell other, boolean first) {
        int missing=0xA51B2025, onlyOne=0xB5333940, neutral=first?0xB51B3544:0xB5452338, yellow=0xD08A7429, green=0xD02F7844, red=0xD07B3434;
        String state=TierComparison.cellState(own==null?null:own.tier(), own!=null&&own.retired(), other==null?null:other.tier(), other!=null&&other.retired());
        return switch(state) {
            case "missing-own"->missing;
            case "missing-other"->onlyOne;
            case "retired-mismatch"->yellow;
            case "better"->green;
            case "worse"->red;
            default->neutral;
        };
    }
    /** Stable top-right anchor: the table never jumps to screen center because of scoreboard heuristics. */
    private int topRightTableY(Object client, int screenH, int tableH, int baseY) {
        return Math.max(6, baseY);
    }
    /** Compact monochrome window glyphs used by the visible Pin / Close controls. */
    private static void px(Method fill, Object ctx, int x, int y, int w, int h, int color)throws Exception {
        fill.invoke(ctx, x, y, x+w, y+h, color);
    }
    private static void drawPinGlyph(Method fill, Object ctx, int x, int y, int color)throws Exception {
        px(fill, ctx, x+5, y+3, 6, 2, color);
        px(fill, ctx, x+6, y+5, 4, 4, color);
        px(fill, ctx, x+4, y+8, 8, 2, color);
        px(fill, ctx, x+7, y+10, 2, 4, color);
        px(fill, ctx, x+8, y+14, 1, 1, color);
    }
    private static void drawCloseGlyph(Method fill, Object ctx, int x, int y, int color)throws Exception {
        for(int i=0; i<8; i++) {
            px(fill, ctx, x+4+i, y+4+i, 1, 1, color);
            px(fill, ctx, x+11-i, y+4+i, 1, 1, color);
        }
    }
    private int notableNameColor(UUID id) {
        if(service==null)return 0xFFFFFFFF;
        NotableStatus n=service.primaryNotable(id);
        if(n==null)return 0xFFE8ECEF;
        return n.type()==NotableStatus.Type.CREATOR?0xFFFF91C8:0xFFFFD56B;
    }
    private int notableAccent(UUID id, int fallback) {
        if(service==null)return fallback;
        NotableStatus n=service.primaryNotable(id);
        if(n==null)return fallback;
        return n.type()==NotableStatus.Type.CREATOR?0xFFFF6DB4:0xFFFFC94F;
    }
    private void renderDetectedKit(Object ctx, Object client, Object tr, Method fill, Method text, boolean ru, float visible, float expand)throws Exception {
        if(!MinecraftBridge.hasWorld(client)||KitDetector.UNKNOWN.equals(detectedKit))return;
        String kit=canonicalKit(detectedKit);
        if(kit==null)return;
        String label=(ru?"Кит: ":"Kit: ")+detectedKitLabel(detectedKit);
        int sw=MinecraftBridge.scaledWidth(client), sh=MinecraftBridge.scaledHeight(client);
        int smallW=22, fullW=Math.max(70, smallW+approxTextWidth(label)+10), w=Math.round(smallW+(fullW-smallW)*ease(expand));
        int h=20;
        int targetX=sw-w-7;
        int x=Math.round(sw+4-(sw+4-targetX)*ease(visible)), y=Math.max(6, sh/2-h/2);
        Theme theme=theme();
        fill.invoke(ctx, x, y, x+w, y+h, theme.bg());
        fill.invoke(ctx, x, y, x+2, y+h, theme.accent());
        KitIconRenderer.draw(ctx, kit, x+3, y+2);
        if(expand>0.18f&&w>smallW+12)text.invoke(ctx, tr, label, x+23, y+6, 0xFFE8ECEF);
        if(open)addActionRegion(x, y, w, h, "KIT_MENU", "", "");
    }
    private static final List<String> DETECTOR_KITS=List.of("sword", "dpot", "npot", "op", "smp", "uhc", "suhc", "dsmp", "mace", "creeper", "vanilla", "minecart", "axe");
    private void renderKitMenu(Object ctx, Object client, Object tr, Method fill, Method text, boolean ru)throws Exception {
        int sw=MinecraftBridge.scaledWidth(client), sh=MinecraftBridge.scaledHeight(client), w=220, rowH=20, cols=2, rows=7, h=28+rows*rowH, x=sw-w-7, y=Math.max(6, sh/2-h/2);
        Theme theme=theme();
        fill.invoke(ctx, x, y, x+w, y+h, theme.bg());
        fill.invoke(ctx, x, y, x+w, y+2, theme.accent());
        text.invoke(ctx, tr, ru?"Кит":"Kit", x+7, y+7, 0xFFFFFFFF);
        ArrayList<String> values=new ArrayList<>();
        values.add("auto");
        values.addAll(DETECTOR_KITS);
        int cellW=(w-12)/cols;
        for(int i=0; i<values.size(); i++) {
            String k=values.get(i);
            int col=i%cols, row=i/cols, cx=x+6+col*cellW, cy=y+24+row*rowH;
            fill.invoke(ctx, cx, cy, cx+cellW-3, cy+18, 0x88232B32);
            if(!"auto".equals(k))KitIconRenderer.draw(ctx, k, cx+3, cy+1);
            String label="auto".equals(k)?(ru?"Авто":"Auto"):kitMenuLabel(k);
            text.invoke(ctx, tr, label, cx+("auto".equals(k)?6:22), cy+5, 0xFFE8ECEF);
            addActionRegion(cx, cy, cellW-3, 18, "KIT_OVERRIDE", k, "");
        }
    }
    private static String kitMenuLabel(String k) {
        return switch(k) {
            case "sword"->"Sword / Beast";
            case "dpot"->"DPot";
            case "npot"->"NETHPOT";
            case "uhc"->"UHC";
            case "suhc"->"SUHC";
            case "dsmp"->"DSMP";
            case "smp"->"SMP";
            case "op"->"OP";
            case "vanilla"->"Vanilla";
            default->k.substring(0, 1).toUpperCase(Locale.ROOT)+k.substring(1);
        };
    }
    private void renderUiTooltip(Object context, Object textRenderer, Method fill, Method text) throws Exception {
        ActionRegion hit = null;
        int mouseX;
        int mouseY;
        synchronized (this) {
            mouseX = lastMouseX;
            mouseY = lastMouseY;
            for (int i = actionRegions.size() - 1; i >= 0; i--) {
                ActionRegion region = actionRegions.get(i);
                if (!"COMPARE_START".equals(region.type())) {
                    continue;
                }
                if (region.contains(mouseX, mouseY) && !region.tooltip().isBlank()) {
                    hit = region;
                    break;
                }
            }
        }
        if (hit == null) {
            return;
        }
        String tooltip = hit.tooltip();
        int width = Math.min(180, approxTextWidth(tooltip) + 10);
        int x = Math.max(4, Math.min( MinecraftBridge.scaledWidth(MinecraftBridge.client()) - width - 4, mouseX + 9));
        int y = Math.max(4, mouseY + 10);
        fill.invoke(context, x, y, x + width, y + 18, 0xEE10151C);
        text.invoke(context, textRenderer, tooltip, x + 5, y + 5, 0xFFF1F5F8);
    }
    private record TierCell(String tier, boolean retired, long fetchedAt, long lastTestAt) {
    }
    private String cellText(TierCell cell) {
        if(cell==null)return "";
        return (cell.retired()?"R":"")+cell.tier();
    }
    private record MatrixRow(String id, String name, long fetchedAt, Map<String, TierCell> cells) {
    }
    private record TierMatrix(List<String> kits, List<MatrixRow> rows) {
    }
    private record CompareRow(String id, String name, long leftFetchedAt, long rightFetchedAt, Map<String, TierCell> left, Map<String, TierCell> right) {
    }
    private record CompareMatrix(List<String> kits, List<CompareRow> rows) {
    }
    private record MatrixBand(int from, int to, int width) {
    }
    private TierMatrix buildMatrixCached(PlayerProfile p, boolean second) {
        if(p==null)return new TierMatrix(List.of(), List.of());
        long rev=config.revision();
        String dk=String.valueOf(detectedKit);
        UUID id=p.player().uuid();
        synchronized(matrixLru) {
            MatrixCacheEntry e=matrixLru.get(id);
            if(e!=null&&e.profile()==p&&e.revision()==rev&&Objects.equals(e.detected(), dk))return e.matrix();
            TierMatrix m=buildMatrix(p);
            matrixLru.put(id, new MatrixCacheEntry(p, rev, dk, m));
            return m;
        }
    }
    private SingleLayoutCacheEntry prepareSingleLayout(PlayerProfile p, int screenWidth) {
        long rev=config.revision();
        String dk=String.valueOf(detectedKit);
        UUID id=p.player().uuid();
        synchronized(singleLayoutLru) {
            SingleLayoutCacheEntry e=singleLayoutLru.get(id);
            if(e!=null&&e.profile()==p&&e.revision()==rev&&e.screenWidth()==screenWidth&&Objects.equals(e.detected(), dk))return e;
        }
        TierMatrix matrix=buildMatrixCached(p, false);
        boolean td=true, pd=false;
        int rn=matrixRowNameWidth(matrix), rh=13;
        List<MatrixBand> bs=List.copyOf(matrixBands(matrix.kits().size(), rn, Math.max(96, screenWidth-12), CELL_W));
        SingleLayoutCacheEntry made=new SingleLayoutCacheEntry(p,
            rev,
            dk,
            screenWidth,
            matrix,
            rn,
            rh,
            bs,
            matrixWidth(bs,
            rn),
            matrixBodyHeight(bs,
            matrix.rows().size(),
            rh),
            td,
            pd);
        synchronized(singleLayoutLru) {
            singleLayoutLru.put(id, made);
        }
        return made;
    }
    private TierMatrix buildMatrix(PlayerProfile p) {
        TierMatrix filtered=buildMatrix(p, true);
        // If kit detection misses, fall back to the complete cached table.
        if(config.filterCurrentKit()&&(filtered.kits().isEmpty()||filtered.rows().isEmpty()))return buildMatrix(p, false);
        return filtered;
    }
    private TierMatrix buildMatrix(PlayerProfile p, boolean applyCurrentKitFilter) {
        String detected=canonicalKit(detectedKit);
        boolean restrict=applyCurrentKitFilter&&config.filterCurrentKit();
        if(restrict&&detected==null&&!config.showAllWhenKitUnknown())return new TierMatrix(List.of(), List.of());
        LinkedHashSet<String> presentKits=new LinkedHashSet<>();
        ArrayList<MatrixRow> rows=new ArrayList<>();
        for(String providerId:config.providerOrder()) {
            ProviderResult pr=p.providers().get(providerId);
            if(pr==null||!config.enabled(providerId)||pr.status()!=ProviderResult.Status.OK||pr.tiers().isEmpty())continue;
            LinkedHashMap<String, TierCell> cells=new LinkedHashMap<>();
            for(TierEntry e:pr.tiers()) {
                String kit=canonicalKit(e.gamemode());
                if(kit==null||!config.kitEnabled(kit))continue;
                if(restrict&&detected!=null&&!detected.equals(kit))continue;
                String tier=TierRank.normalize(e.currentTier());
                if(tier==null)continue;
                long testAt=lastTestMillis(e.lastTest(), pr.fetchedAt());
                cells.putIfAbsent(kit, new TierCell(tier, e.retired(), pr.fetchedAt(), testAt));
                presentKits.add(kit);
            }
            if(!cells.isEmpty())rows.add(new MatrixRow(pr.providerId(), pr.displayName(), pr.fetchedAt(), cells));
        }
        ArrayList<String> kits=new ArrayList<>();
        for(String k:config.kitOrder())if(presentKits.contains(k))kits.add(k);
        return new TierMatrix(List.copyOf(kits), List.copyOf(rows));
    }
    private CompareMatrix buildCompareMatrix(PlayerProfile a, PlayerProfile b) {
        TierMatrix ma=buildMatrix(a), mb=buildMatrix(b);
        Map<String, MatrixRow> ar=new HashMap<>(), br=new HashMap<>();
        for(MatrixRow r:ma.rows())ar.put(r.id(), r);
        for(MatrixRow r:mb.rows())br.put(r.id(), r);
        LinkedHashSet<String> comparableKits=new LinkedHashSet<>();
        ArrayList<CompareRow> rows=new ArrayList<>();
        for(String id:config.providerOrder()) {
            MatrixRow l=ar.get(id), r=br.get(id);
            if(l==null||r==null)continue;
            LinkedHashMap<String, TierCell> lc=new LinkedHashMap<>(), rc=new LinkedHashMap<>();
            for(String kit:config.kitOrder()) {
                TierCell lv=l.cells().get(kit), rv=r.cells().get(kit);
                if(lv==null||rv==null)continue;
                lc.put(kit, lv);
                rc.put(kit, rv);
                comparableKits.add(kit);
            }
            if(!lc.isEmpty())rows.add(new CompareRow(id, l.name(), l.fetchedAt(), r.fetchedAt(), lc, rc));
        }
        ArrayList<String> kits=new ArrayList<>();
        for(String k:config.kitOrder())if(comparableKits.contains(k))kits.add(k);
        return new CompareMatrix(List.copyOf(kits), List.copyOf(rows));
    }
    private int[] compareColors(TierCell a, TierCell b) {
        int leftNeutral=0xB51B3544, rightNeutral=0xB5452338, yellow=0xD08A7429, green=0xD02F7844, red=0xD07B3434;
        if(a==null||b==null)return new int[] {
            0xB5262A2F, 0xB5262A2F
        };
        if(a.tier().equals(b.tier())) {
            if(a.retired()!=b.retired())return new int[] {
                yellow, yellow
            };
            return new int[] {
                leftNeutral, rightNeutral
            };
        }
        int sa=tierScore(a.tier()), sb=tierScore(b.tier());
        if(sa==sb)return new int[] {
            leftNeutral, rightNeutral
        };
        return sa>sb?new int[] {
            green, red
        }
        :new int[] {
            red, green
        };
    }
    private static int tierScore(String tier) {
        return TierSelection.tierScore(tier);
    }
    private static long olderFetch(long a, long b) {
        if(a<=0)return b;
        if(b<=0)return a;
        return Math.min(a, b);
    }
    private void drawTierTestDot(Method fill, Object ctx, int x, int y, long at)throws Exception {
        if(at<=0)return;
        long age=Math.max(0, System.currentTimeMillis()-at);
        if(age<=7L*86_400_000L)drawDot(fill, ctx, x, y, 0xFFFFD24A);
    }
    private void drawProviderDot(Method fill, Object ctx, int x, int y, long at)throws Exception {
        if(at<=0)return;
        drawDot(fill, ctx, x, y, ageColor(at, 7, 30, 90, 180, 365, 730));
    }
    private static void drawDot(Method fill, Object ctx, int x, int y, int c)throws Exception {
        if(c==0xFF050505)fill.invoke(ctx, x-1, y-1, x+4, y+4, 0xFF70777D);
        fill.invoke(ctx, x, y, x+3, y+3, c);
    }
    private static int ageColor(long at, int lightGreen, int green, int yellow, int orange, int red, int black) {
        if(at<=0)return 0xFF050505;
        double days=Math.max(0, System.currentTimeMillis()-at)/86_400_000.0;
        if(days<=lightGreen)return 0xFF9BEA9B;
        if(days<=green)return 0xFF43B95A;
        if(days<=yellow)return 0xFFF0D54A;
        if(days<=orange)return 0xFFF09A3E;
        if(days<=red)return 0xFFE34C4C;
        if(days<=black)return 0xFF050505;
        return 0xFF050505;
    }
    private static final Pattern AGO=Pattern.compile("(?i)^\\s*(\\d+)\\s*([dhm])(?:\\s*ago)?\\s*$");
    public static long lastTestMillis(String raw, long fetchedAt) {
        if(raw==null||raw.isBlank())return 0;
        String s=raw.trim();
        try {
            long n=Long.parseLong(s);
            if(n>10_000_000_000L)return n;
            if(n>1_000_000_000L)return n*1000L;
        } catch (Exception ignored) {
        }
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        if(s.equalsIgnoreCase("today"))return fetchedAt>0?fetchedAt:System.currentTimeMillis();
        if(s.equalsIgnoreCase("yesterday")) {
            long base=fetchedAt>0?fetchedAt:System.currentTimeMillis();
            return base-86_400_000L;
        }
        Matcher m=AGO.matcher(s);
        if(m.matches()) {
            long n;
            try {
                n=Long.parseLong(m.group(1));
            } catch (Exception e) {
                return 0;
            }
            long unit=switch(m.group(2).toLowerCase(Locale.ROOT)) {
                case "h"->3_600_000L;
                case "m"->60_000L;
                default->86_400_000L;
            };
            long base=fetchedAt>0?fetchedAt:System.currentTimeMillis();
            return Math.max(1, base-n*unit);
        }
        return 0;
    }
    private static int matrixRowNameWidth(TierMatrix matrix) {
        int w=48;
        for(MatrixRow r:matrix.rows())w=Math.max(w, 8+approxTextWidth(r.name()));
        return Math.min(110, w);
    }
    private static int compareRowNameWidth(CompareMatrix matrix) {
        int w=48;
        for(CompareRow r:matrix.rows())w=Math.max(w, 8+approxTextWidth(r.name()));
        return Math.min(110, w);
    }
    private static List<MatrixBand> matrixBands(int kitCount, int rowNameW, int maxWidth, int colW) {
        ArrayList<MatrixBand> out=new ArrayList<>();
        int from=0, used=0, room=Math.max(colW, maxWidth-rowNameW);
        for(int i=0; i<kitCount; i++) {
            if(i>from&&used+colW>room) {
                out.add(new MatrixBand(from, i, used));
                from=i;
                used=0;
            }
            used+=colW;
        }
        if(from<kitCount)out.add(new MatrixBand(from, kitCount, used));
        return out;
    }
    private static int matrixWidth(List<MatrixBand> bands, int rowNameW) {
        int w=70;
        for(MatrixBand b:bands)w=Math.max(w, rowNameW+b.width());
        return w;
    }
    private static int matrixHeight(List<MatrixBand> bands, int rows, int rowH) {
        return 20+bands.size()*(KIT_HEADER_H+rows*rowH)+Math.max(0, bands.size()-1)*2+3;
    }
    private static void drawCentered(Method text, Object ctx, Object tr, String value, int x, int y, int w, int h, int color)throws Exception {
        int tw=approxTextWidth(value);
        text.invoke(ctx, tr, value, x+Math.max(1, (w-tw)/2), y+Math.max(1, (h-9)/2), color);
    }
    private static int approxTextWidth(String s) {
        return s==null?0:s.length()*6;
    }
    private static String ellipsize(String s, int max) {
        if(s==null)return "";
        return s.length()<=max?s:s.substring(0, Math.max(0, max-1))+"…";
    }
    private record Theme(int bg, int accent, int panel, int muted) {
    }
    private Theme theme() {
        return switch(config.theme()) {
            case "classic"->new Theme(0xD9191919, 0xFFDDDDDD, 0xEE252525, 0xFF9A9A9A);
            case "glass"->new Theme(0xB5151B22, 0xFF79D7FF, 0xB525303A, 0xFFAAB7C2);
            case "warm"->new Theme(0xD91D1714, 0xFFFFB36B, 0xEE30241F, 0xFFC7A98D);
            default->new Theme(0xD911151A, 0xFF66D9EF, 0xEE20262D, 0xFF89949E);
        };
    }
    private static float approach(float cur, float target, float dt, float speed) {
        float a=1f-(float)Math.exp(-speed*dt);
        float v=cur+(target-cur)*a;
        if(Math.abs(v-target)<0.002f)return target;
        return v;
    }
    private static float ease(float t) {
        t=Math.max(0f, Math.min(1f, t));
        return 1f-(1f-t)*(1f-t)*(1f-t);
    }
    public static String canonicalKit(String mode) {
        String s=mode==null?"":mode.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').replaceAll("\s+", " ").trim();
        if(s.isEmpty()||s.equals("unknown"))return null;
        String compact=s.replace(" ", "");
        if(s.equals("suhc")||compact.contains("shieldlessuhc"))return "suhc";
        if(s.contains("vanilla")||s.contains("crystal")||s.contains("cpvp"))return "vanilla";
        if(s.equals("dpot")||s.equals("pot")||compact.equals("diapot")||compact.equals("diamondpot"))return "dpot";
        if(s.equals("npot")||s.equals("n pot")||s.equals("netherite")||compact.equals("nethpot")||compact.equals("netheritepot"))return "npot";
        if(s.equals("dsmp")||compact.equals("diasmp")||compact.equals("diamondsmp"))return "dsmp";
        if(s.contains("sword")||s.contains("beast")||s.contains("classic"))return "sword";
        if(s.contains("uhc"))return "uhc";
        if(s.equals("smp")||s.endsWith(" smp"))return "smp";
        if(s.equals("op")||compact.contains("nethop")||s.endsWith(" op"))return "op";
        if(compact.contains("spearmace")||s.contains("spear"))return "spear";
        // must precede generic mace
        if(s.contains("minecart")||s.equals("cart")||s.contains("tnt cart"))return "minecart";
        for(String k:List.of("axe", "mace", "shield", "trident", "speed", "debuff", "manhunt", "creeper", "elytra", "bed", "bow"))if(s.contains(k))return k;
        return null;
    }
    private static String detectedKitLabel(String kit) {
        String k=canonicalKit(kit);
        if(k==null)return kit;
        return kitMenuLabel(k);
    }
}
