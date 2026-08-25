package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Set;

/**
 * Результат анализа замкнутой области фермы.
 * Хранит набор внутренних блоков, чтобы животные и рыбы учитывались только внутри неё.
 */
public record FarmAreaCache(
        boolean valid,
        FarmObjectType type,
        Location center,
        Set<BlockPosition> interior
) {
    public FarmAreaCache {
        center = center.clone();
        interior = Set.copyOf(interior);
    }

    public boolean contains(Location location) {
        if (location == null || center.getWorld() == null || location.getWorld() == null) return false;
        if (!center.getWorld().getUID().equals(location.getWorld().getUID())) return false;
        return interior.contains(new BlockPosition(
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        ));
    }

    public boolean sameWorld(World world) {
        return world != null && center.getWorld() != null && center.getWorld().getUID().equals(world.getUID());
    }

    public record BlockPosition(int x, int y, int z) {}
}
