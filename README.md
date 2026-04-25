# amethystads plugin

> **Early Access** — This plugin is currently in early access. To request access, join the Discord server at https://discord.gg/KkNfCQjczs

Bukkit/Spigot plugin that fetches banner ads from the amethystADS edge node and displays them in-game. Servers earn credits per verified player click.

## Requirements

- **Java 8+** — not included in this repo; download from [Adoptium](https://adoptium.net/) or any JDK 8+ distribution
- **Gradle 8** — not included in this repo; download from [gradle.org](https://gradle.org/install/) or use the system Gradle wrapper

> Note: Java and Gradle are excluded from version control via `.gitignore`. You must install them separately before building.

## Building

Clone the repo and make sure Java 8+ and Gradle 8 are available on your `PATH`.

**Windows:**

```bat
compile.bat
```

Runs `gradle build` using whatever Java and Gradle are on your system PATH. The compiled jar is output to `build/libs/amethystads-plugin.jar`.

**Other platforms:**

```bash
gradle build
```

Output: `build/libs/amethystads-plugin.jar`

## Installing

1. Build the jar (see above), or download a pre-built release.
2. Copy `build/libs/amethystads-plugin.jar` into your server's `plugins/` directory.
3. Restart (or reload) your Bukkit/Spigot server.
4. A default `plugins/amethystads/config.yml` will be generated on first run.

## Configuration

Edit `plugins/amethystads/config.yml` after first run. Key fields:

| Field | Description |
|---|---|
| `server_id` | Unique identifier for your server, assigned during registration |
| `hmac_secret` | Shared secret used to sign click events sent to the edge node |
| `edge_node_url` | URL of the amethystADS edge node (default provided) |

Run `/aa register` in-game to generate a registration token. Submit that token through the admin panel to complete server registration and receive your `server_id` and `hmac_secret`.

## How It Works

1. The plugin connects to the amethystADS edge node at startup and fetches banner ad data.
2. Ads are displayed to players in-game (scoreboard, chat, boss bar, or sign, depending on config).
3. When a player clicks/interacts with an ad, the plugin sends a signed click event to the edge node.
4. The edge node verifies the event and credits your server account.
