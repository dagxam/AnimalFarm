package ru.dagxam.animalfarm;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Единая точка проверки доступа к объектам AnimalFarm. */
public final class FarmAccessService {
    private final FarmOwnershipManager ownershipManager;

    public FarmAccessService(FarmOwnershipManager ownershipManager) {
        this.ownershipManager = ownershipManager;
    }

    public boolean canUse(Player player, Block block) {
        return ownershipManager.canUse(player, block);
    }

    public boolean canManage(Player player, Block block) {
        return ownershipManager.canManage(player, block);
    }

    /** Действия, разрешённые только владельцу или администратору. */
    public boolean canHarvest(Player player, Block block) {
        return ownershipManager.canManage(player, block);
    }
}
