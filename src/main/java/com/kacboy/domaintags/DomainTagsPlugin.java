package com.kacboy.domaintags;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DomainTagsPlugin extends JavaPlugin implements Listener {

    private FileConfiguration cfg;

    /** Rule for a host.
     * tag:  non-blank => add this scoreboard tag
     *       ""        => remove all known rule-tags
     *       null      => (not used here) reserved
     */
    static final class Rule {
        final String tag;     // specific scoreboard tag for this host; "" means clear-all
        final String message; // optional command; supports %player%
        Rule(String tag, String message) { this.tag = tag; this.message = message; }
    }

    private static final Rule UNMAPPED = new Rule(null, null); // sentinel to avoid null map values

    /** host (lowercased) -> rule */
    private final Map<String, Rule> rules = new HashMap<>();
    /** uuid -> rule decided at login (never null; UNMAPPED if not found) */
    private final Map<UUID, Rule> pending = new ConcurrentHashMap<>();
    /** all distinct non-blank rule tag names (for exclusive/clear behaviors) */
    private final Set<String> knownTags = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadRules();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("DomainTags enabled");
    }

    /** Reload config and rebuild rules/knownTags (used on startup and /domaintags reload). */
    private void loadRules() {
        reloadConfig();
        cfg = getConfig();
        rules.clear();
        knownTags.clear();

        // REQUIRED: rules: [ {host, tag, message}, ... ]
        if (!cfg.isList("rules")) {
            getLogger().severe("No 'rules' list found in config.yml. The plugin requires the 'rules:' format.");
        } else {
            List<?> list = cfg.getList("rules");
            if (list != null) {
                for (Object o : list) {
                    if (o instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) o;
                        String host = optLower(m.get("host"));
                        String tag  = optString(m.get("tag"));
                        String msg  = optString(m.get("message"));
                        if (host == null || host.isBlank()) {
                            getLogger().warning("[DEBUG] Skipping a rule with missing/blank 'host'");
                            continue;
                        }
                        rules.put(host, new Rule(tag, msg));
                        if (!isBlank(tag)) knownTags.add(tag);
                    }
                }
            }
        }

        if (rules.isEmpty()) {
            getLogger().warning("[DEBUG] No domain rules loaded. Add entries under 'rules:' in config.yml.");
        } else {
            getLogger().info("[DEBUG] Loaded domain mappings:");
            rules.forEach((k, v) ->
                getLogger().info("  - '" + k + "' -> tag='" + (v.tag == null ? "null" : v.tag) + "' message=" + (isBlank(v.message) ? "none" : "set"))
            );
        }
        getLogger().info("[DEBUG] Known rule tags: " + (knownTags.isEmpty() ? "(none)" : knownTags));
    }

    // Decide the rule at login (has hostname); just store it for join.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLogin(PlayerLoginEvent event) {
        String raw = event.getHostname();
        getLogger().info("[DEBUG] PlayerLoginEvent for " + event.getPlayer().getName() + " raw hostname='" + raw + "'");

        String host;
        if (isBlank(raw)) {
            getLogger().warning("[DEBUG] raw hostname is null/empty; treating as UNMAPPED");
            pending.put(event.getPlayer().getUniqueId(), UNMAPPED);
            return;
        } else {
            host = raw.split("\0", 2)[0];                 // strip proxy extras
            host = host.split(":", 2)[0]                  // strip :port
                       .toLowerCase(Locale.ROOT).trim();
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            getLogger().info("[DEBUG] Normalized host='" + host + "'");
        }

        Rule rule = rules.get(host);
        if (rule == null) {
            getLogger().info("[DEBUG] No mapping found for host='" + host + "'");
            rule = UNMAPPED;
        } else {
            getLogger().info("[DEBUG] Matched rule for host='" + host + "' -> tag='" + rule.tag + "', message=" + (isBlank(rule.message) ? "none" : "set"));
        }

        pending.put(event.getPlayer().getUniqueId(), rule);
    }

    // Apply tag & send message after the player fully joins (delay ensures tellraw works).
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player p = event.getPlayer();
        final UUID uuid = p.getUniqueId();
        final Rule rule = pending.getOrDefault(uuid, UNMAPPED);
        pending.remove(uuid);

        final boolean exclusive = cfg.getBoolean("exclusive", true); // only one rule-tag at a time
        final boolean clearAllKnownOnUnmapped = cfg.getBoolean("clear_all_known_on_unmapped", false);
        final int delayTicks = Math.max(1, cfg.getInt("message_delay_ticks", 20)); // ~1s default

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (rule == UNMAPPED || rule.tag == null) {
                if (clearAllKnownOnUnmapped && !knownTags.isEmpty()) {
                    int removed = removeAllKnownTags(p, knownTags);
                    getLogger().info("[DEBUG] (" + p.getName() + ") UNMAPPED -> cleared " + removed + " known tag(s)");
                } else {
                    getLogger().info("[DEBUG] (" + p.getName() + ") UNMAPPED -> no tag changes");
                }
            } else if (rule.tag.isBlank()) {
                int removed = removeAllKnownTags(p, knownTags); // explicit "clear-all" rule
                getLogger().info("[DEBUG] (" + p.getName() + ") MAPPED(blank tag) -> cleared " + removed + " known tag(s)");
            } else {
                String target = rule.tag;
                if (exclusive && !knownTags.isEmpty()) {
                    int removed = removeAllExcept(p, knownTags, target);
                    if (removed > 0) getLogger().info("[DEBUG] (" + p.getName() + ") EXCLUSIVE -> removed " + removed + " other known tag(s)");
                }
                boolean had = p.getScoreboardTags().contains(target);
                if (!had) {
                    p.addScoreboardTag(target);
                    getLogger().info("[DEBUG] (" + p.getName() + ") ADDED tag '" + target + "'");
                } else {
                    getLogger().info("[DEBUG] (" + p.getName() + ") tag '" + target + "' already present");
                }
            }

            if (!isBlank(rule.message)) {
                String filled = rule.message.replace("%player%", p.getName());
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), filled);
                getLogger().info("[DEBUG] (" + p.getName() + ") Ran message command -> " + (ok ? "OK" : "FAILED") + " : " + filled);
            }
        }, delayTicks);
    }

    // /domaintags reload
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("domaintags")) return false;

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("domaintags.reload")) {
                sender.sendMessage("§cYou don't have permission.");
                return true;
            }
            loadRules();
            sender.sendMessage("§aDomainTags config reloaded.");
            return true;
        }

        sender.sendMessage("§eUsage: /" + label + " reload");
        return true;
    }

    // ---- helpers ----

    private static int removeAllKnownTags(Player p, Set<String> known) {
        int removed = 0;
        for (String t : known) {
            if (p.getScoreboardTags().contains(t) && p.removeScoreboardTag(t)) removed++;
        }
        return removed;
    }

    private static int removeAllExcept(Player p, Set<String> known, String keep) {
        int removed = 0;
        for (String t : known) {
            if (!t.equals(keep) && p.getScoreboardTags().contains(t) && p.removeScoreboardTag(t)) removed++;
        }
        return removed;
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String optString(Object o) { return (o == null) ? null : String.valueOf(o); }
    private static String optLower(Object o) { return (o == null) ? null : String.valueOf(o).toLowerCase(Locale.ROOT).trim(); }
}
