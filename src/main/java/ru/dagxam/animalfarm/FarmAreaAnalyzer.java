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

/** Centralized, cached validation of farm areas. */
public final class FarmAreaAnalyzer {
    private static final long CACHE_TTL_TICKS = 40L;
    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final FarmSettings settings;
    private final ConcurrentMap<FarmObjectKey, FarmAreaCache> cache = new ConcurrentHashMap<>();

    public FarmAreaAnalyzer(FarmSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public FarmAreaCache analyze(FarmObjectKey key, FarmObjectType type, long serverTick, org.bukkit.Server server) {
        FarmAreaCache cached = cache.get(key);
        if (cached != null && cached.type() == type && !cached.isExpired(serverTick, CACHE_TTL_TICKS)) {
            return cached;
        }

        Location location = key.location(server);
        FarmAreaCache fresh = switch (type) {
            case LAND_FEEDER -> analyzePen(location, serverTick);
            case AQUARIUM_SHELF -> analyzeAquarium(location, serverTick);
        };
        cache.put(key, fresh);
        return fresh;
    }

    public void invalidate(FarmObjectKey key) {
        cache.remove(key);
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
        boolean openGate = false;

        while (!queue.isEmpty() && visited.size() <= radius * radius * 8) {
            Block block = queue.removeFirst();
            if (!visited.add(block)) continue;
            if (Math.abs(block.getX() - start.getX()) > radius || Math.abs(block.getZ() - start.getZ()) > radius) {
                escaped = true;
                break;
            }

            for (int[] direction : HORIZONTAL) {
                Block next = block.getRelative(direction[0], 0, direction[1]);
                if (Tag.FENCES.isTagged(next.getType())) {
                    continue;
                }
                if (Tag.FENCE_GATES.isTagged(next.getType())) {
                    gates++;
                    if (next.getBlockData() instanceof Openable openable && openable.isOpen()) {
                        openGate = true;
                        escaped = true;
                    }
                    continue;
                }
                queue.addLast(next);
            }
        }

        boolean valid = !escaped && gates == 1 && !openGate;
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
            if (block.getType() == Material.WATER) {
                hasWater = true;
            }

            for (int[] direction : HORIZONTAL) {
                Block next = block.getRelative(direction[0], 0, direction[1]);
                if (isGlass(next)) continue;
                queue.addLast(next);
            }
        }

        boolean valid = !escaped && hasWater;
        return new FarmAreaCache(valid, FarmObjectType.AQUARIUM_SHELF, shelf.clone(), tick);
    }

    private boolean isGlass(Block block) {
        Material type = block.getType();
        return type == Material.GLASS
                || type == Material.TINTED_GLASS
                || type.name().endsWith("_STAINED_GLASS")
                || type.name().endsWith("_GLASS_PANE");
    }
}
