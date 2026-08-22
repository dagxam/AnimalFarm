package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fish;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Единый процессор фермы v2: вместимость, состояние ресурсов, производство, золотой режим и аквакультура. */
public final class FarmProcessor {
    private final AnimalFarmPlugin plugin;
    private final FarmSettings settings;
    private final org.bukkit.NamespacedKey nextBreedDayKey;
    private final org.bukkit.NamespacedKey productionDayKey;
    private final org.bukkit.NamespacedKey goldenDayKey;
    private final org.bukkit.NamespacedKey goldenCyclesKey;
    private final org.bukkit.NamespacedKey farmStateKey;

    public FarmProcessor(AnimalFarmPlugin plugin, FarmSettings settings) {
        this.plugin = plugin; this.settings = settings;
        nextBreedDayKey = new org.bukkit.NamespacedKey(plugin, "next_breed_day");
        productionDayKey = new org.bukkit.NamespacedKey(plugin, "production_day");
        goldenDayKey = new org.bukkit.NamespacedKey(plugin, "golden_day");
        goldenCyclesKey = new org.bukkit.NamespacedKey(plugin, "golden_cycles");
        farmStateKey = new org.bukkit.NamespacedKey(plugin, "farm_state");
    }

    public void process(FarmObjectKey key, FarmObjectType type, long serverTick) {
        Location location = key.location(plugin.getServer());
        if (location.getWorld() == null || !(location.getBlock().getState() instanceof Barrel barrel)) return;
        long day = location.getWorld().getFullTime() / 24000L;
        if (type == FarmObjectType.LAND_FEEDER) processLand(barrel, location, day);
        else if (settings.aquariumEnabled()) processAquarium(barrel, location, day);
        barrel.update(true, false);
    }

    private void processLand(Barrel feeder, Location location, long day) {
        List<Animals> animals = findNearbyAnimals(location);
        updateLandState(feeder, animals);
        long next = feeder.getPersistentDataContainer().getOrDefault(nextBreedDayKey, PersistentDataType.LONG, -1L);
        if (next < 0 || day >= next) runBreeding(feeder, animals, location, day);

        long productionDay = feeder.getPersistentDataContainer().getOrDefault(productionDayKey, PersistentDataType.LONG, -1L);
        if (productionDay < day) runProduction(feeder, animals, location, day);
    }

    private void updateLandState(Barrel feeder, List<Animals> animals) {
        Inventory inv = feeder.getInventory();
        boolean food = countAnimalFood(inv) >= settings.animalFoodMin();
        boolean water = count(inv, Material.WATER_BUCKET) > 0;
        String state = animals.size() >= settings.animalCapacity() ? "FULL" : (!food ? "NO_FOOD" : (!water ? "NO_WATER" : "HEALTHY"));
        feeder.getPersistentDataContainer().set(farmStateKey, PersistentDataType.STRING, state);
    }

    private void runBreeding(Barrel feeder, List<Animals> animals, Location location, long day) {
        if (animals.size() >= settings.animalCapacity()) return;
        Inventory inv = feeder.getInventory();
        int cycles = goldenCycles(feeder, inv, day);
        int bred = 0;
        Map<EntityType, List<Animals>> groups = new HashMap<>();
        for (Animals animal : animals) if (animal.isAdult() && animal.canBreed() && !animal.isLoveMode()) groups.computeIfAbsent(animal.getType(), x -> new ArrayList<>()).add(animal);

        outer: for (int cycle = 0; cycle < cycles; cycle++) {
            for (List<Animals> group : groups.values()) {
                for (int i = 0; i + 1 < group.size(); i += 2) {
                    if (bred >= settings.maxBreedingPairsPerDay() || animals.size() + bred >= settings.animalCapacity()) break outer;
                    Animals a = group.get(i), b = group.get(i + 1);
                    int cost = random(settings.animalFoodMin(), settings.animalFoodMax());
                    if (!consumeFoodForPair(a, b, inv, cost) || !consumeWater(inv, 1)) continue;
                    a.setLoveModeTicks(600); b.setLoveModeTicks(600); bred++;
                }
            }
        }
        feeder.getPersistentDataContainer().set(nextBreedDayKey, PersistentDataType.LONG, day + random(1, cycles > 1 ? 1 : 3));
    }

