package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Animals;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** 5-10 яиц в сутки на взрослую курицу, если в загоне есть пара. Яйца остаются на земле. */
public final class EggProductionManager implements Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey feederKey;
    private final NamespacedKey dayKey;

    public EggProductionManager(JavaPlugin plugin) {
        this.plugin = plugin;
        feederKey = new NamespacedKey(plugin, "feeder_block");
        dayKey = new NamespacedKey(plugin, "egg_production_day");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        new BukkitRunnable() {
            @Override public void run() { processAllFeeders(); }
        }.runTaskTimer(plugin, 40L, 40L);
    }

    private void processAllFeeders() {
        for (World world : plugin.getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) for (BlockState state : chunk.getTileEntities())
                if (state instanceof Barrel barrel && isFeeder(barrel.getBlock())) produce(barrel);
        }
    }

    private void produce(Barrel feeder) {
        Pen pen = analyzePen(feeder.getBlock());
        if (!pen.valid) return;
        long day = feeder.getWorld().getFullTime() / 24000L;
        long last = feeder.getPersistentDataContainer().getOrDefault(dayKey, PersistentDataType.LONG, -1L);
        if (last >= day) return;

        List<Chicken> chickens = pen.chickens;
        long adults = chickens.stream().filter(Animals::isAdult).count();
        if (adults < 2) return;
        for (Chicken chicken : chickens) if (chicken.isAdult()) {
            int amount = ThreadLocalRandom.current().nextInt(5, 11);
            for (int i = 0; i < amount; i++) chicken.getWorld().dropItemNaturally(chicken.getLocation(), new ItemStack(Material.EGG));
        }
        feeder.getPersistentDataContainer().set(dayKey, PersistentDataType.LONG, day);
        feeder.update(true, false);
    }

    /** В загоне с кормушкой ванильные яйца не добавляются сверх суточных 5-10. */
    @EventHandler(ignoreCancelled = true)
    public void onVanillaEgg(EntityDropItemEvent event) {
        if (!(event.getEntity() instanceof Chicken chicken)) return;
        if (findFeederPen(chicken) != null) event.setCancelled(true);
    }

    private Block findFeederPen(Chicken chicken) {
        for (var chunk : chicken.getWorld().getLoadedChunks()) for (BlockState state : chunk.getTileEntities())
            if (state instanceof Barrel barrel && isFeeder(barrel.getBlock()) && analyzePen(barrel.getBlock()).contains(chicken)) return barrel.getBlock();
        return null;
    }

    private Pen analyzePen(Block feeder) {
        int r = plugin.getConfig().getInt("pen.max-radius", 16), sx = feeder.getX(), sz = feeder.getZ();
        Set<String> inside = new HashSet<>(); ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{sx, sz}); inside.add(key(sx, sz)); int gates = 0; boolean open = false, escaped = false;
        int[][] dirs={{1,0},{-1,0},{0,1},{0,-1}};
        while(!q.isEmpty()) { int[] p=q.removeFirst(); for(int[] d:dirs){ int x=p[0]+d[0],z=p[1]+d[1];
            if(Math.abs(x-sx)>r||Math.abs(z-sz)>r){escaped=true;continue;}
            Block b=feeder.getWorld().getBlockAt(x,feeder.getY(),z);
            if(isGate(b)){if(inside.add(key(x,z))){gates++; if(isOpen(b))open=true;}continue;}
            if(isFence(b))continue;
            if(b.getType().isAir()&&inside.add(key(x,z)))q.add(new int[]{x,z});
        }}
        List<Chicken> chickens=new ArrayList<>();
        for(Entity e:feeder.getWorld().getNearbyEntities(feeder.getLocation(),r+1,5,r+1)) if(e instanceof Chicken c && inside.contains(key(c.getLocation().getBlockX(),c.getLocation().getBlockZ()))) chickens.add(c);
        return new Pen(!escaped&&gates==1&&!open,chickens);
    }
    private boolean isFeeder(Block b){return b.getType()==Material.BARREL&&b.getState() instanceof Barrel barrel&&barrel.getPersistentDataContainer().has(feederKey,PersistentDataType.BYTE);}
    private boolean isFence(Block b){String n=b.getType().name();return n.endsWith("_FENCE")&&!n.endsWith("_FENCE_GATE");}
    private boolean isGate(Block b){return b.getBlockData() instanceof Openable&&b.getType().name().endsWith("_FENCE_GATE");}
    private boolean isOpen(Block b){return b.getBlockData() instanceof Openable o&&o.isOpen();}
    private String key(int x,int z){return x+":"+z;}
    private record Pen(boolean valid,List<Chicken> chickens){boolean contains(Chicken c){return chickens.contains(c);}}
}
