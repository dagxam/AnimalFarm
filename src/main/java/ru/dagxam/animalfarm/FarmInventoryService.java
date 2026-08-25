package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Единая точка подсчёта и расходования корма фермы. */
public final class FarmInventoryService {
    private final FarmFoodService foodService;

    public FarmInventoryService(FarmFoodService foodService) {
        this.foodService = foodService;
    }

    public int countAnimalFood(EntityType type, Inventory inventory) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && foodService.isFoodFor(type, item.getType())) total += item.getAmount();
        }
        return total;
    }

    public boolean consumeAnimalFood(EntityType type, Inventory inventory, int amount) {
        if (amount <= 0) return true;
        if (countAnimalFood(type, inventory) < amount) return false;
        return consume(inventory, amount, item -> foodService.isFoodFor(type, item.getType()));
    }

    public int countFishFood(Inventory inventory) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && foodService.isFishFood(item.getType())) total += item.getAmount();
        }
        return total;
    }

    public boolean consumeFishFood(Inventory inventory, int amount) {
        if (amount <= 0) return true;
        if (countFishFood(inventory) < amount) return false;
        return consume(inventory, amount, item -> foodService.isFishFood(item.getType()));
    }

    public boolean removeOne(Inventory inventory, Material material) {
        return consume(inventory, 1, item -> item.getType() == material);
    }

    private boolean consume(Inventory inventory, int amount, ItemFilter filter) {
        int remaining = amount;
        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !filter.matches(item)) continue;
            int consumed = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - consumed);
            remaining -= consumed;
            if (item.getAmount() <= 0) inventory.setItem(slot, null);
        }
        return remaining == 0;
    }

    @FunctionalInterface
    private interface ItemFilter {
        boolean matches(ItemStack item);
    }
}
