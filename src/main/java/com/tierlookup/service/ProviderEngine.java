package com.tierlookup.service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.tierlookup.model.*;
import com.tierlookup.provider.TierProvider;

/**
* Provider Engine 2.0: bounded worker pool, per-provider serialization/rate spacing, timeouts and
* a small circuit breaker. A failing source never blocks other sources or the render thread.
*/ public final class ProviderEngine implements AutoCloseable {
    private static final int BREAKER_FAILURES=3;
    private static final long BREAKER_MS=30_000L;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor( 4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), r-> {
        Thread t=new Thread(r, "TierLookup-Provider"); t.setDaemon(true); t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY-2)); return t;
    }, new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, RuntimeState> states=new ConcurrentHashMap<>();
    private final Set<CompletableFuture<ProviderResult>> outputs=ConcurrentHashMap.newKeySet();
    /** Raw provider futures are tracked separately so shutdown/cancel can stop HTTP work, not only UI wrappers. */
    private final Set<CompletableFuture<ProviderResult>> rawFutures=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final TierLookupConfig config;
    public ProviderEngine(TierLookupConfig config) {
        this.config=config;
    }
    public CompletableFuture<ProviderResult> lookup(TierProvider provider, PlayerIdentity player) {
        if(provider==null||player==null)return CompletableFuture.completedFuture(ProviderResult.error(provider==null?"unknown":provider.id(),
            provider==null?"Unknown":provider.displayName(),
            "invalid provider lookup"));
        if(closed.get())return CompletableFuture.completedFuture(ProviderResult.error(provider.id(), provider.displayName(), "provider engine closed"));
        RuntimeState state=states.computeIfAbsent(provider.id(), k->new RuntimeState());
        long now=System.currentTimeMillis();
        if(state.openUntil.get()>now) {
            return CompletableFuture.completedFuture(ProviderResult.error(provider.id(), provider.displayName(), "circuit open"));
        }
        CompletableFuture<ProviderResult> out=new CompletableFuture<>();
        outputs.add(out);
        out.whenComplete((r, e)->outputs.remove(out));
        try {
            workers.execute(()-> {
                boolean acquired=false; CompletableFuture<ProviderResult> raw=null; try {
                    if(out.isDone())return;
                    state.slot.acquire();
                    acquired=true;
                    if(out.isDone())return;
                    long wait=Math.max(0, state.nextAllowedAt.get()-System.currentTimeMillis());
                    if(wait>0)Thread.sleep(Math.min(wait, 2500));
                    if(out.isDone())return;
                    state.nextAllowedAt.set(System.currentTimeMillis()+minSpacingMs(provider.id()));
                    try {
                        raw=provider.lookup(player);
                    } catch (Throwable t) {
                        raw=CompletableFuture.failedFuture(t);
                    }
                    if(raw==null)raw=CompletableFuture.completedFuture(ProviderResult.error(provider.id(), provider.displayName(), "provider returned null future"));
                    rawFutures.add(raw);
                    CompletableFuture<ProviderResult> trackedRaw=raw;
                    trackedRaw.whenComplete((r, e)->rawFutures.remove(trackedRaw));
                    int timeout=timeoutSeconds(provider.id());
                    CompletableFuture<ProviderResult> guarded=raw.copy().orTimeout(timeout, TimeUnit.SECONDS);
                    ProviderResult result=guarded.join();
                    if(result!=null&&result.status()==ProviderResult.Status.ERROR)registerFailure(provider, state);
                    else {
                        state.failures.set(0); state.openUntil.set(0);
                    }
                    out.complete(result==null?ProviderResult.error(provider.id(), provider.displayName(), "empty provider result"):result);
                } catch (Throwable t) {
                    if(raw!=null&&!raw.isDone())raw.cancel(true);
                    Throwable root=unwrap(t);
                    if(closed.get()||root instanceof InterruptedException||root instanceof CancellationException) {
                        out.completeExceptionally(new CancellationException("provider lookup cancelled"));
                    } else {
                        registerFailure(provider, state); out.complete(ProviderResult.error(provider.id(), provider.displayName(), rootMessage(root)));
                    }
                } finally {
                    if(acquired)state.slot.release();
                }
            }
            );
        } catch (RejectedExecutionException saturated) {
            out.complete(ProviderResult.error(provider.id(), provider.displayName(), "provider queue full"));
        }
        return out;
    }
    public List<TierProvider> ordered(List<TierProvider> providers) {
        ArrayList<TierProvider> out=new ArrayList<>(providers);
        out.sort(Comparator.comparingInt(p->priority(p.id())));
        return out;
    }
    public boolean circuitOpen(String id) {
        RuntimeState s=states.get(id);
        return s!=null&&s.openUntil.get()>System.currentTimeMillis();
    }
    public String health(String id) {
        RuntimeState s=states.get(id);
        if(s==null)return "idle";
        if(circuitOpen(id))return "open";
        return s.failures.get()==0?"ok":"failures="+s.failures.get();
    }
    public void reset() {
        for(RuntimeState s:states.values()) {
            s.failures.set(0);
            s.openUntil.set(0);
            s.nextAllowedAt.set(0);
        }
    }
    /** Cancel current/queued profile lookups without closing the reusable engine. Used by master-off and hot-cache-only switches. */
    public void cancelActive() {
        for(CompletableFuture<ProviderResult> raw:new ArrayList<>(rawFutures))if(raw!=null&&!raw.isDone())raw.cancel(true);
        for(CompletableFuture<ProviderResult> out:new ArrayList<>(outputs))if(out!=null&&!out.isDone())out.completeExceptionally(new CancellationException("provider lookup cancelled by runtime mode"));
    }
    private void registerFailure(TierProvider provider, RuntimeState state) {
        int failures=state.failures.incrementAndGet();
        if(failures>=BREAKER_FAILURES) {
            state.openUntil.set(System.currentTimeMillis()+BREAKER_MS);
            
        }
    }
    private int priority(String id) {
        return switch(id) {
            case "mctiers", "subtiers", "flowpvp"->0;
            case "pvptiers", "atiers", "cistiers", "mytiers", "centraltierlist"->1;
            default->3;
        };
    }
    private int timeoutSeconds(String id) {
        return switch(id) {
            case "mctiers", "subtiers"->4;
            case "pvptiers"->7;
            case "atiers", "mytiers", "centraltierlist"->7;
            case "flowpvp"->6;
            default->5;
        };
    }
    private long minSpacingMs(String id) {
        return switch(id) {
            case "atiers", "mytiers", "centraltierlist"->180;
            default->90;
        };
    }
    private static Throwable unwrap(Throwable t) {
        Throwable x=t;
        while(x!=null&&x.getCause()!=null&&(x instanceof CompletionException||x instanceof ExecutionException))x=x.getCause();
        return x==null?t:x;
    }
    private static String rootMessage(Throwable t) {
        String m=t==null?null:t.getMessage();
        return m==null||m.isBlank()?(t==null?"unknown":t.getClass().getSimpleName()):m;
    }
    @Override
    public void close() {
        if(!closed.compareAndSet(false, true))return;
        List<Runnable> dropped=workers.shutdownNow();
        for(CompletableFuture<ProviderResult> raw:rawFutures)if(raw!=null&&!raw.isDone())raw.cancel(true);
        rawFutures.clear();
        for(CompletableFuture<ProviderResult> out:outputs)out.completeExceptionally(new CancellationException("provider engine closed"));
        outputs.clear();
        states.clear();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    private static final class RuntimeState {
        final Semaphore slot=new Semaphore(1);
        final AtomicInteger failures=new AtomicInteger();
        final AtomicLong openUntil=new AtomicLong(), nextAllowedAt=new AtomicLong();
    }
}