    private void runProduction(Barrel feeder, List<Animals> animals, Location location, long day) {
        Inventory inv = feeder.getInventory();
        int cycles = goldenCycles(feeder, inv, day);
        int chickens = 0, sheep = 0, milk = 0;
        for (Animals a : animals) if (a.isAdult()) switch (a.getType()) { case CHICKEN -> chickens++; case SHEEP -> sheep++; case COW, GOAT -> milk++; default -> {} }

        for (int cycle = 0; cycle < cycles; cycle++) {
            if (chickens > 0) {
                int amount = Math.min(chickens, random(plugin.getConfig().getInt("production.chicken.eggs-min", 5), plugin.getConfig().getInt("production.chicken.eggs-max", 10)));
                if (consumeAnyAnimalFood(inv, settings.animalFoodMin())) addItems(inv, Material.EGG, amount);
            }
            if (sheep > 0) {
                int amount = Math.min(sheep, random(plugin.getConfig().getInt("production.wool.min", 2), plugin.getConfig().getInt("production.wool.max", 3)));
                if (consumeAnyAnimalFood(inv, settings.animalFoodMin())) addItems(inv, Material.WHITE_WOOL, amount);
            }
            if (milk > 0 && plugin.getConfig().getBoolean("milking.enabled", true)) {
                int amount = Math.min(Math.min(milk, count(inv, Material.BUCKET)), random(plugin.getConfig().getInt("milking.milk-min", 1), plugin.getConfig().getInt("milking.milk-max", 3)));
                if (amount > 0) { remove(inv, Material.BUCKET, amount); addItems(inv, Material.MILK_BUCKET, amount); }
            }
        }
        feeder.getPersistentDataContainer().set(productionDayKey, PersistentDataType.LONG, day);
    }

    private int goldenCycles(Barrel feeder, Inventory inv, long day) {
        long markedDay = feeder.getPersistentDataContainer().getOrDefault(goldenDayKey, PersistentDataType.LONG, -1L);
        if (markedDay == day) return feeder.getPersistentDataContainer().getOrDefault(goldenCyclesKey, PersistentDataType.INTEGER, 1);
        int cycles = 1;
        if (removeOneIfPresent(inv, Material.GOLDEN_APPLE) || removeOneIfPresent(inv, Material.GOLDEN_CARROT)) cycles = random(settings.goldenCyclesMin(), settings.goldenCyclesMax());
        feeder.getPersistentDataContainer().set(goldenDayKey, PersistentDataType.LONG, day);
        feeder.getPersistentDataContainer().set(goldenCyclesKey, PersistentDataType.INTEGER, cycles);
        return cycles;
    }

    private void processAquarium(Barrel shelf, Location location, long day) {
        List<Fish> fish = findNearbyFish(location);
        Inventory inv = shelf.getInventory();
        String state = fish.size() >= settings.fishCapacity() ? "FULL" : (countFishFood(inv) < settings.animalFoodMin() ? "NO_FOOD" : "HEALTHY");
        shelf.getPersistentDataContainer().set(farmStateKey, PersistentDataType.STRING, state);
        long next = shelf.getPersistentDataContainer().getOrDefault(nextBreedDayKey, PersistentDataType.LONG, -1L);
        if (next >= 0 && day < next) return;
        if (fish.size() >= settings.fishCapacity()) return;

        int cycles = goldenCycles(shelf, inv, day);
        Map<EntityType, List<Fish>> groups = new HashMap<>();
        for (Fish f : fish) groups.computeIfAbsent(f.getType(), x -> new ArrayList<>()).add(f);
        int created = 0;
        outer: for (int cycle = 0; cycle < cycles; cycle++) {
            for (List<Fish> group : groups.values()) {
                for (int i = 0; i + 1 < group.size(); i += 2) {
                    if (fish.size() + created >= settings.fishCapacity() || created >= settings.maxBreedingPairsPerDay()) break outer;
                    int cost = random(settings.animalFoodMin(), settings.animalFoodMax());
                    if (!consumeFishFood(inv, cost)) continue;
                    Location water = findWater(location);
                    if (water == null) break outer;
                    location.getWorld().spawnEntity(water, group.get(i).getType()); created++;
                }
            }
        }
        shelf.getPersistentDataContainer().set(nextBreedDayKey, PersistentDataType.LONG, day + random(1, cycles > 1 ? 1 : 3));
    }

    private List<Animals> findNearbyAnimals(Location l) { List<Animals> out=new ArrayList<>(); for(Entity e:l.getWorld().getNearbyEntities(l,settings.penMaxRadius()+1,settings.penVerticalRange(),settings.penMaxRadius()+1)) if(e instanceof Animals a&&supported(a)) out.add(a); return out; }
    private List<Fish> findNearbyFish(Location l) { List<Fish> out=new ArrayList<>(); for(Entity e:l.getWorld().getNearbyEntities(l,settings.aquariumMaxRadius(),settings.aquariumVerticalRange(),settings.aquariumMaxRadius())) if(e instanceof Fish f&&f.getLocation().getBlock().getType()==Material.WATER) out.add(f); return out; }
    private boolean supported(Animals a) { return switch(a.getType()){case COW,SHEEP,GOAT,CHICKEN,HORSE,RABBIT->true;default->false;}; }

