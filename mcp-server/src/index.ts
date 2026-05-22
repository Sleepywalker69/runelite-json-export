#!/usr/bin/env node

/**
 * OSRS Companion MCP Server v2.0
 *
 * Provides live game data from the RuneLite OSRS MCP Companion plugin via MCP tools.
 * Combines:
 *   - Live game state from the plugin's HTTP API (localhost:8085)
 *   - Wiki search and article summaries
 *   - Grand Exchange price lookups
 *   - Locally synced player data (stats, bank, quests, etc.)
 */

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

import { registerWikiTools } from "./tools/wiki.js";
import { registerPlayerSyncTools } from "./tools/player-sync.js";
import { registerLiveStateTools } from "./tools/live-state.js";
import { registerWidgetTools } from "./tools/widgets.js";
import { registerDefinitionTools } from "./tools/definitions.js";
import { registerDevTools } from "./tools/devtools.js";

const server = new McpServer({
  name: "osrs-mcp-companion",
  version: "2.0.0",
});

// Register all tool groups
registerWikiTools(server);
registerPlayerSyncTools(server);
registerLiveStateTools(server);
registerWidgetTools(server);
registerDefinitionTools(server);
registerDevTools(server);

// Connect via stdio transport
const transport = new StdioServerTransport();
await server.connect(transport);
