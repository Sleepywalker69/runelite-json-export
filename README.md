<p align="center">
  <img src="Images/GH titlecard.png" alt="OSRS MCP Companion" width="600">
</p>

<p align="center">
  <strong>Live game data + AI assistant bridge for Old School RuneScape</strong><br>
  RuneLite plugin &bull; Local HTTP API &bull; 57 MCP tools
</p>

---

A RuneLite plugin that exposes **live game data** via a local HTTP API, paired
with an MCP server that gives AI assistants (Claude Desktop, Claude Code, etc.)
direct access to 57 game state tools. Think IDA for Old School RuneScape —
connect via MCP and query any data you need in real time.

## What It Does

1. **RuneLite Plugin** — Runs a local HTTP API server (default `http://127.0.0.1:8085`)
   inside RuneLite with 53 endpoints covering every aspect of game state.
2. **MCP Server** — A TypeScript MCP server (`mcp-server/`) that wraps all HTTP
   endpoints as 57 MCP tools with rich descriptions for intuitive AI prompting.
3. **File Sync** — Periodically saves player snapshots to JSON files for offline use.

**All data stays on your machine. The API only binds to localhost.**

## MCP Tools (57 total)

The MCP server provides tools across eight categories:

### Live Game State (16 tools)
| Tool | What it does |
|------|-------------|
| `game_state` | Login status, world, tick count, player location |
| `live_player` | Health, prayer, run energy, animation, combat target |
| `live_skills` | Real-time skill levels and XP |
| `live_inventory` | Current inventory contents |
| `live_equipment` | Currently worn gear |
| `live_bank` | Bank contents (must be open in-game) |
| `nearby_npcs` | NPCs near player, filterable by name/ID |
| `nearby_players` | Other visible players |
| `nearby_objects` | Game objects (trees, rocks, doors, etc.) |
| `ground_items` | Items on the ground |
| `menu_entries` | Current right-click menu options |
| `camera` | Camera position and angles |
| `tile_info` | Collision flags at specific coordinates |
| `plugins` | Loaded RuneLite plugins and config |
| `player_appearance` | Equipment, body kits, colors, gender |
| `chat` | Recent chat messages — game, public, private, clan. Filterable by type and count |

### Interfaces & Widgets (4 tools)
| Tool | What it does |
|------|-------------|
| `interfaces` | All currently open game interfaces |
| `inspect_widget` | Read a specific widget group's children and properties |
| `find_item_in_interface` | **Smart search** — finds an item by name in any open container (shop, bank, trade). Supports fuzzy matching: "d scim" finds "Dragon scimitar" |
| `dialog` | Current NPC/player dialogue — text, speaker name, selectable options, continue button state |

### Game Definitions (6 tools)
| Tool | What it does |
|------|-------------|
| `lookup_item` | Item definition by ID or name search |
| `lookup_npc` | NPC definition by ID or name search |
| `lookup_object` | Object definition by ID or name search |
| `lookup_varbit` | Varbit definition (varp index, bit range) |
| `lookup_enum` | Enum definition (key-value pairs) |
| `lookup_struct` | Struct definition (param values) |

### DevTools (9 tools)
| Tool | What it does |
|------|-------------|
| `read_varbit` | Read current varbit value(s) |
| `read_varp` | Read current varp value(s) |
| `var_history` | Recent varbit/varp changes with old/new values |
| `recent_interactions` | Recent clicks and hovers — "what did I just click?" |
| `graphics_objects` | Active visual effects (spell impacts, etc.) |
| `active_prayers` | Currently active prayers and prayer points |
| `xp_tracker` | Session XP gains per skill |
| `loot_log` | Session loot drops, filterable by NPC |
| `raw_actions` | **Multi-layer action capture** — tracks menu clicks, CS2 script callbacks, and inferred state changes. Detects actions that bypass the normal menu system |

### Debug & Recording (8 tools)
| Tool | What it does |
|------|-------------|
| `debug_snapshot` | **Instant** full combat state snapshot — player, NPCs, prayers, inventory, equipment, recent interactions, effects. One call does it all |
| `start_recording` | Start recording game events for a duration (default 3 min, max 10 min). Filter by event type to keep the buffer focused |
| `stop_recording` | Stop an active recording early |
| `recording_status` | Check recording progress — events captured, time remaining, active filter |
| `get_recording` | Retrieve recorded timeline (tick-grouped format v2) with filtering by event type and tick range |
| `buffer` | **Tick-level state history** with delta encoding — query what changed in the last N ticks. Stores ~10 min of per-tick snapshots. Supports filtering by entity type, name, and ID |
| `screenshot` | Capture the game viewport as a PNG image |
| `runelite_logs` | Read RuneLite console/plugin logs — filter by level, logger name, or search text |

