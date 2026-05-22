# OSRS MCP Companion — RuneLite Plugin + MCP Server

A RuneLite plugin that exposes **live game data** via a local HTTP API, paired
with an MCP server that gives AI assistants (Claude Desktop, Claude Code, etc.)
direct access to 40+ game state tools. Think IDA for Old School RuneScape —
connect via MCP and query any data you need in real time.

## What It Does

1. **RuneLite Plugin** — Runs a local HTTP API server (default `http://127.0.0.1:8085`)
   inside RuneLite with 42 endpoints covering every aspect of game state.
2. **MCP Server** — A TypeScript MCP server (`mcp-server/`) that wraps all HTTP
   endpoints as 44 MCP tools with rich descriptions for intuitive AI prompting.
3. **File Sync** — Periodically saves player snapshots to JSON files for offline use.

**All data stays on your machine. The API only binds to localhost.**

## MCP Tools (44 total)

The MCP server provides tools across six categories:

### Live Game State (15 tools)
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

### Interfaces & Widgets (3 tools)
| Tool | What it does |
|------|-------------|
| `interfaces` | All currently open game interfaces |
| `inspect_widget` | Read a specific widget group's children and properties |
| `find_item_in_interface` | **Smart search** — finds an item by name in any open container (shop, bank, trade). Supports fuzzy matching: "d scim" finds "Dragon scimitar" |

### Game Definitions (6 tools)
| Tool | What it does |
|------|-------------|
| `lookup_item` | Item definition by ID or name search |
| `lookup_npc` | NPC definition by ID or name search |
| `lookup_object` | Object definition by ID or name search |
| `lookup_varbit` | Varbit definition (varp index, bit range) |
| `lookup_enum` | Enum definition (key-value pairs) |
| `lookup_struct` | Struct definition (param values) |

### DevTools (7 tools)
| Tool | What it does |
|------|-------------|
| `read_varbit` | Read current varbit value(s) |
| `read_varp` | Read current varp value(s) |
| `var_history` | Recent varbit/varp changes with old/new values |
| `recent_interactions` | Recent clicks and hovers — "what did I just click?" |
| `graphics_objects` | Active visual effects (spell impacts, etc.) |
| `xp_tracker` | Session XP gains per skill |
| `loot_log` | Session loot drops, filterable by NPC |

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

## Setup

### 1. Install the RuneLite Plugin

Build and install the plugin into RuneLite:

```bash
.\gradlew.bat run
```

### 2. Build the MCP Server

```bash
cd mcp-server
npm install
npm run build
```

### 3. Configure Claude Desktop

Add to your Claude Desktop MCP config (`%APPDATA%\Claude\claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "osrs-companion": {
      "command": "node",
      "args": ["C:\\path\\to\\runelite-json-export\\mcp-server\\dist\\index.js"]
    }
  }
}
```

Restart Claude Desktop after updating the config.

### 4. Try It

With RuneLite running and the plugin enabled, ask Claude things like:

- "What's in my inventory?"
- "Find the knife in the shop"
- "What NPCs are near me?"
- "What did I just click?"
- "How much XP have I gained this session?"
- "What's the price of an Abyssal whip?"

## Live API Endpoints

All 42 endpoints available at `http://127.0.0.1:8085`:

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
| `GET /api/chat` | Recent chat messages. `?type=X` to filter |
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
| `GET /api/events` | Server-Sent Events stream |

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

## Privacy

- The API server binds to 127.0.0.1 only (not accessible from the network)
- JSON files are saved to local disk only
- No data is sent to any external server, API, or third party
- You control what gets synced via the config panel

## License

BSD 2-Clause "Simplified" License. See [LICENSE](LICENSE).
