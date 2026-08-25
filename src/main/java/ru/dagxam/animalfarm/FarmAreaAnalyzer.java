package ru.dagxam.animalfarm;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FarmAreaAnalyzer {
    private static final int[][] DIRECTIONS = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0},{0,-1,0}};
    private final FarmSettings settings;
    private final ConcurrentMap<FarmObjectKey, FarmAreaCache> cache = new ConcurrentHashMap<>();
    public FarmAreaAnalyzer(FarmSettings settings){this.settings=Objects.requireNonNull(settings,"settings");}
    public FarmAreaCache analyze(FarmObjectKey key,FarmObjectType type,Server server){FarmAreaCache c=cache.get(key);if(c!=null&&c.type()==type)return c;FarmAreaCache f=analyzeEnclosure(key.location(server),type);cache.put(key,f);return f;}
    public void invalidate(FarmObjectKey key){cache.remove(key);}
    public void invalidateNearby(Location location){if(location==null||location.getWorld()==null)return;int radius=Math.max(settings.penMaxRadius(),settings.aquariumMaxRadius())+1;int vertical=Math.max(settings.penVerticalRange(),settings.aquariumVerticalRange())+1;for(FarmObjectKey key:Set.copyOf(cache.keySet())){Location center=key.location(Bukkit.getServer());if(center.getWorld()==null||!center.getWorld().getUID().equals(location.getWorld().getUID()))continue;if(Math.abs(center.getBlockX()-location.getBlockX())<=radius&&Math.abs(center.getBlockY()-location.getBlockY())<=vertical&&Math.abs(center.getBlockZ()-location.getBlockZ())<=radius)cache.remove(key);}}
    public void clear(){cache.clear();}
    private FarmAreaCache analyzeEnclosure(Location center,FarmObjectType type){if(center==null||center.getWorld()==null)return new FarmAreaCache(false,type,new Location(null,0,0,0),Set.of());if(type==FarmObjectType.AQUARIUM_SHELF&&!settings.aquariumEnabled())return new FarmAreaCache(false,type,center,Set.of());int radius=type==FarmObjectType.LAND_FEEDER?settings.penMaxRadius():settings.aquariumMaxRadius();int vertical=type==FarmObjectType.LAND_FEEDER?settings.penVerticalRange():settings.aquariumVerticalRange();Block start=center.getBlock();Set<FarmAreaCache.BlockPosition> visited=new HashSet<>();ArrayDeque<FarmAreaCache.BlockPosition> queue=new ArrayDeque<>();queue.add(new FarmAreaCache.BlockPosition(start.getX(),start.getY(),start.getZ()));boolean escaped=false,hasWater=false;int maxBlocks=Math.max(256,(radius*2+1)*(radius*2+1)*(vertical*2+1));while(!queue.isEmpty()){if(visited.size()>=maxBlocks){escaped=true;break;}FarmAreaCache.BlockPosition current=queue.removeFirst();if(!visited.add(current))continue;if(Math.abs(current.x()-start.getX())>radius||Math.abs(current.z()-start.getZ())>radius||Math.abs(current.y()-start.getY())>vertical){escaped=true;break;}Block block=center.getWorld().getBlockAt(current.x(),current.y(),current.z());if(block.getType()==Material.WATER)hasWater=true;for(int[] d:DIRECTIONS){FarmAreaCache.BlockPosition next=new FarmAreaCache.BlockPosition(current.x()+d[0],current.y()+d[1],current.z()+d[2]);if(visited.contains(next))continue;Block nextBlock=center.getWorld().getBlockAt(next.x(),next.y(),next.z());if(!isBoundary(nextBlock))queue.addLast(next);}}boolean valid=!escaped&&!visited.isEmpty()&&(type!=FarmObjectType.AQUARIUM_SHELF||hasWater);return new FarmAreaCache(valid,type,center,visited);}
    private boolean isBoundary(Block block){Material material=block.getType();return !material.isAir()&&!block.isPassable();}
}