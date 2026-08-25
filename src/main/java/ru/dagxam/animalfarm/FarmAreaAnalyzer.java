package ru.dagxam.animalfarm;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Анализирует реальную область загона и отдельный замкнутый объём воды аквариума. */
public final class FarmAreaAnalyzer {
    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] WATER_DIRECTIONS = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};

    private final FarmSettings settings;
    private final ConcurrentMap<FarmObjectKey, FarmAreaCache> cache = new ConcurrentHashMap<>();

    public FarmAreaAnalyzer(FarmSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public FarmAreaCache analyze(FarmObjectKey key, FarmObjectType type, Server server) {
        FarmAreaCache cached = cache.get(key);
        if (cached != null && cached.type() == type) return cached;
        FarmAreaCache analyzed = type == FarmObjectType.AQUARIUM_SHELF
                ? analyzeAquarium(key.location(server), type)
                : analyzeLandPen(key.location(server), type);
        cache.put(key, analyzed);
        return analyzed;
    }

    public void invalidate(FarmObjectKey key) {
        cache.remove(key);
    }

    public void invalidateNearby(Location location) {
        if (location == null || location.getWorld() == null) return;
        int radius = Math.max(settings.penMaxRadius(), settings.aquariumMaxRadius()) + 1;
        int vertical = Math.max(settings.penVerticalRange(), settings.aquariumVerticalRange()) + 1;
        for (FarmObjectKey key : Set.copyOf(cache.keySet())) {
            Location center;
            try {
                center = key.location(Bukkit.getServer());
            } catch (IllegalStateException ignored) {
                cache.remove(key);
                continue;
            }
            if (center.getWorld() == null || !center.getWorld().getUID().equals(location.getWorld().getUID())) continue;
            if (Math.abs(center.getBlockX() - location.getBlockX()) <= radius
                    && Math.abs(center.getBlockY() - location.getBlockY()) <= vertical
                    && Math.abs(center.getBlockZ() - location.getBlockZ()) <= radius) {
                cache.remove(key);
            }
        }
    }

    public void clear() {
        cache.clear();
    }

    private FarmAreaCache analyzeLandPen(Location center, FarmObjectType type) {
        if (center == null || center.getWorld() == null) return invalid(type, center);
        World world = center.getWorld();
        int radius = settings.penMaxRadius();
        int vertical = settings.penVerticalRange();

        Set<FarmAreaCache.BlockPosition> best = Set.of();
        for (int yOffset : new int[]{0, 1}) {
            int y = center.getBlockY() + yOffset;
            for (int[] direction : HORIZONTAL) {
                int x = center.getBlockX() + direction[0];
                int z = center.getBlockZ() + direction[1];
                Set<FarmAreaCache.BlockPosition> candidate = floodPassablePlane(world, center, x, y, z, radius);
                if (candidate.size() > best.size()) best = candidate;
            }
        }

        if (best.isEmpty()) return invalid(type, center);

        Set<FarmAreaCache.BlockPosition> interior = new HashSet<>();
        for (FarmAreaCache.BlockPosition position : best) {
            for (int y = position.y() - 1; y <= position.y() + vertical; y++) {
                interior.add(new FarmAreaCache.BlockPosition(position.x(), y, position.z()));
            }
        }
        return new FarmAreaCache(true, type, center, interior);
    }

    private Set<FarmAreaCache.BlockPosition> floodPassablePlane(World world, Location center, int startX, int startY, int startZ, int radius) {
        if (!isPassableAreaBlock(world.getBlockAt(startX, startY, startZ))) return Set.of();
        ArrayDeque<FarmAreaCache.BlockPosition> queue = new ArrayDeque<>();
        Set<FarmAreaCache.BlockPosition> visited = new HashSet<>();
        queue.add(new FarmAreaCache.BlockPosition(startX, startY, startZ));
        boolean escaped = false;
        int maxBlocks = Math.max(256, (radius * 2 + 1) * (radius * 2 + 1));

        while (!queue.isEmpty()) {
            if (visited.size() >= maxBlocks) {
                escaped = true;
                break;
            }
            FarmAreaCache.BlockPosition current = queue.removeFirst();
            if (!visited.add(current)) continue;
            if (Math.abs(current.x() - center.getBlockX()) > radius || Math.abs(current.z() - center.getBlockZ()) > radius) {
                escaped = true;
                break;
            }
            for (int[] direction : HORIZONTAL) {
                int x = current.x() + direction[0];
                int z = current.z() + direction[1];
                if (Math.abs(x - center.getBlockX()) > radius || Math.abs(z - center.getBlockZ()) > radius) {
                    escaped = true;
                    continue;
                }
                FarmAreaCache.BlockPosition next = new FarmAreaCache.BlockPosition(x, current.y(), z);
                if (!visited.contains(next) && isPassableAreaBlock(world.getBlockAt(x, current.y(), z))) queue.addLast(next);
            }
        }
        return escaped ? Set.of() : visited;
    }

    private FarmAreaCache analyzeAquarium(Location center, FarmObjectType type) {
        if (center == null || center.getWorld() == null || !settings.aquariumEnabled()) return invalid(type, center);
        World world = center.getWorld();
        int radius = settings.aquariumMaxRadius();
        int vertical = settings.aquariumVerticalRange();

        FarmAreaCache.BlockPosition seed = findWaterSeed(world, center);
        if (seed == null) return invalid(type, center);

        ArrayDeque<FarmAreaCache.BlockPosition> queue = new ArrayDeque<>();
        Set<FarmAreaCache.BlockPosition> water = new HashSet<>();
        queue.add(seed);
        boolean escaped = false;
        int maxBlocks = Math.max(256, (radius * 2 + 1) * (radius * 2 + 1) * (vertical * 2 + 1));

        while (!queue.isEmpty()) {
            if (water.size() >= maxBlocks) {
                escaped = true;
                break;
            }
            FarmAreaCache.BlockPosition current = queue.removeFirst();
            if (!water.add(current)) continue;
            if (Math.abs(current.x() - center.getBlockX()) > radius
                    || Math.abs(current.z() - center.getBlockZ()) > radius
                    || Math.abs(current.y() - center.getBlockY()) > vertical) {
                escaped = true;
                break;
            }
            for (int[] direction : WATER_DIRECTIONS) {
                int x = current.x() + direction[0];
                int y = current.y() + direction[1];
                int z = current.z() + direction[2];
                if (Math.abs(x - center.getBlockX()) > radius
                        || Math.abs(z - center.getBlockZ()) > radius
                        || Math.abs(y - center.getBlockY()) > vertical) {
                    escaped = true;
                    continue;
                }
                FarmAreaCache.BlockPosition next = new FarmAreaCache.BlockPosition(x, y, z);
                if (!water.contains(next) && world.getBlockAt(x, y, z).getType() == Material.WATER) queue.addLast(next);
            }
        }
        return escaped || water.isEmpty() ? invalid(type, center) : new FarmAreaCache(true, type, center, water);
    }

    private FarmAreaCache.BlockPosition findWaterSeed(World world, Location center) {
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        for (int yOffset : new int[]{0, -1, 1, -2}) {
            int y = cy + yOffset;
            if (world.getBlockAt(cx, y, cz).getType() == Material.WATER) return new FarmAreaCache.BlockPosition(cx, y, cz);
            for (int[] direction : HORIZONTAL) {
                int x = cx + direction[0];
                int z = cz + direction[1];
                if (world.getBlockAt(x, y, z).getType() == Material.WATER) return new FarmAreaCache.BlockPosition(x, y, z);
            }
        }
        return null;
    }

    private boolean isPassableAreaBlock(Block block) {
        Material material = block.getType();
        return material.isAir() || block.isPassable();
    }

    private FarmAreaCache invalid(FarmObjectType type, Location center) {
        Location safeCenter = center == null ? new Location(null, 0, 0, 0) : center;
        return new FarmAreaCache(false, type, safeCenter, Set.of());
    }
}