    private Location findWater(Location c) { World w=c.getWorld(); if(w==null)return null; int r=settings.aquariumMaxRadius(); for(int x=c.getBlockX()-r;x<=c.getBlockX()+r;x++) for(int y=c.getBlockY()-settings.aquariumVerticalRange();y<=c.getBlockY()+settings.aquariumVerticalRange();y++) for(int z=c.getBlockZ()-r;z<=c.getBlockZ()+r;z++) if(w.getBlockAt(x,y,z).getType()==Material.WATER)return new Location(w,x+.5,y+.2,z+.5); return null; }
    private boolean consumeFoodForPair(Animals a,Animals b,Inventory inv,int amount){Material fa=findFood(a,inv),fb=findFood(b,inv);if(fa==null||fb==null)return false;if(fa==fb&&count(inv,fa)<amount*2)return false;if(fa!=fb&&(count(inv,fa)<amount||count(inv,fb)<amount))return false;remove(inv,fa,amount);remove(inv,fb,amount);return true;}
    private Material findFood(Animals a,Inventory inv){for(String v:plugin.getConfig().getStringList("feeding."+a.getType().name().toLowerCase(Locale.ROOT)+".foods")){if("ANY_SEEDS".equalsIgnoreCase(v)){for(ItemStack i:inv.getContents())if(i!=null&&isSeed(i.getType()))return i.getType();}else{Material m=Material.matchMaterial(v);if(m!=null&&count(inv,m)>0)return m;}}return null;}
    private boolean consumeAnyAnimalFood(Inventory inv,int amount){for(int s=0;s<inv.getSize();s++){ItemStack i=inv.getItem(s);if(i!=null&&isAnimalFood(i.getType())&&i.getAmount()>=amount){i.setAmount(i.getAmount()-amount);if(i.getAmount()==0)inv.setItem(s,null);return true;}}return false;}
    private boolean consumeFishFood(Inventory inv,int amount){if(countFishFood(inv)<amount)return false;for(int s=0;s<inv.getSize()&&amount>0;s++){ItemStack i=inv.getItem(s);if(i==null||!isFishFood(i.getType()))continue;int take=Math.min(amount,i.getAmount());i.setAmount(i.getAmount()-take);if(i.getAmount()==0)inv.setItem(s,null);amount-=take;}return true;}
    private boolean consumeWater(Inventory inv,int amount){if(count(inv,Material.WATER_BUCKET)<amount)return false;remove(inv,Material.WATER_BUCKET,amount);addItems(inv,Material.BUCKET,amount);return true;}
    private int countAnimalFood(Inventory inv){int n=0;for(ItemStack i:inv.getContents())if(i!=null&&isAnimalFood(i.getType()))n+=i.getAmount();return n;}
    private int countFishFood(Inventory inv){int n=0;for(ItemStack i:inv.getContents())if(i!=null&&isFishFood(i.getType()))n+=i.getAmount();return n;}
    private boolean isSeed(Material m){return m.name().endsWith("_SEEDS")||m==Material.PITCHER_POD;}
    private boolean isFishFood(Material m){return isSeed(m)||m==Material.SEAGRASS||m==Material.KELP||m==Material.SEA_PICKLE;}
    private boolean isAnimalFood(Material m){return isFishFood(m)||m==Material.WHEAT||m==Material.HAY_BLOCK||m==Material.APPLE||m==Material.CARROT||m==Material.MELON_SLICE||m==Material.PUMPKIN||m==Material.MELON||m==Material.GOLDEN_APPLE||m==Material.GOLDEN_CARROT;}
    private int random(int min,int max){return ThreadLocalRandom.current().nextInt(Math.min(min,max),Math.max(min,max)+1);}
    private int count(Inventory inv,Material m){int n=0;for(ItemStack i:inv.getContents())if(i!=null&&i.getType()==m)n+=i.getAmount();return n;}
    private void remove(Inventory inv,Material m,int amount){for(int s=0;s<inv.getSize()&&amount>0;s++){ItemStack i=inv.getItem(s);if(i==null||i.getType()!=m)continue;int t=Math.min(amount,i.getAmount());i.setAmount(i.getAmount()-t);if(i.getAmount()==0)inv.setItem(s,null);amount-=t;}}
    private boolean removeOneIfPresent(Inventory inv,Material m){if(count(inv,m)==0)return false;remove(inv,m,1);return true;}
    private void addItems(Inventory inv,Material m,int amount){if(amount>0){Map<Integer,ItemStack> left=inv.addItem(new ItemStack(m,amount));for(ItemStack i:left.values())inv.getHolder().getLocation().getWorld().dropItemNaturally(inv.getHolder().getLocation(),i);}}
}
