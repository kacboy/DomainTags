
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
- Supports both simple `domains:` mapping or `rules:` list format.
- `/domaintags reload` command to apply config changes live.
- Debug logging to show exactly what hostnames/rules are applied.

---

## 📦 Installation

1. Download the latest release JAR from the [Releases](../../releases) page or build with Maven.
2. Drop the JAR into your server’s `plugins/` folder.
3. Start your Paper server once to generate the default `config.yml`.
4. Edit `plugins/DomainTags/config.yml` to match your domains.
5. Either restart the server or run `/domaintags reload`.

---

## ⚙️ Configuration

### Example using `rules:` (RECOMMENDED)

```yaml
rules:
  - host: "mc.example.com"
    tag: "irl"
    message: 'tellraw %player% ["Server status, Discord link, and live map view:\n",{"text":"example.com/mc","clickEvent":{"action":"open_url","value":"https://example.com/mc"},"color":"#77cc77"}]'
  - host: "play.alt.com"
    tag: ""
    message: 'tellraw %player% ["Server status, Discord link, and live map view:\n",{"text":"alt.com/mc","clickEvent":{"action":"open_url","value":"https://alt.com/mc"},"color":"#2670B7"}]'

tag_name: "irl"
remove_on_unmapped: false
message_delay_ticks: 20   # ~1 second; increase if needed (e.g., 40 = 2s)
```
### Example using `domains:`

```yaml
domains:
  mc.example.com:
    tag: "irl"
    message: 'tellraw %player% ["Server status, Discord link, and live map view:\n",{"text":"example.com/mc","clickEvent":{"action":"open_url","value":"https://example.com/mc"},"color":"#77cc77"}]'
  play.alt.com:
    tag: ""
    message: 'tellraw %player% ["Server status, Discord link, and live map view:\n",{"text":"alt.com/mc","clickEvent":{"action":"open_url","value":"https://alt.com/mc"},"color":"#2670B7"}]'

tag_name: "irl"
remove_on_unmapped: false
message_delay_ticks: 20
```

