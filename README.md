
# DomainTags

A lightweight Minecraft [Paper](https://papermc.io/) plugin that assigns [**scoreboard tags**](https://minecraft.fandom.com/wiki/Commands/tag) and sends **custom messages** to players based on the domain they used to connect.

This lets you:
- Distinguish players joining from different hostnames (e.g. `mc.example.com` vs `play.alt.com`).
- Automatically add/remove scoreboard tags for command selectors.
- Send domain-specific welcome messages (with clickable links).
- Reload config without restarting the server.

---

## ✨ Features

- Match player join hostname (`mc.yourdomain.com`) and apply rules.
- Add, remove, or leave tags unchanged depending on the mapping.
- Send per-player commands/messages (supports `%player%` placeholder).
- Supports two or more potential domain sources for players on the same server.
- `/domaintags reload` command to apply config changes live.
- Debug logging to show exactly what hostnames/rules are applied.
- Reload command requires OP or 'domaintags.reload' permission.

---

## 📦 Installation

1. Download the latest release JAR from the [Releases](../../releases) page or build with Maven.
2. Drop the JAR into your server’s `plugins/` folder.
3. Start your Paper server once to generate the default `config.yml`.
4. Edit `plugins/DomainTags/config.yml` to match your domains.
5. Either restart the server or run `/domaintags reload`.

---

## ⚙️ Configuration

| Key                     | Description                                                                                     |
|-------------------------|-------------------------------------------------------------------------------------------------|
| `host`                  | The domain/hostname players use to join (e.g., `mc.example.com`). Must be written exactly as typed. |
| `tag`                   | - Non-blank string (e.g. `"team1"`) → ensures that specific scoreboard tag is present. <br> - `""` (blank) → removes **all known rule-tags** from the player. |
| `message`               | Any console command (e.g., `tellraw`, `say`, LuckPerms command). <br> `%player%` is replaced with the joining player’s name. |
| `exclusive`             | If `true`, players can only have one rule-tag at a time (switching domains swaps tags). <br> If `false`, players may accumulate multiple rule-tags. |
| `clear_all_known_on_unmapped` | If `true`, removes all known rule-tags when a player joins with an unmapped host (e.g., direct IP). <br> If `false`, leaves tags unchanged on unmapped hosts. |
| `message_delay_ticks`   | Delay (in ticks) before running message commands after join. <br> Default: `20` (≈1 second). Prevents “No player was found” errors on login. |

### Example config.yml

```yaml
rules:
  - host: "mc.website1.com"
    tag: "team1"        # adds scoreboard tag "irl"
    message: 'tellraw %player% ["§aWelcome! See: ",{"text":"website1.com/mc","clickEvent":{"action":"open_url","value":"https://website1.com/mc"}}]'

  - host: "mc.website2.com"
    tag: "team2"     # adds scoreboard tag "friend"
    message: 'tellraw %player% ["§9Hey! Info: ",{"text":"website2.com/mc","clickEvent":{"action":"open_url","value":"https://website2.com/mc"}}]'

  - host: "mc.website3.com"
    tag: "web3"        # adds scoreboard tag "dev"
    message: 'tellraw %player% ["§eWelcome to the website 3 server!"]'

  - host: "mc.website4.com"
    tag: "web4"  # adds scoreboard tag "community"
    message: 'tellraw %player% ["§dWelcome to the website 4 server!"]'

# Behavior switches
exclusive: true                 # keep only ONE of the known rule-tags at a time (recommended)
clear_all_known_on_unmapped: false  # if true, remove ALL known rule-tags when host is unmapped (IP, ISP rDNS, etc.)
message_delay_ticks: 20         # ~1s delay so tellraw hits after join
```

