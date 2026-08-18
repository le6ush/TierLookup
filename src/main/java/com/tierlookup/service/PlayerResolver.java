package com.tierlookup.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import com.tierlookup.model.PlayerIdentity;
import com.tierlookup.net.Http;
import com.tierlookup.net.MiniJson;

/** Username resolver with independent public fallbacks so one stalled Mojang host cannot leave search spinning forever. */
public final class PlayerResolver {
    private PlayerResolver() {
    }
    public static CompletableFuture<PlayerIdentity> resolve(String nickname) {
        String clean=nickname==null?"":nickname.trim();
        if(!clean.matches("[A-Za-z0-9_]{1,16}"))return CompletableFuture.failedFuture(new IllegalArgumentException("invalid nickname"));
        String n=URLEncoder.encode(clean, StandardCharsets.UTF_8);
        List<String> urls=List.of( "https://playerdb.co/api/player/minecraft/"+n,
            "https://api.mojang.com/users/profiles/minecraft/"+n,
            "https://api.minecraftservices.com/minecraft/profile/lookup/name/"+n);
        CompletableFuture<PlayerIdentity> out=new CompletableFuture<>();
        tryNext(clean, urls, 0, out, null);
        return out;
    }
    private static void tryNext(String requested, List<String> urls, int i, CompletableFuture<PlayerIdentity> out, Throwable previous) {
        if(i>=urls.size()) {
            Throwable cause=previous==null?new IllegalArgumentException("profile not found"):unwrap(previous);
            
            out.completeExceptionally(cause);
            return;
        }
        String url=urls.get(i);
        
        Http.get(url, 4).thenApply(body->parse(body, requested)).whenComplete((p, e)-> {
            if(e==null&&p!=null) {
                 out.complete(p);
            } else {
                 tryNext(requested, urls, i+1, out, e);
            }
        }
        );
    }
    @SuppressWarnings("unchecked") private static PlayerIdentity parse(String body, String requested) {
        Object root=MiniJson.parse(body);
        if(!(root instanceof Map<?, ?> rr))throw new IllegalArgumentException("profile not found");
        Map<String, Object> m=(Map<String, Object>)rr;
        // PlayerDB: {data:{player:{username,id,raw_id}}}
        Object data=get(m, "data");
        if(data instanceof Map<?, ?> dm) {
            Object player=get((Map<String, Object>)dm, "player");
            if(player instanceof Map<?, ?> pm) {
                PlayerIdentity p=identity((Map<String, Object>)pm, requested);
                if(p!=null)return p;
            }
        }
        PlayerIdentity direct=identity(m, requested);
        if(direct!=null)return direct;
        throw new IllegalArgumentException("profile not found");
    }
    private static PlayerIdentity identity(Map<String, Object> m, String requested) {
        Object idObj=first(m, "id", "raw_id", "uuid");
        Object nameObj=first(m, "name", "username", "playername");
        if(idObj==null)return null;
        String raw=String.valueOf(idObj).replace("-", "").trim();
        if(raw.length()!=32)return null;
        UUID uuid;
        try {
            uuid=UUID.fromString(raw.substring(0, 8)+"-"+raw.substring(8, 12)+"-"+raw.substring(12, 16)+"-"+raw.substring(16, 20)+"-"+raw.substring(20));
        } catch (Exception e) {
            return null;
        }
        String name=nameObj==null||String.valueOf(nameObj).isBlank()?requested:String.valueOf(nameObj);
        return new PlayerIdentity(uuid, name);
    }
    private static Object first(Map<String, Object>m, String...ks) {
        for(String k:ks) {
            Object v=get(m, k);
            if(v!=null)return v;
        }
        return null;
    }
    private static Object get(Map<String, Object>m, String k) {
        for(var e:m.entrySet())if(e.getKey().equalsIgnoreCase(k))return e.getValue();
        return null;
    }
    private static Throwable unwrap(Throwable t) {
        Throwable x=t;
        while(x!=null&&x.getCause()!=null&&(x instanceof CompletionException||x instanceof ExecutionException))x=x.getCause();
        return x==null?t:x;
    }
}
