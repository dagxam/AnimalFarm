package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Управляет запасом воды фермы. После расхода воды пустое ведро возвращается в кормушку. */
public final class FarmWaterService {
    public boolean hasWater(Inventory inventory, int amount) {
        return count(inventory, Material.WATER_BUCKET) >= amount;
    }

    public boolean consumeWater(Inventory inventory, int amount) {
        if (amount <= 0) return true;
        if (!hasWater(inventory, amount)) return false;

        remove(inventory, Material.WATER_BUCKET, amount);
        for (int i = 0; i < amount; i++) {
            inventory.addItem(new ItemStack(Material.BUCKET));
        }
        return true;
    }

    public int countWater(Inventory inventory) {
        return count(inventory, Material.WATER_BUCKET);
    }

    private int count(Inventory inventory, Material material) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material) total += item.getAmount();
        }
        return total;
    }

    private void remove(Inventory inventory, Material material, int amount) {
        for (int slot = 0; slot < inventory.getSize() && amount > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != material) continue;

            int removed = Math.min(amount, item.getAmount());
            item.setAmount(item.getAmount() - removed);
            amount -= removed;

            if (item.getAmount() <= 0) inventory.setItem(slot, null);
        }
    }
}
