package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ловля живых водных существ обычной удочкой.
 * Все основные параметры берутся из config.yml.
 */
public final class FishingManager implements Listener {

    private final AnimalFarmPlugin plugin;

    public FishingManager(AnimalFarmPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!plugin.getConfig().getBoolean("fishing.enabled", true)) return;
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;

        double chance = Math.max(0, Math.min(100,
                plugin.getConfig().getDouble("fishing.live-catch-chance", 90))) / 100.0D;
        if (ThreadLocalRandom.current().nextDouble() > chance) return;

        Entity originalCatch = event.getCaught();
        if (originalCatch == null || originalCatch.getWorld() == null) return;

        Location water = findWater(originalCatch.getLocation(), 4);
        if (water == null) return;

        EntityType type = chooseCatch(water);
        if (type == null) return;

        // В актуальном Paper API CAUGHT_FISH нельзя заменить через setCaught().
        // Отменяем ванильный улов, удаляем предмет и создаём выбранного живого моба в воде.
        event.setCancelled(true);
        originalCatch.remove();
        water.getWorld().spawnEntity(water, type);
    }

    private EntityType chooseCatch(Location location) {
        String biome = location.getBlock().getBiome().getKey().getKey().toUpperCase(Locale.ROOT);
        List<EntityType> pool = new ArrayList<>();

        // Обычные рыбы добавляются с большим весом, чтобы они попадались чаще редких мобов.
        if (plugin.getConfig().getBoolean("fishing.mobs.fish", true)) {
            add(pool, EntityType.COD, 6);
            add(pool, EntityType.SALMON, 5);
            if (biome.contains("WARM_OCEAN")) {
                add(pool, EntityType.TROPICAL_FISH, 8);
                add(pool, EntityType.PUFFERFISH, 4);
            } else if (biome.contains("COLD") || biome.contains("FROZEN")) {
                add(pool, EntityType.COD, 4);
                add(pool, EntityType.SALMON, 5);
            } else {
                add(pool, EntityType.TROPICAL_FISH, 2);
                add(pool, EntityType.PUFFERFISH, 1);
            }
        }

        boolean ocean = biome.contains("OCEAN") || biome.contains("BEACH");
        boolean swamp = biome.contains("SWAMP");
        boolean deepWater = ocean || location.getBlockY() < 55;

        if (plugin.getConfig().getBoolean("fishing.mobs.squid", true) && !swamp) {
            add(pool, EntityType.SQUID, ocean ? 2 : 1);
        }
        if (plugin.getConfig().getBoolean("fishing.mobs.glow-squid", true) && deepWater) {
            add(pool, EntityType.GLOW_SQUID, 1);
        }
        if (plugin.getConfig().getBoolean("fishing.mobs.axolotl", true)
                && (swamp || !ocean || biome.contains("LUSH"))) {
            add(pool, EntityType.AXOLOTL, 2);
        }
        if (plugin.getConfig().getBoolean("fishing.mobs.turtle", true) && ocean) {
            add(pool, EntityType.TURTLE, 1);
        }
        if (plugin.getConfig().getBoolean("fishing.mobs.dolphin", true) && ocean) {
            add(pool, EntityType.DOLPHIN, 1);
        }
        if (plugin.getConfig().getBoolean("fishing.mobs.frog", true) && swamp) {
            add(pool, EntityType.FROG, 2);
        }

        if (pool.isEmpty()) return null;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private void add(List<EntityType> pool, EntityType type, int weight) {
        for (int i = 0; i < weight; i++) pool.add(type);
    }

    private Location findWater(Location origin, int radius) {
        Location center = origin.clone();
        if (center.getBlock().getType() == Material.WATER) return center;

        List<Location> candidates = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = center.clone().add(x, y, z).getBlock();
                    if (block.getType() == Material.WATER) {
                        candidates.add(block.getLocation().add(0.5, 0.2, 0.5));
                    }
                }
            }
        }
        return candidates.isEmpty() ? null
                : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }
}
