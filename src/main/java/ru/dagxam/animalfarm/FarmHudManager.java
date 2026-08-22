package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fish;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Расширенный HUD фермы: количество, вместимость, ресурсы и состояние. */
public final class FarmHudManager implements Listener {
    private static final long UPDATE_INTERVAL_MS=250L;
    private final AnimalFarmPlugin plugin; private final Map<UUID,Long> next=new HashMap<>(); private final Map<UUID,String> last=new HashMap<>();
    public FarmHudManager(AnimalFarmPlugin plugin){this.plugin=plugin;}
    @EventHandler(ignoreCancelled=true) public void onMove(org.bukkit.event.player.PlayerMoveEvent e){if(e.getFrom().getBlockX()==e.getTo().getBlockX()&&e.getFrom().getBlockY()==e.getTo().getBlockY()&&e.getFrom().getBlockZ()==e.getTo().getBlockZ())return;update(e.getPlayer());}
    @EventHandler(ignoreCancelled=true) public void onInteract(PlayerInteractEvent e){if(e.getAction()==Action.RIGHT_CLICK_BLOCK&&e.getClickedBlock()!=null)send(e.getPlayer(),e.getClickedBlock());}
    private void update(Player p){if(!plugin.getConfig().getBoolean("hud.enabled",true))return;long now=System.currentTimeMillis();UUID id=p.getUniqueId();if(now<next.getOrDefault(id,0L))return;next.put(id,now+UPDATE_INTERVAL_MS);Block b=p.getTargetBlockExact(plugin.settings().hudRange());if(b==null)return;String k=b.getWorld().getUID()+":"+b.getX()+":"+b.getY()+":"+b.getZ();if(k.equals(last.get(id)))return;last.put(id,k);send(p,b);}
    private void send(Player p,Block b){FarmObjectType type=plugin.farmObjectManager().typeOf(b);if(type==null||!(b.getState() instanceof Barrel barrel))return;Location l=b.getLocation();Inventory i=barrel.getInventory();String state=barrel.getPersistentDataContainer().getOrDefault(new NamespacedKey(plugin,"farm_state"),PersistentDataType.STRING,"HEALTHY");if(type==FarmObjectType.LAND_FEEDER){int animals=countAnimals(l),food=countAnimalFood(i),water=count(i,Material.WATER_BUCKET);p.sendActionBar("§6Ферма §7| §fЖивотные: §e"+animals+"§7/§e"+plugin.settings().animalCapacity()+" §7| §fКорм: §e"+food+" §7| §fВода: §b"+water+" §7| "+stateText(state));}else{int fish=countFish(l),food=countFishFood(i);p.sendActionBar("§bАквакультура §7| §fРыбы: §e"+fish+"§7/§e"+plugin.settings().fishCapacity()+" §7| §fКорм: §e"+food+" §7| "+stateText(state));}}
    private String stateText(String s){return switch(s){case "FULL"->"§cЗаполнено";case "NO_FOOD"->"§cНет корма";case "NO_WATER"->"§cНет воды";default->"§aВ норме";};}
    private int countAnimals(Location l){int n=0;for(Entity e:l.getWorld().getNearbyEntities(l,plugin.settings().penMaxRadius()+1,plugin.settings().penVerticalRange(),plugin.settings().penMaxRadius()+1))if(e instanceof Animals a&&switch(a.getType()){case COW,SHEEP,GOAT,CHICKEN,HORSE,RABBIT->true;default->false;})n++;return n;}
    private int countFish(Location l){int n=0;for(Entity e:l.getWorld().getNearbyEntities(l,plugin.settings().aquariumMaxRadius(),plugin.settings().aquariumVerticalRange(),plugin.settings().aquariumMaxRadius()))if(e instanceof Fish f&&f.getLocation().getBlock().getType()==Material.WATER)n++;return n;}
    private int countAnimalFood(Inventory i){int n=0;for(ItemStack x:i.getContents())if(x!=null&&isAnimalFood(x.getType()))n+=x.getAmount();return n;}
    private int countFishFood(Inventory i){int n=0;for(ItemStack x:i.getContents())if(x!=null&&isFishFood(x.getType()))n+=x.getAmount();return n;}
    private boolean isSeed(Material m){return m.name().endsWith("_SEEDS")||m==Material.PITCHER_POD;}
    private boolean isFishFood(Material m){return isSeed(m)||m==Material.SEAGRASS||m==Material.KELP||m==Material.SEA_PICKLE;}
    private boolean isAnimalFood(Material m){return isFishFood(m)||m==Material.WHEAT||m==Material.HAY_BLOCK||m==Material.APPLE||m==Material.CARROT||m==Material.MELON_SLICE||m==Material.PUMPKIN||m==Material.MELON||m==Material.GOLDEN_APPLE||m==Material.GOLDEN_CARROT;}
    private int count(Inventory i,Material m){int n=0;for(ItemStack x:i.getContents())if(x!=null&&x.getType()==m)n+=x.getAmount();return n;}
}
