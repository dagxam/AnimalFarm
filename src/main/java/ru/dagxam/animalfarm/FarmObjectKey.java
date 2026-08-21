package ru.dagxam.animalfarm;

import org.bukkit.Location;

import java.util.UUID;

public record FarmObjectKey(UUID worldId, int x, int y, int z) {
    public static FarmObjectKey of(Location location) {
        if (location.getWorld() == null) throw new IllegalArgumentException("Location must have a world");
        return new FarmObjectKey(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public Location location(org.bukkit.Server server) {
        var world = server.getWorld(worldId);
        if (world == null) throw new IllegalStateException("World is not loaded: " + worldId);
        return new Location(world, x, y, z);
    }
}