**19 recordable event types:** `game_tick`, `hitsplat`, `animation_changed`, `npc_spawned`, `npc_despawned`, `actor_death`, `var_changed`, `menu_clicked`, `stat_changed`, `item_changed`, `interacting_changed`, `object_spawned`, `object_despawned`, `projectile_spawned`, `gfx_created`, `chat_message`, `sound_effect`, `loot_received`, `game_state_changed`

**Recording presets** (pass to `start_recording` types parameter):
- **Boss (full):** `game_tick,hitsplat,animation_changed,npc_spawned,npc_despawned,actor_death,menu_clicked,object_spawned,object_despawned,projectile_spawned,gfx_created,sound_effect,chat_message,loot_received,game_state_changed`
- **Combat (full):** `game_tick,hitsplat,npc_spawned,npc_despawned,actor_death,menu_clicked,object_spawned,object_despawned,projectile_spawned,gfx_created,sound_effect`
- **Combat (lite):** `game_tick,hitsplat,actor_death,menu_clicked,projectile_spawned`
- **Vars only:** `var_changed,game_tick`
- **Clicks/movement:** `menu_clicked,game_tick`

### Wiki & Prices (3 tools)
| Tool | What it does |
|------|-------------|
| `search` | Search the OSRS Wiki |
| `summary` | Get a Wiki page summary |
| `price` | Grand Exchange price lookup |

### Synced Player Data (10 tools)
| Tool | What it does |
|------|-------------|
| `list_synced_players` | List players with locally saved data |
| `get_my_profile` | Full player summary from saved data |
| `get_my_bank` | Saved bank contents (searchable) |
| `get_my_stats` | Saved skill levels and XP |
| `get_my_quests` | Saved quest states |
| `get_my_equipment` | Saved equipment |
| `get_my_inventory` | Saved inventory |
| `get_my_diaries` | Saved achievement diary status |
| `get_my_combat_achievements` | Saved combat achievement status |
| `player` | Fetch player data via WikiSync API |

## Prerequisites

- **Java 11+** (JDK, not JRE) — required for building the RuneLite plugin
- **Node.js 18+** and **npm** — required for building the MCP server

## Setup

### 1. Build & Install the RuneLite Plugin

Clone the repo and build:

```bash
git clone https://github.com/tomsherborne/runelite-json-export.git
cd runelite-json-export
```

**Option A — Run directly in RuneLite dev mode** (recommended for development):
```bash
# Windows
.\gradlew.bat run

# Linux/macOS
./gradlew run
```
This launches RuneLite with the plugin pre-loaded in developer mode.

**Option B — Build a standalone JAR** (for installing into an existing RuneLite):
```bash
# Windows
.\gradlew.bat build

# Linux/macOS
./gradlew build
```
The built JAR is at `build/libs/runelite-json-export-1.0-SNAPSHOT.jar`. Copy it to your RuneLite external plugins folder or load it via the RuneLite plugin hub.

### 2. Build the MCP Server

```bash
cd mcp-server
npm install
npm run build
```

This compiles the TypeScript source in `mcp-server/src/` to JavaScript in `mcp-server/dist/`.

### 3. Configure MCP

The MCP server connects AI assistants to the plugin's HTTP API. Configure it for whichever client you use:

#### Claude Desktop

Add to `%APPDATA%\Claude\claude_desktop_config.json` (Windows) or `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS):

```json
{
  "mcpServers": {
    "osrs-companion": {
      "command": "node",
      "args": ["C:\\path\\to\\runelite-json-export\\mcp-server\\dist\\index.js"],
      "env": {
        "OSRS_API_URL": "http://127.0.0.1:8085"
      }
    }
  }
}
```

Restart Claude Desktop after updating the config.

#### Claude Code

Add the MCP server via the CLI:

```bash
claude mcp add osrs-companion node /path/to/runelite-json-export/mcp-server/dist/index.js
```

Or add it manually to `.claude/settings.json` in your project:

```json
{
  "mcpServers": {
    "osrs-companion": {
      "command": "node",
      "args": ["/path/to/runelite-json-export/mcp-server/dist/index.js"],
      "env": {
        "OSRS_API_URL": "http://127.0.0.1:8085"
      }
    }
  }
}
```

#### Other MCP Clients

Any MCP-compatible client can connect. The server uses **stdio** transport. Set the `OSRS_API_URL` environment variable if the plugin API runs on a non-default port (default: `http://127.0.0.1:8085`).

### 4. Try It

