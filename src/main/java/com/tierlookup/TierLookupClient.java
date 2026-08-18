package com.tierlookup;

import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

import com.tierlookup.client.*;
import com.tierlookup.client.dormant.FullModeScreen;
import com.tierlookup.core.*;
import com.tierlookup.model.*;
import com.tierlookup.provider.*;
import com.tierlookup.service.*;

import net.fabricmc.api.ClientModInitializer;

public final class TierLookupClient implements ClientModInitializer {
    public static final String VERSION = "1.0.0";
private static volatile TierLookupClient INSTANCE;
    private List<TierProvider> providers=List.of();
    private TierLookupConfig config;
    private ProfileService service;
    private OverlayRenderer overlay;
    private BulkSyncService bulkSync;
    private int tick=0;
    private boolean escDown=false;
    private boolean openRawDown=false;
    private boolean gameplayKeysSuppressed=false;
    private boolean inWorldAnnounced=false;
    private boolean wasInWorld=false;
    private boolean disabledApplied=false;
    private int worldGeneration=0;
    private int searchGeneration=0;
    private final StateCoordinator state=new StateCoordinator();
    private boolean searchActivatorDown=false;
    private boolean searchMouseDown=false;
    private boolean searchRightMouseDown=false;
    private boolean searchMouseNativeOkLogged=false;
    private boolean tabInteractionRightDown=false;
    private UUID hoverTarget;
    private boolean searchOpenKeySuppress=false;
    private final ConcurrentHashMap<UUID, Long> networkAttemptAt=new ConcurrentHashMap<>();
    private static final long NETWORK_ATTEMPT_COOLDOWN_MS=30_000L;
    private static final int LIVE_TAB_SCAN_TICKS=LiveRosterPolicy.SCAN_TICKS;
    private static final long LIVE_ATTEMPT_COOLDOWN_MS=LiveRosterPolicy.ATTEMPT_COOLDOWN_MS;
    private static final long LIVE_PLAYER_GAP_MS=LiveRosterPolicy.PLAYER_GAP_MS;
    private static final int LIVE_QUEUE_LIMIT=LiveRosterPolicy.QUEUE_LIMIT;
    private final ConcurrentLinkedDeque<PlayerIdentity> liveQueue=new ConcurrentLinkedDeque<>();
    private final Set<UUID> liveQueued=ConcurrentHashMap.newKeySet();
    private final Set<UUID> liveTabRoster=ConcurrentHashMap.newKeySet();
    private final Set<UUID> liveSatisfied=ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Long> liveAttemptAt=new ConcurrentHashMap<>();
    private volatile List<PlayerIdentity> liveRosterCandidate=List.of();
    private volatile long liveRosterCandidateAt=0L;
    private volatile CompletableFuture<PlayerProfile> liveActive;
    private volatile UUID liveActiveId;
    private volatile long liveNextStartAt=0;
    private final KitDetector.Tracker kitTracker=new KitDetector.Tracker();
    private volatile String manualKitOverride=null;
    private int lastInventoryRevision=Integer.MIN_VALUE;
    private int lastKitEffectBits=Integer.MIN_VALUE;
    private final boolean[] rawDown=new boolean[512];
    private final int[] repeatTicks=new int[512];
    private String lastScreenDiag="";
    private volatile Method glfwGetKeyMethod;
    private volatile Method glfwGetMouseButtonMethod;
    private String coreStatus="not initialized";
    private String keyStatus="not registered";
    private String tickStatus="not registered";
    private String hudStatus="not registered";
    @Override
    public void onInitializeClient() {
        
        
        
        
        INSTANCE=this;
        try {
            providers=Providers.all();
            config=new TierLookupConfig(MinecraftBridge.configDir().resolve("tierlookup.json"), providers.stream().map(TierProvider::id).toList());
            service=new ProfileService(providers, config);
            overlay=new OverlayRenderer(config, service);
            bulkSync=new BulkSyncService(providers, config, service);
            coreStatus="OK";
        } catch (Throwable t) {
            coreStatus="ERROR "+t.getClass().getSimpleName();
            BootstrapLog.error("core init", t);
        }
        try {
            registerStorageShutdownHook();
        } catch (Throwable t) {
            BootstrapLog.error("storage shutdown hook", t);
        }
        try {
            if(config==null)throw new IllegalStateException("config unavailable");
            keyStatus=KeyBindingBridge.register(config);
            
        } catch (Throwable t) {
            keyStatus="ERROR "+t.getClass().getSimpleName();
            BootstrapLog.error("key binding registration", t);
        }
        try {
            registerTick();
            tickStatus="OK";
        } catch (Throwable t) {
            tickStatus="ERROR "+t.getClass().getSimpleName();
            BootstrapLog.error("tick hook", t);
        }

        if(overlay!=null)try {
            hudStatus=HudBridge.register(overlay);
        } catch (Throwable t) {
            hudStatus="ERROR "+t.getClass().getSimpleName();
            BootstrapLog.error("HUD hook", t);
        }
        
        
    }
    private void registerStorageShutdownHook() {
        if(service==null)return;
        Thread hook=new Thread(()-> {
            try {
                ProfileService s=service; if(s!=null) {
                     s.close();
                }
            } catch (Throwable t) {
                BootstrapLog.error("STORAGE shutdown close", t);
            }
        }, "TierLookup-Storage-Shutdown");
        hook.setPriority(Thread.MIN_PRIORITY);
        Runtime.getRuntime().addShutdownHook(hook);
    }
    private void registerTick()throws Exception {
        Class<?> ev=Class.forName("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents");
        Object startEvent=ev.getField("START_CLIENT_TICK").get(null);
        Class<?> startListener=Class.forName("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents$StartTick");
        Object sp=Proxy.newProxyInstance(startListener.getClassLoader(), new Class<?>[] {
            startListener
        },(p, m, a)-> {
            if(ProxySupport.isObjectMethod(m))return ProxySupport.invokeObjectMethod(p, m, a, "TierLookupStartTickListener"); if(m.getName().equals("onStartTick")) {
                captureGameplayKeys(a[0]);
            }
            return null;
        }
        );
        EventBridge.register(startEvent, sp);
        Object endEvent=ev.getField("END_CLIENT_TICK").get(null);
        Class<?> endListener=Class.forName("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents$EndTick");
        Object ep=Proxy.newProxyInstance(endListener.getClassLoader(), new Class<?>[] {
            endListener
        },(p, m, a)-> {
            if(ProxySupport.isObjectMethod(m))return ProxySupport.invokeObjectMethod(p, m, a, "TierLookupEndTickListener"); if(m.getName().equals("onEndTick")) {
                onTick(a[0]); captureGameplayKeys(a[0]);
            }
            return null;
        }
        );
        EventBridge.register(endEvent, ep);
    }
    private void onTick(Object client) {
        tick++;
        boolean inWorldNow=MinecraftBridge.hasWorld(client);
        if(inWorldNow&&!wasInWorld) {
            worldGeneration++;
            resetLiveRosterSession(false);
            
            if(config!=null&&config.masterEnabled())scanLiveTabRoster(client);
        } else if(!inWorldNow&&wasInWorld) {
            worldGeneration++;
            
            // Database sync is allowed to continue across a normal disconnect.
            if(service!=null)service.flushSeenMetadata();
            resetLiveRosterSession(true);
            TabNameDecorator.resetVanillaTabState();
            kitTracker.reset();
            lastInventoryRevision=Integer.MIN_VALUE;
            lastKitEffectBits=Integer.MIN_VALUE;
            hoverTarget=null;
            networkAttemptAt.clear();
            inWorldAnnounced=false;
            if(overlay!=null) {
                overlay.clearHoverImmediate();
                overlay.setDetectedKit(KitDetector.UNKNOWN);
            }
        }
        wasInWorld=inWorldNow;
        if((tick%20)==0) {
            String sd=MinecraftBridge.currentScreenDiagnostics(client);
            if(!Objects.equals(sd, lastScreenDiag)) {
                lastScreenDiag=sd;
                
            }
        }
        if(overlay==null)return;
        if(config!=null&&!config.masterEnabled()) {
            handleDisabledTick(client);
            return;
        }
        if(disabledApplied) {
            disabledApplied=false;
            openRawDown=rawKey(client, KeyBindingBridge.openKeyCode());
            
        }
        // If Minecraft replaces our capture screen, close only the K workspace.
        if(overlay.open()&&!overlay.fullMode()&&!SearchCaptureScreen.isOpen()) {
            searchGeneration++;
            state.closeWorkspace();
            overlay.close();
            releaseGameplayKeys();
            resetInputState();
            searchOpenKeySuppress=false;
        }
        if(inWorldNow) {
            if(!inWorldAnnounced) {
                PlayerIdentity self=MinecraftBridge.localPlayer(client);
                if(self!=null) {
                    inWorldAnnounced=true;
                    
                }
            }
            int invRevision=MinecraftBridge.localInventoryRevision(client), effectBits=MinecraftBridge.localRelevantEffectBits(client);
            if(invRevision!=lastInventoryRevision||effectBits!=lastKitEffectBits) {
                lastInventoryRevision=invRevision;
                lastKitEffectBits=effectBits;
                String before=kitTracker.current();
                String detected=kitTracker.update(MinecraftBridge.localInventorySnapshot(client));
                if(KitDetector.UNKNOWN.equals(detected))manualKitOverride=null;
                String visibleKit=manualKitOverride!=null?manualKitOverride:detected;
                overlay.setDetectedKit(visibleKit);
                if(!Objects.equals(before, detected)) {
                    if(KitDetector.UNKNOWN.equals(detected))state.leave(AppState.KIT_SESSION);
                    else state.activate(AppState.KIT_SESSION);
                    
                }
            }
            if((tick%10)==0&&config!=null&&config.legacyTabEnabled()&&!TabInteractionScreen.isOpen())TabNameDecorator.refreshVanillaTab(client);
            if(config!=null&&(tick%LIVE_TAB_SCAN_TICKS)==0&&!TabInteractionScreen.isOpen())scanLiveTabRoster(client);
            if(!TabInteractionScreen.isOpen()) {
                settleLiveTabRoster(client);
                pumpLiveNetwork(client);
            }
        }
        boolean openConsumed=false;
        while(KeyBindingBridge.consumeOpen())openConsumed=true;
        int openCode=KeyBindingBridge.openKeyCode();
        boolean openRaw=rawKey(client, openCode), openEdge=openRaw&&!openRawDown;
        openRawDown=openRaw;
        boolean gameplayInputFree=MinecraftBridge.gameplayScreenClear(client);
        boolean tabHeld=inWorldNow&&com.tierlookup.client.runtime.MinecraftRuntime.active().playerListPressed(client);
        boolean customTabHeld=tabHeld&&config!=null&&config.customTabEnabled();
        boolean rightNow=rawMouseButton(client, 1), rightEdge=rightNow&&!tabInteractionRightDown;
        tabInteractionRightDown=rightNow;
        if(customTabHeld&&gameplayInputFree&&!overlay.open()&&!overlay.fullMode()&&!TabInteractionScreen.isOpen()&&"right_mouse".equals(config.tabInteractionTrigger())&&rightEdge) {
            if(TabInteractionScreen.open()) {
                resetInputState();
                tabInteractionRightDown=true;
                
            }
        }
        // Gameplay hotkey ownership is strict: chat/sign/book/inventory/Controls/ModMenu and every other Screen keep K.
        if((openConsumed||openEdge)&&inWorldNow&&!overlay.open()&&!overlay.fullMode()&&gameplayInputFree&&!TabInteractionScreen.isOpen()) {
            boolean tabCursor=false;
            if(customTabHeld&&"hotkey".equals(config.tabInteractionTrigger())) {
                tabCursor=TabInteractionScreen.open();
                if(tabCursor) {
                    resetInputState();
                    searchOpenKeySuppress=true;
                    
                }
            }
            if(!tabCursor)openSearchPanel(client);
        }
        boolean esc=rawKey(client, 256);
        if(esc&&!escDown&&overlay.open()) {
            closeSearchPanel(client);
        }
        escDown=esc;
        if(overlay.open()) {
            handleSearchMouse(client);
            processUiActions(client);
        } else searchMouseDown=false;
        if(overlay.searchActive())handleSearchInput(client);
        else resetRepeatOnly();
        if(inWorldNow&&config.targetCardEnabled()&&!overlay.open()&&!overlay.pinnedHudActive()&&!TabInteractionScreen.isOpen()&&!tabHeld)updateHover(client);
        else if(hoverTarget!=null) {
            hoverTarget=null;
            overlay.clearHoverImmediate();
        }
    }
    private void openSearchPanel(Object client) {
        if(overlay.open())return;
        PlayerIdentity observed=MinecraftBridge.targetPlayer(client);
        PlayerProfile promoted=overlay.currentHoverProfile();
        if(promoted!=null) {
            if(observed!=null) {
                PlayerProfile bound=service.bindObservedIdentity(observed);
                if(bound!=null)promoted=bound;
                service.markSeen(observed, MinecraftBridge.currentServerKey(client));
            }
            overlay.promoteHoverToWorkspace();
            if(promoted!=null)overlay.replaceProfile(promoted);
            
        }
        hoverTarget=null;
        state.enter(AppState.SEARCH);
        overlay.openMain();
        overlay.activateSearch();
        overlay.setSearchMessage("");
        SearchCaptureScreen.open();
        resetInputState();
        searchOpenKeySuppress=true;
        captureGameplayKeys(client);
        
    }
    private void closeSearchPanel(Object client) {
        searchGeneration++;
        state.closeWorkspace();
        if(service!=null)service.flushSeenMetadata();
        if(overlay!=null)overlay.close();
        SearchCaptureScreen.closeIfOpen();
        releaseGameplayKeys();
        resetInputState();
        searchOpenKeySuppress=false;
        com.tierlookup.client.runtime.MinecraftRuntime.active().restoreGameplayControl(client);
        
    }
    /** The transparent capture screen forwards only workspace-exit keys. All user actions live in the K UI. */
    public static boolean handleSearchCaptureKey(int code) {
        TierLookupClient i=INSTANCE;
        if(i==null||i.overlay==null||!i.overlay.open())return false;
        int openCode=KeyBindingBridge.openKeyCode();
        if(code==256) {
            i.closeSearchPanel(MinecraftBridge.client());
            return true;
        }
        // K is open-only. Once the workspace is visible it is a normal nickname character, never a close hotkey.
        if(code==openCode)return true;
        return false;
    }
    private void handleSearchInput(Object client) {
        int openCode=KeyBindingBridge.openKeyCode();
        if(searchOpenKeySuppress&&!rawKey(client, openCode)) {
            searchOpenKeySuppress=false;
            if(openCode>=0&&openCode<repeatTicks.length) {
                repeatTicks[openCode]=0;
                rawDown[openCode]=false;
            }
        }
        boolean ctrl=ctrl(client), shift=shift(client);
        if(!ctrl) {
            rawDown[65]=rawDown[67]=rawDown[86]=rawDown[88]=false;
        }
        if(ctrl&&edge(client, 65)) {
            overlay.selectAll();
            return;
        }
        if(ctrl&&edge(client, 67)) {
            setClipboard(client, overlay.selectedText());
            return;
        }
        if(ctrl&&edge(client, 88)) {
            String s=overlay.selectedText();
            if(!s.isEmpty())setClipboard(client, s);
            overlay.cutSelection();
            return;
        }
        if(ctrl&&edge(client, 86)) {
            overlay.insertText(getClipboard(client));
            return;
        }
        if(edge(client, 258)) {
            overlay.acceptSuggestion();
            return;
        }
        if(edge(client, 265)) {
            overlay.moveSuggestion(-1);
            return;
        }
        if(edge(client, 264)) {
            overlay.moveSuggestion(1);
            return;
        }
        if(edge(client, 257)||edge(client, 335)) {
            searchFromMenu(client);
            return;
        }
        if(repeatKey(client, 259)) {
            overlay.backspaceSearch();
            return;
        }
        if(repeatKey(client, 261)) {
            overlay.deleteForward();
            return;
        }
        if(repeatKey(client, 263)) {
            overlay.moveCursor(-1, shift);
            return;
        }
        if(repeatKey(client, 262)) {
            overlay.moveCursor(1, shift);
            return;
        }
        if(edge(client, 268)) {
            overlay.moveHome(shift);
            return;
        }
        if(edge(client, 269)) {
            overlay.moveEnd(shift);
            return;
        }
        if(!ctrl) {
            for(int k=65; k<=90; k++) {
                if(searchOpenKeySuppress&&k==openCode)continue;
                if(repeatKey(client, k)) {
                    overlay.insertText(String.valueOf((char)(shift?k:k+32)));
                    return;
                }
            }
            for(int k=48; k<=57; k++)if(repeatKey(client, k)) {
                overlay.insertText(String.valueOf((char)k));
                return;
            }
            if(shift&&repeatKey(client, 45)) {
                overlay.insertText("_");
            }
        }
    }
    private void searchFromMenu(Object client) {
        searchExplicitQuery(client, overlay.searchText().trim());
    }
    /** One explicit-search pipeline for typed nicknames, autocomplete rows and favorites. RAM always wins first. */
    private void searchExplicitQuery(Object client, String query) {
        boolean ru=MinecraftBridge.russian(client);
        if(query==null||!query.matches("[A-Za-z0-9_]{1,16}")) {
            overlay.setSearchMessage(ru?"Ник: 1–16 символов A-Z, 0-9, _":"Nickname: 1–16 chars A-Z, 0-9, _");
            return;
        }
        int compareStage=overlay.comparePickerStage();
        boolean compare=compareStage>0;
        StateCoordinator.Token token=state.enter(compare?AppState.COMPARE:AppState.SEARCH);
        int generation=++searchGeneration;
        overlay.setSearchMessage(ru?"Ищем…":"Searching…");
        
        PlayerProfile ram=service.searchRam(query, MinecraftBridge.currentServerKey(client));
        if(hasUsableLocalProfile(ram)) {
            acceptExplicitSearchResult(ram, compareStage, ru, generation, token);
            if(config.internetMode()&&service.needsRefresh(ram.player(), config.recacheIntervalMs())) {
                service.refreshStale(ram.player(), config.recacheIntervalMs(), null).whenComplete((updated, e)-> {
                    if(e==null&&updated!=null&&compareStage==0&&generation==searchGeneration&&state.valid(token)&&overlay!=null&&overlay.open())MinecraftBridge.runOnClientThread(()->overlay.replaceProfile(updated));
                }
                );
            }
            return;
        }
        if(config.offlineMode()) {
            searchSqliteFallback(query, null, compareStage, ru, generation, token, ru?"Нет в локальной базе":"Not in local database");
            return;
        }
        searchInternetFirst(query, null, compareStage, ru, generation, token);
    }
    private static boolean hasUsableLocalProfile(PlayerProfile p) {
        return p!=null&&p.providers()!=null&&!p.providers().isEmpty();
    }
    private void searchSqliteFallback(String query, PlayerProfile fallback, int compareStage, boolean ru, int generation, StateCoordinator.Token token, String missMessage) {
        service.searchSqliteAsync(query).whenComplete((disk, e)-> {
            if(generation!=searchGeneration||!state.valid(token)||overlay==null||!overlay.open())return;
            PlayerProfile chosen=hasUsableLocalProfile(disk)?disk:(hasUsableLocalProfile(fallback)?fallback:null);
            if(chosen!=null)acceptExplicitSearchResult(chosen, compareStage, ru, generation, token);
            else overlay.setSearchMessage(missMessage);
        }
        );
    }
    private void searchInternetFirst(String query, PlayerProfile fallback, int compareStage, boolean ru, int generation, StateCoordinator.Token token) {
        PlayerIdentity known=fallback==null?null:fallback.player();
        if(known!=null&&!ProfileService.isSynthetic(known.uuid(), known.name())) {
            searchInternetFirstResolved(query, known, fallback, compareStage, ru, generation, token);
            return;
        }
        PlayerResolver.resolve(query).copy().orTimeout(18, TimeUnit.SECONDS).whenComplete((resolved, e)-> {
            if(generation!=searchGeneration||!state.valid(token)||overlay==null||!overlay.open())return; if(e!=null||resolved==null) {
                searchSqliteFallback(query, fallback, compareStage, ru, generation, token, ru?"Игрок не найден или сеть недоступна":"Player not found or network unavailable");
                return;
            }
            searchInternetFirstResolved(query, resolved, fallback, compareStage, ru, generation, token);
        }
        );
    }
    private void searchInternetFirstResolved(String query,
        PlayerIdentity player,
        PlayerProfile fallback,
        int compareStage,
        boolean ru,
        int generation,
        StateCoordinator.Token token) {
        if(player==null)return;
        if(!permitNetworkAttempt(player.uuid())) {
            PlayerProfile cached=service.cached(player.uuid());
            if(cached==null)cached=fallback;
            if(hasUsableLocalProfile(cached)) {
                acceptExplicitSearchResult(cached, compareStage, ru, generation, token);
                return;
            }
            searchSqliteFallback(query, fallback, compareStage, ru, generation, token, ru?"Сетевой запрос недавно уже выполнялся":"Network lookup was just performed");
            return;
        }
        overlay.setSearchMessage(ru?"Загрузка из интернета…":"Loading from internet…");
        service.lookup(player, true).copy().orTimeout(25, TimeUnit.SECONDS).whenComplete((result, err)-> {
            if(generation!=searchGeneration||!state.valid(token)||overlay==null||!overlay.open())return;
            PlayerProfile chosen=hasUsableLocalProfile(result)?result:service.cached(player.uuid());
            if(!hasUsableLocalProfile(chosen))chosen=fallback;
            if(hasUsableLocalProfile(chosen)) {
                acceptExplicitSearchResult(chosen, compareStage, ru, generation, token);
            } else searchSqliteFallback(query, null, compareStage, ru, generation, token, ru?"Не удалось загрузить профиль":"Could not load profile");
        }
        );
    }
    private boolean acceptExplicitSearchResult(PlayerProfile result, int compareStage, boolean ru, int generation, StateCoordinator.Token token) {
        if(result==null||generation!=searchGeneration||!state.valid(token)||overlay==null||!overlay.open())return false;
        if(compareStage>0) {
            PlayerProfile first=overlay.compareDraftA();
            if(compareStage==2&&first!=null&&first.player().uuid().equals(result.player().uuid())) {
                overlay.setSearchMessage(ru?"Выбери другого игрока":"Choose a different player");
                return false;
            }
            overlay.acceptComparePick(result);
            overlay.setSearchMessage("");
            if(compareStage==1) {
                state.enter(AppState.COMPARE);
                
            } else {
                state.enter(AppState.COMPARE);
                
            }
        } else {
            overlay.show(result);
            overlay.setSearchMessage("");
            state.enter(AppState.SEARCH);
            
        }
        if(service.watchlisted(result.player().uuid())) {
            service.markFavoriteUsed(result.player().uuid());
            service.markWatchViewed(result.player().uuid());
        }
        return true;
    }
    private boolean permitNetworkAttempt(UUID id) {
        if(id==null)return false;
        long now=System.currentTimeMillis();
        Long prev=networkAttemptAt.putIfAbsent(id, now);
        if(prev==null)return true;
        if(now-prev<NETWORK_ATTEMPT_COOLDOWN_MS)return false;
        return networkAttemptAt.replace(id, prev, now);
    }
    private void openInteractiveTabPlayer(PlayerIdentity player) {
        if(player==null||overlay==null||service==null||config==null)return;
        Object client=MinecraftBridge.client();
        TabInteractionScreen.finishForSelection();
        hoverTarget=null;
        state.enter(AppState.SEARCH);
        overlay.openMain();
        overlay.setSearchText(player.name());
        PlayerProfile local=service.profileForDisplay(player.uuid(), player.name());
        if(local!=null)overlay.show(local);
        overlay.activateSearch();
        SearchCaptureScreen.open();
        resetInputState();
        searchOpenKeySuppress=true;
        captureGameplayKeys(client);
        
        // Keep the table immediate from RAM, then reuse the normal explicit-search refresh policy.
        searchExplicitQuery(client, player.name());
    }
    public static void openPlayerFromInteractiveTab(PlayerIdentity player) {
        TierLookupClient i=INSTANCE;
        if(i!=null)i.openInteractiveTabPlayer(player);
    }
    private void selectPlayerFromUi(Object client, String name) {
        if(name==null||name.isBlank())return;
        // Do not overwrite the visible search field: favorites/recent list stays available for rapid switching.
        searchExplicitQuery(client, name.trim());
    }
    private void processUiActions(Object client) {
        for(OverlayRenderer.UiAction a; (a=overlay.pollUiAction())!=null;) {
            String type=a.type(), value=a.value();
            try {
                switch(type) {
                    case "SELECT_PLAYER" -> selectPlayerFromUi(client, value);
                    case "FAVORITES_VIEW" -> overlay.setFavoritesView("favorites".equals(value));
                    case "COMPARE_START" -> {
                        overlay.beginComparePicker();
                        state.enter(AppState.COMPARE);
                    }
                    case "COMPARE_CANCEL" -> {
                        overlay.cancelComparePicker();
                        state.enter(AppState.SEARCH);
                    }
                    case "COMPARE_CLEAR" -> {
                        overlay.clearCompareResult();
                        state.enter(AppState.SEARCH);
                    }
                    case "FULL_OPEN" -> {
                        if(ClientFeatures.FULL_MODE_VISIBLE) {
                            PlayerProfile p=profileByUuid(value);
                            if(p==null)p=overlay.primaryProfile();
                            if(p!=null)FullModeScreen.open(p);
                        }
                    }
                    case "PIN_TOGGLE" -> {
                        PlayerProfile p=profileByUuid(value);
                        if(p!=null)overlay.toggleTablePin(p);
                    }
                    case "WINDOW_CLOSE" -> {
                        PlayerProfile p=profileByUuid(value);
                        overlay.closeParticipant(p==null?null:p.player().uuid());
                        state.enter(overlay.comparePickerActive()?AppState.COMPARE:AppState.SEARCH);
                    }
                    case "WORKSPACE_CLOSE" -> closeSearchPanel(client);
                    case "WATCH_TOGGLE" -> {
                        PlayerProfile p=profileByUuid(value);
                        if(p!=null) {
                            boolean next=!service.watchlisted(p.player().uuid());
                            service.setWatchlisted(p.player().uuid(), next);
                            if(next)service.markWatchViewed(p.player().uuid());
                        }
                    }
                    case "NOTE_EDIT" -> {
                        PlayerProfile p=profileByUuid(value);
                        if(p!=null)overlay.beginNoteEdit(p);
                    }
                    case "MESSAGE_PLAYER" -> {
                        PlayerProfile p=profileByUuid(value);
                        if(p!=null) {
                            closeSearchPanel(client);
                            com.tierlookup.client.runtime.MinecraftRuntime.active().openPrefilledChat(client, "/msg "+p.player().name()+" ");
                        }
                    }
                    case "COPY_NAME" -> {
                        PlayerProfile p=profileByUuid(value);
                        if(p!=null)setClipboard(client, p.player().name());
                    }
                    case "PROFILE_NAME_CLICK" -> {
                        PlayerProfile p=profileByUuid(value);
                        if(p!=null) {
                            if(shift(client)&&config.normalRuntimeNetworkAllowed())forceRefreshVisiblePlayer(p);
                            else setClipboard(client, p.player().name());
                        }
                    }
                    case "KIT_MENU" -> overlay.setKitMenuOpen(!overlay.kitMenuOpen());
                    case "KIT_OVERRIDE" -> {
                        if("auto".equals(value)) {
                            manualKitOverride=null;
                            overlay.setDetectedKit(kitTracker.current());
                        } else {
                            manualKitOverride=value;
                            overlay.setDetectedKit(value);
                        }
                        overlay.setKitMenuOpen(false);
                    }
                    case "SYNC_CANCEL" -> cancelBulkSyncFromSettings();
                    default -> {}
                }
            } catch (Throwable t) {
                BootstrapLog.error("UI action "+type, t);
            }
        }
    }
    private PlayerProfile profileByUuid(String raw) {
        try {
            UUID id=UUID.fromString(raw);
            PlayerProfile a=overlay.primaryProfile(), b=overlay.secondaryProfile();
            if(a!=null&&a.player().uuid().equals(id))return a;
            if(b!=null&&b.player().uuid().equals(id))return b;
            return service.cached(id);
        } catch (Exception e) {
            return null;
        }
    }
    private void forceRefreshVisiblePlayer(PlayerProfile p) {
        if(p==null||config==null||!config.normalRuntimeNetworkAllowed()||service==null)return;
        PlayerIdentity id=p.player();
        overlay.setSearchMessage("");
        service.lookup(id, true, updated-> {
            if(updated!=null)MinecraftBridge.runOnClientThread(()->overlay.replaceProfile(updated));
        }
        );
    }
    private void updateHover(Object client) {
        PlayerIdentity p=MinecraftBridge.targetPlayer(client);
        if(p==null) {
            hoverTarget=null;
            overlay.markHoverLost();
            return;
        }
        boolean changed=hoverTarget==null||!p.uuid().equals(hoverTarget);
        if(changed) {
            hoverTarget=p.uuid();
            overlay.beginHover(p);
        } else overlay.touchHover(p.uuid());
        // Hover only touches the in-memory cache.
        if(changed)service.markSeenMemoryOnly(p, MinecraftBridge.currentServerKey(client));
        if(changed||(tick%5)==0) {
            PlayerProfile c=service.hoverLookup(p);
            if(c!=null&&!c.providers().isEmpty())overlay.showHover(c);
        }
    }
    private void scanLiveTabRoster(Object client) {
        if(TabInteractionScreen.isOpen())return;
        if(config==null||!config.masterEnabled()||!MinecraftBridge.hasWorld(client)||service==null)return;
        List<PlayerIdentity> roster=MinecraftBridge.tabPlayers(client);
        if(roster.isEmpty())return;
        LinkedHashSet<UUID> ids=new LinkedHashSet<>();
        for(PlayerIdentity p:roster)if(p!=null&&p.uuid()!=null)ids.add(p.uuid());
        if(ids.equals(liveTabRoster)) {
            if((tick%(LIVE_TAB_SCAN_TICKS*4))==0)service.flushSeenMetadata();
            return;
        }
        liveRosterCandidate=List.copyOf(roster);
        liveRosterCandidateAt=System.currentTimeMillis()+LiveRosterPolicy.ROSTER_SETTLE_MS;
    }
    /** Apply a roster change only after it stayed stable for a short debounce window. */
    private void settleLiveTabRoster(Object client) {
        long due=liveRosterCandidateAt;
        if(due<=0||System.currentTimeMillis()<due||TabInteractionScreen.isOpen())return;
        List<PlayerIdentity> nowRoster=MinecraftBridge.tabPlayers(client);
        LinkedHashSet<UUID> nowIds=new LinkedHashSet<>();
        for(PlayerIdentity p:nowRoster)if(p!=null&&p.uuid()!=null)nowIds.add(p.uuid());
        LinkedHashSet<UUID> candidateIds=new LinkedHashSet<>();
        for(PlayerIdentity p:liveRosterCandidate)if(p!=null&&p.uuid()!=null)candidateIds.add(p.uuid());
        if(!nowIds.equals(candidateIds)) {
            liveRosterCandidate=List.copyOf(nowRoster);
            liveRosterCandidateAt=System.currentTimeMillis()+LiveRosterPolicy.ROSTER_SETTLE_MS;
            return;
        }
        liveRosterCandidateAt=0;
        applyLiveTabRoster(nowRoster, client);
    }
    private void applyLiveTabRoster(List<PlayerIdentity> roster, Object client) {
        LinkedHashSet<UUID> current=new LinkedHashSet<>();
        PlayerIdentity self=MinecraftBridge.localPlayer(client);
        String server=MinecraftBridge.currentServerKey(client);
        for(PlayerIdentity observed:roster) {
            if(observed==null||observed.uuid()==null)continue;
            current.add(observed.uuid());
            if(self!=null&&self.uuid().equals(observed.uuid()))continue;
            boolean joined=!liveTabRoster.contains(observed.uuid());
            PlayerProfile unified=service.bindObservedIdentity(observed);
            PlayerIdentity p=unified==null?observed:unified.player();
            // Every observed player belongs to the hot RAM encounter cache, even if no tier data is known yet.
            service.markSeenMemoryOnly(observed, server);
            if(joined) {
                PlayerIdentity observedCopy=observed;
                service.warmObservedFromSqliteAsync(observedCopy).whenComplete((disk, err)->MinecraftBridge.runOnClientThread(()-> {
                    if(config==null||!config.masterEnabled()||TabInteractionScreen.isOpen())return;
                    PlayerProfile bound=service.bindObservedIdentity(observedCopy);
                    PlayerIdentity resolved=bound==null?observedCopy:bound.player();
                    if(config.internetMode()&&service.needsInitialCoverage(resolved))enqueueLivePlayer(resolved, true);
                    else liveSatisfied.add(resolved.uuid());
                }
                ));
            }
        }
        for(UUID old:new ArrayList<>(liveTabRoster))if(!current.contains(old)) {
            liveSatisfied.remove(old);
            removeQueuedLive(old);
        }
        liveTabRoster.clear();
        liveTabRoster.addAll(current);
    }
    private void removeQueuedLive(UUID id) {
        if(id==null||!liveQueued.remove(id))return;
        liveQueue.removeIf(p->p!=null&&id.equals(p.uuid()));
    }
    private void enqueueLivePlayer(PlayerIdentity p, boolean priority) {
        if(p==null||p.uuid()==null||config==null||!config.masterEnabled()||!config.normalRuntimeNetworkAllowed()||service==null)return;
        // Passive TAB loading only considers players who just appeared.
        if(!service.needsInitialCoverage(p)) {
            liveSatisfied.add(p.uuid());
            removeQueuedLive(p.uuid());
            return;
        }
        liveSatisfied.remove(p.uuid());
        long now=System.currentTimeMillis(), last=liveAttemptAt.getOrDefault(p.uuid(), 0L);
        if(now-last<LIVE_ATTEMPT_COOLDOWN_MS)return;
        if(liveActiveId!=null&&liveActiveId.equals(p.uuid()))return;
        if(liveQueued.contains(p.uuid()))return;
        if(liveQueued.size()>=LIVE_QUEUE_LIMIT) {
            if(!priority)return;
            PlayerIdentity dropped=liveQueue.pollLast();
            if(dropped!=null)liveQueued.remove(dropped.uuid());
        }
        if(liveQueued.add(p.uuid())) {
            if(priority)liveQueue.addFirst(p);
            else liveQueue.addLast(p);
        }
    }
    private void pumpLiveNetwork(Object client) {
        if(TabInteractionScreen.isOpen())return;
        if(config==null||!config.masterEnabled()||!config.normalRuntimeNetworkAllowed()||service==null||!MinecraftBridge.hasWorld(client))return;
        if(bulkSync!=null&&bulkSync.running())return;
        if(overlay!=null&&(overlay.open()||overlay.fullMode()))return;
        CompletableFuture<PlayerProfile> active=liveActive;
        if(active!=null&&!active.isDone())return;
        long now=System.currentTimeMillis();
        if(now<liveNextStartAt)return;
        PlayerIdentity p;
        while((p=liveQueue.pollFirst())!=null) {
            liveQueued.remove(p.uuid());
            if(service.needsInitialCoverage(p))break;
            liveSatisfied.add(p.uuid());
            p=null;
        }
        if(p==null)return;
        final PlayerIdentity target=p;
        liveAttemptAt.put(target.uuid(), now);
        liveActiveId=target.uuid();
        
        CompletableFuture<PlayerProfile> f=service.refreshStale(target, Long.MAX_VALUE/4, null).copy().orTimeout(22, TimeUnit.SECONDS);
        liveActive=f;
        f.whenComplete((result, err)-> {
            boolean satisfied=err==null&&!service.needsInitialCoverage(target);
            if(satisfied)liveSatisfied.add(target.uuid());
            else liveSatisfied.remove(target.uuid());
            liveNextStartAt=System.currentTimeMillis()+LIVE_PLAYER_GAP_MS;
            if(liveActive==f)liveActive=null;
            if(Objects.equals(liveActiveId, target.uuid()))liveActiveId=null;
            MinecraftBridge.runOnClientThread(()-> {
                if(config!=null&&config.legacyTabEnabled())TabNameDecorator.refreshVanillaTab(MinecraftBridge.client());
            }
            );
        }
        );
    }
    private void resetLiveRosterSession(boolean clearAttempts) {
        CompletableFuture<PlayerProfile> active=liveActive;
        if(active!=null&&!active.isDone())active.cancel(true);
        liveActive=null;
        liveQueue.clear();
        liveQueued.clear();
        liveTabRoster.clear();
        liveSatisfied.clear();
        liveRosterCandidate=List.of();
        liveRosterCandidateAt=0;
        liveActiveId=null;
        liveNextStartAt=0;
        if(clearAttempts)liveAttemptAt.clear();
    }
    private void startBulkSync(Object client, BulkSyncService.Scope scope) {
        startBulkSync(client, scope, false, null, null);
    }
    private void startBulkSync(Object client, BulkSyncService.Scope scope, boolean rebuild) {
        startBulkSync(client, scope, rebuild, null, null);
    }
    private void startBulkSync(Object client, BulkSyncService.Scope scope, boolean rebuild, String targetProviderId) {
        startBulkSync(client, scope, rebuild, targetProviderId, null);
    }
    private void startBulkSync(Object client, BulkSyncService.Scope scope, boolean rebuild, String targetProviderId, Collection<String> selectedProviderIds) {
        boolean ru=MinecraftBridge.russian(client);
        if(bulkSync==null) {
            overlay.beginSync(ru?"Синхронизация":"Sync");
            overlay.finishSync(ru?"Недоступна":"Unavailable");
            return;
        }
        if(bulkSync.running()) {
            overlay.beginSync(ru?"Синхронизация":"Sync");
            overlay.finishSync(ru?"Уже выполняется":"Already running");
            return;
        }
        String providerName=targetProviderId;
        if(targetProviderId!=null)for(TierProvider p:providers)if(p.id().equalsIgnoreCase(targetProviderId)) {
            providerName=p.displayName();
            break;
        }
        StateCoordinator.Token token=state.activate(AppState.SYNC);
        String title;
        if(scope==BulkSyncService.Scope.PROVIDER)title=(ru?"Тирлист: ":"Tierlist: ")+(providerName==null?targetProviderId:providerName);
        else if(scope==BulkSyncService.Scope.SELECTED)title=(rebuild?(ru?"Полный ресет · ":"Full reset · "):(ru?"Подгрузка · ":"Download · "))+(selectedProviderIds==null?0:selectedProviderIds.size())+(ru?" тирлистов":" tierlists");
        else title=ru?"Все тирлисты":"All tierlists";
        overlay.beginSync(title);
        java.util.function.Consumer<String> progress=line->overlay.setSyncProgress(line);
        CompletableFuture<BulkSyncService.Summary> syncFuture;
        if(scope==BulkSyncService.Scope.SELECTED)syncFuture=bulkSync.startSelected(selectedProviderIds, rebuild, progress);
        else if(scope==BulkSyncService.Scope.PROVIDER)syncFuture=bulkSync.startProvider(targetProviderId, progress);
        else syncFuture=rebuild?bulkSync.startRebuild(scope, progress):bulkSync.start(scope, progress);
        syncFuture.whenComplete((sum, e)-> {
            if(!state.valid(token))return; state.leave(AppState.SYNC); if(e!=null) {
                if(isCancellation(e)) {
                    overlay.cancelSync(); return;
                }
                overlay.finishSync((ru?"Ошибка: ":"Error: ")+rootMessage(e)); return;
            }
            String done=(ru?"Готово: ":"Done: ")+sum.touched()+(ru?" обновлено":" updated")+" · "+sum.sourcesComplete()+" COMPLETE";
            if(sum.sourcesPartial()>0)done+=(ru?", частичных ":", partial ")+sum.sourcesPartial();
            if(sum.sourcesFailed()>0)done+=(ru?", ошибок ":", errors ")+sum.sourcesFailed();
            overlay.finishSync(done);
        }
        );
    }
    private void handleSearchMouse(Object client) {
        try {
            long h=MinecraftBridge.windowHandle(client);
            if(h==0)return;
            Class<?> g=Class.forName("org.lwjgl.glfw.GLFW");
            DoubleBuffer xb=ByteBuffer.allocateDirect(Double.BYTES).order(ByteOrder.nativeOrder()).asDoubleBuffer();
            DoubleBuffer yb=ByteBuffer.allocateDirect(Double.BYTES).order(ByteOrder.nativeOrder()).asDoubleBuffer();
            g.getMethod("glfwGetCursorPos", long.class, DoubleBuffer.class, DoubleBuffer.class).invoke(null, h, xb, yb);
            IntBuffer wb=ByteBuffer.allocateDirect(Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
            IntBuffer hb=ByteBuffer.allocateDirect(Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
            g.getMethod("glfwGetWindowSize", long.class, IntBuffer.class, IntBuffer.class).invoke(null, h, wb, hb);
            int ww=Math.max(1, wb.get(0)), wh=Math.max(1, hb.get(0));
            int mx=(int)Math.round(xb.get(0)*MinecraftBridge.scaledWidth(client)/ww);
            int my=(int)Math.round(yb.get(0)*MinecraftBridge.scaledHeight(client)/wh);
            if(!searchMouseNativeOkLogged) {
                searchMouseNativeOkLogged=true;
                
            }
            boolean down=((int)g.getMethod("glfwGetMouseButton", long.class, int.class).invoke(null, h, 0))==1;
            boolean pressed=down&&!searchMouseDown;
            searchMouseDown=down;
            boolean right=((int)g.getMethod("glfwGetMouseButton", long.class, int.class).invoke(null, h, 1))==1;
            boolean rightPressed=right&&!searchRightMouseDown;
            searchRightMouseDown=right;
            overlay.handleSearchMouse(mx, my, down, pressed, rightPressed);
        } catch (Throwable t) {
            MinecraftBridge.logOnce("searchMouse", t);
        }
    }
    private static boolean isCancellation(Throwable t) {
        Throwable x=t;
        while(x!=null) {
            if(x instanceof CancellationException)return true;
            x=x.getCause();
        }
        return false;
    }
    private static String rootMessage(Throwable t) {
        Throwable x=t;
        while(x!=null&&x.getCause()!=null&&(x instanceof CompletionException||x instanceof ExecutionException))x=x.getCause();
        if(x==null)return "unknown";
        String m=x.getMessage();
        return m==null||m.isBlank()?x.getClass().getSimpleName():m;
    }
    private boolean alt(Object client) {
        return rawKey(client, 342)||rawKey(client, 346);
    }
    private boolean ctrl(Object client) {
        return rawKey(client, 341)||rawKey(client, 345);
    }
    private boolean shift(Object client) {
        return rawKey(client, 340)||rawKey(client, 344);
    }
    private boolean edge(Object client, int key) {
        if(key<0||key>=rawDown.length)return false;
        boolean d=rawKey(client, key), hit=d&&!rawDown[key];
        rawDown[key]=d;
        return hit;
    }
    private boolean repeatKey(Object client, int key) {
        if(key<0||key>=repeatTicks.length)return false;
        boolean d=rawKey(client, key);
        if(!d) {
            repeatTicks[key]=0;
            return false;
        }
        int t=repeatTicks[key]++;
        return t==0||(t>=8&&((t-8)%2==0));
    }
    private void resetInputState() {
        Arrays.fill(rawDown, false);
        Arrays.fill(repeatTicks, 0);
        searchActivatorDown=false;
        searchMouseDown=false;
        searchRightMouseDown=false;
        tabInteractionRightDown=false;
    }
    private void resetRepeatOnly() {
        Arrays.fill(repeatTicks, 0);
    }
    private boolean rawKey(Object client, int key) {
        try {
            if(key<0)return false;
            long h=MinecraftBridge.windowHandle(client);
            if(h==0)return false;
            Method m=glfwGetKeyMethod;
            if(m==null) {
                synchronized(this) {
                    m=glfwGetKeyMethod;
                    if(m==null)glfwGetKeyMethod=m=Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwGetKey", long.class, int.class);
                }
            }
            return ((int)m.invoke(null, h, key))==1;
        } catch (Exception e) {
            return false;
        }
    }
    private boolean rawMouseButton(Object client, int button) {
        try {
            long h=MinecraftBridge.windowHandle(client);
            if(h==0)return false;
            Method m=glfwGetMouseButtonMethod;
            if(m==null) {
                synchronized(this) {
                    m=glfwGetMouseButtonMethod;
                    if(m==null)glfwGetMouseButtonMethod=m=Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwGetMouseButton", long.class, int.class);
                }
            }
            return ((int)m.invoke(null, h, button))==1;
        } catch (Exception e) {
            return false;
        }
    }
    private String getClipboard(Object client) {
        try {
            long h=MinecraftBridge.windowHandle(client);
            Object v=Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwGetClipboardString", long.class).invoke(null, h);
            return v==null?"":String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }
    private void setClipboard(Object client, String s) {
        try {
            long h=MinecraftBridge.windowHandle(client);
            Class<?> g=Class.forName("org.lwjgl.glfw.GLFW");
            Method m;
            try {
                m=g.getMethod("glfwSetClipboardString", long.class, CharSequence.class);
            } catch (NoSuchMethodException e) {
                m=g.getMethod("glfwSetClipboardString", long.class, String.class);
            }
            m.invoke(null, h, s==null?"":s);
        } catch (Throwable t) {
            BootstrapLog.error("clipboard", t);
        }
    }
    private void captureGameplayKeys(Object client) {
        if(overlay==null||!overlay.ownsKeyboard()) {
            if(gameplayKeysSuppressed)releaseGameplayKeys();
            return;
        }
        try {
            Class.forName("net.minecraft.class_304").getMethod("method_1437").invoke(null);
            gameplayKeysSuppressed=true;
        } catch (Throwable t) {
            if(!gameplayKeysSuppressed)BootstrapLog.error("SEARCH gameplay key capture", t);
        }
    }
    private void releaseGameplayKeys() {
        if(!gameplayKeysSuppressed)return;
        try {
            Class.forName("net.minecraft.class_304").getMethod("method_1424").invoke(null);
        } catch (Throwable t) {
            BootstrapLog.error("SEARCH gameplay key release", t);
        } finally {
            gameplayKeysSuppressed=false;
        }
    }
    private void handleDisabledTick(Object client) {
        // Drain the user hotkey while disabled/inside settings so no latent press can open TierLookup later.
        while(KeyBindingBridge.consumeOpen()) {
        }
        openRawDown=rawKey(client, KeyBindingBridge.openKeyCode());
        if(!disabledApplied) {
            disabledApplied=true;
            searchGeneration++;
            state.closeWorkspace();
            if(overlay!=null)overlay.close();
            SearchCaptureScreen.closeIfOpen();
            if(TabInteractionScreen.isOpen())TabInteractionScreen.finishForSelection();
            releaseGameplayKeys();
            resetInputState();
            resetLiveRosterSession(true);
            networkAttemptAt.clear();
            hoverTarget=null;
            if(service!=null)service.cancelActiveNetworkLookups();
            if(bulkSync!=null&&bulkSync.running())bulkSync.cancel();
            TabNameDecorator.restoreVanillaTab(client);
            
        }
    }
    public static void reconcileInputAfterModal() {
        TierLookupClient i=INSTANCE;
        if(i==null)return;
        Object client=MinecraftBridge.client();
        i.releaseGameplayKeys();
        i.resetInputState();
        if(i.overlay!=null&&i.overlay.open()&&SearchCaptureScreen.isOpen())i.captureGameplayKeys(client);
        else if(MinecraftBridge.currentScreenObject(client)==null)com.tierlookup.client.runtime.MinecraftRuntime.active().restoreGameplayControl(client);
    }
    public static void onRamCacheLimitChanged() {
        TierLookupClient i=INSTANCE;
        if(i!=null&&i.service!=null)i.service.enforceRamBudgetNow();
    }
    public static boolean pinFromFullMode(PlayerProfile p) {
        TierLookupClient i=INSTANCE;
        if(i==null||i.overlay==null||p==null)return false;
        return i.overlay.toggleTablePin(p);
    }
    public static void enterFullModeState() {
        TierLookupClient i=INSTANCE;
        if(i!=null)i.state.enter(AppState.FULL_MODE);
    }
    public static void returnToKFromFullMode() {
        TierLookupClient i=INSTANCE;
        if(i==null||i.overlay==null)return;
        Object client=MinecraftBridge.client();
        i.overlay.setFullMode(false);
        i.state.enter(i.overlay.secondaryProfile()!=null?AppState.COMPARE:AppState.SEARCH);
        i.overlay.openMain();
        i.overlay.activateSearch();
        i.overlay.setSearchMessage("");
        SearchCaptureScreen.open();
        i.resetInputState();
        i.searchOpenKeySuppress=true;
        i.captureGameplayKeys(client);
        
    }
    public static void exitFullModeToGame() {
        TierLookupClient i=INSTANCE;
        if(i==null)return;
        Object client=MinecraftBridge.client();
        i.searchGeneration++;
        i.state.closeWorkspace();
        if(i.overlay!=null) {
            i.overlay.setFullMode(false);
            i.overlay.close();
        }
        SearchCaptureScreen.closeIfOpen();
        i.releaseGameplayKeys();
        i.resetInputState();
        
    }
    public static boolean startBulkSyncFromSettings(boolean all) {
        TierLookupClient i=INSTANCE;
        if(i==null||i.bulkSync==null||i.overlay==null||i.bulkSync.running()||i.config==null||!i.config.masterEnabled())return false;
        i.startBulkSync(MinecraftBridge.client(), BulkSyncService.Scope.ALL, false);
        return true;
    }
    public static boolean startProviderSyncFromSettings(String providerId) {
        TierLookupClient i=INSTANCE;
        if(i==null||i.bulkSync==null||i.overlay==null||!BulkSyncService.supportsSingleProvider(providerId)||i.bulkSync.running()||i.config==null||!i.config.masterEnabled())return false;
        i.startBulkSync(MinecraftBridge.client(), BulkSyncService.Scope.PROVIDER, false, providerId);
        return true;
    }
    public static boolean startFullRebuildFromSettings() {
        TierLookupClient i=INSTANCE;
        if(i==null||i.bulkSync==null||i.overlay==null||i.bulkSync.running()||i.config==null||!i.config.masterEnabled())return false;
        i.startBulkSync(MinecraftBridge.client(), BulkSyncService.Scope.ALL, true);
        return true;
    }
    public static boolean startSelectedSyncFromSettings(Collection<String> providerIds, boolean fullReset) {
        TierLookupClient i=INSTANCE;
        if(i==null||i.bulkSync==null||i.overlay==null||i.bulkSync.running()||i.config==null||!i.config.masterEnabled()||providerIds==null||providerIds.isEmpty())return false;
        i.startBulkSync(MinecraftBridge.client(), BulkSyncService.Scope.SELECTED, fullReset, null, providerIds);
        return true;
    }
    public static boolean cancelBulkSyncFromSettings() {
        TierLookupClient i=INSTANCE;
        if(i==null||i.bulkSync==null||!i.bulkSync.running())return false;
        i.bulkSync.cancel();
        if(i.overlay!=null)i.overlay.cancelSync();
        i.state.leave(AppState.SYNC);
        return true;
    }
    public static List<ProfileService.SyncManifest> syncManifests() {
        TierLookupClient i=INSTANCE;
        return i==null||i.service==null?List.of():i.service.syncManifests();
    }
    public static boolean bulkSyncRunning() {
        TierLookupClient i=INSTANCE;
        return i!=null&&i.bulkSync!=null&&i.bulkSync.running();
    }
    public static TierLookupConfig configInstance() {
        TierLookupClient i=INSTANCE;
        return i==null?null:i.config;
    }
    public static List<TierProvider> providersInstance() {
        TierLookupClient i=INSTANCE;
        return i==null?List.of():i.providers;
    }
    public static ProfileService profileServiceInstance() {
        TierLookupClient i=INSTANCE;
        return i==null?null:i.service;
    }
    public static OverlayRenderer overlayInstance() {
        TierLookupClient i=INSTANCE;
        return i==null?null:i.overlay;
    }
    public static void copyTextFromUi(String value) {
        TierLookupClient i=INSTANCE;
        if(i!=null)i.setClipboard(MinecraftBridge.client(), value==null?"":value);
    }
    public static void onProviderSettingsChanged() {
        TierLookupClient i=INSTANCE;
        if(i==null)return;
        i.liveSatisfied.clear();
        if(i.config!=null&&i.config.masterEnabled()&&MinecraftBridge.hasWorld(MinecraftBridge.client()))i.scanLiveTabRoster(MinecraftBridge.client());
    }
    public static void onDataModeChanged() {
        TierLookupClient i=INSTANCE;
        if(i==null||i.config==null)return;
        i.resetLiveRosterSession(false);
        i.networkAttemptAt.clear();
        if(!i.config.normalRuntimeNetworkAllowed()&&i.service!=null)i.service.cancelActiveNetworkLookups();
        if(i.config.masterEnabled()&&MinecraftBridge.hasWorld(MinecraftBridge.client()))i.scanLiveTabRoster(MinecraftBridge.client());
        
    }
    public static void onMasterSettingChanged() {
        TierLookupClient i=INSTANCE;
        if(i==null||i.config==null)return;
        Object client=MinecraftBridge.client();
        if(!i.config.masterEnabled())i.handleDisabledTick(client);
        else {
            i.disabledApplied=false;
            i.openRawDown=i.rawKey(client, KeyBindingBridge.openKeyCode());
            
        }
    }
}
