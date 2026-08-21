package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Проверяет любое замкнутое пространство, пригодное для соответствующей кормушки. */
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
        if (cached != null && cached.type() == type && !cached.isExpired(serverTick, CACHE_TTL_TICKS)) return cached;

        Location location = key.location(server);
        FarmAreaCache fresh = switch (type) {
            case LAND_FEEDER -> analyzeEnclosure(location, FarmObjectType.LAND_FEEDER, serverTick);
            case AQUARIUM_SHELF -> analyzeEnclosure(location, FarmObjectType.AQUARIUM_SHELF, serverTick);
        };
        cache.put(key, fresh);
        return fresh;
    }

    public void invalidate(FarmObjectKey key) { cache.remove(key); }
    public void clear() { cache.clear(); }

    private FarmAreaCache analyzeEnclosure(Location center, FarmObjectType type, long tick) {
        if (type == FarmObjectType.AQUARIUM_SHELF && !settings.aquariumEnabled()) {
            return new FarmAreaCache(false, type, center.clone(), tick);
        }

        int radius = type == FarmObjectType.LAND_FEEDER ? settings.penMaxRadius() : settings.aquariumMaxRadius();
        int vertical = type == FarmObjectType.LAND_FEEDER ? settings.penVerticalRange() : settings.aquariumVerticalRange();
        Block start = center.getBlock();
        Set<Block> visited = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(start);

        boolean closed = true;
        boolean hasWater = false;
        int inspected = 0;
        int maxBlocks = Math.max(256, (radius * 2 + 1) * (radius * 2 + 1) * Math.max(2, vertical + 1));

        while (!queue.isEmpty() && inspected++ < maxBlocks) {
            Block block = queue.removeFirst();
            if (!visited.add(block)) continue;

            if (Math.abs(block.getX() - start.getX()) > radius
                    || Math.abs(block.getZ() - start.getZ()) > radius
                    || Math.abs(block.getY() - start.getY()) > vertical) {
                closed = false;
                break;
            }

            if (block.getType() == Material.WATER) hasWater = true;

            for (int[] dir : HORIZONTAL) {
                Block next = block.getRelative(dir[0], 0, dir[1]);
                if (isBoundary(next)) continue;
                queue.addLast(next);
            }
            if (block.getY() < start.getY() + vertical) {
                Block up = block.getRelative(0, 1, 0);
                if (!isBoundary(up)) queue.addLast(up);
            }
            if (block.getY() > start.getY() - vertical) {
                Block down = block.getRelative(0, -1, 0);
                if (!isBoundary(down)) queue.addLast(down);
            }
        }

        if (inspected >= maxBlocks) closed = false;
        boolean valid = closed && !visited.isEmpty() && (type != FarmObjectType.AQUARIUM_SHELF || hasWater);
        return new FarmAreaCache(valid, type, center.clone(), tick);
    }

    /** Любой непроходимый блок считается частью границы. */
    private boolean isBoundary(Block block) {
        Material material = block.getType();
        return !material.isAir() && !block.isPassable();
    }
}
