package ru.dagxam.animalfarm;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Отправляет игрокам ресурс-пак AnimalFarm.
 *
 * Сам менеджер отвечает только за загрузку пакета на клиент. Выбор
 * визуального варианта конкретного животного будет подключаться отдельно,
 * потому что ванильный ресурс-пак сам по себе не может выбрать текстуру
 * отдельно для одной конкретной коровы или свиньи.
 */
public final class ResourcePackManager implements Listener {
    private final AnimalFarmPlugin plugin;

    public ResourcePackManager(AnimalFarmPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> sendIfEnabled(player), 20L);
    }

    public void sendIfEnabled(Player player) {
        if (!plugin.getConfig().getBoolean("resource-pack.enabled", false)) return;
        String url = plugin.getConfig().getString("resource-pack.url", "").trim();
        if (url.isEmpty()) {
            plugin.getLogger().warning("Ресурс-пак AnimalFarm включён, но resource-pack.url не задан.");
            return;
        }
        try {
            player.setResourcePack(url);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Не удалось отправить ресурс-пак игроку " + player.getName() + ": " + exception.getMessage());
        }
    }
}
