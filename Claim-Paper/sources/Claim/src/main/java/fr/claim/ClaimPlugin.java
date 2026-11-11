package fr.claim;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Claim : zones safe délimitées avec un bâton.
 * Dans une zone : pas de spawn de mobs, pas de blocs cassés/posés (sauf modos), pas d'explosions,
 * pas de PvP, pas de perles, et les joueurs en combat (CombatTagPlugin) sont repoussés.
 */
public class ClaimPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    record Point(String world, int x, int y, int z) {}

    record Zone(int id, String world, int minX, int minZ, int maxX, int maxZ) {
        boolean contains(String w, int x, int z) {
            return world.equals(w) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private final List<Zone> zones = new ArrayList<>();
    private final Map<UUID, Point> pos1 = new HashMap<>();
    private final Map<UUID, Point> pos2 = new HashMap<>();
    private int nextId = 1;
    private NamespacedKey stickKey;
    private boolean blockPassive = true;

    // connexion à CombatTagPlugin (réflexion : il n'a pas d'API publique)
    private Plugin combatPlugin;
    private Method combatMethod;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        blockPassive = getConfig().getBoolean("block-passive-mobs", true);
        stickKey = new NamespacedKey(this, "stick");
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("claim") != null) {
            getCommand("claim").setExecutor(this);
            getCommand("claim").setTabCompleter(this);
        }
        load();
        connectCombatTag();
        startPearlTask();
        getLogger().info(zones.size() + " zone(s) chargée(s).");
    }

    private static Component c(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    // ---------------------------------------------------------------- CombatTag

    private void connectCombatTag() {
        Plugin ct = getServer().getPluginManager().getPlugin("CombatTagPlugin");
        if (ct == null) {
            getLogger().warning("CombatTagPlugin introuvable : les joueurs en combat ne seront pas repoussés.");
            return;
        }
        try {
            Method m = ct.getClass().getDeclaredMethod("estEnCombat", Player.class);
            m.setAccessible(true);
            combatPlugin = ct;
            combatMethod = m;
            getLogger().info("Connecté à CombatTagPlugin.");
        } catch (Exception ex) {
            getLogger().warning("Connexion à CombatTagPlugin impossible : " + ex);
        }
    }

    private boolean inCombat(Player p) {
        if (combatMethod == null) return false;
        try {
            return (Boolean) combatMethod.invoke(combatPlugin, p);
        } catch (Exception ex) {
            return false;
        }
    }

    // ---------------------------------------------------------------- Zones

    private boolean inZone(Location l) {
        if (l == null || l.getWorld() == null || zones.isEmpty()) return false;
        String w = l.getWorld().getName();
        int x = l.getBlockX();
        int z = l.getBlockZ();
        for (Zone zn : zones) {
            if (zn.contains(w, x, z)) return true;
        }
        return false;
    }

    private File file() {
        return new File(getDataFolder(), "zones.yml");
    }

    private void load() {
        zones.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file());
        ConfigurationSection root = cfg.getConfigurationSection("zones");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            try {
                int id = Integer.parseInt(key);
                zones.add(new Zone(id, s.getString("world", ""), s.getInt("minX"), s.getInt("minZ"),
                        s.getInt("maxX"), s.getInt("maxZ")));
                nextId = Math.max(nextId, id + 1);
            } catch (NumberFormatException ignored) {
                // entrée invalide ignorée
            }
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Zone zn : zones) {
            String p = "zones." + zn.id() + ".";
            cfg.set(p + "world", zn.world());
            cfg.set(p + "minX", zn.minX());
            cfg.set(p + "minZ", zn.minZ());
            cfg.set(p + "maxX", zn.maxX());
            cfg.set(p + "maxZ", zn.maxZ());
        }
        try {
            getDataFolder().mkdirs();
            cfg.save(file());
        } catch (IOException ex) {
            getLogger().warning("Sauvegarde impossible : " + ex);
        }
    }

    // ---------------------------------------------------------------- Bâton

    private ItemStack createStick() {
        ItemStack item = new ItemStack(Material.STICK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(c("§aClaim"));
        meta.lore(List.of(
                c("§7Clic gauche sur un bloc : position 1"),
                c("§7Clic droit sur un bloc : position 2"),
                c("§7La zone safe est créée au 2ᵉ point.")));
        meta.getPersistentDataContainer().set(stickKey, PersistentDataType.STRING, "stick");
        item.setItemMeta(meta);
        return item;
    }

    private boolean isStick(ItemStack item) {
        if (item == null || item.getType() != Material.STICK || !item.hasItemMeta()) return false;
        return "stick".equals(item.getItemMeta().getPersistentDataContainer().get(stickKey, PersistentDataType.STRING));
    }

    @EventHandler
    public void onStickClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        boolean left = a == Action.LEFT_CLICK_BLOCK;
        boolean right = a == Action.RIGHT_CLICK_BLOCK;
        if (!left && !right) return;
        Player p = e.getPlayer();
        if (!isStick(p.getInventory().getItemInMainHand()) || e.getClickedBlock() == null) return;
        e.setCancelled(true);
        if (!p.hasPermission("claim.mod")) return;

        Point pt = new Point(e.getClickedBlock().getWorld().getName(),
                e.getClickedBlock().getX(), e.getClickedBlock().getY(), e.getClickedBlock().getZ());
        UUID id = p.getUniqueId();
        if (left) {
            pos1.put(id, pt);
            p.sendMessage("§aPosition 1 §7: §f" + pt.x() + ", " + pt.y() + ", " + pt.z());
        } else {
            pos2.put(id, pt);
            p.sendMessage("§aPosition 2 §7: §f" + pt.x() + ", " + pt.y() + ", " + pt.z());
        }

        Point a1 = pos1.get(id);
        Point a2 = pos2.get(id);
        if (a1 == null || a2 == null) return;
        if (!a1.world().equals(a2.world())) {
            p.sendMessage("§cLes deux positions doivent être dans le même monde.");
            return;
        }
        Zone zn = new Zone(nextId++, a1.world(),
                Math.min(a1.x(), a2.x()), Math.min(a1.z(), a2.z()),
                Math.max(a1.x(), a2.x()), Math.max(a1.z(), a2.z()));
        zones.add(zn);
        save();
        pos1.remove(id);
        pos2.remove(id);
        p.sendMessage("§aZone safe #" + zn.id() + " créée §7(" + (zn.maxX() - zn.minX() + 1) + " x "
                + (zn.maxZ() - zn.minZ() + 1) + " blocs, toute la hauteur).");
    }

    // ---------------------------------------------------------------- Blocs (modos exceptés)

    private boolean denied(Player p, Location l) {
        if (!inZone(l) || p.hasPermission("claim.mod")) return false;
        p.sendActionBar(c("§cZone safe : tu ne peux pas construire ici."));
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (isStick(p.getInventory().getItemInMainHand())) {
            e.setCancelled(true);
            return;
        }
        if (denied(p, e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() != null && denied(e.getPlayer(), e.getEntity().getLocation())) e.setCancelled(true);
    }

    /** Tableaux et cadres : cassables seulement par les modos. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreakBy(HangingBreakByEntityEvent e) {
        if (!inZone(e.getEntity().getLocation())) return;
        Entity remover = e.getRemover();
        if (remover instanceof Projectile proj && proj.getShooter() instanceof Player shooter) remover = shooter;
        if (remover instanceof Player p && p.hasPermission("claim.mod")) return;
        e.setCancelled(true);
    }

    // ---------------------------------------------------------------- Mobs

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobSpawn(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Mob)) return; // les supports invisibles d'autres plugins passent
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r == CreatureSpawnEvent.SpawnReason.COMMAND || r == CreatureSpawnEvent.SpawnReason.CUSTOM) return;
        if (!blockPassive && !(e.getEntity() instanceof Enemy)) return;
        if (inZone(e.getLocation())) e.setCancelled(true);
    }

    /** Les mobs ne détruisent rien dans la zone (enderman, wither, zombies qui cassent les portes...). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobChangeBlock(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Mob && inZone(e.getBlock().getLocation())) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- Explosions

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (inZone(e.getLocation())) {
            e.setCancelled(true);
            return;
        }
        e.blockList().removeIf(b -> inZone(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (inZone(e.getBlock().getLocation())) {
            e.setCancelled(true);
            return;
        }
        e.blockList().removeIf(b -> inZone(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent e) {
        EntityDamageEvent.DamageCause cause = e.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;
        if (inZone(e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (e.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION && inZone(e.getEntity().getLocation())) {
            e.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- PvP

    /**
     * Priorité HIGH : on annule avant le MONITOR de CombatTagPlugin, qui ignore les événements
     * annulés -> personne n'est mis "en combat" dans la zone.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player direct) {
            attacker = direct;
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker == null || attacker == victim) return;
        if (inZone(victim.getLocation()) || inZone(attacker.getLocation())) {
            e.setCancelled(true);
            attacker.sendActionBar(c("§cPvP interdit dans une zone safe."));
        }
    }

    // ---------------------------------------------------------------- Joueurs en combat : entrée interdite

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (zones.isEmpty() || !e.hasChangedBlock()) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (!inZone(to) || inZone(from)) return;
        Player p = e.getPlayer();
        if (!inCombat(p)) return;

        e.setTo(from);
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        if (dx * dx + dz * dz < 1.0E-6) {
            Vector look = p.getLocation().getDirection();
            dx = -look.getX();
            dz = -look.getZ();
        }
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 1.0E-6) {
            dx = dx / len * 0.9;
            dz = dz / len * 0.9;
        }
        p.setVelocity(new Vector(dx, 0.35, dz));
        p.sendActionBar(c("§cTu es en combat : tu ne peux pas entrer dans la zone safe !"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location to = e.getTo();
        if (!inZone(to)) return;
        Player p = e.getPlayer();
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            e.setCancelled(true);
            p.sendActionBar(c("§cLes perles sont désactivées dans une zone safe."));
            return;
        }
        if (!inZone(e.getFrom()) && inCombat(p)) {
            e.setCancelled(true);
            p.sendActionBar(c("§cTu es en combat : tu ne peux pas entrer dans la zone safe !"));
        }
    }

    // ---------------------------------------------------------------- Perles : despawn dès qu'elles sont dans la zone

    private void startPearlTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (zones.isEmpty()) return;
                for (World w : Bukkit.getWorlds()) {
                    for (EnderPearl pearl : w.getEntitiesByClass(EnderPearl.class)) {
                        if (inZone(pearl.getLocation())) {
                            if (pearl.getShooter() instanceof Player shooter) {
                                shooter.sendActionBar(c("§cLes perles sont désactivées dans une zone safe."));
                            }
                            pearl.remove();
                        }
                    }
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    // ---------------------------------------------------------------- Commande

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cCommande réservée aux joueurs.");
            return true;
        }
        if (!p.hasPermission("claim.mod")) {
            p.sendMessage("§cCette commande est réservée aux modérateurs.");
            return true;
        }
        if (args.length == 0) {
            p.sendMessage("§c/claim stick §7| §c/claim remove §7| §c/claim list");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "stick" -> {
                Map<Integer, ItemStack> left = p.getInventory().addItem(createStick());
                for (ItemStack rest : left.values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), rest);
                }
                p.sendMessage("§aTu as reçu le bâton §2Claim§a. Clic gauche = position 1, clic droit = position 2.");
            }
            case "remove" -> {
                Location l = p.getLocation();
                String w = l.getWorld().getName();
                int before = zones.size();
                zones.removeIf(zn -> zn.contains(w, l.getBlockX(), l.getBlockZ()));
                int removed = before - zones.size();
                if (removed == 0) {
                    p.sendMessage("§cAucune zone à cet endroit.");
                } else {
                    save();
                    p.sendMessage("§aZone supprimée (" + removed + ").");
                }
            }
            case "list" -> {
                if (zones.isEmpty()) {
                    p.sendMessage("§7Aucune zone.");
                } else {
                    for (Zone zn : zones) {
                        p.sendMessage("§a#" + zn.id() + " §7" + zn.world() + " : §f" + zn.minX() + "," + zn.minZ()
                                + " §7→ §f" + zn.maxX() + "," + zn.maxZ());
                    }
                }
            }
            default -> p.sendMessage("§c/claim stick §7| §c/claim remove §7| §c/claim list");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("claim.mod") || args.length != 1) return out;
        for (String s : List.of("stick", "remove", "list")) {
            if (s.startsWith(args[0].toLowerCase())) out.add(s);
        }
        return out;
    }
}
