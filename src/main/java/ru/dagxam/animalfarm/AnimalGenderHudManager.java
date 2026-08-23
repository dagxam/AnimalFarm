package ru.dagxam.animalfarm;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

/** Показывает в action bar пол и название животного только когда игрок смотрит прямо на него. */
public final class AnimalGenderHudManager {
    private final AnimalFarmPlugin plugin;
    private final AnimalGenderManager genders;
    private BukkitTask task;

    public AnimalGenderHudManager(AnimalFarmPlugin plugin, AnimalGenderManager genders) {
        this.plugin = plugin;
        this.genders = genders;
    }

    public void start() {
        if (task != null) return;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAll, 1L, 4L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void updateAll() {
        if (!plugin.getConfig().getBoolean("animal-genders.interaction.show-info-on-look", true)) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Entity target = findTarget(player);
            if (!(target instanceof Animals animal) || !genders.supported(animal)) continue;
            AnimalGender gender = genders.getOrAssign(animal);
            sendActionBar(player, buildText(animal, gender));
        }
    }

    /** Совместимый с Purpur/Paper/Bukkit поиск сущности по направлению взгляда. */
    private Entity findTarget(Player player) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                8.0,
                entity -> entity instanceof Animals && genders.supported((Animals) entity)
        );
        return result == null ? null : result.getHitEntity();
    }

    private void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
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
            default -> animal.getType().name();
        };
    }
}
