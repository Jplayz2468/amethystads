# amethystads plugin

> **Early Access** — This plugin is currently in early access. To request access, join the Discord server at https://discord.gg/KkNfCQjczs

Bukkit/Spigot plugin that fetches banner ads from the amethystADS edge node and displays them in-game. Servers earn credits per verified player click.

## Account & website

**[jplayz.net](https://jplayz.net) is where server operators create an account.** You sign in with Google, link this plugin to your account using the registration token described below, upload/manage ad creatives, and view your earnings dashboard. The plugin itself is a thin reporter — all account, billing, and ad management happens at jplayz.net.

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

Run `/aa register` in-game to generate a registration token. Sign in at **[jplayz.net](https://jplayz.net)** (this is where you create your account) and paste the token into the admin panel to link this server and receive your `server_id` and `hmac_secret`.

## Commands

All commands require the `amethystads.admin` permission (granted to ops by default). The `/aa` alias works everywhere `/amethystads` does.

| Command | Description |
|---|---|
| `/aa` | Show whether the plugin is connected and a link to jplayz.net |
| `/aa register` | Generate a registration token; paste it at [jplayz.net](https://jplayz.net) to link this server to your account |
| `/aa give` | Give yourself the ad placement tool (a blaze rod) |
| `/aa reload` | Clear the image cache and re-poll the edge node |
| `/aa status` | Show connection status, API URL, server-id, active ad count, placed ad-group count, pending impression count, and the most recent flush error (if any) |

## How It Works

1. The operator creates an account at **[jplayz.net](https://jplayz.net)** and links this plugin instance via `/aa register` + the admin panel.
2. The plugin connects to the amethystADS edge node at startup and fetches the current banner ad set.
3. Ads are rendered as 2×2 item-frame maps placed by ops with the ad tool from `/aa give`.
4. When a player clicks/interacts with an ad, the plugin sends a signed click event to the edge node.
5. The edge node verifies the event and credits the server's jplayz.net account, where the operator can see earnings and manage ads.

## Auto-updates

On startup and every 30 minutes, the plugin compares its own version against the latest release published at [github.com/jplayz2468/amethystads/releases](https://github.com/jplayz2468/amethystads/releases). If a newer release is available, it downloads the release's `.jar` asset into `plugins/update/` (Bukkit's standard update folder, which replaces the live jar on the next server start) and notifies any online admins in chat. Admins are also notified on join while an update is staged. Restart the server to apply the staged jar.

## Memory & ad fetching

Ad images are fetched from the edge node only when an ad in the current rotation is about to be displayed, drawn once onto the in-game map canvas, and then released — the plugin does not retain `BufferedImage`s in its own caches. When an ad rotates out of the active set its `MapView` and renderer are discarded.



NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.