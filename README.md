# OSRS MCP Companion — RuneLite Plugin

A RuneLite plugin that exposes **live game data** via a local HTTP API, enabling
AI assistants to query any game state on demand through the Model Context
Protocol (MCP). Think IDA for Old School RuneScape — connect via MCP and scrape
any data you need in real time.

## What It Does

Runs a local HTTP API server (default `http://127.0.0.1:8085`) inside RuneLite
that lets MCP tools query live game data on demand. Also periodically saves
player snapshots to JSON files for offline use.

**All data stays on your machine. The API only binds to localhost.**

## Live API Endpoints

| Endpoint | Description |
|---|---|
| `GET /api/player` | Current player: position, animation, health, prayer, run energy, interacting target |
| `GET /api/npcs` | All loaded NPCs with IDs, names, positions, animations, health. `?name=X` or `?id=X` to filter |
| `GET /api/players` | All visible players with names, positions, animations |
| `GET /api/widgets` | List all active widget group IDs and child counts |
| `GET /api/widget?group=X` | All children in a widget group with full properties |
| `GET /api/widget?group=X&child=Y` | Specific widget with nested children, text, items, sprites |
| `GET /api/objects?radius=N` | Game objects near player (walls, decorations, interactive objects) |
| `GET /api/ground-items?radius=N` | Ground items near player |
| `GET /api/inventory` | Inventory contents with item IDs, names, quantities, slots |
| `GET /api/equipment` | Equipment slots with item IDs and names |
| `GET /api/bank` | Bank contents (available after opening bank) |
| `GET /api/skills` | All skill levels (real + boosted) and XP |
| `GET /api/quests` | All quest states |
| `GET /api/varbit?id=X` | Query any varbit value. `?from=X&to=Y` for ranges |
| `GET /api/varp?id=X` | Query any VarPlayer value. `?from=X&to=Y` for ranges |
| `GET /api/item-def?id=X` | Item definition lookup (name, price, members, stackable, actions) |
| `GET /api/npc-def?id=X` | NPC definition lookup (name, combat level, size, actions, transforms) |
| `GET /api/obj-def?id=X` | Object definition lookup (name, actions, impostors) |
| `GET /api/location` | Detailed location: world point, region IDs, instance info |
| `GET /api/scene` | Scene info: base coords, map regions, plane |
| `GET /api/tile?x=X&y=Y` | Tile info: collision flags, objects, ground items at world coordinates |
| `GET /api/chat` | Recent chat messages (last 200). `?type=X` to filter |
| `GET /api/game-state` | Login state, tick count, FPS, world info |
| `GET /` | Lists all available endpoints with descriptions |

## Configuration

### API Server
- **Enable API Server**: Toggle the HTTP API on/off (default: on)
- **API Port**: Port number (default: 8085, requires restart to change)

### File Export
- **Save Interval**: How often to write JSON snapshots to disk (default: 60s)
- **Data Toggles**: Enable/disable syncing for each data category

## File Export

Player snapshots are still saved periodically to:
```
~/.runelite/osrs-companion/{username}.json
```

| Data | Trigger |
|---|---|
| Skill levels & XP | On login + when stats change |
| Bank contents | When you open your bank |
| Inventory | On item changes |
| Equipment | On equipment changes |
| Quest status | Polled every ~18 seconds |
| Achievement Diaries | Polled every ~18 seconds |
| Combat Achievements | Polled every ~18 seconds |

## Using with an MCP Server

The API server can be consumed directly by any MCP server that makes HTTP
requests. You can also use the companion npm package for a ready-made setup:

### Quick Setup (Claude Code / Claude Desktop)

Add to your MCP configuration:

```json
{
  "mcpServers": {
    "osrs-companion": {
      "command": "npx",
      "args": ["-y", "osrs-companion"]
    }
  }
}
```

### Direct API Access

With the plugin running, query live data directly:

```bash
# Get all nearby NPCs
curl http://127.0.0.1:8085/api/npcs

# Find a specific NPC by name
curl "http://127.0.0.1:8085/api/npcs?name=Guard"

# Inspect a widget group
curl "http://127.0.0.1:8085/api/widget?group=161"

# Look up an item definition
curl "http://127.0.0.1:8085/api/item-def?id=4151"

# Scan varbits in a range
curl "http://127.0.0.1:8085/api/varbit?from=100&to=200"

# Check collision at a tile
curl "http://127.0.0.1:8085/api/tile?x=3222&y=3218"
```

## Privacy

- The API server binds to 127.0.0.1 only (not accessible from the network)
- JSON files are saved to local disk only
- No data is sent to any external server, API, or third party
- You control what gets synced via the config panel

## License

BSD 2-Clause "Simplified" License. See [LICENSE](LICENSE).
