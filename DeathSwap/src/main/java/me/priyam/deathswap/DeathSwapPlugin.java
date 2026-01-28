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
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.util.*;

public class DeathSwapPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private boolean running = false;

    private int swapIntervalSeconds = 300;
    private int timeLeft = 0;
    private int deathsBeforeGhost = 1;

    private BukkitTask timerTask;
    private BukkitTask fireworkTask;

    private final Set<UUID> ghostPlayers = new HashSet<>();
    private final Map<UUID, Integer> ghostSpectateIndex = new HashMap<>();
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

        Bukkit.getPluginManager().registerEvents(this, this);

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
        swapIntervalSeconds = Math.max(10, getConfig().getInt("swap-interval-seconds", 300));
        deathsBeforeGhost = Math.max(1, getConfig().getInt("deaths-before-ghost", 1));
    }

    /* ================= SCOREBOARD ================= */

    private void setupScoreboard() {
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        if (mgr == null) return;

        scoreboard = mgr.getNewScoreboard();
        objective = scoreboard.registerNewObjective("deathswap", Criteria.DUMMY, TITLE);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    private void updateScoreboardAll() {
        if (!running) return;

        scoreboard.getEntries().forEach(scoreboard::resetScores);

        int score = 15;
        objective.getScore(" ").setScore(score--);
        objective.getScore(ChatColor.YELLOW + "Time: " + ChatColor.GREEN + formatTime(timeLeft)).setScore(score--);

        if (deathsBeforeGhost > 1) {
            objective.getScore("  ").setScore(score--);
            objective.getScore(ChatColor.GOLD + "Deaths:").setScore(score--);

            deathCounts.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(6)
                    .forEach(e -> {
                        Player p = Bukkit.getPlayer(e.getKey());
                        if (p != null) {
                            objective.getScore(ChatColor.YELLOW + p.getName() +
                                    ChatColor.GRAY + ": " +
                                    ChatColor.RED + e.getValue()).setScore(score--);
                        }
                    });
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(scoreboard);
        }
    }

    private String formatTime(int s) {
        return (s / 60) + "m " + (s % 60) + "s";
    }

    /* ================= GAME FLOW ================= */

    private void startGame(CommandSender sender) {
        if (running) return;

        if (Bukkit.getOnlinePlayers().size() < 2) {
            sender.sendMessage(ChatColor.RED + "Need at least 2 players.");
            return;
        }

        running = true;
        ghostPlayers.clear();
        ghostSpectateIndex.clear();
        deathCounts.clear();

        Bukkit.getOnlinePlayers().forEach(p -> deathCounts.put(p.getUniqueId(), 0));

        timeLeft = swapIntervalSeconds;
        updateScoreboardAll();

        timerTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            timeLeft--;

            if (timeLeft <= 10 && timeLeft > 0) {
                Bukkit.getOnlinePlayers().forEach(p ->
                        p.sendTitle(ChatColor.RED + "" + timeLeft, "", 0, 20, 0));
            }

            if (timeLeft <= 0) {
                doSwap();
                Bukkit.broadcastMessage(ChatColor.GREEN + "All the players have been swapped");
                timeLeft = swapIntervalSeconds;
            }

            updateScoreboardAll();
            checkWin();

        }, 20L, 20L);
    }

    private void stopGame(boolean announce) {
        running = false;

        if (timerTask != null) timerTask.cancel();
        if (fireworkTask != null) fireworkTask.cancel();

        Bukkit.getOnlinePlayers().forEach(this::disableGhost);
        ghostPlayers.clear();
        deathCounts.clear();

        if (announce) Bukkit.broadcastMessage(ChatColor.RED + "DeathSwap ended.");
    }

    /* ================= SWAP ================= */

    private void doSwap() {
        List<Player> alive = getAlivePlayers();
        if (alive.size() < 2) return;

        List<Location> locs = new ArrayList<>();
        alive.forEach(p -> locs.add(p.getLocation()));
        Collections.shuffle(locs);

        for (int i = 0; i < alive.size(); i++) {
            alive.get(i).teleport(locs.get(i));
        }
    }

    private List<Player> getAlivePlayers() {
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!ghostPlayers.contains(p.getUniqueId())) list.add(p);
        }
        return list;
    }

    /* ================= GHOST (SPECTATOR) ================= */

    private void enableGhost(Player p) {
        ghostPlayers.add(p.getUniqueId());
        p.setGameMode(GameMode.SPECTATOR);
        p.getInventory().clear();
        p.getInventory().addItem(makeGhostCompass());
        p.sendMessage(ChatColor.GRAY + "You are now a ghost.");
    }

    private void disableGhost(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) {
            p.setGameMode(GameMode.SURVIVAL);
        }
    }

    private ItemStack makeGhostCompass() {
        ItemStack it = new ItemStack(Material.COMPASS);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(COMPASS_NAME);
        meta.setLore(Collections.singletonList(COMPASS_LORE));
        it.setItemMeta(meta);
        return it;
    }

    private void teleportGhost(Player ghost) {
        List<Player> alive = getAlivePlayers();
        if (alive.isEmpty()) return;

        int i = ghostSpectateIndex.getOrDefault(ghost.getUniqueId(), 0);
        Player target = alive.get(i % alive.size());
        ghost.teleport(target.getLocation());
        ghostSpectateIndex.put(ghost.getUniqueId(), i + 1);
    }

    /* ================= EVENTS ================= */

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!running) return;

        Player p = e.getEntity();
        int deaths = deathCounts.merge(p.getUniqueId(), 1, Integer::sum);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            p.spigot().respawn();
            if (deaths >= deathsBeforeGhost) enableGhost(p);
            updateScoreboardAll();
            checkWin();
        }, 1L);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!ghostPlayers.contains(e.getPlayer().getUniqueId())) return;

        if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack it = e.getItem();
            if (it != null && it.getType() == Material.COMPASS) {
                teleportGhost(e.getPlayer());
            }
        }
    }

    private void checkWin() {
        List<Player> alive = getAlivePlayers();
        if (alive.size() == 1) {
            Player winner = alive.get(0);
            Bukkit.broadcastMessage(ChatColor.GOLD + winner.getName() + " has won!!");
            stopGame(true);
        }
    }

    /* ================= COMMANDS ================= */

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("deathswap.admin")) return true;
        if (a.length == 0) return true;

        switch (a[0]) {
            case "start" -> startGame(s);
            case "stop" -> stopGame(true);
            case "reload" -> { reloadConfig(); loadSettings(); }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        return Arrays.asList("start", "stop", "reload");
    }
}
