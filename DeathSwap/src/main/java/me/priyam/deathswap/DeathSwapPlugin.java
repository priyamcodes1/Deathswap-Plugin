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

    private int deathsBeforeGhost = 1;

    private BukkitTask timerTask = null;
    private BukkitTask fireworkTask = null;

    private final Set<UUID> ghostPlayers = new HashSet<>();
    private final Map<UUID, Integer> ghostSpectateIndex = new HashMap<>();

    // death counter for alive players (and players who died but not eliminated yet)
    private final Map<UUID, Integer> deathCounts = new HashMap<>();

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

        deathsBeforeGhost = getConfig().getInt("deaths-before-ghost", 1);
        if (deathsBeforeGhost < 1) deathsBeforeGhost = 1;
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

        // clear old lines
        for (String entry : scoreboard.getEntries()) {
            scoreboard.resetScores(entry);
        }

        // Build lines top->bottom using score values
        // Scoreboard shows higher score at top
        int score = 15;

        // Spacer
        objective.getScore(" ").setScore(score--);

        // Time line
        String timeLine = ChatColor.YELLOW + "Time: " + ChatColor.GREEN + formatTime(timeLeft);
        objective.getScore(makeUnique(timeLine, score)).setScore(score--);

        // Show deaths section ONLY if deathsBeforeGhost > 1
        if (deathsBeforeGhost > 1) {
            objective.getScore("  ").setScore(score--);

            String deathsHeader = ChatColor.GOLD + "Deaths:";
            objective.getScore(makeUnique(deathsHeader, score)).setScore(score--);

            // Only show players with deaths > 0, sorted high->low
            List<Map.Entry<UUID, Integer>> entries = new ArrayList<>(deathCounts.entrySet());
            entries.removeIf(e -> e.getValue() == null || e.getValue() <= 0);

            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

            int shown = 0;
            for (Map.Entry<UUID, Integer> e : entries) {
                if (shown >= 6) break; // keep scoreboard clean
                Player p = Bukkit.getPlayer(e.getKey());
                if (p == null) continue;

                int d = e.getValue();
                String line = ChatColor.YELLOW + p.getName() + ChatColor.GRAY + ": " + ChatColor.RED + d;

                objective.getScore(makeUnique(line, score)).setScore(score--);
                shown++;
            }

            // If nobody died yet, show nothing under Deaths: (clean)
        }

        objective.getScore("   ").setScore(score--);

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(scoreboard);
        }
    }

    // Make scoreboard entries unique even if text repeats
    private String makeUnique(String base, int score) {
        // scoreboard lines must be <= 40 chars, and unique
        String suffix = ChatColor.COLOR_CHAR + "" + (char) ('a' + (score % 26));
        String out = base;

        if (out.length() > 38) out = out.substring(0, 38);
        return out + suffix;
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
        deathCounts.clear();

        for (Player p : Bukkit.getOnlinePlayers()) {
            deathCounts.put(p.getUniqueId(), 0);
        }

        timeLeft = swapIntervalSeconds;
        updateScoreboardAll();

        giveStartItems(players);

        Bukkit.broadcastMessage(ChatColor.GREEN + "DeathSwap has started!");
        Bukkit.broadcastMessage(ChatColor.GRAY + "Deaths before ghost: " + ChatColor.YELLOW + deathsBeforeGhost);

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

        // retry a few times to reduce chance of self-swap
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
            announceWinner(alive.get(0));
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
        Bukkit.getScheduler().runTaskLater(this, () -> stopGame(true), 100L);
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
        deathCounts.clear();

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
        UUID id = p.getUniqueId();

        int newDeaths = deathCounts.getOrDefault(id, 0) + 1;
        deathCounts.put(id, newDeaths);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;

            p.spigot().respawn();

            // If deaths reached limit => ghost
            if (newDeaths >= deathsBeforeGhost) {
                enableGhost(p);
                p.sendMessage(ChatColor.RED + "You are eliminated! (" + newDeaths + "/" + deathsBeforeGhost + ")");
            } else {
                p.sendMessage(ChatColor.YELLOW + "You died! (" + newDeaths + "/" + deathsBeforeGhost + ")");
            }

            updateScoreboardAll();
            checkWinCondition();

        }, 1L);
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
        deathCounts.remove(id);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            updateScoreboardAll();
            checkWinCondition();
        }, 5L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!running) return;
        deathCounts.putIfAbsent(e.getPlayer().getUniqueId(), 0);
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
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " toggle <water|food|deaths>");
                    return true;
                }
                toggleOption(sender, args);
            }
            default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
        }

        return true;
    }

    private void toggleOption(CommandSender sender, String[] args) {
        String opt = args[1].toLowerCase();

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
            case "deaths" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /ds toggle deaths <number>");
                    return;
                }
                try {
                    int val = Integer.parseInt(args[2]);
                    if (val < 1) val = 1;

                    getConfig().set("deaths-before-ghost", val);
                    saveConfig();
                    deathsBeforeGhost = val;

                    sender.sendMessage(ChatColor.GREEN + "deaths-before-ghost = " + val);

                    if (running) {
                        Bukkit.broadcastMessage(ChatColor.GRAY + "Deaths before ghost is now: " + ChatColor.YELLOW + val);
                        updateScoreboardAll();
                    }

                } catch (NumberFormatException ex) {
                    sender.sendMessage(ChatColor.RED + "That is not a valid number.");
                }
            }
            default -> sender.sendMessage(ChatColor.RED + "Unknown toggle option. Use water/food/deaths.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("deathswap.admin")) return Collections.emptyList();

        if (args.length == 1) {
            return partial(args[0], Arrays.asList("start", "stop", "reload", "toggle"));
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            return partial(args[1], Arrays.asList("water", "food", "deaths"));
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("toggle") && args[1].equalsIgnoreCase("deaths")) {
            return Arrays.asList("1", "2", "3", "4", "5");
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
