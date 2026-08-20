package ru.dagxam.animalfarm;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/** Дополнительные дропы, HUD и контроль роста малышей сельскохозяйственных животных. */
public final class AnimalExtrasManager implements Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey feederKey;
    private final NamespacedKey guardKey;
    private final NamespacedKey pluginMilkCountKey;
    private final NamespacedKey milkFeedDayKey;
    private final Map<UUID,Integer> babyFeeds = new HashMap<>();

    public AnimalExtrasManager(JavaPlugin plugin) {
        this.plugin = plugin;
        feederKey = new NamespacedKey(plugin, "feeder_block");
        guardKey = new NamespacedKey(plugin, "baby_growth_guard");
        pluginMilkCountKey = new NamespacedKey(plugin, "milk_feed_count");
        milkFeedDayKey = new NamespacedKey(plugin, "milk_feed_day");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        new BukkitRunnable(){ @Override public void run(){ enforceBabyGrowth(); updateHud(); } }.runTaskTimer(plugin, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Horse) {
            event.getDrops().clear();
            event.getDrops().add(named(Material.BEEF, "&fКонина"));
            event.getDrops().add(new ItemStack(Material.LEATHER));
            event.getDrops().add(new ItemStack(Material.BONE, 2));
        } else if (event.getEntity() instanceof Rabbit) {
            event.getDrops().clear();
            event.getDrops().add(named(Material.RABBIT, "&fКрольчатина"));
            event.getDrops().add(new ItemStack(Material.RABBIT_HIDE));
            event.getDrops().add(new ItemStack(Material.BONE));
        } else if (event.getEntity() instanceof Chicken) {
            event.getDrops().clear();
            event.getDrops().add(new ItemStack(Material.CHICKEN));
            event.getDrops().add(new ItemStack(Material.FEATHER, 2));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onManualBabyMilk(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Animals baby) || baby.isAdult() || !isMilkAnimal(baby)) return;
        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        if (hand == null || hand.getType() != Material.MILK_BUCKET) return;
        event.setCancelled(true);
        long day = baby.getWorld().getFullTime() / 24000L;
        long last = baby.getPersistentDataContainer().getOrDefault(milkFeedDayKey, PersistentDataType.LONG, -1L);
        if (last >= day) return;
        int required = Math.max(2, plugin.getConfig().getInt("baby.milk-feedings-required", 3));
        int count = baby.getPersistentDataContainer().getOrDefault(pluginMilkCountKey, PersistentDataType.INTEGER, 0) + 1;
        baby.getPersistentDataContainer().set(pluginMilkCountKey, PersistentDataType.INTEGER, count);
        baby.getPersistentDataContainer().set(milkFeedDayKey, PersistentDataType.LONG, day);
        if (hand.getAmount() <= 1) event.getPlayer().getInventory().setItemInMainHand(new ItemStack(Material.BUCKET));
        else { hand.setAmount(hand.getAmount() - 1); event.getPlayer().getInventory().addItem(new ItemStack(Material.BUCKET)); }
        if (count >= required) { baby.setAge(0); baby.getPersistentDataContainer().remove(pluginMilkCountKey); }
        event.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getConfig().getString("messages.prefix", "") + plugin.getConfig().getString("messages.milk-baby", "&aМалыш выпил молоко.")));
    }

    private ItemStack named(Material type, String name) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name)); item.setItemMeta(meta); }
        return item;
    }

    private void enforceBabyGrowth() {
        int required = Math.max(2, plugin.getConfig().getInt("baby.milk-feedings-required", 3));
        for (World world : plugin.getServer().getWorlds()) for (var chunk : world.getLoadedChunks())
            for (BlockState state : chunk.getTileEntities()) if (state instanceof Barrel feeder && isFeeder(feeder.getBlock())) {
                Pen pen = analyze(feeder.getBlock()); if (!pen.valid) continue;
                for (Animals animal : pen.animals) {
                    if (!isMilkAnimal(animal)) continue;
                    UUID id = animal.getUniqueId(); int seen = babyFeeds.getOrDefault(id, 0);
                    int pluginCount = animal.getPersistentDataContainer().getOrDefault(pluginMilkCountKey, PersistentDataType.INTEGER, 0);
                    if (!animal.isAdult()) {
                        animal.getPersistentDataContainer().set(guardKey, PersistentDataType.BYTE, (byte)1);
                        if (pluginCount > 0) seen = Math.max(seen, pluginCount);
                        if (seen >= required - 1 && pluginCount > 0) { animal.setAge(0); babyFeeds.remove(id); animal.getPersistentDataContainer().remove(guardKey); }
                        else babyFeeds.put(id, seen);
                        continue;
                    }
                    if (!animal.getPersistentDataContainer().has(guardKey, PersistentDataType.BYTE)) continue;
                    if (seen < required) { animal.setAge(-24000); babyFeeds.put(id, seen + 1); }
                    else { animal.getPersistentDataContainer().remove(guardKey); babyFeeds.remove(id); }
                }
            }
    }

    private boolean isMilkAnimal(Animals a) { return switch(a.getType()){case COW,SHEEP,GOAT -> true; default -> false;}; }

    private void updateHud() {
        if (!plugin.getConfig().getBoolean("hud.enabled", true)) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Block target = player.getTargetBlockExact(plugin.getConfig().getInt("hud.range", 6));
            if (target == null || !isFeeder(target)) continue;
            Barrel feeder = (Barrel) target.getState(); Pen pen = analyze(target);
            String text = !pen.valid ? "&6Кормушка &7| &cЗагон не готов" : "&6Кормушка &7| &aЗагон активен &7| &fЖивотных: &e" + pen.animals.size() + " &7| &bВода: &e" + count(feeder, Material.WATER_BUCKET) + " &7| &eПшеница: &f" + count(feeder, Material.WHEAT) + " &7| &eСемена: &f" + countSeeds(feeder);
            player.sendActionBar(ChatColor.translateAlternateColorCodes('&', text));
        }
    }
    private int count(Barrel b,Material m){int n=0;for(ItemStack i:b.getInventory().getContents())if(i!=null&&i.getType()==m)n+=i.getAmount();return n;}
    private int countSeeds(Barrel b){int n=0;for(ItemStack i:b.getInventory().getContents())if(i!=null&&isSeed(i.getType()))n+=i.getAmount();return n;}
    private boolean isSeed(Material m){return m.name().endsWith("_SEEDS")||m==Material.PITCHER_POD;}

    private Pen analyze(Block feeder){int r=plugin.getConfig().getInt("pen.max-radius",16),sx=feeder.getX(),sz=feeder.getZ();Set<String>in=new HashSet<>();ArrayDeque<int[]>q=new ArrayDeque<>();q.add(new int[]{sx,sz});in.add(key(sx,sz));int gates=0;boolean open=false,escaped=false;int[][]d={{1,0},{-1,0},{0,1},{0,-1}};while(!q.isEmpty()){int[]p=q.removeFirst();for(int[]v:d){int x=p[0]+v[0],z=p[1]+v[1];if(Math.abs(x-sx)>r||Math.abs(z-sz)>r){escaped=true;continue;}Block b=feeder.getWorld().getBlockAt(x,feeder.getY(),z);if(isGate(b)){if(in.add(key(x,z))){gates++;if(isOpen(b))open=true;}continue;}if(isFence(b))continue;if(b.getType().isAir()&&in.add(key(x,z)))q.add(new int[]{x,z});}}List<Animals>a=new ArrayList<>();for(Entity e:feeder.getWorld().getNearbyEntities(feeder.getLocation(),r+1,plugin.getConfig().getInt("pen.vertical-range",5),r+1))if(e instanceof Animals an&&in.contains(key(an.getLocation().getBlockX(),an.getLocation().getBlockZ())))a.add(an);return new Pen(!escaped&&gates==1&&!open,a);}
    private boolean isFeeder(Block b){return b.getType()==Material.BARREL&&b.getState() instanceof Barrel x&&x.getPersistentDataContainer().has(feederKey,PersistentDataType.BYTE);}
    private boolean isFence(Block b){String n=b.getType().name();return n.endsWith("_FENCE")&&!n.endsWith("_FENCE_GATE");}
    private boolean isGate(Block b){return b.getBlockData() instanceof Openable&&b.getType().name().endsWith("_FENCE_GATE");}
    private boolean isOpen(Block b){return b.getBlockData() instanceof Openable o&&o.isOpen();}
    private String key(int x,int z){return x+":"+z;}
    private record Pen(boolean valid,List<Animals> animals){}
}
