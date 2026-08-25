package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fish;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class GoldenBoostManager {
    private final AnimalFarmPlugin plugin; private final FarmSettings settings; private final FarmEntityService entityService; private final NamespacedKey dayKey,nextBreedDayKey;
    public GoldenBoostManager(AnimalFarmPlugin plugin,FarmSettings settings){this.plugin=plugin;this.settings=settings;this.entityService=plugin.farmEntityService();this.dayKey=new NamespacedKey(plugin,"golden_boost_day");this.nextBreedDayKey=new NamespacedKey(plugin,"next_breed_day");}
    public void process(FarmObjectKey key,FarmObjectType type,long currentTick){Location location=key.location(plugin.getServer());if(location.getWorld()==null||!(location.getBlock().getState() instanceof Barrel feeder)||!hasGoldenFood(feeder.getInventory()))return;FarmAreaCache area=plugin.farmObjectManager().areaAnalyzer().analyze(key,type,plugin.getServer());if(!area.valid())return;long day=location.getWorld().getFullTime()/24000L;long last=feeder.getPersistentDataContainer().getOrDefault(dayKey,PersistentDataType.LONG,-1L);if(last>=day)return;int produced=type==FarmObjectType.AQUARIUM_SHELF?breedFish(feeder,area,day):breedAnimals(feeder,area,day);if(produced>0){feeder.getPersistentDataContainer().set(dayKey,PersistentDataType.LONG,day);feeder.update(true,false);}}
    private int breedAnimals(Barrel feeder,FarmAreaCache area,long day){List<Animals> animals=entityService.findAnimals(area);Map<EntityType,List<Animals>> groups=new HashMap<>();for(Animals a:animals)if(a.isAdult()&&supported(a))groups.computeIfAbsent(a.getType(),k->new ArrayList<>()).add(a);int pairs=0,children=0,currentCount=animals.size();outer:for(List<Animals> group:groups.values()){Animals male=null,female=null;for(Animals a:group){AnimalGender g=plugin.genderManager().getOrAssign(a);if(g==AnimalGender.MALE&&male==null)male=a;if(g==AnimalGender.FEMALE&&female==null)female=a;if(male!=null&&female!=null)break;}if(male==null||female==null||!plugin.genderManager().canBreed(male,female))continue;while(pairs<settings.maxBreedingPairsPerDay()){int amount=Math.min(random(2,4),settings.animalCapacity()-currentCount-children);if(amount<=0)break outer;if(!consumeGoldenFood(feeder.getInventory()))break outer;Location spawn=midpoint(male.getLocation(),female.getLocation());for(int i=0;i<amount;i++){Entity child=male.getWorld().spawnEntity(spawn.clone().add(randomOffset(),0,randomOffset()),male.getType());if(child instanceof Ageable ageable){ageable.setBaby();if(child instanceof Animals animalChild)plugin.genderManager().assignRandomIfSupported(animalChild);}children++;}scheduleNext(male,day);scheduleNext(female,day);pairs++;break;}}return children;}
    private int breedFish(Barrel feeder,FarmAreaCache area,long day){List<Fish> fish=entityService.findFish(area);Map<EntityType,List<Fish>> groups=new HashMap<>();for(Fish f:fish)groups.computeIfAbsent(f.getType(),k->new ArrayList<>()).add(f);int pairs=0,children=0;outer:for(List<Fish> group:groups.values())for(int i=0;i+1<group.size()&&pairs<settings.maxBreedingPairsPerDay();i+=2){int amount=Math.min(random(2,4),settings.fishCapacity()-fish.size()-children);if(amount<=0)break outer;if(!consumeGoldenFood(feeder.getInventory()))break outer;Location spawn=group.get(i).getLocation().clone();for(int j=0;j<amount;j++){group.get(i).getWorld().spawnEntity(spawn.clone().add(randomOffset(),0,randomOffset()),group.get(i).getType());children++;}scheduleNext(group.get(i),day);scheduleNext(group.get(i+1),day);pairs++;}return children;}
    private boolean supported(Animals a){return switch(a.getType()){case COW,SHEEP,GOAT,PIG,CHICKEN,HORSE,RABBIT->true;default->false;};}
    private boolean hasGoldenFood(Inventory i){return count(i,Material.GOLDEN_APPLE)>0||count(i,Material.GOLDEN_CARROT)>0;}
    private boolean consumeGoldenFood(Inventory i){return consumeOne(i,Material.GOLDEN_APPLE)||consumeOne(i,Material.GOLDEN_CARROT);}
    private int count(Inventory i,Material m){int total=0;for(ItemStack item:i.getContents())if(item!=null&&item.getType()==m)total+=item.getAmount();return total;}
    private boolean consumeOne(Inventory i,Material m){for(int s=0;s<i.getSize();s++){ItemStack item=i.getItem(s);if(item==null||item.getType()!=m||item.getAmount()<=0)continue;if(item.getAmount()==1)i.setItem(s,null);else item.setAmount(item.getAmount()-1);return true;}return false;}
    private Location midpoint(Location a,Location b){Location r=a.clone();r.add(b).multiply(.5);r.setWorld(a.getWorld());return r;}
    private void scheduleNext(Entity e,long day){e.getPersistentDataContainer().set(nextBreedDayKey,PersistentDataType.LONG,day+random(1,3));}
    private int random(int min,int max){return ThreadLocalRandom.current().nextInt(min,max+1);} private double randomOffset(){return ThreadLocalRandom.current().nextDouble(-.35,.36);}
}