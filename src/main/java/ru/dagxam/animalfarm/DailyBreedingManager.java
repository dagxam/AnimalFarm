package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/** Размножение животных один раз после смены игровых суток. */
public final class DailyBreedingManager {
    private static final int RADIUS = 16;
    private static final int VERTICAL = 5;
    private final JavaPlugin plugin;
    private final NamespacedKey feederKey = new NamespacedKey("animalfarm", "feeder_block");
    private final NamespacedKey dayKey;
    private final NamespacedKey waterKey;

    public DailyBreedingManager(JavaPlugin plugin) {
        this.plugin = plugin;
        dayKey = new NamespacedKey(plugin, "daily_breeding_day");
        waterKey = new NamespacedKey(plugin, "water_progress");
        new BukkitRunnable() { @Override public void run() { processAll(); } }.runTaskTimer(plugin, 40L, 40L);
    }

    private void processAll() {
        for (World world : plugin.getServer().getWorlds()) for (var chunk : world.getLoadedChunks())
            for (var state : chunk.getTileEntities()) if (state instanceof Barrel b && isFeeder(b.getBlock())) process(b);
    }

    private void process(Barrel feeder) {
        Pen pen = analyze(feeder.getBlock());
        if (!pen.valid) return;
        long day = feeder.getWorld().getFullTime() / 24000L;
        long last = feeder.getPersistentDataContainer().getOrDefault(dayKey, PersistentDataType.LONG, -1L);
        if (last >= day) return;

        Map<String,List<Animals>> groups = new HashMap<>();
        for (Animals a : pen.animals) if (a.isAdult() && a.canBreed() && !a.isLoveMode() && supported(a))
            groups.computeIfAbsent(a.getType().name().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(a);

        int pairs = 0, max = Math.max(1, plugin.getConfig().getInt("feeder.max-breeding-pairs-per-day", 10));
        for (List<Animals> group : groups.values()) {
            for (int i=0; i+1<group.size() && pairs<max; i+=2) {
                Animals a=group.get(i), b=group.get(i+1);
                Material fa=findFood(a, feeder.getInventory()), fb=findFood(b, feeder.getInventory());
                if (fa==null || fb==null) continue;
                if (a.getLocation().distanceSquared(feeder.getLocation())>36 || b.getLocation().distanceSquared(feeder.getLocation())>36) {
                    move(a, feeder); move(b, feeder); continue;
                }
                if (!consumeWater(feeder)) continue;
                removeOne(feeder.getInventory(), fa); removeOne(feeder.getInventory(), fb);
                a.setLoveModeTicks(600); b.setLoveModeTicks(600); pairs++;
            }
        }
        // День засчитывается даже если сегодня не хватило пары/корма: следующая попытка только завтра.
        feeder.getPersistentDataContainer().set(dayKey, PersistentDataType.LONG, day);
        feeder.update(true,false);
    }

    private boolean supported(Animals a) {
        return switch (a.getType()) { case COW,SHEEP,GOAT,CHICKEN,HORSE,RABBIT -> true; default -> false; };
    }

    private Material findFood(Animals a, Inventory inv) {
        String key=a.getType().name().toLowerCase(Locale.ROOT);
        for(String s:plugin.getConfig().getStringList("feeding."+key+".foods")) { Material m=Material.matchMaterial(s); if(m!=null&&contains(inv,m)) return m; }
        return null;
    }
    private boolean contains(Inventory inv,Material m){for(ItemStack i:inv.getContents())if(i!=null&&i.getType()==m&&i.getAmount()>0)return true;return false;}
    private void removeOne(Inventory inv,Material m){for(int s=0;s<inv.getSize();s++){ItemStack i=inv.getItem(s);if(i==null||i.getType()!=m)continue;if(i.getAmount()==1)inv.setItem(s,null);else i.setAmount(i.getAmount()-1);return;}}
    private boolean consumeWater(Barrel b){Inventory inv=b.getInventory();for(int s=0;s<inv.getSize();s++){ItemStack i=inv.getItem(s);if(i==null||i.getType()!=Material.WATER_BUCKET)continue;if(i.getAmount()==1)inv.setItem(s,null);else i.setAmount(i.getAmount()-1);Map<Integer,ItemStack> left=inv.addItem(new ItemStack(Material.BUCKET));for(ItemStack x:left.values())b.getWorld().dropItemNaturally(b.getLocation(),x);return true;}return false;}
    private void move(Animals a,Barrel b){if(a instanceof Mob m)m.getPathfinder().moveTo(b.getLocation(),plugin.getConfig().getDouble("feeder.path-speed",1.1));}

    private Pen analyze(Block feeder){
        int sx=feeder.getX(),sz=feeder.getZ();Set<String> in=new HashSet<>();ArrayDeque<int[]>q=new ArrayDeque<>();q.add(new int[]{sx,sz});in.add(key(sx,sz));int gates=0;boolean open=false,escaped=false;
        int[][] d={{1,0},{-1,0},{0,1},{0,-1}};
        while(!q.isEmpty()){int[]p=q.removeFirst();for(int[]v:d){int x=p[0]+v[0],z=p[1]+v[1];if(Math.abs(x-sx)>RADIUS||Math.abs(z-sz)>RADIUS){escaped=true;continue;}Block n=feeder.getWorld().getBlockAt(x,feeder.getY(),z);if(isGate(n)){if(in.add(key(x,z))){gates++;if(isOpen(n))open=true;}continue;}if(isFence(n))continue;if(n.getType().isAir()&&in.add(key(x,z)))q.add(new int[]{x,z});}}
        List<Animals>a=new ArrayList<>();for(Entity e:feeder.getWorld().getNearbyEntities(feeder.getLocation(),RADIUS+1,VERTICAL,RADIUS+1))if(e instanceof Animals an&&in.contains(key(an.getLocation().getBlockX(),an.getLocation().getBlockZ())))a.add(an);
        return new Pen(!escaped&&gates==1&&!open,a);
    }
    private boolean isFeeder(Block b){return b.getType()==Material.BARREL&&b.getState() instanceof Barrel x&&x.getPersistentDataContainer().has(new NamespacedKey(plugin,"feeder_block"),PersistentDataType.BYTE);}
    private boolean isFence(Block b){String n=b.getType().name();return n.endsWith("_FENCE")&&!n.endsWith("_FENCE_GATE");}
    private boolean isGate(Block b){return b.getBlockData() instanceof Openable&&b.getType().name().endsWith("_FENCE_GATE");}
    private boolean isOpen(Block b){return b.getBlockData() instanceof Openable o&&o.isOpen();}
    private String key(int x,int z){return x+":"+z;}
    private record Pen(boolean valid,List<Animals> animals){}
}
