import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { apiGet, isApiError } from "../api.js";

export function registerDefinitionTools(server: McpServer) {
  server.tool(
    "lookup_item",
    "Look up an item definition by ID or search by name. Returns item stats, options, and properties. Use this when the user asks about an item's details, what it does, or wants to look up an item ID.",
    {
      id: z.number().optional().describe("Item ID"),
      name: z.string().optional().describe("Item name to search for (partial match)"),
    },
    async ({ id, name }) => {
      try {
        if (id !== undefined) {
          const data = await apiGet(`/api/item-def?id=${id}`);
          return {
            content: [
              {
                type: "text" as const,
                text: `# Item Definition — ${data.name || `ID ${id}`}\n\`\`\`json\n${JSON.stringify(data, null, 2)}\n\`\`\``,
              },
            ],
          };
        }
        if (name) {
          const data = await apiGet(`/api/item-def?name=${encodeURIComponent(name)}`);
          const results = data.results || [];
          if (results.length === 0)
            return {
              content: [{ type: "text" as const, text: `No items matching "${name}" found.` }],
            };
          const lines = [`# Item Search — "${name}" — ${results.length} result(s)`];
          for (const item of results.slice(0, 25)) {
            lines.push(`  ${item.name} (ID: ${item.id})${item.members ? " [P2P]" : ""}`);
          }
          if (results.length > 25)
            lines.push(`\n... and ${results.length - 25} more results`);
          return { content: [{ type: "text" as const, text: lines.join("\n") }] };
        }
        return {
          content: [{ type: "text" as const, text: "Provide either an item ID or name to search." }],
        };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "lookup_npc",
    "Look up an NPC definition by ID or search by name. Returns NPC stats, options, and combat level. Use this to learn about an NPC's properties.",
    {
      id: z.number().optional().describe("NPC ID"),
      name: z.string().optional().describe("NPC name to search for (partial match)"),
    },
    async ({ id, name }) => {
      try {
        if (id !== undefined) {
          const data = await apiGet(`/api/npc-def?id=${id}`);
          return {
            content: [
              {
                type: "text" as const,
                text: `# NPC Definition — ${data.name || `ID ${id}`}\n\`\`\`json\n${JSON.stringify(data, null, 2)}\n\`\`\``,
              },
            ],
          };
        }
        if (name) {
          const data = await apiGet(`/api/npc-def?name=${encodeURIComponent(name)}`);
          const results = data.results || [];
          if (results.length === 0)
            return {
              content: [{ type: "text" as const, text: `No NPCs matching "${name}" found.` }],
            };
          const lines = [`# NPC Search — "${name}" — ${results.length} result(s)`];
          for (const npc of results.slice(0, 25)) {
            lines.push(
              `  ${npc.name} (ID: ${npc.id}) — Combat: ${npc.combatLevel}`
            );
          }
          if (results.length > 25)
            lines.push(`\n... and ${results.length - 25} more results`);
          return { content: [{ type: "text" as const, text: lines.join("\n") }] };
        }
        return {
          content: [{ type: "text" as const, text: "Provide either an NPC ID or name to search." }],
        };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "lookup_object",
    "Look up a game object definition by ID or search by name. Returns object actions, size, and properties.",
    {
      id: z.number().optional().describe("Object ID"),
      name: z.string().optional().describe("Object name to search for (partial match)"),
    },
    async ({ id, name }) => {
      try {
        if (id !== undefined) {
          const data = await apiGet(`/api/obj-def?id=${id}`);
          return {
            content: [
              {
                type: "text" as const,
                text: `# Object Definition — ${data.name || `ID ${id}`}\n\`\`\`json\n${JSON.stringify(data, null, 2)}\n\`\`\``,
              },
            ],
          };
        }
        if (name) {
          const data = await apiGet(`/api/obj-def?name=${encodeURIComponent(name)}`);
          const results = data.results || [];
          if (results.length === 0)
            return {
              content: [{ type: "text" as const, text: `No objects matching "${name}" found.` }],
            };
          const lines = [`# Object Search — "${name}" — ${results.length} result(s)`];
          for (const obj of results.slice(0, 25)) {
            lines.push(`  ${obj.name} (ID: ${obj.id})`);
          }
          if (results.length > 25)
            lines.push(`\n... and ${results.length - 25} more results`);
          return { content: [{ type: "text" as const, text: lines.join("\n") }] };
        }
        return {
          content: [
            { type: "text" as const, text: "Provide either an object ID or name to search." },
          ],
        };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "lookup_varbit",
    "Look up a varbit definition: which varp it maps to, bit positions, and current value. Use this for understanding game state variables.",
    {
      id: z.number().describe("Varbit ID"),
    },
    async ({ id }) => {
      try {
        const data = await apiGet(`/api/varbit-def?id=${id}`);
        return {
          content: [
            {
              type: "text" as const,
              text: `# Varbit ${id}\n\`\`\`json\n${JSON.stringify(data, null, 2)}\n\`\`\``,
            },
          ],
        };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "lookup_enum",
    "Look up an enum definition (key-value pairs used by game interfaces). Returns enum type information and all key-value entries.",
    {
      id: z.number().describe("Enum ID"),
    },
    async ({ id }) => {
      try {
        const data = await apiGet(`/api/enum-def?id=${id}`);
        return {
          content: [
            {
              type: "text" as const,
              text: `# Enum ${id}\n\`\`\`json\n${JSON.stringify(data, null, 2)}\n\`\`\``,
            },
          ],
        };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "lookup_struct",
    "Look up a struct definition (param key-value pairs used by game definitions). Returns struct parameters.",
    {
      id: z.number().describe("Struct ID"),
    },
    async ({ id }) => {
      try {
        const data = await apiGet(`/api/struct-def?id=${id}`);
        return {
          content: [
            {
              type: "text" as const,
              text: `# Struct ${id}\n\`\`\`json\n${JSON.stringify(data, null, 2)}\n\`\`\``,
            },
          ],
        };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );
}
