package com.tierlookup.core;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
* Session-aware state coordinator. Workspace states (TARGET/SEARCH/COMPARE/FULL_MODE) are mutually
* exclusive; SYNC and KIT_SESSION are orthogonal and may stay active at the same time. Each state
* has its own generation, so starting a sync cannot invalidate an in-flight compare callback and
* vice versa. Disconnect invalidates every token from the previous world in one operation.
*/ public final class StateCoordinator {
    public record Token(long session, long generation, AppState state) {
    }
    private static final EnumSet<AppState> WORKSPACE=EnumSet.of(AppState.TARGET, AppState.SEARCH, AppState.COMPARE, AppState.FULL_MODE);
    private final AtomicLong session=new AtomicLong(1), clock=new AtomicLong(1);
    private final EnumMap<AppState, Long> generations=new EnumMap<>(AppState.class);
    private final EnumSet<AppState> active=EnumSet.noneOf(AppState.class);
    private volatile AppState workspace=AppState.IDLE;
    public StateCoordinator() {
        for(AppState s:AppState.values())generations.put(s, 1L);
    }
    /** Enter a state. Workspace entry replaces only another workspace state, not sync/kit session. */
    public synchronized Token enter(AppState next) {
        if(next==null||next==AppState.IDLE) {
            closeWorkspace();
            return token(AppState.IDLE);
        }
        if(WORKSPACE.contains(next)) {
            for(AppState s:WORKSPACE)if(s!=next&&active.remove(s))bump(s);
            workspace=next;
        }
        active.add(next);
        long g=bump(next);
        return new Token(session.get(), g, next);
    }
    /** Mark an orthogonal state active without needlessly changing its generation when already active. */
    public synchronized Token activate(AppState next) {
        if(next==null||next==AppState.IDLE)return token(AppState.IDLE);
        if(active.contains(next))return token(next);
        return enter(next);
    }
    public synchronized Token token() {
        return token(workspace==AppState.IDLE?AppState.IDLE:workspace);
    }
    public synchronized Token token(AppState state) {
        return new Token(session.get(), generations.getOrDefault(state, 1L), state);
    }
    public synchronized void leave(AppState expected) {
        if(expected==null||expected==AppState.IDLE)return;
        if(active.remove(expected))bump(expected);
        if(WORKSPACE.contains(expected)&&workspace==expected)workspace=AppState.IDLE;
    }
    public synchronized void closeWorkspace() {
        for(AppState s:WORKSPACE)if(active.remove(s))bump(s);
        workspace=AppState.IDLE;
    }
    public synchronized void disconnect() {
        session.incrementAndGet();
        clock.incrementAndGet();
        for(AppState s:AppState.values())generations.put(s, clock.get());
        active.clear();
        workspace=AppState.IDLE;
    }
    /** Valid means same world/session, same state generation, and the state is still active. */
    public synchronized boolean valid(Token token) {
        return token!=null&&token.state()!=AppState.IDLE&&token.session()==session.get()&&active.contains(token.state())&&generations.getOrDefault(token.state(),
            -1L)==token.generation();
    }
    public boolean sameSession(Token token) {
        return token!=null&&token.session()==session.get();
    }
    public synchronized boolean active(AppState state) {
        return active.contains(state);
    }
    public synchronized Set<AppState> activeStates() {
        return Set.copyOf(active);
    }
    public AppState state() {
        return workspace;
    }
    public long session() {
        return session.get();
    }
    public long generation() {
        return clock.get();
    }
    private long bump(AppState state) {
        long g=clock.incrementAndGet();
        generations.put(state, g);
        return g;
    }
}
