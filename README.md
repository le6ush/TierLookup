# TierLookup

TierLookup is a client-side Fabric mod for Minecraft 1.21.11 that shows PvP tier information without requiring server-side installation.

It combines a fast in-memory cache, an optional local SQLite mirror, live lookups, a sortable player list, hover cards, search, comparison, favorites, and player notes in one client UI.

[Русская версия README](README_RU.md)

## Features

- **Player tiers in TAB** — Legacy and Custom modes, tier sorting, configurable scale, and kit selection.
- **Interactive Custom TAB** — freeze the list, inspect players with the mouse, and open quick actions.
- **Hover cards** — lightweight tier tables for players in front of you. The render path reads RAM only.
- **Search with `K`** — search recent players, favorites, or a nickname while in-game.
- **Compare players** — compare stored tiers using the selected comparison method.
- **Player notes** — local notes stored in the TierLookup database.
- **Internet or Offline loading** — RAM is always checked first; Internet mode can query tier lists, while Offline mode stays local.
- **Local database sync** — selected tier lists can be mirrored to SQLite from the settings screen.
- **Exact rank handling** — LT, MT, and HT values are kept as reported by the source. Retired is shown only when the source explicitly marks it.
- **Client-side only** — no server plugin and no runtime Mixin injection.

## Supported sources

TierLookup currently integrates:

- MCTiers
- PvPTiers
- SubTiers
- FlowPVP
- CISTiers
- ATiers
- MyTiers
- Central Tier List

Availability and completeness depend on what each source exposes publicly. TierLookup keeps partial and failed syncs separate so an incomplete refresh does not silently replace a better local mirror.

## Requirements

- Minecraft Java Edition **1.21.11**
- Java **21**
- Fabric Loader
- Fabric API
- Mod Menu is optional, but recommended for easy access to settings

## Installation

1. Install Fabric Loader for Minecraft 1.21.11.
2. Install Fabric API.
3. Put the TierLookup JAR into your `mods` folder.
4. Optionally install Mod Menu.
5. Start Minecraft and configure TierLookup from Mod Menu.

The default TierLookup key is `K` and can be changed in Minecraft Controls.

## Data and privacy

TierLookup stores its cache, favorites, aliases, notes, and mirror metadata locally in the Minecraft directory. Live lookups are only sent to the configured public tier-list services when the selected data mode allows network access.

Dynamic hover rendering never performs HTTP or SQLite work directly.

## Building

The project targets Java 21 and uses Fabric Loom.

```bash
gradle clean build
```

The production JAR is written to `build/libs/`.

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for the source layout and development notes.

## Diagnostics



## License

Copyright © 2026 Lebushe. All rights reserved. See [LICENSE](LICENSE).
