package com.tierlookup.client;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

import com.tierlookup.model.PlayerIdentity;

/** Draws provider-aware tier-list icons as vanilla Minecraft item sprites. */
public final class KitIconRenderer {
    private static final Map<String, Object> STACKS = new ConcurrentHashMap<>();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    private static volatile Method drawItemMethod, fillRectMethod;
    private static volatile Class<?> itemStackClass;
    private static volatile boolean initFailureLogged;
    // Profile heads use Minecraft's own PlayerSkinProvider asynchronously. They are never requested for
    // autocomplete rows, only for visible profile/table headers. LRU + failure cooldown keep the path bounded.
    private static final Map<UUID, Object> SKIN_CACHE=Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Object> e) {
            return size()>512;
        }
    }
    );
    private static final ConcurrentHashMap<UUID, CompletableFuture<?>> SKIN_PENDING=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> SKIN_FAILURE_UNTIL=new ConcurrentHashMap<>();
    private static final ExecutorService SKIN_EXECUTOR=Executors.newSingleThreadExecutor(r-> {
        Thread t=new Thread(r, "TierLookup-Skin"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t;
    }
    );
    private static volatile Method skinDrawMethod, defaultSkinMethod, getSkinProviderMethod, fetchSkinMethod;
    private static volatile Method getNetworkHandlerMethod, getPlayerListEntryMethod, getEntrySkinMethod;
    private static volatile boolean skinInitFailureLogged;
    private KitIconRenderer() {
    }
    /** Backward-compatible generic mapping. */
    public static String itemIdForMode(String mode) {
        return itemIdForMode("", mode);
    }
    /** Provider-specific mapping. The same mode label may intentionally use a different icon per tierlist. */
    public static String itemIdForMode(String providerId, String mode) {
        String p=normalize(providerId), s=normalize(mode);
        // Explicit provider-specific meanings from the tier-list UIs.
        if(p.equals("mctiers")) {
            if(isVanilla(s)||s.contains("crystal"))return "end_crystal";
            if(isDiamondPot(s))return "splash_potion";
            if(isNetheritePot(s))return "netherite_chestplate";
            if(isSmp(s))return "ender_pearl";
        }
        if(p.equals("pvptiers")) {
            if(isVanilla(s)||s.contains("crystal"))return "end_crystal";
            if(isDiamondPot(s))return "splash_potion";
            if(isNetheritePot(s))return "netherite_chestplate";
            if(isSmp(s))return "shield";
        }
        if(p.equals("subtiers")) {
            if(isDiamondSmp(s))return "chorus_fruit";
            if(s.contains("dia crystal")||s.contains("diamond crystal")||s.contains("crystal"))return "end_crystal";
            if(s.contains("og vanilla"))return "golden_apple";
        }
        if(p.equals("cistiers")) {
            if(isVanilla(s)||s.contains("crystal"))return "end_crystal";
            if(isDiamondPot(s))return "splash_potion";
            if(isDiamondSmp(s))return "diamond_chestplate";
            if(isSmp(s))return "shield";
            if(isOp(s))return "ender_pearl";
            if(isNetheritePot(s)||s.equals("netherite"))return "netherite_chestplate";
        }
        if(p.equals("atiers")) {
            if(isVanilla(s)||s.contains("crystal"))return "end_crystal";
            if(isDiamondSmp(s))return "chorus_fruit";
            if(isSmp(s))return "shield";
            if(isOp(s))return "ender_pearl";
            if(isNetheritePot(s)||s.equals("netherite"))return "netherite_chestplate";
        }
        // Generic aliases for remaining providers/modes. Spear is intentionally checked before Mace:
        // providers may call the later-version kit "SpearMace", but it must not inherit the vanilla Mace/Trident icon.
        if (s.contains("minecart") || s.equals("cart")) return "tnt_minecart";
        if (isSpear(s)) return "__tierlookup_spear__";
        if (s.contains("mace")) return "mace";
        if (s.contains("axe")) return "diamond_axe";
        if (s.contains("crystal") || s.contains("cpvp") || isVanilla(s)) return "end_crystal";
        // SUHC / Shieldless UHC used to fall through contains("uhc") and therefore rendered a second
        // indistinguishable golden apple next to regular UHC. Give it the honeycomb icon used by the kit detector.
        if (s.equals("suhc") || s.contains("shieldless uhc") || s.contains("shieldlessuhc")) return "honeycomb";
        if (s.contains("uhc")) return "golden_apple";
        if (isNetheritePot(s)) return "netherite_chestplate";
        if (isDiamondPot(s)) return "splash_potion";
        if (isDiamondSmp(s)) return "diamond_chestplate";
        if (isOp(s)) return "ender_pearl";
        if (isSmp(s)) return "shield";
        if (s.contains("sword") || s.contains("classic")) return "diamond_sword";
        if (s.contains("shield")) return "shield";
        if (s.contains("debuff")) return "fermented_spider_eye";
        if (s.contains("speed")) return "sugar";
        if (s.contains("bow")) return "bow";
        if (s.contains("trident")) return "trident";
        if (s.contains("elytra")) return "elytra";
        if (s.contains("bed")) return "red_bed";
        if (s.contains("creeper")) return "creeper_head";
        if (s.contains("manhunt")) return "compass";
        if (s.contains("netherite") || s.equals("neth")) return "netherite_chestplate";
        return "paper";
    }
    /**
    * Draw the real Minecraft skin head (including hat layer) for a visible profile. The first frame uses
    * Minecraft's deterministic default skin and starts one bounded async skin-provider request; later frames
    * reuse the cached SkinTextures. Autocomplete never calls this method.
    */ public static boolean drawPlayerHead(Object drawContext, PlayerIdentity player, int x, int y) {
        if(drawContext==null||player==null||player.uuid()==null)return false;
        try {
            Object skin;
            synchronized(SKIN_CACHE) {
                skin=SKIN_CACHE.get(player.uuid());
            }
            // If the player is currently on this server, vanilla already has the real SkinTextures in the
            // tab-list entry. Prefer that zero-network path before any profile/session lookup.
            if(skin==null) {
                skin=onlineSkin(player.uuid());
                if(skin!=null)synchronized(SKIN_CACHE) {
                    SKIN_CACHE.put(player.uuid(), skin);
                }
            }
            if(skin==null) {
                skin=defaultSkin(player.uuid());
                requestSkin(player);
            }
            if(skin==null)return drawItem(drawContext, "player_head", x, y);
            Method draw=skinDrawMethod;
            Class<?> ctxCl=Class.forName("net.minecraft.class_332"), skinCl=Class.forName("net.minecraft.class_8685");
            if(draw==null) {
                draw=Class.forName("net.minecraft.class_7532").getMethod("method_52722", ctxCl, skinCl, int.class, int.class, int.class);
                skinDrawMethod=draw;
            }
            draw.invoke(null, drawContext, skin, x, y, 16);
            return true;
        } catch (Throwable t) {
            if(!skinInitFailureLogged) {
                skinInitFailureLogged=true;
                BootstrapLog.error("PLAYER HEAD draw/init", unwrap(t));
            }
            return drawItem(drawContext, "player_head", x, y);
        }
    }
    private static Object onlineSkin(UUID id) {
        try {
            Object client=MinecraftBridge.client();
            if(client==null)return null;
            Method nh=getNetworkHandlerMethod;
            if(nh==null) {
                nh=client.getClass().getMethod("method_1562");
                getNetworkHandlerMethod=nh;
            }
            Object handler=nh.invoke(client);
            if(handler==null)return null;
            Method entryM=getPlayerListEntryMethod;
            if(entryM==null||!entryM.getDeclaringClass().isAssignableFrom(handler.getClass())) {
                entryM=handler.getClass().getMethod("method_2871", UUID.class);
                getPlayerListEntryMethod=entryM;
            }
            Object entry=entryM.invoke(handler, id);
            if(entry==null)return null;
            Method skinM=getEntrySkinMethod;
            if(skinM==null||!skinM.getDeclaringClass().isAssignableFrom(entry.getClass())) {
                skinM=entry.getClass().getMethod("method_52810");
                getEntrySkinMethod=skinM;
            }
            Object skin=skinM.invoke(entry);
            return skin;
        } catch (Throwable ignored) {
            return null;
        }
    }
    public static boolean drawPlayerHead(Object drawContext, int x, int y) {
        return drawItem(drawContext, "player_head", x, y);
    }
    private static Object defaultSkin(UUID id) {
        try {
            Method m=defaultSkinMethod;
            if(m==null) {
                m=Class.forName("net.minecraft.class_1068").getMethod("method_4648", UUID.class);
                defaultSkinMethod=m;
            }
            return m.invoke(null, id);
        } catch (Throwable t) {
            return null;
        }
    }
    private static void requestSkin(PlayerIdentity player) {
        UUID id=player.uuid();
        long now=System.currentTimeMillis();
        Long failUntil=SKIN_FAILURE_UNTIL.get(id);
        if(failUntil!=null&&failUntil>now)return;
        CompletableFuture<Object> marker=new CompletableFuture<>();
        if(SKIN_PENDING.putIfAbsent(id, marker)!=null)return;
        // Profile-property resolution may touch Mojang/session services. Keep the entire path off the render/client thread.
        SKIN_EXECUTOR.execute(()-> {
            try {
                Object client=MinecraftBridge.client(); if(client==null) {
                    finishSkinMarker(id, marker); return;
                }
                Method get=getSkinProviderMethod; if(get==null) {
                    get=client.getClass().getMethod("method_1582"); getSkinProviderMethod=get;
                }
                Object provider=get.invoke(client); if(provider==null) {
                    finishSkinMarker(id, marker); return;
                }
                Class<?> gpCl=Class.forName("com.mojang.authlib.GameProfile");
                Object gp=gpCl.getConstructor(UUID.class, String.class).newInstance(id, player.name());
                // A bare UUID/name GameProfile often has no signed texture properties. Resolve the enriched profile
                // through Minecraft's ApiServices/GameProfileResolver first, then let PlayerSkinProvider cache it.
                try {
                    Object api=client.getClass().getMethod("method_73361").invoke(client); if(api!=null) {
                        Object resolver=api.getClass().getMethod("comp_4624").invoke(api); if(resolver!=null) {
                            Object resolved=resolver.getClass().getMethod("method_73290", UUID.class).invoke(resolver, id);
                            if(resolved instanceof Optional<?> opt&&opt.isPresent())gp=opt.get();
                            // Some resolvers can know the UUID but still return a profile without texture properties.
                            // A name resolution is a safe secondary vanilla path and remains off the render thread.
                            if(!profileHasTextures(gp)&&player.name()!=null&&!player.name().isBlank()) {
                                Object byName=resolver.getClass().getMethod("method_73289", String.class).invoke(resolver, player.name());
                                if(byName instanceof Optional<?> opt&&opt.isPresent()&&profileHasTextures(opt.get()))gp=opt.get();
                            }
                        }
                    }
                } catch (Throwable resolveError) {
                    
                }
                Method fetch=fetchSkinMethod; if(fetch==null||!fetch.getDeclaringClass().isAssignableFrom(provider.getClass())) {
                    fetch=provider.getClass().getMethod("method_52863", gpCl); fetchSkinMethod=fetch;
                }
                Object f=fetch.invoke(provider, gp); if(!(f instanceof CompletableFuture<?> future)) {
                    finishSkinMarker(id, marker); return;
                }
                future.whenComplete((value, error)-> {
                    try {
                        if(error!=null) {
                            SKIN_FAILURE_UNTIL.put(id, System.currentTimeMillis()+2*60_000L); return;
                        }
                        Object skin=value; if(skin instanceof Optional<?> opt)skin=opt.orElse(null); if(skin!=null) {
                            synchronized(SKIN_CACHE) {
                                SKIN_CACHE.put(id, skin);
                            }
                            SKIN_FAILURE_UNTIL.remove(id); 
                        } else SKIN_FAILURE_UNTIL.put(id, System.currentTimeMillis()+2*60_000L);
                    } finally {
                        finishSkinMarker(id, marker);
                    }
                }
                );
            } catch (Throwable t) {
                finishSkinMarker(id, marker); SKIN_FAILURE_UNTIL.put(id, System.currentTimeMillis()+2*60_000L); if(!skinInitFailureLogged) {
                    skinInitFailureLogged=true; BootstrapLog.error("PLAYER HEAD fetch/init", unwrap(t));
                }
            }
        }
        );
    }
    private static boolean profileHasTextures(Object gp) {
        if(gp==null)return false;
        try {
            Object props=gp.getClass().getMethod("getProperties").invoke(gp);
            if(props instanceof Map<?, ?> m)return m.containsKey("textures")&&!Objects.toString(m.get("textures"), "").isBlank();
            Object v=props.getClass().getMethod("containsKey", Object.class).invoke(props, "textures");
            return Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            return false;
        }
    }
    private static void finishSkinMarker(UUID id, CompletableFuture<Object> marker) {
        SKIN_PENDING.remove(id, marker);
        marker.complete(null);
    }
    /** Runtime test hook; does not perform a network request. */
    /** Draw any vanilla item by registry id; cached exactly like kit icons. */
    public static boolean drawItem(Object drawContext, String itemId, int x, int y) {
        if(drawContext==null||itemId==null||itemId.isBlank())return false;
        try {
            Object st=stack(itemId);
            if(st==null)return false;
            Method m=drawItemMethod;
            if(m==null||!m.getDeclaringClass().isAssignableFrom(drawContext.getClass())) {
                Class<?> stackCl=itemStackClass!=null?itemStackClass:Class.forName("net.minecraft.class_1799");
                m=drawContext.getClass().getMethod("method_51427", stackCl, int.class, int.class);
                drawItemMethod=m;
            }
            m.invoke(drawContext, st, x, y);
            return true;
        } catch (Throwable t) {
            if(!initFailureLogged) {
                initFailureLogged=true;
                BootstrapLog.error("ITEM ICON draw/init", unwrap(t));
            }
            return false;
        }
    }
    public static boolean draw(Object drawContext, String mode, int x, int y) {
        return draw(drawContext, "", mode, x, y);
    }
    public static boolean draw(Object drawContext, String providerId, String mode, int x, int y) {
        if (drawContext == null) return false;
        try {
            String itemId=itemIdForMode(providerId, mode);
            if ("__tierlookup_spear__".equals(itemId)) return drawSpearSprite(drawContext, x, y);
            Object stack = stackForMode(providerId, mode);
            if (stack == null) return false;
            Method m = drawItemMethod;
            if (m == null || !m.getDeclaringClass().isAssignableFrom(drawContext.getClass())) {
                Class<?> stackCl = itemStackClass != null ? itemStackClass : Class.forName("net.minecraft.class_1799");
                m = drawContext.getClass().getMethod("method_51427", stackCl, int.class, int.class);
                drawItemMethod = m;
            }
            m.invoke(drawContext, stack, x, y);
            return true;
        } catch (Throwable t) {
            if (!initFailureLogged) {
                initFailureLogged=true;
                BootstrapLog.error("KIT ICON draw/init", unwrap(t));
            }
            return false;
        }
    }
    /** Dedicated 16x16 Spear placeholder baked from the user-provided later-version sprite. */
    private static final int[][] SPEAR_RUNS= {
        {
            13, 0, 3, 0xFF49283F
        }, {
            11, 1, 2, 0xFF49283F
        }, {
            13, 1, 1, 0xFF5C555C
        }, {
            14, 1, 1, 0xFF847A84
        }, {
            15, 1, 1, 0xFF231012
        }, {
            9, 2, 2, 0xFF49283F
        }, {
            11, 2, 1, 0xFF574D55
        }, {
            12, 2, 1, 0xFF5C555C
        }, {
            13, 2, 1, 0xFF847A84
        }, {
            14, 2, 1, 0xFF3B3131
        }, {
            15, 2, 1, 0xFF231012
        }, {
            7, 3, 2, 0xFF49283F
        }, {
            9, 3, 1, 0xFF4A3E45
        }, {
            10, 3, 1, 0xFF5C555C
        }, {
            11, 3, 1, 0xFF574D55
        }, {
            12, 3, 1, 0xFF847A84
        }, {
            13, 3, 1, 0xFF3B3131
        }, {
            14, 3, 1, 0xFF231012
        }, {
            7, 4, 1, 0xFF49283F
        }, {
            8, 4, 1, 0xFF4A3E45
        }, {
            9, 4, 1, 0xFF574D55
        }, {
            10, 4, 1, 0xFF5C555C
        }, {
            11, 4, 1, 0xFF847A84
        }, {
            12, 4, 1, 0xFF3B3131
        }, {
            13, 4, 1, 0xFF302829
        }, {
            14, 4, 1, 0xFF231012
        }, {
            8, 5, 1, 0xFF49283F
        }, {
            9, 5, 1, 0xFF574D55
        }, {
            10, 5, 1, 0xFF847A84
        }, {
            11, 5, 1, 0xFF3B3131
        }, {
            12, 5, 1, 0xFF302829
        }, {
            13, 5, 1, 0xFF231012
        }, {
            8, 6, 1, 0xFF2E2122
        }, {
            9, 6, 1, 0xFF847A84
        }, {
            10, 6, 3, 0xFF302829
        }, {
            13, 6, 1, 0xFF231012
        }, {
            7, 7, 1, 0xFF2E2122
        }, {
            8, 7, 1, 0xFF5F3331
        }, {
            9, 7, 2, 0xFF231012
        }, {
            11, 7, 1, 0xFF302829
        }, {
            12, 7, 1, 0xFF231012
        }, {
            6, 8, 1, 0xFF2E2122
        }, {
            7, 8, 1, 0xFF724442
        }, {
            8, 8, 1, 0xFF231012
        }, {
            11, 8, 2, 0xFF231012
        }, {
            5, 9, 1, 0xFF2E2122
        }, {
            6, 9, 1, 0xFF724442
        }, {
            7, 9, 1, 0xFF231012
        }, {
            4, 10, 1, 0xFF2E2122
        }, {
            5, 10, 1, 0xFF724442
        }, {
            6, 10, 1, 0xFF231012
        }, {
            3, 11, 1, 0xFF2E2122
        }, {
            4, 11, 1, 0xFF724442
        }, {
            5, 11, 1, 0xFF231012
        }, {
            2, 12, 1, 0xFF2E2122
        }, {
            3, 12, 1, 0xFF724442
        }, {
            4, 12, 1, 0xFF231012
        }, {
            1, 13, 1, 0xFF2E2122
        }, {
            2, 13, 1, 0xFF423F42
        }, {
            3, 13, 1, 0xFF231012
        }, {
            0, 14, 1, 0xFF2E2122
        }, {
            1, 14, 1, 0xFF3A383A
        }, {
            2, 14, 1, 0xFF231012
        }, {
            0, 15, 1, 0xFF3A383A
        }, {
            1, 15, 1, 0xFF231012
        }
    };
    private static boolean drawSpearSprite(Object drawContext, int x, int y) {
        try {
            Method fill=fillRectMethod;
            if(fill==null||!fill.getDeclaringClass().isAssignableFrom(drawContext.getClass())) {
                fill=drawContext.getClass().getMethod("method_25294", int.class, int.class, int.class, int.class, int.class);
                fillRectMethod=fill;
            }
            // The source sprite is 16x16, but visually reads larger than vanilla item icons. Render it at 14x14 and center it.
            final int target=14, source=16, ox=x+1, oy=y+1;
            for(int[] r:SPEAR_RUNS) {
                int x0=ox+(r[0]*target)/source, x1=ox+Math.max((r[0]*target)/source+1, ((r[0]+r[2])*target+source-1)/source);
                int y0=oy+(r[1]*target)/source, y1=oy+Math.max((r[1]*target)/source+1, ((r[1]+1)*target+source-1)/source);
                fill.invoke(drawContext, x0, y0, x1, y1, r[3]);
            }
            return true;
        } catch (Throwable t) {
            if(!initFailureLogged) {
                initFailureLogged=true;
                BootstrapLog.error("KIT ICON Spear sprite", unwrap(t));
            }
            return false;
        }
    }
    public static boolean spearTridentSeparated() {
        String spear=itemIdForMode("flowpvp", "Spear"), spearMace=itemIdForMode("flowpvp", "SpearMace"), trident=itemIdForMode("", "Trident");
        return "__tierlookup_spear__".equals(spear)&&"__tierlookup_spear__".equals(spearMace)&&"trident".equals(trident)&&!spear.equals(trident);
    }
    private static Object stackForMode(String providerId, String mode) {
        String item=itemIdForMode(providerId, mode);
        if ("splash_potion".equals(item)) {
            Object healing=healingSplashPotionStack();
            if (healing!=null) return healing;
        }
        return stack(item);
    }
    private static Object healingSplashPotionStack() {
        final String cacheKey="__healing_splash_potion__";
        Object cached=STACKS.get(cacheKey);
        if(cached!=null)return cached;
        if(FAILED.contains(cacheKey))return null;
        try {
            Object splashItem=item("splash_potion");
            if(splashItem==null)throw new IllegalStateException("splash_potion item missing");
            Class<?> potions=Class.forName("net.minecraft.class_1847");
            Object healing=potions.getField("field_8963").get(null);
            Class<?> itemCl=Class.forName("net.minecraft.class_1792"), entryCl=Class.forName("net.minecraft.class_6880"), contents=Class.forName("net.minecraft.class_1844");
            Method create=contents.getMethod("method_57400", itemCl, entryCl);
            Object result=create.invoke(null, splashItem, healing);
            itemStackClass=Class.forName("net.minecraft.class_1799");
            STACKS.put(cacheKey, result);
            return result;
        } catch (Throwable t) {
            FAILED.add(cacheKey);
            BootstrapLog.error("KIT ICON healing potion stack", unwrap(t));
            return null;
        }
    }
    private static Object stack(String itemId) {
        Object cached=STACKS.get(itemId);
        if(cached!=null)return cached;
        if(FAILED.contains(itemId))return null;
        try {
            Object item=item(itemId);
            if(item==null) {
                FAILED.add(itemId);
                return null;
            }
            Class<?> convertible=Class.forName("net.minecraft.class_1935"), stackCl=Class.forName("net.minecraft.class_1799");
            Object stack=stackCl.getConstructor(convertible).newInstance(item);
            itemStackClass=stackCl;
            STACKS.put(itemId, stack);
            return stack;
        } catch (Throwable t) {
            FAILED.add(itemId);
            if(!initFailureLogged) {
                initFailureLogged=true;
                BootstrapLog.error("KIT ICON stack init item="+itemId, unwrap(t));
            }
            return null;
        }
    }
    private static Object item(String itemId) throws Exception {
        Class<?> registries=Class.forName("net.minecraft.class_7923");
        Object itemRegistry=registries.getField("field_41178").get(null);
        Class<?> identifier=Class.forName("net.minecraft.class_2960");
        Object id=identifier.getMethod("method_60656", String.class).invoke(null, itemId);
        Class<?> registry=Class.forName("net.minecraft.class_2378");
        Object optObj=registry.getMethod("method_17966", identifier).invoke(itemRegistry, id);
        if (!(optObj instanceof Optional<?> opt)||opt.isEmpty())return null;
        return opt.get();
    }
    public static boolean isVanillaLabel(String s) {
        return isVanilla(normalize(s));
    }
    private static boolean isVanilla(String s) {
        return s.equals("vanilla")||s.endsWith(" vanilla")||s.startsWith("vanilla ");
    }
    private static boolean isSpear(String s) {
        return s.equals("spear")||s.startsWith("spear ")||s.endsWith(" spear")||s.contains("spearmace")||s.contains("spear mace")||s.contains("spear_mace");
    }
    private static boolean isDiamondPot(String s) {
        return s.equals("pot")||s.equals("dpot")||s.equals("dia pot")||s.equals("diapot")||s.contains("diamond pot")||s.contains("diamondpot")||s.contains("diamond potion");
    }
    private static boolean isNetheritePot(String s) {
        return s.equals("npot")||s.equals("n pot")||s.contains("neth pot")||s.contains("nethpot")||s.contains("netherite pot")||s.contains("netheritepot")||s.contains("netherite potion");
    }
    private static boolean isDiamondSmp(String s) {
        return s.equals("dsmp")||s.equals("dia smp")||s.equals("diasmp")||s.contains("diamond smp")||s.contains("dia smp");
    }
    private static boolean isSmp(String s) {
        return s.equals("smp")||s.endsWith(" smp")||s.startsWith("smp ");
    }
    private static boolean isOp(String s) {
        return s.equals("op")||s.equals("nethop")||s.equals("netherite op")||s.startsWith("op ")||s.endsWith(" op");
    }
    private static String normalize(String s) {
        if(s==null)return "";
        return s.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
    }
    private static Throwable unwrap(Throwable t) {
        Throwable x=t;
        while(x instanceof InvocationTargetException ite&&ite.getCause()!=null)x=ite.getCause();
        return x;
    }
    /** Runtime smoke for real vanilla ItemStack rendering used by TAB 2.0. */
}
