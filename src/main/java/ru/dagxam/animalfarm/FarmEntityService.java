package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fish;

import java.util.ArrayList;
import java.util.List;

/** Единая точка поиска сущностей, принадлежащих конкретной области фермы. */
public final class FarmEntityService {
    private final AnimalFarmPlugin plugin;
    private final FarmSettings settings;

    public FarmEntityService(AnimalFarmPlugin plugin, FarmSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public List<Animals> findAnimals(FarmAreaCache area) {
        List<Animals> result = new ArrayList<>();
        if (area == null || !area.valid() || area.center().getWorld() == null) return result;

        Location center = area.center();
        World world = center.getWorld();
        int radius = settings.penMaxRadius() + 1;
        int vertical = settings.penVerticalRange() + 1;

        for (Entity entity : world.getNearbyEntities(center, radius, vertical, radius)) {
            if (entity instanceof Animals animal && supported(animal) && area.contains(animal.getLocation())) {
                result.add(animal);
            }
        }
        return result;
    }

    public List<Fish> findFish(FarmAreaCache area) {
        List<Fish> result = new ArrayList<>();
        if (area == null || !area.valid() || area.center().getWorld() == null) return result;

        Location center = area.center();
        World world = center.getWorld();
        int radius = settings.aquariumMaxRadius() + 1;
        int vertical = settings.aquariumVerticalRange() + 1;

        for (Entity entity : world.getNearbyEntities(center, radius, vertical, radius)) {
            if (entity instanceof Fish fish
                    && area.contains(fish.getLocation())
                    && fish.getLocation().getBlock().isLiquid()) {
                result.add(fish);
            }
        }
        return result;
    }

    public List<Animals> getAnimals(Location location) {
        if (location == null || location.getWorld() == null) return List.of();
        List<Animals> result = new ArrayList<>();
        int radius = settings.penMaxRadius() + 1;
        int vertical = settings.penVerticalRange() + 1;
        for (Entity entity : location.getWorld().getNearbyEntities(location, radius, vertical, radius)) {
            if (entity instanceof Animals animal && supported(animal)) {
                result.add(animal);
            }
        }
        return result;
    }

    public List<Fish> getFish(Location location) {
        if (location == null || location.getWorld() == null) return List.of();
        List<Fish> result = new ArrayList<>();
        int radius = settings.aquariumMaxRadius() + 1;
        int vertical = settings.aquariumVerticalRange() + 1;
        for (Entity entity : location.getWorld().getNearbyEntities(location, radius, vertical, radius)) {
            if (entity instanceof Fish fish && fish.getLocation().getBlock().isLiquid()) {
                result.add(fish);
            }
        }
        return result;
    }

    public boolean supported(Animals animal) {
        return switch (animal.getType()) {
            case COW, SHEEP, GOAT, PIG, CHICKEN, HORSE, RABBIT -> true;
            default -> false;
        };
    }

    public int countAnimals(FarmAreaCache area) {
        return findAnimals(area).size();
    }

    public int countFish(FarmAreaCache area) {
        return findFish(area).size();
    }
}
