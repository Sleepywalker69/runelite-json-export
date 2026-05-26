import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { apiGet, isApiError, fuzzyMatch, normalize } from "../api.js";

export function registerLiveStateTools(server: McpServer) {
  server.tool(
    "game_state",
    "Get the current game state: login status, player location, tick count, world number, and run energy. Use this automatically when the user asks 'where am I', 'what world am I on', 'am I logged in', or 'what's happening'.",
    {},
    async () => {
      try {
        const [gs, loc] = await Promise.all([apiGet("/api/game-state"), apiGet("/api/location")]);
        const lines = ["# Game State"];
        lines.push(`Login State: ${gs.gameState}`);
        lines.push(`World: ${gs.world ?? "N/A"}`);
        lines.push(`Tick: ${gs.tick ?? "N/A"}`);
        lines.push(`FPS: ${gs.fps ?? "N/A"}`);
        if (loc) {
          lines.push(`\n## Location`);
          lines.push(`Position: (${loc.x}, ${loc.y}, plane ${loc.plane})`);
          if (loc.regionId) lines.push(`Region ID: ${loc.regionId}`);
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "live_player",
    "Get real-time player data: health, prayer, run energy, special attack, animation, position, and combat target. Use this automatically when the user asks 'what am I doing', 'am I in combat', 'what's my health', or 'am I idle'.",
    {},
    async () => {
      try {
        const p = await apiGet("/api/player");
        const lines = ["# Current Player"];
        lines.push(`Name: ${p.name}`);
        lines.push(`Combat Level: ${p.combatLevel}`);
        lines.push(`Position: (${p.position?.x}, ${p.position?.y}, plane ${p.position?.plane})`);
        lines.push(`Health: ${p.health} | Prayer: ${p.prayer}`);
        lines.push(`Run Energy: ${p.runEnergy}%`);
        lines.push(`Special Attack: ${p.specialAttack}%`);
        lines.push(`Animation: ${p.animation} (${p.animation === -1 ? "idle" : "active"})`);
        if (p.poseAnimation !== undefined) lines.push(`Pose: ${p.poseAnimation}`);
        if (p.interacting) {
          lines.push(`\nInteracting with: ${p.interacting.name} (${p.interacting.type})`);
        }
        lines.push(`Idle: ${p.isIdle ? "Yes" : "No"}`);
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "live_skills",
    "Get real-time skill levels and XP from the live game. Use this when the user asks about their current levels, XP, or total level.",
    {
      skill: z.string().optional().describe("Specific skill name (e.g. 'attack', 'mining'). Omit for all."),
    },
    async ({ skill }) => {
      try {
        const data = await apiGet("/api/skills");
        if (skill) {
          const key = skill.toUpperCase();
          const entry = data.skills?.[key];
          if (!entry)
            return {
              content: [
                {
                  type: "text" as const,
                  text: `Skill "${skill}" not found. Available: ${Object.keys(data.skills || {}).join(", ")}`,
                },
              ],
            };
          return {
            content: [
              {
                type: "text" as const,
                text: `# ${key}\nLevel: ${entry.level} | Boosted: ${entry.boostedLevel}\nXP: ${entry.xp?.toLocaleString()}`,
              },
            ],
          };
        }
        const lines = ["# Live Skills"];
        if (data.totalLevel) lines.push(`Total Level: ${data.totalLevel}`);
        for (const [name, entry] of Object.entries(data.skills || {}) as [string, any][]) {
          const boost = entry.boostedLevel !== entry.level ? ` (boosted: ${entry.boostedLevel})` : "";
          lines.push(`  ${name}: ${entry.level}${boost} — ${entry.xp?.toLocaleString()} xp`);
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "live_inventory",
    "Get the current inventory contents in real-time. Use this when the user asks 'what do I have', 'what's in my inventory', 'do I have a [item]', or 'is my inventory full'.",
    {},
    async () => {
      try {
        const data = await apiGet("/api/inventory");
        const items = (data.items || []).filter((i: any) => i.id !== -1);
        if (items.length === 0)
          return { content: [{ type: "text" as const, text: "Inventory is empty." }] };
        const lines = [`# Inventory — ${items.length}/28 slots used`];
        for (const item of items) {
          const qty = item.quantity > 1 ? ` x${item.quantity.toLocaleString()}` : "";
          lines.push(`  [${item.slot}] ${item.name}${qty} (ID: ${item.id})`);
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "live_equipment",
    "Get currently worn equipment in real-time. Use this when the user asks 'what am I wearing', 'what's equipped', or 'what gear do I have on'.",
    {},
    async () => {
      try {
        const data = await apiGet("/api/equipment");
        const items = (data.items || []).filter((i: any) => i.id !== -1);
        if (items.length === 0)
          return { content: [{ type: "text" as const, text: "No equipment worn." }] };
        const lines = ["# Equipment"];
        for (const item of items) {
          lines.push(`  ${item.slot}: ${item.name} (ID: ${item.id})`);
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "live_bank",
    "Get bank contents in real-time. The bank must be open in-game for this to work. Use this when the user asks about their bank while it's open.",
    {},
    async () => {
      try {
        const data = await apiGet("/api/bank");
        const items = (data.items || []).filter((i: any) => i.id !== -1);
        if (items.length === 0)
          return {
            content: [
              {
                type: "text" as const,
                text: "Bank is empty or not currently open. Open the bank interface in-game to see contents.",
              },
            ],
          };
        const lines = [`# Bank — ${items.length} items`];
        for (const item of items.slice(0, 200)) {
          const qty = item.quantity > 1 ? ` x${item.quantity.toLocaleString()}` : "";
          lines.push(`  ${item.name}${qty} (ID: ${item.id})`);
        }
        if (items.length > 200) lines.push(`\n... and ${items.length - 200} more items`);
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "nearby_npcs",
    "Get NPCs near the player. Filter by name or ID. Use this when the user asks 'who is near me', 'what NPCs are around', 'is there a [NPC name] nearby', or mentions an NPC by name. Supports partial name matching (e.g. 'drag' matches 'Dragon').",
    {
      name: z.string().optional().describe("Filter NPCs by name (partial match, case-insensitive)"),
      id: z.number().optional().describe("Filter NPCs by exact NPC ID"),
    },
    async ({ name, id }) => {
      try {
        let path = "/api/npcs";
        const params: string[] = [];
        if (name) params.push(`name=${encodeURIComponent(name)}`);
        if (id !== undefined) params.push(`id=${id}`);
        if (params.length > 0) path += `?${params.join("&")}`;

        const data = await apiGet(path);
        const npcs = data.npcs || [];
        if (npcs.length === 0)
          return {
            content: [
              {
                type: "text" as const,
                text: name
                  ? `No NPCs matching "${name}" found nearby.`
                  : "No NPCs found nearby.",
              },
            ],
          };

        const lines = [`# Nearby NPCs — ${npcs.length} found`];
        for (const npc of npcs) {
          const pos = npc.position ? `(${npc.position.x}, ${npc.position.y})` : "unknown";
          const anim = npc.animation !== -1 ? ` anim:${npc.animation}` : "";
          const pose = npc.poseAnimation !== undefined ? ` pose:${npc.poseAnimation}` : "";
          lines.push(
            `  ${npc.name} (ID:${npc.id}) — lvl ${npc.combatLevel} — ${pos}${anim}${pose}`
          );
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "nearby_players",
    "Get other players visible near you. Use this when the user asks 'who else is here', 'any players nearby', or 'is [player name] near me'.",
    {},
    async () => {
      try {
        const data = await apiGet("/api/players");
        const players = data.players || [];
        if (players.length === 0)
          return { content: [{ type: "text" as const, text: "No other players visible nearby." }] };
        const lines = [`# Nearby Players — ${players.length} found`];
        for (const p of players) {
          const pos = p.position ? `(${p.position.x}, ${p.position.y})` : "unknown";
          const anim = p.animation !== -1 ? ` anim:${p.animation}` : "";
          lines.push(`  ${p.name} — lvl ${p.combatLevel} — ${pos}${anim}`);
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "nearby_objects",
    "Get game objects near the player (trees, rocks, doors, etc). Use this when the user asks 'what objects are around me', 'is there a [object] nearby', or 'what can I interact with'.",
    {
      radius: z.number().min(1).max(50).default(15).describe("Search radius in tiles"),
    },
    async ({ radius }) => {
      try {
        const data = await apiGet(`/api/objects?radius=${radius}`);
        const objects = data.objects || [];
        if (objects.length === 0)
          return { content: [{ type: "text" as const, text: "No objects found nearby." }] };
        const lines = [`# Nearby Objects — ${objects.length} found (radius ${radius})`];
        for (const obj of objects.slice(0, 100)) {
          const pos = `(${obj.worldX}, ${obj.worldY})`;
          lines.push(`  ${obj.name || "Unknown"} (ID:${obj.id}) — ${pos} [${obj.type}]`);
        }
        if (objects.length > 100) lines.push(`\n... and ${objects.length - 100} more objects`);
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "ground_items",
    "Get items on the ground near the player. Use this when the user asks 'what's on the ground', 'any drops nearby', or 'is there a [item] on the floor'.",
    {
      radius: z.number().min(1).max(50).default(15).describe("Search radius in tiles"),
    },
    async ({ radius }) => {
      try {
        const data = await apiGet(`/api/ground-items?radius=${radius}`);
        const items = data.items || [];
        if (items.length === 0)
          return { content: [{ type: "text" as const, text: "No items on the ground nearby." }] };
        const lines = [`# Ground Items — ${items.length} found (radius ${radius})`];
        for (const item of items) {
          const pos = `(${item.worldX}, ${item.worldY})`;
          const qty = item.quantity > 1 ? ` x${item.quantity.toLocaleString()}` : "";
          lines.push(`  ${item.name}${qty} (ID:${item.id}) — ${pos}`);
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "menu_entries",
    "Get the current right-click menu entries. Use this when the user asks 'what can I do here', 'what are my options', or 'what does the right-click menu show'.",
    {},
    async () => {
      try {
        const data = await apiGet("/api/menu-entries");
        const entries = data.entries || [];
        if (entries.length === 0)
          return { content: [{ type: "text" as const, text: "No menu entries available." }] };
        const lines = ["# Menu Entries"];
        for (const e of entries) {
          const target = e.target ? ` > ${e.target}` : "";
          lines.push(`  ${e.option}${target} [${e.type}]`);
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "camera",
    "Get the camera position and angles. Use this when the user asks about their view angle or camera settings.",
    {},
    async () => {
      try {
        const data = await apiGet("/api/camera");
        const lines = ["# Camera"];
        lines.push(`Yaw: ${data.yaw} | Pitch: ${data.pitch}`);
        lines.push(`Position: (${data.x}, ${data.y}, ${data.z})`);
        if (data.zoom !== undefined) lines.push(`Zoom: ${data.zoom}`);
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "tile_info",
    "Get collision flags and info for a specific tile. Use this to check if a tile is walkable or what flags it has.",
    {
      x: z.number().describe("World X coordinate"),
      y: z.number().describe("World Y coordinate"),
      plane: z.number().min(0).max(3).default(0).describe("Plane/floor level (0-3)"),
    },
    async ({ x, y, plane }) => {
      try {
        const data = await apiGet(`/api/tile?x=${x}&y=${y}&plane=${plane}`);
        return {
          content: [
            {
              type: "text" as const,
              text: `# Tile (${x}, ${y}, plane ${plane})\n\`\`\`json\n${JSON.stringify(data, null, 2)}\n\`\`\``,
            },
          ],
        };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "plugins",
    "List loaded RuneLite plugins and optionally get a plugin's configuration. Use this when the user asks about their RuneLite setup or plugin settings.",
    {
      plugin: z.string().optional().describe("Plugin name to get detailed config for"),
    },
    async ({ plugin }) => {
      try {
        if (plugin) {
          const data = await apiGet(`/api/plugin-config?plugin=${encodeURIComponent(plugin)}`);
          return {
            content: [
              {
                type: "text" as const,
                text: `# Plugin: ${plugin}\n\`\`\`json\n${JSON.stringify(data, null, 2)}\n\`\`\``,
              },
            ],
          };
        }
        const data = await apiGet("/api/plugins");
        const plugins = data.plugins || [];
        const lines = [`# Loaded Plugins — ${plugins.length}`];
        for (const p of plugins) {
          lines.push(`  ${p.name}${p.enabled ? "" : " (disabled)"}`);
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "player_appearance",
    "Get a player's visual appearance: equipment, body kits, colors, and gender. Use this when the user asks 'what does [player] look like' or about someone's gear.",
    {
      name: z.string().optional().describe("Player name. Omit for local player."),
    },
    async ({ name }) => {
      try {
        const path = name ? `/api/player-appearance?name=${encodeURIComponent(name)}` : "/api/player-appearance";
        const data = await apiGet(path);
        return {
          content: [
            {
              type: "text" as const,
              text: `# Player Appearance${name ? ` — ${name}` : ""}\n\`\`\`json\n${JSON.stringify(data, null, 2)}\n\`\`\``,
            },
          ],
        };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "chat",
    "Get recent chat messages from the game. Shows game messages, public chat, private messages, clan chat, and more. Use this when the user asks 'what did the game say', 'any chat messages', 'what did someone say', or wants to read recent chat history.",
    {
      type: z
        .string()
        .optional()
        .describe(
          "Filter by chat type: GAMEMESSAGE, PUBLICCHAT, PRIVATECHAT, PRIVATECHATOUT, FRIENDSCHAT, CLAN_CHAT, CLAN_MESSAGE, TRADE, SPAM, ENGINE, etc. Omit for all types."
        ),
      last: z
        .number()
        .min(1)
        .max(200)
        .optional()
        .describe("Limit to the last N messages. Omit to return all buffered messages (up to 200)."),
    },
    async ({ type, last }) => {
      try {
        const params: string[] = [];
        if (type) params.push(`type=${encodeURIComponent(type)}`);
        if (last) params.push(`last=${last}`);
        const query = params.length > 0 ? `?${params.join("&")}` : "";
        const data = await apiGet(`/api/chat${query}`);
        const messages = data.messages || [];
        if (messages.length === 0) {
          return {
            content: [{ type: "text" as const, text: "No chat messages found." }],
          };
        }
        const lines = [`# Chat Messages — ${messages.length}`];
        for (const m of messages) {
          const sender = m.sender ? `${m.sender}: ` : "";
          lines.push(`[${m.type}] ${sender}${m.message}`);
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );
}
