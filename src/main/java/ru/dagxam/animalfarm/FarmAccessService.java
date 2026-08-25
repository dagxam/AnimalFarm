package ru.dagxam.animalfarm;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Единая точка проверки доступа к объектам AnimalFarm. */
public final class FarmAccessService {
    private final AnimalFarmPlugin plugin;
    private final FarmOwnershipManager ownershipManager;

    public FarmAccessService(AnimalFarmPlugin plugin, FarmOwnershipManager ownershipManager) {
        this.plugin = plugin;
        this.ownershipManager = ownershipManager;
    }

    public boolean canUse(Player player, Block block) {
        return ownershipManager.canUse(player, block);
    }

    public boolean canManage(Player player, Block block) {
        return ownershipManager.canManage(player, block);
    }

    /**
     * Сбор из аквариума подчиняется owner-only-harvest.
     * При выключенной настройке используется обычное право использования объекта.
     */
    public boolean canHarvest(Player player, Block block) {
        if (!plugin.settings().aquariumOwnerOnlyHarvest()) {
            return canUse(player, block);
        }
        return canManage(player, block);
    }
}
