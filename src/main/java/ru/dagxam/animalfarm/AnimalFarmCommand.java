package ru.dagxam.animalfarm;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

public final class AnimalFarmCommand implements CommandExecutor, TabCompleter {

    private final AnimalFarmPlugin plugin;

    public AnimalFarmCommand(AnimalFarmPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("animalfarm.admin")) {
            sender.sendMessage(color(plugin.getConfig().getString("messages.prefix", "") + plugin.getConfig().getString("messages.no-permission", "&cНет прав.")));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(color("&8[&6AnimalFarm&8] &fИспользование: &e/animalfarm <reload|info>"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadPluginConfig();
                sender.sendMessage(color(plugin.getConfig().getString("messages.prefix", "") + plugin.getConfig().getString("messages.reloaded", "&aКонфигурация перезагружена.")));
            }
            case "info" -> sender.sendMessage(color(plugin.getConfig().getString("messages.prefix", "") + plugin.getConfig().getString("messages.info", "&fAnimalFarm")));
            default -> sender.sendMessage(color("&8[&6AnimalFarm&8] &cНеизвестная команда. &fИспользование: &e/animalfarm <reload|info>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "info").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
