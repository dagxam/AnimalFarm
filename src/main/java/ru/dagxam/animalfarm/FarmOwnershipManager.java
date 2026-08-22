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

/** Хранит доверенных игроков непосредственно в PDC каждой кормушки/аквариума. */
public final class FarmOwnershipManager {
    private final AnimalFarmPlugin plugin;

    public FarmOwnershipManager(AnimalFarmPlugin plugin) { this.plugin = plugin; }

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
        if (!canManage(owner, block) || target.getUniqueId().equals(owner.getUniqueId())) return false;
        Set<UUID> trusted = trusted(block);
        boolean changed = trusted.add(target.getUniqueId());
        save(block, trusted);
        return changed;
    }

    public boolean untrust(Player owner, Block block, OfflinePlayer target) {
        if (!canManage(owner, block)) return false;
        Set<UUID> trusted = trusted(block);
        boolean changed = trusted.remove(target.getUniqueId());
        save(block, trusted);
        return changed;
    }

    public Set<UUID> trusted(Block block) {
        if (!(block.getState() instanceof Barrel barrel)) return Set.of();
        String raw = barrel.getPersistentDataContainer().get(key(), PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return new LinkedHashSet<>();
        Set<UUID> result = new LinkedHashSet<>();
        for (String part : raw.split(",")) try { result.add(UUID.fromString(part)); } catch (IllegalArgumentException ignored) { }
        return result;
    }

    public void transfer(Player owner, Block block, OfflinePlayer target) {
        if (!(block.getState() instanceof Barrel barrel)) return;
        barrel.getPersistentDataContainer().set(new NamespacedKey(plugin, "owner_uuid"), PersistentDataType.STRING, target.getUniqueId().toString());
        barrel.getPersistentDataContainer().set(new NamespacedKey(plugin, "owner_name"), PersistentDataType.STRING, target.getName() == null ? target.getUniqueId().toString() : target.getName());
        barrel.update(true, false);
    }

    private void save(Block block, Set<UUID> trusted) {
        if (!(block.getState() instanceof Barrel barrel)) return;
        String value = trusted.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("");
        barrel.getPersistentDataContainer().set(key(), PersistentDataType.STRING, value);
        barrel.update(true, false);
    }

    private NamespacedKey key() { return new NamespacedKey(plugin, "trusted_players"); }
}
