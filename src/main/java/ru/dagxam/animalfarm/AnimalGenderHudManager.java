package ru.dagxam.animalfarm;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Показывает пол животного пакетно, чтобы не выполнять ray trace для всех игроков одновременно. */
public final class AnimalGenderHudManager {
    private final AnimalFarmPlugin plugin;
    private final AnimalGenderManager genders;
    private final Map<UUID, UUID> lastTarget = new HashMap<>();
    private final Map<UUID, String> lastMessage = new HashMap<>();
    private BukkitTask task;
    private int cursor;

    public AnimalGenderHudManager(AnimalFarmPlugin plugin, AnimalGenderManager genders) {
        this.plugin = plugin;
        this.genders = genders;
    }

    public void start() {
        if (task != null) return;
        long interval = Math.max(5L, plugin.getConfig().getLong("animal-genders.hud-update-ticks", 10L));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateBatch, interval, interval);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        cursor = 0;
        lastTarget.clear();
        lastMessage.clear();
    }

    private void updateBatch() {
        if (!plugin.getConfig().getBoolean("animal-genders.interaction.show-info-on-look", true)) return;
        List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        if (players.isEmpty()) { cursor = 0; lastTarget.clear(); lastMessage.clear(); return; }

        int batchSize = Math.max(1, plugin.getConfig().getInt("animal-genders.hud-max-players-per-update", 8));
        int amount = Math.min(batchSize, players.size());
        if (cursor >= players.size()) cursor = 0;

        for (int i = 0; i < amount; i++) {
            if (cursor >= players.size()) cursor = 0;
            updatePlayer(players.get(cursor++));
        }

        lastTarget.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
        lastMessage.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
    }

    private void updatePlayer(Player player) {
        if (!player.isOnline() || player.isDead()) return;
        UUID playerId = player.getUniqueId();
        Entity target = findTarget(player);
        if (!(target instanceof Animals animal) || !genders.supported(animal)) {
            lastTarget.remove(playerId);
            lastMessage.remove(playerId);
            return;
        }

        AnimalGender gender = genders.getOrAssign(animal);
        String message = buildText(animal, gender);
        UUID targetId = animal.getUniqueId();
        if (targetId.equals(lastTarget.get(playerId)) && message.equals(lastMessage.get(playerId))) return;

        lastTarget.put(playerId, targetId);
        lastMessage.put(playerId, message);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
    }

    private Entity findTarget(Player player) {
        double range = Math.max(1.0, plugin.getConfig().getDouble("animal-genders.hud-range", 8.0));
        RayTraceResult result = player.getWorld().rayTraceEntities(player.getEyeLocation(), player.getEyeLocation().getDirection(), range,
                entity -> entity instanceof Animals animal && genders.supported(animal));
        return result == null ? null : result.getHitEntity();
    }

    private String buildText(Animals animal, AnimalGender gender) {
        String symbol = gender == AnimalGender.MALE ? "♂" : "♀";
        String color = gender == AnimalGender.MALE ? "§c" : "§d";
        String age = animal instanceof Ageable ageable && !ageable.isAdult() ? "§7Детёныш" : "§fВзрослый";
        return color + symbol + " §f" + displayName(animal, gender) + " §8| " + age;
    }

    private String displayName(Animals animal, AnimalGender gender) {
        return switch (animal.getType()) {
            case COW -> gender == AnimalGender.MALE ? "Бык" : "Корова";
            case SHEEP -> gender == AnimalGender.MALE ? "Баран" : "Овца";
            case GOAT -> gender == AnimalGender.MALE ? "Козёл" : "Коза";
            case PIG -> gender == AnimalGender.MALE ? "Хряк" : "Свинья";
            case CHICKEN -> gender == AnimalGender.MALE ? "Петух" : "Курица";
            case HORSE -> gender == AnimalGender.MALE ? "Жеребец" : "Лошадь";
            case RABBIT -> gender == AnimalGender.MALE ? "Кролик" : "Крольчиха";
            default -> animal.getType().name();
        };
    }
}