With RuneLite running and the plugin enabled, ask Claude things like:

- "What's in my inventory?"
- "Find the knife in the shop"
- "What NPCs are near me?"
- "What did I just click?"
- "How much XP have I gained this session?"
- "What's the price of an Abyssal whip?"
- "Why is it stuck? Debug this" (instant snapshot)
- "Record the next 3 minutes while I run the script"
- "What happened? Show me the recording"
- "What does the NPC say?" (reads dialogue text and options)
- "Take a screenshot" (captures game viewport as PNG)
- "What changed in the last 5 ticks?" (delta-encoded state history)
- "Any errors in the RuneLite console?" (reads plugin logs)
- "What chat messages came in?" (reads recent chat)
- "Show me raw actions — anything bypass the menu?" (multi-layer action capture)

## Live API Endpoints

All 53 endpoints available at `http://127.0.0.1:8085`:

| Endpoint | Description |
|----------|-------------|
| `GET /` | List all endpoints with descriptions |
| `GET /api/game-state` | Login state, tick count, FPS, world |
| `GET /api/player` | Current player state |
| `GET /api/location` | Detailed location with region IDs |
| `GET /api/skills` | All skill levels and XP |
| `GET /api/inventory` | Inventory contents |
| `GET /api/equipment` | Equipment slots |
| `GET /api/bank` | Bank contents |
| `GET /api/quests` | Quest states |
| `GET /api/npcs` | Nearby NPCs. `?name=X` or `?id=X` to filter |
| `GET /api/players` | Nearby players |
| `GET /api/objects?radius=N` | Nearby game objects |
| `GET /api/ground-items?radius=N` | Ground items |
| `GET /api/widgets` | Active widget groups |
| `GET /api/widget?group=X` | Widget group contents |
| `GET /api/interfaces` | Open interfaces with visibility |
| `GET /api/menu-entries` | Current menu entries |
| `GET /api/varbit?id=X` | Varbit values |
| `GET /api/varp?id=X` | Varp values |
| `GET /api/var-history` | Recent var changes. `?type=varbit\|varp`, `?last=N` |
| `GET /api/interaction-history` | Recent clicks and hovers. `?last=N` |
| `GET /api/graphics-objects` | Active visual effects |
| `GET /api/item-def?id=X` | Item definition. `?name=X` for search |
| `GET /api/npc-def?id=X` | NPC definition. `?name=X` for search |
| `GET /api/obj-def?id=X` | Object definition. `?name=X` for search |
| `GET /api/varbit-def?id=X` | Varbit definition |
| `GET /api/struct-def?id=X` | Struct definition |
| `GET /api/enum-def?id=X` | Enum definition |
| `GET /api/player-appearance` | Player appearance. `?name=X` for others |
| `GET /api/chat` | Recent chat messages. `?type=X`, `?last=N` |
| `GET /api/dialog` | Current NPC/player dialogue state |
| `GET /api/screenshot` | Capture game viewport as PNG (`Content-Type: image/png`) |
| `GET /api/buffer` | Tick-level state history. `?t=N`, `?types=npc,player,skills,hits`, `?names=X`, `?ids=X` |
| `GET /api/logs` | RuneLite console logs. `?level=INFO\|WARN\|ERROR`, `?logger=X`, `?search=X`, `?last=N` |
| `GET /api/actions` | Multi-layer action tracker. `?last=N`, `?source=menu\|script\|inferred`, `?search=X` |
| `GET /api/camera` | Camera position and angles |
| `GET /api/scene` | Scene base coords and map regions |
| `GET /api/tile?x=X&y=Y` | Tile collision flags |
| `GET /api/plugins` | Loaded RuneLite plugins |
| `GET /api/plugin-config?plugin=X` | Plugin configuration |
| `GET /api/xp-tracker` | Session XP gains |
| `GET /api/loot` | Session loot drops. `?npc=X` to filter |
| `GET /api/projectiles` | Active projectiles |
| `GET /api/wiki/search?q=X` | Wiki search proxy |
| `GET /api/wiki/page-info?title=X` | Wiki page info proxy |
| `GET /api/wiki/parse?title=X` | Wiki page parse proxy |
| `GET /api/prayers` | Active prayers and prayer points |
| `POST /api/recording/start?duration=N` | Start recording events (seconds, default 180, max 600) |
| `POST /api/recording/stop` | Stop recording |
| `GET /api/recording/status` | Recording state and progress |
| `GET /api/recording/data` | Recorded events. `?types=X`, `?from_tick=X&to_tick=Y`, `?last=N` |
| `GET /api/events` | Server-Sent Events stream |

