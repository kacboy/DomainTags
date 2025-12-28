package com.kacboy.domaintags;

import com.destroystokyo.paper.event.player.PlayerHandshakeEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DomainTagsPlugin extends JavaPlugin implements Listener {

    private FileConfiguration cfg;

    /** Rule for a host.
     * tag:  non-blank => add this scoreboard tag
     *       ""        => remove all known rule-tags
     */
    static final class Rule {
        final String tag;     // specific scoreboard tag for this host; "" means clear-all
        final String message; // optional command; supports %player%
        Rule(String tag, String message) { this.tag = tag; this.message = message; }
    }

    private static final Rule UNMAPPED = new Rule(null, null); // sentinel to avoid null map values

    /** host (lowercased) -> rule */
    private final Map<String, Rule> rules = new HashMap<>();

    /** uuid -> rule decided at handshake (if UUID available) */
    private final Map<UUID, Rule> pendingByUuid = new ConcurrentHashMap<>();

    /** ip -> queue of pending rules (fallback when handshake UUID is null) */
    private static final class PendingEntry {
        final long createdMs;
        final Rule rule;
        PendingEntry(long createdMs, Rule rule) { this.createdMs = createdMs; this.rule = rule; }
    }
    private final Map<String, Deque<PendingEntry>> pendingByIp = new ConcurrentHashMap<>();

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
                    getLogger().info("  - '" + k + "' -> tag='" + (v.tag == null ? "null" : v.tag) +
                            "' message=" + (isBlank(v.message) ? "none" : "set"))
            );
        }
        getLogger().info("[DEBUG] Known rule tags: " + (knownTags.isEmpty() ? "(none)" : knownTags));
    }

    /**
     * Decide the rule at handshake time (Paper).
     * - If UUID is present: store in pendingByUuid.
     * - If UUID is null (common with Floodgate/Geyser): store in pendingByIp using original socket host.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerHandshake(PlayerHandshakeEvent event) {
        UUID uuid = event.getUniqueId(); // may be null with Floodgate/Geyser flows
        String serverHostname = event.getServerHostname(); // may be null
        String originalHandshake = event.getOriginalHandshake(); // often includes the requested host
        String ip = safeLower(event.getOriginalSocketAddressHostname()); // should be non-null, no port

        getLogger().info("[DEBUG] PlayerHandshakeEvent uuid=" + uuid +
                " serverHostname='" + serverHostname + "'" +
                " originalHandshake='" + originalHandshake + "'" +
                " originalSocketHost='" + ip + "'");

        String host = extractRequestedHost(serverHostname, originalHandshake);
        if (isBlank(host)) {
            getLogger().warning("[DEBUG] Could not determine requested host from handshake; treating as UNMAPPED");
            storePending(uuid, ip, UNMAPPED);
            return;
        }

        Rule rule = rules.get(host);
        if (rule == null) {
            getLogger().info("[DEBUG] No mapping found for host='" + host + "'");
            rule = UNMAPPED;
        } else {
            getLogger().info("[DEBUG] Matched rule for host='" + host + "' -> tag='" + rule.tag +
                    "', message=" + (isBlank(rule.message) ? "none" : "set"));
        }

        storePending(uuid, ip, rule);
    }

    private void storePending(UUID uuid, String ip, Rule rule) {
        if (uuid != null) {
            pendingByUuid.put(uuid, rule);
            return;
        }

        if (isBlank(ip)) {
            // No UUID and no IP? Nothing reliable to correlate with join.
            getLogger().warning("[DEBUG] Handshake UUID null and socket host blank; cannot correlate -> skipping");
            return;
        }

        long now = System.currentTimeMillis();
        long ttlMs = Math.max(5_000L, cfg.getLong("pending_ip_ttl_ms", 30_000L)); // default 30s

        // Clean stale entries for this IP, then append
        Deque<PendingEntry> q = pendingByIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        while (!q.isEmpty() && (now - q.peekFirst().createdMs) > ttlMs) q.pollFirst();
        q.addLast(new PendingEntry(now, rule));

        getLogger().info("[DEBUG] Stored pending rule by IP '" + ip + "' (queue size=" + q.size() + ")");
    }

    // Apply tag & send message after the player fully joins (delay ensures tellraw works).
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player p = event.getPlayer();
        final UUID uuid = p.getUniqueId();

        Rule rule = pendingByUuid.remove(uuid);

        // If we didn't get a UUID-keyed rule (common for Bedrock flows), try IP fallback
        if (rule == null) {
            String ip = getPlayerIp(p);
            rule = pollPendingByIp(ip);
        }

        if (rule == null) rule = UNMAPPED;

        final boolean exclusive = cfg.getBoolean("exclusive", true); // only one rule-tag at a time
        final boolean clearAllKnownOnUnmapped = cfg.getBoolean("clear_all_known_on_unmapped", false);
        final int delayTicks = Math.max(1, cfg.getInt("message_delay_ticks", 20)); // ~1s default

        final Rule finalRule = rule;

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (finalRule == UNMAPPED || finalRule.tag == null) {
                if (clearAllKnownOnUnmapped && !knownTags.isEmpty()) {
                    int removed = removeAllKnownTags(p, knownTags);
                    getLogger().info("[DEBUG] (" + p.getName() + ") UNMAPPED -> cleared " + removed + " known tag(s)");
                } else {
                    getLogger().info("[DEBUG] (" + p.getName() + ") UNMAPPED -> no tag changes");
                }
            } else if (finalRule.tag.isBlank()) {
                int removed = removeAllKnownTags(p, knownTags); // explicit "clear-all" rule
                getLogger().info("[DEBUG] (" + p.getName() + ") MAPPED(blank tag) -> cleared " + removed + " known tag(s)");
            } else {
                String target = finalRule.tag;
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

            if (!isBlank(finalRule.message)) {
                String filled = finalRule.message.replace("%player%", p.getName());
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), filled);
                getLogger().info("[DEBUG] (" + p.getName() + ") Ran message command -> " + (ok ? "OK" : "FAILED") + " : " + filled);
            }
        }, delayTicks);
    }

    private Rule pollPendingByIp(String ip) {
        if (isBlank(ip)) return null;

        long now = System.currentTimeMillis();
        long ttlMs = Math.max(5_000L, cfg.getLong("pending_ip_ttl_ms", 30_000L)); // default 30s

        Deque<PendingEntry> q = pendingByIp.get(ip);
        if (q == null) return null;

        // Drop stale head entries
        while (!q.isEmpty() && (now - q.peekFirst().createdMs) > ttlMs) q.pollFirst();

        PendingEntry e = q.pollFirst();
        if (q.isEmpty()) pendingByIp.remove(ip);

        if (e != null) {
            getLogger().info("[DEBUG] Matched pending rule by IP '" + ip + "' (remaining queue size=" + (q == null ? 0 : q.size()) + ")");
            return e.rule;
        }
        return null;
    }

    private static String getPlayerIp(Player p) {
        InetSocketAddress addr = p.getAddress();
        if (addr == null) return null;
        // getHostString avoids reverse DNS lookup
        String s = addr.getHostString();
        return s == null ? null : s.toLowerCase(Locale.ROOT).trim();
    }

    private static String extractRequestedHost(String serverHostname, String originalHandshake) {
        String host = null;

        if (!isBlank(serverHostname)) {
            host = serverHostname.toLowerCase(Locale.ROOT).trim();
        } else if (!isBlank(originalHandshake)) {
            // originalHandshake often looks like "mc.kacboy.com." (or includes extras)
            String h = originalHandshake.split("\0", 2)[0];
            h = h.split(":", 2)[0].toLowerCase(Locale.ROOT).trim();
            host = h;
        }

        if (isBlank(host)) return null;
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host;
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
    private static String safeLower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT).trim(); }
}
