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
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Replaces most successful rod catches with living aquatic mobs.
 * The mob is always spawned in nearby water so large aquatic mobs do not suffocate on land.
 */
public final class FishingManager implements Listener {

    private static final EntityType[] OCEAN_MOBS = {
            EntityType.COD, EntityType.SALMON, EntityType.TROPICAL_FISH, EntityType.PUFFERFISH,
            EntityType.SQUID, EntityType.GLOW_SQUID, EntityType.DOLPHIN, EntityType.TURTLE
    };

    private static final EntityType[] RIVER_MOBS = {
            EntityType.COD, EntityType.SALMON, EntityType.SQUID, EntityType.AXOLOTL
    };

    private static final EntityType[] SWAMP_MOBS = {
            EntityType.FROG, EntityType.AXOLOTL, EntityType.SALMON, EntityType.COD
    };

    private static final double LIVE_CATCH_CHANCE = 0.90D;

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (ThreadLocalRandom.current().nextDouble() > LIVE_CATCH_CHANCE) return;

        Entity originalCatch = event.getCaught();
        if (originalCatch == null || originalCatch.getWorld() == null) return;

        Location water = findWater(originalCatch.getLocation(), 4);
        if (water == null) return;

        EntityType type = chooseCatch(event.getPlayer(), water);
        Entity caught = water.getWorld().spawnEntity(water, type);
        event.setCaught(caught);
        originalCatch.remove();
        event.setExpToDrop(ThreadLocalRandom.current().nextInt(1, 5));

        // Prevent the newly created mob from being instantly pulled to a dry block.
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!caught.isValid()) return;
                Location safeWater = findWater(caught.getLocation(), 6);
                if (safeWater != null && caught.getLocation().getBlock().getType() != Material.WATER) {
                    caught.teleport(safeWater);
                }
            }
        }.runTaskLater(AnimalFarmPlugin.getProvidingPlugin(FishingManager.class), 1L);
    }

    private EntityType chooseCatch(Player player, Location location) {
        String biome = location.getBlock().getBiome().name();

        if (biome.contains("SWAMP")) {
            return weighted(SWAMP_MOBS);
        }
        if (biome.contains("WARM_OCEAN")) {
            return weighted(
                    EntityType.TROPICAL_FISH, EntityType.TROPICAL_FISH, EntityType.TROPICAL_FISH,
                    EntityType.PUFFERFISH, EntityType.TURTLE, EntityType.TURTLE,
                    EntityType.DOLPHIN, EntityType.SQUID, EntityType.COD
            );
        }
        if (biome.contains("COLD") || biome.contains("FROZEN")) {
            return weighted(
                    EntityType.SALMON, EntityType.SALMON, EntityType.COD, EntityType.COD,
                    EntityType.SQUID, EntityType.GLOW_SQUID, EntityType.DOLPHIN
            );
        }
        if (biome.contains("OCEAN") || biome.contains("BEACH")) {
            return weighted(OCEAN_MOBS);
        }
        return weighted(RIVER_MOBS);
    }

    private EntityType weighted(EntityType... types) {
        return types[ThreadLocalRandom.current().nextInt(types.length)];
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
        if (candidates.isEmpty()) return null;
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }
}
