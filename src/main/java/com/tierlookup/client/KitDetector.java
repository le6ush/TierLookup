package com.tierlookup.client;

import java.util.*;

/**
* Conservative detector for the local player's own kit. Detection is latched for the duel:
* once a kit is recognized, spending/removing items cannot turn it into another kit. A new kit
* may be recognized only after the local inventory has been observed completely empty.
*/ public final class KitDetector {
    public static final String UNKNOWN="Unknown";
    private KitDetector() {
    }
    public record Detection(String kit, String reason, int fingerprint) {
    }
    public static final class Tracker {
        private String current=UNKNOWN;
        private boolean armed=true;
        private int lastFingerprint=Integer.MIN_VALUE;
        private Detection lastDetection=new Detection(UNKNOWN, "not evaluated", 0);
        /** Detector work only runs when inventory/effects actually changed. */
        public synchronized String update(LocalInventorySnapshot snapshot) {
            if(snapshot==null)return current;
            int fp=fingerprint(snapshot);
            if(fp==lastFingerprint)return current;
            lastFingerprint=fp;
            if(snapshot.empty()) {
                current=UNKNOWN;
                armed=true;
                lastDetection=new Detection(current, "inventory reset", fp);
                return current;
            }
            if(!UNKNOWN.equals(current)) {
                lastDetection=new Detection(current, "latched until full inventory reset", fp);
                return current;
            }
            if(!armed)return current;
            Detection detailed=detectDetailed(snapshot);
            String found=detailed.kit();
            lastDetection=new Detection(found, detailed.reason(), fp);
            if(!UNKNOWN.equals(found)) {
                current=found;
                armed=false;
            }
            return current;
        }
        public synchronized void reset() {
            current=UNKNOWN;
            armed=true;
            lastFingerprint=Integer.MIN_VALUE;
            lastDetection=new Detection(UNKNOWN, "manual/session reset", 0);
        }
        public synchronized String current() {
            return current;
        }
    }
    public static int fingerprint(LocalInventorySnapshot s) {
        if(s==null)return 0;
        int h=Objects.hash(s.speed(), s.strength(), s.regeneration());
        ArrayList<String> parts=new ArrayList<>();
        for(var i:s.items())parts.add(i.id()+":"+i.count()+":"+i.customNamed()+":"+new TreeSet<>(i.potionEffects()));
        Collections.sort(parts);
        for(String x:parts)h=31*h+x.hashCode();
        return h;
    }
    private record Context(LocalInventorySnapshot snapshot, Bag bag) {
    }
    private record Requirement(String label, java.util.function.Predicate<Context> test) {
    }
    private record KitRule(String kit, List<Requirement> requirements) {
        RuleEval evaluate(Context c) {
            int ok=0;
            ArrayList<String> missing=new ArrayList<>();
            for(Requirement r:requirements) {
                if(r.test().test(c))ok++;
                else missing.add(r.label());
            }
            return new RuleEval(kit, ok, requirements.size(), missing);
        }
    }
    private record RuleEval(String kit, int matched, int total, List<String> missing) {
        boolean complete() {
            return matched==total;
        }
        double confidence() {
            return total==0?0:(double)matched/total;
        }
    }
    private static Requirement req(String label, java.util.function.Predicate<Context> p) {
        return new Requirement(label, p);
    }
    private static final List<KitRule> RULES=List.of( new KitRule("Mace",
        List.of(req("mace",
        c->c.bag.has("mace")))),
        new KitRule("Creeper",
        List.of(req("creeper egg",
        c->c.bag.has("creeper_spawn_egg")))),
        new KitRule("DSMP",
        List.of(req("chorus fruit",
        c->c.bag.has("chorus_fruit")))),
        new KitRule("Vanilla",
        List.of(req("end crystal",
        c->c.bag.has("end_crystal")))),
        new KitRule("Minecart",
        List.of(req("TNT minecart",
        c->c.bag.has("tnt_minecart")))),
        new KitRule("SUHC",
        List.of(req("UHC base",
        c->isUhc(c.bag)),
        req("Golden Head",
        c->c.bag.goldenHead()))),
        new KitRule("UHC",
        List.of(req("UHC base",
        c->isUhc(c.bag)))),
        new KitRule("NETHPOT",
        List.of(req(">=2 netherite armor",
        c->c.bag.armor("netherite_")>=2),
        req("netherite sword",
        c->c.bag.has("netherite_sword")),
        req("totem",
        c->c.bag.has("totem_of_undying")),
        req("XP bottles",
        c->c.bag.has("experience_bottle")),
        req("healing potion",
        c->c.bag.hasHealingPotion()),
        req("golden apple",
        c->c.bag.hasGoldenApple()))),
        new KitRule("SMP",
        List.of(req(">=2 netherite armor",
        c->c.bag.armor("netherite_")>=2),
        req("netherite sword",
        c->c.bag.has("netherite_sword")),
        req("ender pearl",
        c->c.bag.has("ender_pearl")),
        req("totem",
        c->c.bag.has("totem_of_undying")),
        req("XP bottles",
        c->c.bag.has("experience_bottle")),
        req("netherite axe",
        c->c.bag.has("netherite_axe")),
        req("shield",
        c->c.bag.has("shield")))),
        new KitRule("OP",
        List.of(req(">=2 netherite armor",
        c->c.bag.armor("netherite_")>=2),
        req("netherite sword",
        c->c.bag.has("netherite_sword")),
        req("ender pearl",
        c->c.bag.has("ender_pearl")),
        req("no totem",
        c->!c.bag.has("totem_of_undying")),
        req("no XP bottles",
        c->!c.bag.has("experience_bottle")))),
        new KitRule("DPot",
        List.of(req(">=2 diamond armor",
        c->c.bag.armor("diamond_")>=2),
        req("diamond sword",
        c->c.bag.has("diamond_sword")),
        req("healing potion",
        c->c.bag.hasHealingPotion()),
        req("strength/speed/regen potion or effect",
        c->c.bag.hasBuffPotion()||c.snapshot.speed()||c.snapshot.strength()||c.snapshot.regeneration()))),
        new KitRule("Axe",
        List.of(req(">=2 diamond armor",
        c->c.bag.armor("diamond_")>=2),
        req("shield",
        c->c.bag.has("shield")),
        req("diamond sword",
        c->c.bag.has("diamond_sword")),
        req("axe",
        c->c.bag.anyAxe()),
        req("crossbow",
        c->c.bag.has("crossbow")),
        req("bow",
        c->c.bag.has("bow")),
        req("arrow",
        c->c.bag.hasArrow()),
        req("Axe whitelist only",
        c->c.bag.onlyAxeKitItems()))),
        new KitRule("Sword",
        List.of(req(">=2 diamond armor",
        c->c.bag.armor("diamond_")>=2),
        req("diamond sword",
        c->c.bag.has("diamond_sword")),
        req("Sword/Beast whitelist only",
        c->c.bag.onlySwordKitItems()))));
    public static String detect(LocalInventorySnapshot s) {
        return detectDetailed(s).kit();
    }
    private static Detection detectDetailed(LocalInventorySnapshot s) {
        if(s==null||s.items().isEmpty())return new Detection(UNKNOWN, "empty inventory", fingerprint(s));
        Context c=new Context(s, new Bag(s));
        RuleEval closest=null;
        for(KitRule rule:RULES) {
            RuleEval e=rule.evaluate(c);
            if(e.complete())return new Detection(e.kit(), "matched "+e.total()+"/"+e.total()+" canonical requirements", fingerprint(s));
            if(closest==null||e.confidence()>closest.confidence()||(e.confidence()==closest.confidence()&&e.total()>closest.total()))closest=e;
        }
        String reason="no canonical signature matched";
        if(closest!=null) {
            String miss=String.join(", ", closest.missing().subList(0, Math.min(3, closest.missing().size())));
            reason="closest "+closest.kit()+" "+closest.matched()+"/"+closest.total()+"; missing: "+miss;
        }
        return new Detection(UNKNOWN, reason, fingerprint(s));
    }
    private static boolean isUhc(Bag b) {
        return b.has("lava_bucket")&&b.has("water_bucket")&&(b.has("cobblestone")||b.anyPlanks()) &&b.hasGoldenApple()&&b.has("cobweb")&&b.has("bow")&&b.has("crossbow")&&!b.hasExplosive();
    }
    private static final class Bag {
        private final List<LocalInventorySnapshot.Item> items;
        private final Set<String> ids=new HashSet<>();
        Bag(LocalInventorySnapshot s) {
            items=s.items();
            for(var it:items)ids.add(it.id());
        }
        boolean has(String id) {
            return ids.contains(id);
        }
        boolean hasGoldenApple() {
            return has("golden_apple")||has("enchanted_golden_apple");
        }
        boolean anyPlanks() {
            for(String id:ids)if(id.endsWith("_planks"))return true;
            return false;
        }
        boolean anyAxe() {
            for(String id:ids)if(id.endsWith("_axe"))return true;
            return false;
        }
        boolean hasArrow() {
            return has("arrow")||has("spectral_arrow")||has("tipped_arrow");
        }
        int armor(String prefix) {
            int n=0;
            for(String part:List.of("helmet", "chestplate", "leggings", "boots"))if(has(prefix+part))n++;
            return n;
        }
        boolean goldenHead() {
            if(has("honeycomb"))return true;
            for(var it:items)if(it.id().equals("player_head")&&it.customNamed())return true;
            return false;
        }
        boolean hasHealingPotion() {
            return potionEffect("instant_health");
        }
        boolean hasBuffPotion() {
            return potionEffect("strength")||potionEffect("speed")||potionEffect("regeneration");
        }
        boolean potionEffect(String effect) {
            for(var it:items)if(it.potionEffects().contains(effect))return true;
            return false;
        }
        boolean hasExplosive() {
            if(has("tnt_minecart")||has("end_crystal")||has("tnt")||has("respawn_anchor")||has("creeper_spawn_egg"))return true;
            for(String id:ids)if(id.endsWith("_bed"))return true;
            return false;
        }
        boolean onlySwordKitItems() {
            for(var it:items) {
                String id=it.id();
                if(isDiamondArmor(id)||id.equals("diamond_sword")||id.equals("wooden_sword")||id.equals("player_head"))continue;
                return false;
            }
            return true;
        }
        boolean onlyAxeKitItems() {
            for(var it:items) {
                String id=it.id();
                if(isDiamondArmor(id)||id.equals("player_head")||id.equals("shield")||id.equals("diamond_sword") ||id.endsWith("_axe")||id.equals("crossbow")||id.equals("bow")||id.equals("arrow")||id.equals("spectral_arrow")||id.equals("tipped_arrow"))continue;
                return false;
            }
            return true;
        }
        private static boolean isDiamondArmor(String id) {
            return id.equals("diamond_helmet")||id.equals("diamond_chestplate")||id.equals("diamond_leggings")||id.equals("diamond_boots");
        }
    }
}
