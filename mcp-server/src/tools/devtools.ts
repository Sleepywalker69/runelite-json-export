import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { apiGet, isApiError } from "../api.js";

export function registerDevTools(server: McpServer) {
  server.tool(
    "read_varbit",
    "Read the current value of a varbit or range of varbits. Varbits store game state like quest progress, unlocks, and toggle settings.",
    {
      id: z.number().optional().describe("Single varbit ID to read"),
      from: z.number().optional().describe("Start of varbit range"),
      to: z.number().optional().describe("End of varbit range"),
    },
    async ({ id, from, to }) => {
      try {
        let path = "/api/varbit";
        if (id !== undefined) path += `?id=${id}`;
        else if (from !== undefined && to !== undefined) path += `?from=${from}&to=${to}`;
        else return { content: [{ type: "text" as const, text: "Provide either id or from+to range." }] };
        const data = await apiGet(path);
        return {
          content: [
            {
              type: "text" as const,
              text: `# Varbit Value(s)\n\`\`\`json\n${JSON.stringify(data, null, 2)}\n\`\`\``,
            },
          ],
        };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "read_varp",
    "Read the current value of a varp (player variable) or range of varps. Varps are the underlying storage that varbits map into.",
    {
      id: z.number().optional().describe("Single varp ID to read"),
      from: z.number().optional().describe("Start of varp range"),
      to: z.number().optional().describe("End of varp range"),
    },
    async ({ id, from, to }) => {
      try {
        let path = "/api/varp";
        if (id !== undefined) path += `?id=${id}`;
        else if (from !== undefined && to !== undefined) path += `?from=${from}&to=${to}`;
        else return { content: [{ type: "text" as const, text: "Provide either id or from+to range." }] };
        const data = await apiGet(path);
        return {
          content: [
            {
              type: "text" as const,
              text: `# Varp Value(s)\n\`\`\`json\n${JSON.stringify(data, null, 2)}\n\`\`\``,
            },
          ],
        };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "var_history",
    "Get recent var (varbit/varp) changes with old and new values. Use this to see what game state changed recently, debug quest progression, or track toggle changes. Each entry shows the tick, type (varbit or varp), ID, and old/new values.",
    {
      type: z
        .enum(["varbit", "varp"])
        .optional()
        .describe("Filter by var type: 'varbit' or 'varp'"),
      last: z.number().min(1).max(200).default(50).describe("Number of recent changes to return"),
    },
    async ({ type, last }) => {
      try {
        let path = `/api/var-history?last=${last}`;
        if (type) path += `&type=${type}`;
        const data = await apiGet(path);
        const entries = data.history || data.entries || [];
        if (entries.length === 0)
          return {
            content: [
              {
                type: "text" as const,
                text: "No var changes recorded yet. Changes are tracked while the plugin is running.",
              },
            ],
          };
        const lines = [`# Var Change History — ${entries.length} entries`];
        for (const e of entries) {
          lines.push(
            `  [tick ${e.tick}] ${e.type} ${e.id}: ${e.oldValue} → ${e.newValue}`
          );
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "recent_interactions",
    "Get recent user clicks and hover interactions. Use this automatically when the user asks 'what did I just click', 'what am I looking at', 'what is this', 'what did I just do', or 'what happened'. Shows the last N click and hover events with their targets.",
    {
      last: z.number().min(1).max(200).default(20).describe("Number of recent interactions to return"),
    },
    async ({ last }) => {
      try {
        const data = await apiGet(`/api/interaction-history?last=${last}`);
        const entries = data.history || data.entries || [];
        if (entries.length === 0)
          return {
            content: [
              {
                type: "text" as const,
                text: "No interactions recorded yet. Click or hover over things in-game to see them here.",
              },
            ],
          };
        const lines = [`# Recent Interactions — ${entries.length} entries`];
        for (const e of entries) {
          const icon = e.type === "click" ? "🖱️" : "👁️";
          lines.push(
            `  ${icon} [tick ${e.tick}] ${e.action} → ${e.target}${e.menuAction ? ` [${e.menuAction}]` : ""}`
          );
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "graphics_objects",
    "Get active visual effects on the game world (spell impacts, special attacks, area effects, etc). Use this when the user asks 'what visual effects are active', 'what spell was cast', or to identify on-screen animations.",
    {},
    async () => {
      try {
        const data = await apiGet("/api/graphics-objects");
        const objects = data.graphicsObjects || [];
        if (objects.length === 0)
          return {
            content: [
              { type: "text" as const, text: "No active graphics objects (visual effects) on screen." },
            ],
          };
        const lines = [`# Graphics Objects — ${objects.length} active`];
        for (const obj of objects) {
          const pos = obj.worldPoint
            ? `(${obj.worldPoint.x}, ${obj.worldPoint.y})`
            : `local(${obj.localX}, ${obj.localY})`;
          lines.push(
            `  GFX ${obj.id} — ${pos} — started cycle ${obj.startCycle}${obj.finished ? " (finished)" : ""}`
          );
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "xp_tracker",
    "Get XP gains this session — total and per-skill breakdown. Use this when the user asks 'how much XP have I gained', 'what's my XP rate', or 'show my session stats'.",
    {},
    async () => {
      try {
        const data = await apiGet("/api/xp-tracker");
        const lines = ["# XP Tracker — This Session"];
        lines.push(`Session Duration: ${data.sessionDuration || "N/A"}`);
        lines.push(`Total XP Gained: ${(data.totalXpGained ?? 0).toLocaleString()}`);
        if (data.skills) {
          lines.push("\n## Per-Skill Gains");
          for (const [skill, info] of Object.entries(data.skills || {}) as [string, any][]) {
            if (info.gained > 0) {
              lines.push(`  ${skill}: +${info.gained.toLocaleString()} xp`);
            }
          }
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "loot_log",
    "Get loot drops received this session. Filter by NPC name. Use this when the user asks 'what drops have I gotten', 'what did I get from [NPC]', or 'show my loot'.",
    {
      npc: z.string().optional().describe("Filter by NPC name (partial match)"),
    },
    async ({ npc }) => {
      try {
        let path = "/api/loot";
        if (npc) path += `?npc=${encodeURIComponent(npc)}`;
        const data = await apiGet(path);
        const drops = data.drops || [];
        if (drops.length === 0)
          return {
            content: [
              {
                type: "text" as const,
                text: npc
                  ? `No loot received from "${npc}" this session.`
                  : "No loot received this session.",
              },
            ],
          };
        const lines = [`# Loot Log — ${drops.length} drops`];
        for (const drop of drops) {
          lines.push(`\n**${drop.npcName}** (lvl ${drop.npcCombatLevel}):`);
          for (const item of drop.items || []) {
            const qty = item.quantity > 1 ? ` x${item.quantity.toLocaleString()}` : "";
            lines.push(`  ${item.name || `ID:${item.itemId}`}${qty}`);
          }
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );
}
