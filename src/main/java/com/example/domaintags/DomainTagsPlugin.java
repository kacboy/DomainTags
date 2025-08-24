package com.example.domaintags;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
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

    // Rule for a given host: tag=null => treat as UNMAPPED, tag="" => remove, non-blank => add
    static final class Rule {
        final String tag;
        final String message; // optional tellraw/command; %player% placeholder supported
        Rule(String tag, String message) { this.tag = tag; this.message = message; }
    }

    private static final Rule UNMAPPED = new Rule(null, null); // sentinel (avoid null in maps)

    private final Map<String, Rule> rules = new HashMap<>();          // host -> rule (lowercased host)
    private final Map<UUID, Rule> pending = new ConcurrentHashMap<>(); // uuid -> rule decided at login

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadRules();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("DomainTags enabled");
    }

    /** Reload config + rebuild rules map (used at startup and by /domaintags reload) */
    private void loadRules() {
        reloadConfig();
        cfg = getConfig();
        rules.clear();

        // Preferred format: rules: [ {host, tag, message}, ... ]
        if (cfg.isList("rules")) {
            List<?> list = cfg.getList("rules");
            if (list != null) {
                for (Object o : list) {
                    if (o instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) o;
                        String host = optLower(m.get("host"));
                        String tag  = optString(m.get("tag"));
                        String msg  = optString(m.get("message"));
                        if (host != null) rules.put(host, new Rule(tag, msg));
                    }
                }
            }
        }

        // Back-compat: domains: section (supports dotted keys or nested sections)
        ConfigurationSection dom = cfg.getConfigurationSection("domains");
        if (dom != null) {
            flattenDomains(dom, "", rules);
        }

        // Debug summary
        if (rules.isEmpty()) {
            getLogger().warning("[DEBUG] No domain rules found (check config)");
        } else {
            getLogger().info("[DEBUG] Loaded domain mappings:");
            rules.forEach((k, v) ->
                getLogger().info("  - '" + k + "' -> tag='" + (v.tag == null ? "null" : v.tag) + "' message=" + (isBlank(v.message) ? "none" : "set"))
            );
        }
    }

    /** Decide rule at login (has hostname), store it; don't touch the player yet. */
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
            // Normalize: strip proxy extras (\0...), strip :port, lowercase, trim, strip trailing dot.
            host = raw.split("\0", 2)[0];
            host = host.split(":", 2)[0].toLowerCase(Locale.ROOT).trim();
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            getLogger().info("[DEBUG] Normalized host='" + host + "'");
        }

        Rule rule = rules.get(host);
        if (rule == null) {
            getLogger().info("[DEBUG] No mapping found for host='" + host + "'");
            rule = UNMAPPED;
        } else {
            getLogger().info("[DEBUG] Matched rule for host='" + host + "' -> desiredTag='" + rule.tag + "', message=" + (isBlank(rule.message) ? "none" : "set"));
        }

        pending.put(event.getPlayer().getUniqueId(), rule); // never null
    }

    /** Apply tag & send message after player fully joins; small delay ensures tellraw works. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player p = event.getPlayer();
        final UUID uuid = p.getUniqueId();
        final Rule rule = pending.getOrDefault(uuid, UNMAPPED);
        pending.remove(uuid);

        final String tagName = cfg.getString("tag_name", "irl");
        final boolean removeOnUnmapped = cfg.getBoolean("remove_on_unmapped", false);
        final int delayTicks = Math.max(1, cfg.getInt("message_delay_ticks", 20)); // default ~1s

        Bukkit.getScheduler().runTaskLater(this, () -> {
            boolean had = p.getScoreboardTags().contains(tagName);

            if (rule == UNMAPPED || rule.tag == null) {
                if (removeOnUnmapped && had) {
                    p.removeScoreboardTag(tagName);
                    getLogger().info("[DEBUG] (" + p.getName() + ") UNMAPPED -> REMOVED tag '" + tagName + "'");
                } else {
                    getLogger().info("[DEBUG] (" + p.getName() + ") UNMAPPED -> no tag change (had=" + had + ")");
                }
            } else if (rule.tag.isBlank()) {
                boolean removed = p.removeScoreboardTag(tagName);
                if (removed) {
                    getLogger().info("[DEBUG] (" + p.getName() + ") MAPPED(blank) -> REMOVED tag '" + tagName + "'");
                } else {
                    getLogger().info("[DEBUG] (" + p.getName() + ") MAPPED(blank) -> tag already absent");
                }
            } else {
                if (!had) {
                    p.addScoreboardTag(tagName);
                    getLogger().info("[DEBUG] (" + p.getName() + ") MAPPED('" + rule.tag + "') -> ADDED tag '" + tagName + "'");
                } else {
                    getLogger().info("[DEBUG] (" + p.getName() + ") MAPPED('" + rule.tag + "') -> tag already present");
                }
            }

            if (!isBlank(rule.message)) {
                String filled = rule.message.replace("%player%", p.getName());
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), filled);
                getLogger().info("[DEBUG] (" + p.getName() + ") Ran message command -> " + (ok ? "OK" : "FAILED") + " : " + filled);
            }
        }, delayTicks);
    }

    /** /domaintags reload */
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

    // ------- helpers -------

    /** Flatten nested "domains:" sections into dotted hostnames in the rules map. */
    private static void flattenDomains(ConfigurationSection section, String prefix, Map<String, Rule> out) {
        Set<String> keys = section.getKeys(false);

        boolean hasTag = section.contains("tag") || section.contains("message");
        if (hasTag && !isBlank(prefix)) {
            String tag = section.getString("tag", "");
            String msg = section.getString("message", "");
            out.put(prefix.toLowerCase(Locale.ROOT), new Rule(tag, msg));
        }

        for (String k : keys) {
            if ("tag".equals(k) || "message".equals(k)) continue;
            Object val = section.get(k);
            String nextPrefix = isBlank(prefix) ? k : (prefix + "." + k);
            if (val instanceof ConfigurationSection) {
                flattenDomains((ConfigurationSection) val, nextPrefix, out);
            } else {
                String tag = section.getString(k, "");
                out.put(nextPrefix.toLowerCase(Locale.ROOT), new Rule(tag, null));
            }
        }
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String optString(Object o) { return (o == null) ? null : String.valueOf(o); }
    private static String optLower(Object o) { return (o == null) ? null : String.valueOf(o).toLowerCase(Locale.ROOT).trim(); }
}
