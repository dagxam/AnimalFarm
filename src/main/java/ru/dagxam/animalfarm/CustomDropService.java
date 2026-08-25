package ru.dagxam.animalfarm;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Создаёт кастомные предметы дропа с постоянным PDC-идентификатором. */
public final class CustomDropService {
    private final NamespacedKey dropTypeKey;

    public CustomDropService(AnimalFarmPlugin plugin) {
        this.dropTypeKey = new NamespacedKey(plugin, "custom_drop_type");
    }

    public ItemStack create(Material material, String displayName, String dropType) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName));
            meta.getPersistentDataContainer().set(dropTypeKey, PersistentDataType.STRING, dropType);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isCustomDrop(ItemStack item, String dropType) {
        if (item == null || !item.hasItemMeta()) return false;
        String stored = item.getItemMeta().getPersistentDataContainer()
                .get(dropTypeKey, PersistentDataType.STRING);
        return dropType.equals(stored);
    }
}
