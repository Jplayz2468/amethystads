# amethystads plugin

Bukkit/Spigot plugin that fetches banner ads from the amethystADS edge node and displays them in-game. Servers earn credits per verified player click.

## Requirements

- Java 8+
- Gradle 8 (wrapper included in `../gradle/`)
- JDK included at `../jdk/` (used by `compile.bat`)

## Building

**Windows (recommended):**

```bat
compile.bat
```

This sets up the local JDK and Gradle paths and runs `gradle build`. The compiled jar is output to `build/libs/amethystads-plugin.jar`.

**Manual (any platform):**

```bash
gradle build
```

Output: `build/libs/amethystads-plugin.jar`

## Installing

Copy `build/libs/amethystads-plugin.jar` into your server's `plugins/` directory and restart.

## Configuration

Edit `plugins/amethystads/config.yml` after first run. The plugin registers with the edge node using a server ID and HMAC secret — use `/amethystregister` in-game to generate a registration token and submit it to the admin panel.
