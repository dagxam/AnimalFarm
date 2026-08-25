package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;

/**
 * Последняя линия защиты вместимости: детёныш не может появиться внутри фермы,
 * если лимит уже достигнут. Это покрывает и размножение игроками, и любые
 * другие источники EntityBreedEvent, помимо FarmProcessor.
 */
public final class FarmBreedingProtectionListener implements Listener {
    private final AnimalFarmPlugin plugin;
    private final FarmCapacityService capacityService;

    public FarmBreedingProtectionListener(AnimalFarmPlugin plugin, FarmSettings settings) {
        this.plugin = plugin;
        this.capacityService = new FarmCapacityService(settings);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!(event.getMother() instanceof Animals childMother)) return;

        Location location = event.getMother().getLocation();
        FarmAreaCache area = findContainingFarm(location);
        if (area == null || !area.valid() || area.type() != FarmObjectType.LAND_FEEDER) return;

        int current = plugin.farmEntityService().findAnimals(area).size();
        if (capacityService.canAddAnimal(current, 1)) return;

        event.setCancelled(true);
    }

    private FarmAreaCache findContainingFarm(Location location) {
        for (FarmObjectKey key : plugin.farmObjectManager().objects()) {
            Location farmLocation = key.location(plugin.getServer());
            if (farmLocation.getWorld() == null || location.getWorld() == null
                    || !farmLocation.getWorld().getUID().equals(location.getWorld().getUID())) continue;

            Block block = farmLocation.getBlock();
            FarmObjectType type = plugin.farmObjectManager().typeOf(block);
            if (type != FarmObjectType.LAND_FEEDER) continue;

            FarmAreaCache area = plugin.farmObjectManager().areaAnalyzer().analyze(
                    key, type, plugin.getServer()
            );
            if (area.valid() && area.contains(location)) return area;
        }
        return null;
    }
}
