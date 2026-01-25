package me.priyam.deathswap;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.util.*;

public class DeathSwapPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private boolean running = false;

    private int swapIntervalSeconds = 300;
    private int timeLeft = 0;

    private BukkitTask timerTask = null;
    private BukkitTask fireworkTask = null;

    private final Set<UUID> ghostPlayers = new HashSet<>();
    private final Map<UUID, Integer> ghostSpectateIndex = new HashMap<>();

    private Scoreboard scoreboard;
    private Objective objective;

    private static final String TITLE = ChatColor.RED + "Death Swap";
    private static final String COMPASS_NAME = ChatColor.AQUA + "Ghost Compass";
    private static final String COMPASS_LORE = ChatColor.GRAY + "Right click to teleport to next player";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("deathswap")).setExecutor(this);
        Objects.requireNonNull(getCommand("deathswap")).setTabCompleter(this);

        Objects.requireNonNull(getCommand("ds")).setExecutor(this);
        Objects.requireNonNull(getCommand("ds")).setTabCompleter(this);

        setupScoreboard();
    }

    @Override
    public void onDisable() {
        stopGame(false);
    }

    private void loadSettings() {
        swapIntervalSeconds = getConfig().getInt("swap-interval-seconds", 300);
        if (swapIntervalSeconds < 10) swapIntervalSeconds = 10;
    }

    private void setupScoreboard() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;

        scoreboard = manager.getNewScoreboard();
        objective = scoreboard.registerNewObjective("deathswap", Criteria.DUMMY, TITLE);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    private void updateScoreboardAll() {
        if (!running || scoreboard == null || objective == null) return;

        for (String entry : scoreboard.getEntries()) {
            scoreboard.resetScores(entry);
        }

        String timeLine = ChatColor.YELLOW + "Time Remaining: " + ChatColor.GREEN + formatTime(timeLeft);

        objective.getScore(timeLine).setScore(1);

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(scoreboard);
        }
    }

    private void clearScoreboardAll() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;

        Scoreboard empty = manager.getNewScoreboard();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(empty);
        }
    }

    private String formatTime(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return m + "m " + s + "s";
    }

    private void startGame(CommandSender sender) {
        if (running) {
            sender.sendMessage(ChatColor.RED + "DeathSwap is already running.");
            return;
        }

        List<Player> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isDead()) players.add(p);
        }

        if (players.size() < 2) {
            sender.sendMessage(ChatColor.RED + "Need at least 2 players online to start.");
            return;
        }

        running = true;
        ghostPlayers.clear();
        ghostSpectateIndex.clear();

        timeLeft = swapIntervalSeconds;
        updateScoreboardAll();

        giveStartItems(players);

        Bukkit.broadcastMessage(ChatColor.GREEN + "DeathSwap has started!");

        timerTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!running) return;

            timeLeft--;

            if (timeLeft <= 10 && timeLeft >= 1) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!ghostPlayers.contains(p.getUniqueId())) {
                        p.sendTitle(ChatColor.RED.toString() + timeLeft, "", 0, 20, 0);
                    } else {
                        p.sendTitle(ChatColor.GRAY.toString() + timeLeft, "", 0, 20, 0);
                    }
                }
            }

            if (timeLeft <= 0) {
                doSwap();
                Bukkit.broadcastMessage(ChatColor.GREEN + "All the players have been swapped");
                timeLeft = swapIntervalSeconds;
            }

            updateScoreboardAll();
            checkWinCondition();

        }, 20L, 20L);
    }

    private void giveStartItems(List<Player> players) {
        boolean giveWater = getConfig().getBoolean("give-water-bucket-on-start", true);
        boolean giveFood = getConfig().getBoolean("give-food-on-start", true);

        Material foodMat = Material.matchMaterial(getConfig().getString("food-item", "COOKED_BEEF"));
        if (foodMat == null) foodMat = Material.COOKED_BEEF;

        int foodAmount = Math.max(1, getConfig().getInt("food-amount", 16));

        for (Player p : players) {
            if (giveWater) {
                p.getInventory().addItem(new ItemStack(Material.WATER_BUCKET, 1));
            }
            if (giveFood) {
                p.getInventory().addItem(new ItemStack(foodMat, foodAmount));
            }
        }
    }

    private void doSwap() {
        List<Player> alive = getAlivePlayers();
        if (alive.size() < 2) return;

        List<Location> locations = new ArrayList<>();
        for (Player p : alive) {
            locations.add(p.getLocation().clone());
        }

        Collections.shuffle(locations);

        // Ensure no one gets their own position (try a few times)
        for (int tries = 0; tries < 10; tries++) {
            boolean bad = false;
            for (int i = 0; i < alive.size(); i++) {
                if (sameBlock(alive.get(i).getLocation(), locations.get(i))) {
                    bad = true;
                    break;
                }
            }
            if (!bad) break;
            Collections.shuffle(locations);
        }

        for (int i = 0; i < alive.size(); i++) {
            Player p = alive.get(i);
            Location target = locations.get(i);

            // Keep yaw/pitch from target location itself (which already has yaw/pitch)
            p.teleport(target);
        }
    }

    private boolean sameBlock(Location a, Location b) {
        if (a == null || b == null) return false;
        if (a.getWorld() == null || b.getWorld() == null) return false;
        return a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private List<Player> getAlivePlayers() {
        List<Player> alive = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!ghostPlayers.contains(p.getUniqueId()) && !p.isDead()) {
                alive.add(p);
            }
        }
        return alive;
    }

    private void checkWinCondition() {
        if (!running) return;

        List<Player> alive = getAlivePlayers();
        if (alive.size() == 1) {
            Player winner = alive.get(0);
            announceWinner(winner);
        } else if (alive.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.RED + "No one survived. Game ended.");
            stopGame(true);
        }
    }

    private void announceWinner(Player winner) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(ChatColor.GOLD + winner.getName() + " has won!!", "", 10, 60, 10);
        }

        startFireworks(winner);

        Bukkit.getScheduler().runTaskLater(this, () -> stopGame(true), 100L); // 5 sec
    }

    private void startFireworks(Player winner) {
        if (fireworkTask != null) fireworkTask.cancel();

        fireworkTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!winner.isOnline()) return;

            Location loc = winner.getLocation().clone();
            Firework fw = (Firework) winner.getWorld().spawnEntity(loc, EntityType.FIREWORK_ROCKET);

            FireworkMeta meta = fw.getFireworkMeta();
            meta.setPower(1);
            fw.setFireworkMeta(meta);

        }, 0L, 5L);
    }

    private void stopGame(boolean announce) {
        if (!running) return;

        running = false;

        if (timerTask != null) timerTask.cancel();
        timerTask = null;

        if (fireworkTask != null) fireworkTask.cancel();
        fireworkTask = null;

        for (Player p : Bukkit.getOnlinePlayers()) {
            disableGhost(p);
        }

        ghostPlayers.clear();
        ghostSpectateIndex.clear();

        clearScoreboardAll();

        if (announce) Bukkit.broadcastMessage(ChatColor.RED + "DeathSwap has ended.");
    }

    private void enableGhost(Player p) {
        ghostPlayers.add(p.getUniqueId());

        p.setGameMode(GameMode.ADVENTURE);
        p.setAllowFlight(true);
        p.setFlying(true);

        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, Integer.MAX_VALUE, 255, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 255, false, false));

        p.getInventory().addItem(makeGhostCompass());
        p.sendMessage(ChatColor.GRAY + "You are now a ghost.");
    }

    private void disableGhost(Player p) {
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        p.removePotionEffect(PotionEffectType.WEAKNESS);
        p.removePotionEffect(PotionEffectType.SLOWNESS);

        p.setAllowFlight(false);
        p.setFlying(false);

        // Remove compass
        for (ItemStack item : p.getInventory().getContents()) {
            if (item == null) continue;
            if (item.getType() == Material.COMPASS && item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null && COMPASS_NAME.equals(meta.getDisplayName())) {
                    p.getInventory().remove(item);
                }
            }
        }
    }

    private ItemStack makeGhostCompass() {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(COMPASS_NAME);
            meta.setLore(Collections.singletonList(COMPASS_LORE));
            compass.setItemMeta(meta);
        }
        return compass;
    }

    private void teleportGhostToNext(Player ghost) {
        List<Player> alive = getAlivePlayers();
        if (alive.isEmpty()) {
            ghost.sendMessage(ChatColor.RED + "No alive players to spectate.");
            return;
        }

        int idx = ghostSpectateIndex.getOrDefault(ghost.getUniqueId(), 0);
        idx = idx % alive.size();

        Player target = alive.get(idx);
        ghost.teleport(target.getLocation());

        ghostSpectateIndex.put(ghost.getUniqueId(), idx + 1);
        ghost.sendMessage(ChatColor.GRAY + "Teleported to: " + ChatColor.YELLOW + target.getName());
    }

    // ===================== EVENTS =====================

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!running) return;

        Player p = e.getEntity();

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            p.spigot().respawn();
            enableGhost(p);
            checkWinCondition();
        }, 1L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!running) return;
        // keep normal respawn; ghost will be handled right after death
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (ghostPlayers.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (ghostPlayers.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!running) return;

        if (ghostPlayers.contains(p.getUniqueId())) {
            e.setCancelled(true);

            if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                ItemStack item = e.getItem();
                if (item != null && item.getType() == Material.COMPASS && item.hasItemMeta()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null && COMPASS_NAME.equals(meta.getDisplayName())) {
                        teleportGhostToNext(p);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (ghostPlayers.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (ghostPlayers.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (ghostPlayers.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!running) return;

        UUID id = e.getPlayer().getUniqueId();
        ghostPlayers.remove(id);
        ghostSpectateIndex.remove(id);

        Bukkit.getScheduler().runTaskLater(this, this::checkWinCondition, 5L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!running) return;
        updateScoreboardAll();
    }

    // ===================== COMMANDS =====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("deathswap.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <start|stop|reload|toggle>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> startGame(sender);
            case "stop" -> stopGame(true);
            case "reload" -> {
                reloadConfig();
                loadSettings();
                sender.sendMessage(ChatColor.GREEN + "DeathSwap config reloaded.");
            }
            case "toggle" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " toggle <water|food>");
                    return true;
                }
                toggleOption(sender, args[1].toLowerCase());
            }
            default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
        }

        return true;
    }

    private void toggleOption(CommandSender sender, String opt) {
        switch (opt) {
            case "water" -> {
                boolean current = getConfig().getBoolean("give-water-bucket-on-start", true);
                getConfig().set("give-water-bucket-on-start", !current);
                saveConfig();
                sender.sendMessage(ChatColor.GREEN + "give-water-bucket-on-start = " + (!current));
            }
            case "food" -> {
                boolean current = getConfig().getBoolean("give-food-on-start", true);
                getConfig().set("give-food-on-start", !current);
                saveConfig();
                sender.sendMessage(ChatColor.GREEN + "give-food-on-start = " + (!current));
            }
            default -> sender.sendMessage(ChatColor.RED + "Unknown toggle option. Use water/food.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("deathswap.admin")) return Collections.emptyList();

        if (args.length == 1) {
            return partial(args[0], Arrays.asList("start", "stop", "reload", "toggle"));
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            return partial(args[1], Arrays.asList("water", "food"));
        }

        return Collections.emptyList();
    }

    private List<String> partial(String input, List<String> options) {
        String low = input.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String s : options) {
            if (s.startsWith(low)) out.add(s);
        }
        return out;
    }
}
