package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Ловля живых морских существ и рыб удочкой. */
public final class FishingManager implements Listener {
    private final AnimalFarmPlugin plugin;
    public FishingManager(AnimalFarmPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!plugin.getConfig().getBoolean("fishing.enabled", true)) return;
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        double chance = Math.max(0, Math.min(100, plugin.getConfig().getDouble("fishing.live-catch-chance", 90))) / 100.0;
        if (ThreadLocalRandom.current().nextDouble() > chance) return;

        Entity caught = event.getCaught();
        if (caught == null || caught.getWorld() == null) return;
        Location water = findWater(caught.getLocation(), 4);
        if (water == null) return;

        EntityType type = chooseCatch(water);
        if (type == null) return;

        event.setCancelled(true);
        caught.remove();

        // Сначала пытаемся выдать существу натуральное яйцо призыва.
        Material egg = spawnEgg(type);
        Player player = event.getPlayer();
        if (egg != null) {
            player.getInventory().addItem(new ItemStack(egg));
        } else {
            // Для типов без Spawn Egg в Bukkit API создаём моба прямо в воде.
            water.getWorld().spawnEntity(water, type);
        }
    }

    private EntityType chooseCatch(Location location) {
        String biome = location.getBlock().getBiome().getKey().getKey().toUpperCase(Locale.ROOT);
        List<EntityType> pool = new ArrayList<>();
        if (enabled("fish")) {
            add(pool, EntityType.COD, 6); add(pool, EntityType.SALMON, 5);
            if (biome.contains("WARM_OCEAN")) { add(pool, EntityType.TROPICAL_FISH, 8); add(pool, EntityType.PUFFERFISH, 4); }
            else { add(pool, EntityType.TROPICAL_FISH, 2); add(pool, EntityType.PUFFERFISH, 1); }
        }
        boolean ocean = biome.contains("OCEAN") || biome.contains("BEACH");
        boolean swamp = biome.contains("SWAMP");
        boolean deep = ocean || location.getBlockY() < 55;
        if (enabled("squid") && !swamp) add(pool, EntityType.SQUID, ocean ? 2 : 1);
        if (enabled("glow-squid") && deep) add(pool, EntityType.GLOW_SQUID, 1);
        if (enabled("axolotl") && (!ocean || biome.contains("LUSH"))) add(pool, EntityType.AXOLOTL, 2);
        if (enabled("turtle") && ocean) add(pool, EntityType.TURTLE, 1);
        if (enabled("dolphin") && ocean) add(pool, EntityType.DOLPHIN, 1);
        if (enabled("frog") && swamp) add(pool, EntityType.FROG, 2);
        return pool.isEmpty() ? null : pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private boolean enabled(String key) { return plugin.getConfig().getBoolean("fishing.mobs." + key, true); }
    private void add(List<EntityType> pool, EntityType type, int weight) { for (int i = 0; i < weight; i++) pool.add(type); }

    private Material spawnEgg(EntityType type) {
        return switch (type) {
            case COD -> Material.COD_SPAWN_EGG;
            case SALMON -> Material.SALMON_SPAWN_EGG;
            case TROPICAL_FISH -> Material.TROPICAL_FISH_SPAWN_EGG;
            case PUFFERFISH -> Material.PUFFERFISH_SPAWN_EGG;
            case AXOLOTL -> Material.AXOLOTL_SPAWN_EGG;
            case SQUID -> Material.SQUID_SPAWN_EGG;
            case GLOW_SQUID -> Material.GLOW_SQUID_SPAWN_EGG;
            case TURTLE -> Material.TURTLE_SPAWN_EGG;
            case DOLPHIN -> Material.DOLPHIN_SPAWN_EGG;
            case FROG -> Material.FROG_SPAWN_EGG;
            default -> null;
        };
    }

    private Location findWater(Location origin, int radius) {
        Location center = origin.clone();
        if (center.getBlock().getType() == Material.WATER) return center;
        List<Location> candidates = new ArrayList<>();
        for (int x=-radius;x<=radius;x++) for(int y=-2;y<=2;y++) for(int z=-radius;z<=radius;z++) {
            Block block = center.clone().add(x,y,z).getBlock();
            if (block.getType() == Material.WATER) candidates.add(block.getLocation().add(.5,.2,.5));
        }
        return candidates.isEmpty() ? null : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }
} 
