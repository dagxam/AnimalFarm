package ru.dagxam.animalfarm;

import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Хранит владельца и доверенных игроков непосредственно в PDC каждой кормушки/аквариума. */
public final class FarmOwnershipManager {
    private final AnimalFarmPlugin plugin;

    public FarmOwnershipManager(AnimalFarmPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isOwner(Player player, Block block) {
        UUID owner = plugin.farmObjectManager().ownerOf(block);
        return owner != null && owner.equals(player.getUniqueId());
    }

    public boolean isTrusted(Player player, Block block) {
        return trusted(block).contains(player.getUniqueId());
    }

    public boolean canManage(Player player, Block block) {
        if (!plugin.settings().ownershipEnabled()) return true;
        return player.hasPermission("animalfarm.admin") || isOwner(player, block);
    }

    public boolean canUse(Player player, Block block) {
        if (!plugin.settings().ownershipEnabled()) return true;
        return player.hasPermission("animalfarm.admin") || isOwner(player, block) || isTrusted(player, block);
    }

    public boolean trust(Player owner, Block block, OfflinePlayer target) {
        if (target == null || target.getUniqueId().equals(owner.getUniqueId())) return false;
        if (!canManage(owner, block)) return false;

        Set<UUID> trusted = trusted(block);
        boolean changed = trusted.add(target.getUniqueId());
        if (changed) save(block, trusted);
        return changed;
    }

    public boolean untrust(Player owner, Block block, OfflinePlayer target) {
        if (target == null || !canManage(owner, block)) return false;

        Set<UUID> trusted = trusted(block);
        boolean changed = trusted.remove(target.getUniqueId());
        if (changed) save(block, trusted);
        return changed;
    }

    public Set<UUID> trusted(Block block) {
        if (!(block.getState() instanceof Barrel barrel)) return Set.of();
        String raw = barrel.getPersistentDataContainer().get(trustedKey(), PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return new LinkedHashSet<>();

        Set<UUID> result = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            try {
                result.add(UUID.fromString(part));
            } catch (IllegalArgumentException ignored) {
                // Повреждённая запись не должна ломать весь список доверенных.
            }
        }
        return result;
    }

    /**
     * Передаёт объект новому владельцу и полностью очищает старый trusted-список.
     */
    public boolean transfer(Player owner, Block block, OfflinePlayer target) {
        if (owner == null || target == null) return false;
        if (!canManage(owner, block)) return false;
        if (target.getUniqueId().equals(owner.getUniqueId())) return false;
        if (!(block.getState() instanceof Barrel barrel)) return false;

        barrel.getPersistentDataContainer().set(ownerUuidKey(), PersistentDataType.STRING, target.getUniqueId().toString());
        barrel.getPersistentDataContainer().set(
                ownerNameKey(),
                PersistentDataType.STRING,
                target.getName() == null ? target.getUniqueId().toString() : target.getName()
        );
        // После передачи старые доверенные не должны сохранять доступ.
        barrel.getPersistentDataContainer().remove(trustedKey());
        barrel.update(true, false);
        return true;
    }

    private void save(Block block, Set<UUID> trusted) {
        if (!(block.getState() instanceof Barrel barrel)) return;

        String value = trusted.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("");
        if (value.isBlank()) {
            barrel.getPersistentDataContainer().remove(trustedKey());
        } else {
            barrel.getPersistentDataContainer().set(trustedKey(), PersistentDataType.STRING, value);
        }
        barrel.update(true, false);
    }

    private NamespacedKey ownerUuidKey() {
        return new NamespacedKey(plugin, "owner_uuid");
    }

    private NamespacedKey ownerNameKey() {
        return new NamespacedKey(plugin, "owner_name");
    }

    private NamespacedKey trustedKey() {
        return new NamespacedKey(plugin, "trusted_players");
    }
}
