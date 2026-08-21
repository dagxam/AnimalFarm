package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Makes a normal fishing rod catch almost every vanilla fish that can live underwater.
 * Vanilla fishing still remains possible; most successful catches are replaced with a live fish.
 */
public final class FishingManager implements Listener {

    private static final EntityType[] FISH = {
            EntityType.COD,
            EntityType.SALMON,
            EntityType.TROPICAL_FISH,
            EntityType.PUFFERFISH
    };

    /** 90% keeps a small amount of normal vanilla loot. */
    private static final double LIVE_FISH_CHANCE = 0.90D;

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() > LIVE_FISH_CHANCE) {
            return;
        }

        Entity originalCatch = event.getCaught();
        if (originalCatch == null || originalCatch.getWorld() == null) {
            return;
        }

        Location location = originalCatch.getLocation();
        Player player = event.getPlayer();
        EntityType type = chooseFish(player, location);

        Entity fish = location.getWorld().spawnEntity(location, type);
        event.setCaught(fish);
        originalCatch.remove();

        // A small amount of experience is still granted for a successful catch.
        event.setExpToDrop(ThreadLocalRandom.current().nextInt(1, 4));
    }

    private EntityType chooseFish(Player player, Location location) {
        // Keep the result varied while favoring fish that naturally fit the environment.
        String biome = location.getBlock().getBiome().name();
        if (biome.contains("WARM_OCEAN")) {
            return weighted(EntityType.TROPICAL_FISH, EntityType.TROPICAL_FISH,
                    EntityType.PUFFERFISH, EntityType.COD, EntityType.SALMON);
        }
        if (biome.contains("COLD") || biome.contains("FROZEN")) {
            return weighted(EntityType.SALMON, EntityType.SALMON,
                    EntityType.COD, EntityType.COD, EntityType.PUFFERFISH);
        }
        return FISH[ThreadLocalRandom.current().nextInt(FISH.length)];
    }

    private EntityType weighted(EntityType... types) {
        return types[ThreadLocalRandom.current().nextInt(types.length)];
    }
}