## Standalone GUI Window

Click the **Open GUI** button in the RuneLite sidebar to launch a standalone 1100×720 window with 8 data tabs, a nav rail, live header status pills, and a footer status bar. Supports standard minimize/maximize/close window controls. The sidebar stays slim — just a brand header, the launch button, and a compact live-status block.

<p align="center">
  <img src="Images/Dashboard GUI.png" alt="Dashboard GUI" width="800"><br>
  <em>Dashboard tab — player stats, API status, session info, and quick actions</em>
</p>

<p align="center">
  <img src="Images/Sidebar GUI.png" alt="Sidebar Panel" width="260"><br>
  <em>RuneLite sidebar — compact status block with Open GUI button</em>
</p>

**Keyboard shortcuts:** `Ctrl+1-8` to switch tabs, `Esc` to close (state preserved, reopen from sidebar).

| Tab | What it shows |
|-----|--------------|
| **Dashboard** | Two-column layout: player status bars (HP, prayer, run, spec) on the left; API server status, session summary, and quick action buttons (Save, Snapshot, Screenshot) on the right |
| **Record** | Start/stop event recording with duration and event type selection. Includes presets (Boss, Combat, Lite, Vars, Clicks), a live progress bar, and a **recorded events viewer** with Load Events and Copy All buttons. Recordings auto-save to gzipped JSON (tick-grouped format v2) on stop — click **Reveal file** to open the recordings folder |
| **Actions** | Live feed of the ActionTracker — menu clicks, CS2 script callbacks, and inferred state changes. Filterable by source and searchable. Copy button for clipboard export |
| **Chat** | Real-time chat viewer with type filtering (Game, Public, Private, Clan) and search. Color-coded by chat type. Copy button for clipboard export |
| **Logs** | RuneLite console log viewer with level filtering (ERROR/WARN/INFO/DEBUG) and search. Stack traces on hover |
| **Stats** | Session XP tracker with per-skill gains and XP/hr rates. Loot log with NPC grouping and item details. Copy button for clipboard export |
| **Vars** | Varbit/varp change timeline — shows recent variable changes with type badges (orange for varbits, blue for varps), tick numbers, old/new values with human-readable var names. Filterable and searchable by ID. **Exclude filters** let you hide noisy varps/varbits by ID (comma-separated). Copy button for clipboard export |
| **Buffer** | Tick-level state buffer viewer with delta encoding. Shows entity spawns/despawns, HP changes, skill gains, and hitsplats between ticks |

## Configuration

### API Server
- **Enable API Server**: Toggle the HTTP API on/off (default: on)
- **API Port**: Port number (default: 8085, requires restart to change)

### File Export
- **Save Interval**: How often to write JSON snapshots to disk (default: 60s)
- **Data Toggles**: Enable/disable syncing for each data category

## File Sync

Player snapshots are saved periodically to:
```
~/.runelite/osrs-companion/{username}.json
```

Event recordings are auto-saved as gzipped JSON (format v2: tick-grouped) when stopped:
```
~/.runelite/osrs-companion/recordings/recording_{timestamp}.json.gz
```

### Recording Format v2

Recordings use a tick-grouped format that organizes events chronologically by game tick. This reduces token usage by ~30% compared to flat event arrays while preserving all data.

```json
{
  "format": 2,
  "meta": {
    "startTick": 83, "endTick": 212,
    "durationTicks": 129, "totalEvents": 4120,
    "eventCounts": { "hitsplat": 157, "game_tick": 39, ... }
  },
  "ticks": [
    {
      "tick": 85, "ts": 1780088760830,
      "fps": 50,
      "player": { "position": {"x":3427,"y":3541,"plane":2}, "health": 99, "prayer": 12, ... },
      "nearbyNpcs": [ ... ],
      "events": [
        {"type": "hitsplat", "target": {"name":"Gargoyle"}, "amount": 6, "hitsplatType": 17, ...},
        {"type": "animation_changed", "actor": {"name":"Gargoyle"}, "animation": 1517}
      ]
    }
  ]
}
```

- **`game_tick`** data (player snapshot, FPS, nearby NPCs) is promoted to tick-level fields
- **`tick`, `timestamp`, `ticksElapsed`** are not repeated on every event — they live on the tick wrapper
- **`eventType`** is shortened to **`type`** on each event
- All original event fields are preserved with no data loss

## Privacy

- The API server binds to 127.0.0.1 only (not accessible from the network)
- JSON files are saved to local disk only
- No data is sent to any external server, API, or third party
- You control what gets synced via the config panel

## License

BSD 2-Clause "Simplified" License. See [LICENSE](LICENSE).
