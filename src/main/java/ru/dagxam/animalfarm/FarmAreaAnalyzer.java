package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Centralized, cached validation of farm areas.
 * The analyzer deliberately performs no world-wide chunk scanning.
 */
public final class FarmAreaAnalyzer {

    private static final long CACHE_TTL_TICKS = 40L;
    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final FarmSettings settings;
    private final ConcurrentMap<FarmObjectKey, FarmAreaCache> cache = new ConcurrentHashMap<>();

    public FarmAreaAnalyzer(FarmSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public FarmAreaCache analyze(FarmObjectKey key, FarmObjectType type, long serverTick) {
        FarmAreaCache cached = cache.get(key);
        if (cached != null && cached.type() == type && !cached.isExpired(serverTick, CACHE_TTL_TICKS)) {
            return cached;
        }

        FarmAreaCache fresh = switch (type) {
            case LAND_FEEDER -> analyzePen(key.location(), serverTick);
            case AQUARIUM_SHELF -> analyzeAquarium(key.location(), serverTick);
        };
        cache.put(key, fresh);
        return fresh;
    }

    public void invalidate(FarmObjectKey key) {
        cache.remove(key);
    }

    public void invalidateNear(Location location, int radius) {
        cache.keySet().removeIf(key -> key.location().getWorld().equals(location.getWorld())
                && key.location().distanceSquared(location) <= (double) radius * radius);
    }

    public void clear() {
        cache.clear();
    }

    private FarmAreaCache analyzePen(Location feeder, long tick) {
        int radius = settings.penMaxRadius();
        Block start = feeder.getBlock();
        Set<Block> visited = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(start);

        int gates = 0;
        boolean escaped = false;
        while (!queue.isEmpty() && visited.size() <= radius * radius * 8) {
            Block block = queue.removeFirst();
            if (!visited.add(block)) continue;
            if (Math.abs(block.getX() - start.getX()) > radius || Math.abs(block.getZ() - start.getZ()) > radius) {
                escaped = true;
                break;
            }
            for (int[] direction : HORIZONTAL) {
                Block next = block.getRelative(direction[0], 0, direction[1]);
                if (isFence(next)) continue;
                if (Tag.FENCES.isTagged(next.getType()) || Tag.FENCE_GATES.isTagged(next.getType())) {
                    if (Tag.FENCE_GATES.isTagged(next.getType())) {
                        gates++;
                        if (next.getBlockData() instanceof Openable openable && openable.isOpen()) {
                            escaped = true;
                        }
                    }
                    continue;
                }
                queue.addLast(next);
            }
        }

        boolean valid = !escaped && gates == 1 && !visited.isEmpty();
        return new FarmAreaCache(valid, FarmObjectType.LAND_FEEDER, feeder.clone(), tick);
    }

    private FarmAreaCache analyzeAquarium(Location shelf, long tick) {
        if (!settings.aquariumEnabled()) {
            return new FarmAreaCache(false, FarmObjectType.AQUARIUM_SHELF, shelf.clone(), tick);
        }
        int radius = settings.aquariumMaxRadius();
        Block start = shelf.getBlock();
        Set<Block> visited = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        boolean escaped = false;
        boolean hasWater = false;

        while (!queue.isEmpty() && visited.size() <= radius * radius * 8) {
            Block block = queue.removeFirst();
            if (!visited.add(block)) continue;
            if (Math.abs(block.getX() - start.getX()) > radius || Math.abs(block.getZ() - start.getZ()) > radius) {
                escaped = true;
                break;
            }
            if (block.getType() == Material.WATER) hasWater = true;
            for (int[] direction : HORIZONTAL) {
                Block next = block.getRelative(direction[0], 0, direction[1]);
                if (isGlass(next)) continue;
                queue.addLast(next);
            }
        }

        boolean valid = !escaped && hasWater && !visited.isEmpty();
        return new FarmAreaCache(valid, FarmObjectType.AQUARIUM_SHELF, shelf.clone(), tick);
    }

    private boolean isFence(Block block) {
        return Tag.FENCES.isTagged(block.getType());
    }

    private boolean isGlass(Block block) {
        Material type = block.getType();
        return type == Material.GLASS || type == Material.TINTED_GLASS || type.name().endsWith("_STAINED_GLASS")
                || type.name().endsWith("_GLASS_PANE");
    }
}
