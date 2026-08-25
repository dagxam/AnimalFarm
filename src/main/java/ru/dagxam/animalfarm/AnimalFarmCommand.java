package ru.dagxam.animalfarm;

import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public final class AnimalFarmCommand implements CommandExecutor, TabCompleter {
    private final AnimalFarmPlugin plugin;

    public AnimalFarmCommand(AnimalFarmPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(color("&8[&6AnimalFarm&8] &fКоманды: &e/animalfarm trust <игрок>, untrust <игрок>, members, owner, transfer <игрок>, info, reload"));
            return true;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("reload")) {
            if (!sender.hasPermission("animalfarm.admin")) {
                sender.sendMessage(plugin.message("no-permission"));
                return true;
            }
            plugin.reloadPluginConfig();
            sender.sendMessage(plugin.message("reloaded"));
            return true;
        }

        if (sub.equals("info")) {
            sender.sendMessage(plugin.message("info"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Только игрок может управлять владельцами ферм.");
            return true;
        }

        Block block = player.getTargetBlockExact(6);
        if (block == null || !plugin.farmObjectManager().isFarmObject(block)) {
            player.sendMessage(color("&cПосмотрите на кормушку или аквариум в пределах 6 блоков."));
            return true;
        }

        FarmOwnershipManager ownership = plugin.ownershipManager();
        switch (sub) {
            case "owner" -> {
                String name = plugin.farmObjectManager().ownerNameOf(block);
                player.sendMessage(color("&6Владелец: &f" + (name == null ? "не назначен" : name)));
            }
            case "members" -> {
                var members = ownership.trusted(block);
                if (members.isEmpty()) {
                    player.sendMessage(color("&eДоверенных игроков нет."));
                } else {
                    StringBuilder out = new StringBuilder("&6Доверенные: &f");
                    boolean first = true;
                    for (UUID id : members) {
                        OfflinePlayer member = plugin.getServer().getOfflinePlayer(id);
                        if (!first) out.append(", ");
                        out.append(member.getName() == null ? id : member.getName());
                        first = false;
                    }
                    player.sendMessage(color(out.toString()));
                }
            }
            case "trust", "untrust", "transfer" -> {
                if (!ownership.canManage(player, block)) {
                    player.sendMessage(plugin.message("not-owner"));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(color("&cУкажите игрока."));
                    return true;
                }

                OfflinePlayer target = plugin.getServer().getOfflinePlayer(args[1]);
                if (sub.equals("trust")) {
                    player.sendMessage(color(ownership.trust(player, block, target)
                            ? "&aИгрок добавлен в доверенные."
                            : "&eИгрок уже доверенный или указан владелец."));
                } else if (sub.equals("untrust")) {
                    player.sendMessage(color(ownership.untrust(player, block, target)
                            ? "&aИгрок удалён из доверенных."
                            : "&eИгрок не был в списке."));
                } else {
                    if (target.getUniqueId().equals(player.getUniqueId())) {
                        player.sendMessage(color("&eНельзя передать ферму самому себе."));
                        return true;
                    }
                    if (ownership.transfer(player, block, target)) {
                        player.sendMessage(color("&aВладение передано игроку &f"
                                + (target.getName() == null ? args[1] : target.getName())));
                    } else {
                        player.sendMessage(color("&cНе удалось передать владение этой фермой."));
                    }
                }
            }
            default -> player.sendMessage(color("&cНеизвестная команда."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("trust", "untrust", "members", "owner", "transfer", "info", "reload")
                    .stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && List.of("trust", "untrust", "transfer").contains(args[0].toLowerCase())) {
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
