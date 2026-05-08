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

`plugins/amethystads/config.yml` is generated on first run and populated with sensible defaults. You normally don't need to edit it.

| Field | Description |
|---|---|
| `server-id` | Unique identifier for this server. Auto-generated as a random UUID on first run. |
| `server-secret` | Shared secret used to HMAC-sign click and impression events. Auto-generated as 32 bytes of random hex on first run. |
| `api-url` | URL of the amethystADS API. Defaults to `https://api.jplayz.net`. |

To link this server to your jplayz.net account, run `/aa register` in-game to generate a registration token, then sign in at **[jplayz.net](https://jplayz.net)** and paste the token into the publisher dashboard. The token embeds the `server-id` and `server-secret` already in the config, so no manual copying is needed.

## Commands

All commands require the `amethystads.admin` permission (granted to ops by default). The `/aa` alias works everywhere `/amethystads` does.

| Command | Description |
|---|---|
| `/aa` | Show whether the plugin is connected and a link to jplayz.net |
| `/aa register` | Generate a registration token; paste it at [jplayz.net](https://jplayz.net) to link this server to your account (click the chat link to copy) |
| `/aa give` | Give yourself the ad placement tool (a blaze rod) |
| `/aa reload` | Clear the image cache and re-poll the edge node |
| `/aa status` | Show connection status, API URL, server-id, active ad count, placed ad-group count, pending impression count, and the most recent flush error (if any) |
| `/aa toggle` | Hide or show all placed ads on this server. **While hidden, the server earns no money** — no impressions or clicks are reported. Frames stay placed and reappear on toggle-on. Persists across restarts. |
| `/aa update` | Check [github.com/jplayz2468/amethystads](https://github.com/jplayz2468/amethystads) for a newer release immediately and stage it to `plugins/update/` if found |

## How It Works

1. The operator creates an account at **[jplayz.net](https://jplayz.net)** and links this plugin instance via `/aa register` + the publisher dashboard.
2. The plugin polls the amethystADS API at startup (and every 5 seconds thereafter) and fetches the current banner ad set.
3. Ads are rendered as 2×2 item-frame maps placed by ops with the ad tool from `/aa give`.
4. Per-player impressions are batched and flushed every 30 seconds; clicks/interactions are sent immediately. All events are HMAC-signed with the server's secret.
5. The API verifies the signature, runs click-fraud checks, and credits the server's jplayz.net account where the operator can see earnings and manage ads.

## Auto-updates

On startup and every 30 minutes, the plugin compares its own version against the latest release published at [github.com/jplayz2468/amethystads/releases](https://github.com/jplayz2468/amethystads/releases). If a newer release is available, it downloads the release's `.jar` asset into `plugins/update/` (Bukkit's standard update folder, which replaces the live jar on the next server start) and notifies any online admins in chat. Admins are also notified on join while an update is staged. Restart the server to apply the staged jar.

## Memory & ad fetching

Ad images are fetched from the edge node only when an ad first enters the current rotation, and only the ads actually in the current rotation are downloaded — there is no proactive pre-fetch and no time-based image cache. While an ad is in the active rotation its 256×256 `BufferedImage` is held in memory so Minecraft can populate per-player map canvases reliably (releasing it earlier causes blank-map textures to appear when a player approaches an ad after the first render). When an ad rotates out of the active set its `MapView`, renderer, and image reference are all discarded.



NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.