package ru.dagxam.animalfarm;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/** Переносит живых мобов в специальном ведре. Игроки и ArmorStand исключены. */
public final class MobBucketManager implements Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey bucketKey;
    private final NamespacedKey mobDataKey;

    public MobBucketManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.bucketKey = new NamespacedKey(plugin, "mob_bucket");
        this.mobDataKey = new NamespacedKey(plugin, "mob_data");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registerRecipe();
        registerAnimalFarmCrafts();
    }

    private void registerRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "mob_bucket");
        plugin.getServer().removeRecipe(key);
        ShapedRecipe recipe = new ShapedRecipe(key, createEmptyBucket());
        recipe.shape("BBB");
        recipe.setIngredient('B', Material.BUCKET);
        plugin.getServer().addRecipe(recipe);
    }

    private void registerAnimalFarmCrafts() {
        NamespacedKey nameTagKey = new NamespacedKey(plugin, "craft_name_tag");
        plugin.getServer().removeRecipe(nameTagKey);
        ShapedRecipe nameTag = new ShapedRecipe(nameTagKey, new ItemStack(Material.NAME_TAG));
        nameTag.shape("LS", "  ");
        nameTag.setIngredient('L', Material.LEATHER);
        nameTag.setIngredient('S', Material.STRING);
        plugin.getServer().addRecipe(nameTag);

        NamespacedKey leadKey = new NamespacedKey(plugin, "craft_lead");
        plugin.getServer().removeRecipe(leadKey);
        ShapedRecipe lead = new ShapedRecipe(leadKey, new ItemStack(Material.LEAD));
        lead.shape("LL", "S ");
        lead.setIngredient('L', Material.LEATHER);
        lead.setIngredient('S', Material.STRING);
        plugin.getServer().addRecipe(lead);

        NamespacedKey stringKey = new NamespacedKey(plugin, "craft_wool_to_string");
        plugin.getServer().removeRecipe(stringKey);
        ShapedRecipe string = new ShapedRecipe(stringKey, new ItemStack(Material.STRING, 4));
        string.shape("W");
        string.setIngredient('W', new RecipeChoice.MaterialChoice(Arrays.stream(Material.values())
                .filter(material -> material.name().endsWith("_WOOL"))
                .toList()));
        plugin.getServer().addRecipe(string);

        NamespacedKey bundleKey = new NamespacedKey(plugin, "craft_bundle");
        plugin.getServer().removeRecipe(bundleKey);
        ShapedRecipe bundle = new ShapedRecipe(bundleKey, new ItemStack(Material.BUNDLE));
        bundle.shape("L L", " S ", " L ");
        bundle.setIngredient('L', Material.LEATHER);
        bundle.setIngredient('S', Material.STRING);
        plugin.getServer().addRecipe(bundle);
    }

    private ItemStack createEmptyBucket() {
        ItemStack item = new ItemStack(Material.BUCKET);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(color("&6Ведро для мобов"));
        meta.setLore(java.util.List.of(color("&7ПКМ по мобу — поместить его в ведро."), color("&7ПКМ по свободному месту — выпустить моба.")));
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(bucketKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isBucket(ItemStack item) {
        if (item == null || item.getType() != Material.BUCKET || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(bucketKey, PersistentDataType.BYTE);
    }

    private boolean hasMob(ItemStack item) {
        if (!isBucket(item)) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(mobDataKey, PersistentDataType.STRING);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobPickup(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack bucket = player.getInventory().getItemInMainHand();
        if (!isBucket(bucket) || hasMob(bucket)) return;
        Entity target = event.getRightClicked();
        if (!(target instanceof LivingEntity living) || target instanceof Player || target instanceof org.bukkit.entity.ArmorStand || target.isDead()) return;
        event.setCancelled(true);
        ItemStack filled = createFilledBucket(living);
        target.remove();
        player.getInventory().setItemInMainHand(filled);
        player.sendMessage(color("&8[&6AnimalFarm&8] &aМоб помещён в ведро: &f" + displayType(living)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onRelease(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack bucket = player.getInventory().getItemInMainHand();
        if (!hasMob(bucket)) return;
        event.setCancelled(true);
        Block clicked = event.getClickedBlock();
        if (clicked == null || event.getBlockFace() == null) return;
        Location spawn = clicked.getRelative(event.getBlockFace()).getLocation().add(0.5, 0.0, 0.5);
        if (!isFree(spawn)) {
            player.sendMessage(color("&8[&6AnimalFarm&8] &cНедостаточно места, чтобы выпустить моба."));
            return;
        }
        String data = getMobData(bucket);
        String[] parts = data == null ? null : decode(data);
        if (parts == null || parts.length < 4) return;
        try {
            org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf(parts[0]);
            if (!type.isAlive() || type == org.bukkit.entity.EntityType.PLAYER) return;
            Entity entity = spawn.getWorld().spawnEntity(spawn, type);
            if (entity instanceof LivingEntity living) {
                if (!parts[1].isEmpty()) living.setCustomName(parts[1]);
                if (living instanceof Ageable ageable) ageable.setAge(Integer.parseInt(parts[2]));
                if (living instanceof Tameable tameable && !parts[3].isEmpty()) {
                    try {
                        UUID owner = UUID.fromString(parts[3]);
                        tameable.setOwner(plugin.getServer().getOfflinePlayer(owner));
                        tameable.setTamed(true);
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            player.getInventory().setItemInMainHand(createEmptyBucket());
            player.sendMessage(color("&8[&6AnimalFarm&8] &aМоб выпущен: &f" + displayType(entity)));
        } catch (IllegalArgumentException ex) {
            player.sendMessage(color("&8[&6AnimalFarm&8] &cНе удалось восстановить моба из ведра."));
        }
    }

    private ItemStack createFilledBucket(LivingEntity entity) {
        ItemStack item = createEmptyBucket();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        String customName = entity.getCustomName() == null ? "" : entity.getCustomName().replace("|", "");
        String age = entity instanceof Ageable ageable ? Integer.toString(ageable.getAge()) : "0";
        String owner = entity instanceof Tameable tameable && tameable.isTamed() && tameable.getOwner() != null ? tameable.getOwner().getUniqueId().toString() : "";
        String raw = entity.getType().name() + "|" + customName + "|" + age + "|" + owner;
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        meta.getPersistentDataContainer().set(mobDataKey, PersistentDataType.STRING, encoded);
        meta.setDisplayName(color("&6Ведро с мобом: &f" + displayType(entity)));
        meta.setLore(java.util.List.of(color("&7ПКМ по свободному месту — выпустить моба.")));
        item.setItemMeta(meta);
        return item;
    }

    private String getMobData(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(mobDataKey, PersistentDataType.STRING);
    }

    private String[] decode(String data) {
        try { return new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8).split("\\|", -1); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private boolean isFree(Location location) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        return feet.isPassable() && head.isPassable();
    }

    private String displayType(Entity entity) {
        String name = entity.getType().name().toLowerCase().replace('_', ' ');
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }
}
