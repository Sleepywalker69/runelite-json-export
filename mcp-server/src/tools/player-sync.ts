import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { homedir } from "node:os";

const SYNC_DIR = join(homedir(), ".runelite", "osrs-companion");
const USER_AGENT = "osrs-companion/2.0 (Node.js; RuneLite MCP plugin)";

// ── WikiSync Player Cache ──────────────────────────────────────────
const playerDataCache: Record<string, { data: any; fetchedAt: number }> = {};

async function fetchWikiSyncPlayer(
  username: string,
  forceRefresh = false
): Promise<{ data: any; message?: string }> {
  const now = Date.now();
  const cache = playerDataCache[username];
  if (cache && !forceRefresh && now - cache.fetchedAt < 3600_000) {
    return { data: cache.data };
  }
  const url = `https://sync.runescape.wiki/runelite/player/${encodeURIComponent(username)}/STANDARD`;
  try {
    const resp = await fetch(url, { headers: { "User-Agent": USER_AGENT } });
    if (!resp.ok) return { data: null, message: `WikiSync API returned ${resp.status}` };
    const data = await resp.json();
    if (!data || Object.keys(data as any).length === 0) {
      return {
        data: null,
        message:
          "No player data found. Ensure the username is correct and the WikiSync RuneLite plugin is installed.",
      };
    }
    playerDataCache[username] = { data, fetchedAt: now };
    return { data };
  } catch (err: any) {
    return { data: null, message: `Error: ${err?.message ?? "Unknown error"}` };
  }
}

// ── Local file helpers ──────────────────────────────────────────────
async function getPlayerSyncData(username: string): Promise<any | null> {
  const filename = username.toLowerCase().replace(/[^a-z0-9_-]/g, "_") + ".json";
  const filepath = join(SYNC_DIR, filename);
  try {
    const raw = await readFile(filepath, "utf-8");
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

async function listSyncedPlayers(): Promise<string[]> {
  try {
    const files = await readdir(SYNC_DIR);
    return files.filter((f) => f.endsWith(".json")).map((f) => f.replace(/\.json$/, ""));
  } catch {
    return [];
  }
}

export function registerPlayerSyncTools(server: McpServer) {
  server.tool(
    "player",
    "Fetch player data via the WikiSync plugin (external API). Use this for players NOT currently logged in or for historical data.",
    {
      username: z.string().describe("RuneScape username"),
      forceRefresh: z.boolean().default(false).describe("Force refresh cached data"),
    },
    async ({ username, forceRefresh }) => {
      if (!username.trim()) {
        return { content: [{ type: "text" as const, text: "Please provide a username." }] };
      }
      const { data, message } = await fetchWikiSyncPlayer(username, forceRefresh);
      if (!data) {
        return { content: [{ type: "text" as const, text: message ?? "No player data found." }] };
      }
      return {
        content: [
          {
            type: "text" as const,
            text: `# ${username} — Player Data (via WikiSync)\n\n\`\`\`json\n${JSON.stringify(data, null, 2)}\n\`\`\``,
          },
        ],
      };
    }
  );

  server.tool(
    "list_synced_players",
    "List all players with locally synced data from the RuneLite plugin. Use this first to find available usernames for other tools.",
    {},
    async () => {
      const players = await listSyncedPlayers();
      if (players.length === 0) {
        return {
          content: [
            {
              type: "text" as const,
              text: `No synced players found. Make sure the OSRS MCP Companion RuneLite plugin is running and you've logged in.\n\nExpected data directory: ${SYNC_DIR}`,
            },
          ],
        };
      }
      return { content: [{ type: "text" as const, text: `Synced players: ${players.join(", ")}` }] };
    }
  );

  server.tool(
    "get_my_profile",
    "Get a full summary of synced player data including stats, quest count, bank size, and diary progress. This reads from locally saved data (not real-time). For live data, use the live_ tools instead.",
    {
      username: z.string().describe("Player username"),
    },
    async ({ username }) => {
      const data = await getPlayerSyncData(username);
      if (!data) {
        const players = await listSyncedPlayers();
        const hint = players.length > 0 ? ` Available players: ${players.join(", ")}` : "";
        return {
          content: [
            {
              type: "text" as const,
              text: `No synced data found for "${username}".${hint}\n\nMake sure the OSRS MCP Companion RuneLite plugin is running.`,
            },
          ],
        };
      }
      const lines = [`# ${data.player.username} — Synced Profile`];
      lines.push(`Combat Level: ${data.player.combatLevel} | World: ${data.player.world}`);
      lines.push(`Last Updated: ${data.lastUpdated}`);
      if (data.skills) {
        const totalLevel =
          data.skills.OVERALL?.level ??
          Object.values(data.skills as Record<string, any>).reduce(
            (sum: number, s: any) => sum + s.level,
            0
          );
        lines.push(`\n## Skills — Total Level: ${totalLevel}`);
        for (const [skill, entry] of Object.entries(data.skills) as [string, any][]) {
          if (skill === "OVERALL") continue;
          lines.push(`  ${skill}: ${entry.level} (${entry.xp.toLocaleString()} xp)`);
        }
      }
      if (data.quests) {
        const finished = data.quests.filter((q: any) => q.state === "FINISHED").length;
        const inProgress = data.quests.filter((q: any) => q.state === "IN_PROGRESS").length;
        const notStarted = data.quests.filter((q: any) => q.state === "NOT_STARTED").length;
        lines.push(
          `\n## Quests — ${finished} complete, ${inProgress} in progress, ${notStarted} not started`
        );
      }
      if (data.bank) {
        lines.push(
          `\n## Bank — ${data.bank.totalItems} unique items across ${data.bank.tabs.length} tabs`
        );
      }
      if (data.achievementDiaries) {
        lines.push("\n## Achievement Diaries");
        for (const [region, diary] of Object.entries(data.achievementDiaries) as [string, any][]) {
          const tiers = [
            diary.easy ? "Easy" : null,
            diary.medium ? "Medium" : null,
            diary.hard ? "Hard" : null,
            diary.elite ? "Elite" : null,
          ].filter(Boolean);
          lines.push(`  ${region}: ${tiers.length > 0 ? tiers.join(", ") : "None complete"}`);
        }
      }
      if (data.combatAchievements) {
        const ca = data.combatAchievements;
        lines.push(`\n## Combat Achievements — ${ca.completedTasks.length} tasks complete`);
      }
      return { content: [{ type: "text" as const, text: lines.join("\n") }] };
    }
  );

  server.tool(
    "get_my_bank",
    "Search and browse the player's synced bank contents. This reads from locally saved data. For the live bank (must be open in-game), use live_bank instead.",
    {
      username: z.string().describe("Player username"),
      search: z.string().optional().describe("Search term to filter items by name"),
      tab: z.number().optional().describe("Bank tab number (0-indexed)"),
      minQuantity: z.number().optional().describe("Minimum quantity filter"),
    },
    async ({ username, search, tab, minQuantity }) => {
      const data = await getPlayerSyncData(username);
      if (!data)
        return { content: [{ type: "text" as const, text: `No synced data found for "${username}".` }] };
      if (!data.bank?.tabs)
        return {
          content: [
            { type: "text" as const, text: `No bank data synced for "${username}". Open your bank in-game.` },
          ],
        };
      let allItems = data.bank.tabs.flatMap((t: any) =>
        t.items.map((item: any) => ({ ...item, tab: t.tabIndex }))
      );
      if (search) {
        const term = search.toLowerCase();
        allItems = allItems.filter((item: any) => item.name.toLowerCase().includes(term));
      }
      if (tab !== undefined) allItems = allItems.filter((item: any) => item.tab === tab);
      if (minQuantity !== undefined)
        allItems = allItems.filter((item: any) => item.quantity >= minQuantity);
      if (allItems.length === 0)
        return {
          content: [{ type: "text" as const, text: `No matching items found in ${username}'s bank.` }],
        };
      const lines = [`# ${username}'s Bank — ${allItems.length} items found`];
      for (const item of allItems) {
        const qty = item.quantity > 1 ? ` x${item.quantity.toLocaleString()}` : "";
        lines.push(`  [Tab ${item.tab}] ${item.name}${qty} (ID: ${item.itemId})`);
      }
      return { content: [{ type: "text" as const, text: lines.join("\n") }] };
    }
  );

  server.tool(
    "get_my_stats",
    "Get the player's synced skill levels and XP from locally saved data. For real-time levels, use live_skills instead.",
    {
      username: z.string().describe("Player username"),
      skill: z.string().optional().describe("Specific skill name (e.g. 'ATTACK', 'MINING')"),
    },
    async ({ username, skill }) => {
      const data = await getPlayerSyncData(username);
      if (!data)
        return { content: [{ type: "text" as const, text: `No synced data found for "${username}".` }] };
      if (!data.skills)
        return { content: [{ type: "text" as const, text: `No skill data synced for "${username}".` }] };
      if (skill) {
        const key = skill.toUpperCase();
        const entry = data.skills[key];
        if (!entry)
          return {
            content: [
              {
                type: "text" as const,
                text: `Skill "${skill}" not found. Available: ${Object.keys(data.skills).join(", ")}`,
              },
            ],
          };
        return {
          content: [
            {
              type: "text" as const,
              text: `# ${username} — ${key}\nLevel: ${entry.level}\nXP: ${entry.xp.toLocaleString()}`,
            },
          ],
        };
      }
      const lines = [`# ${username}'s Skills`];
      for (const [name, entry] of Object.entries(data.skills) as [string, any][]) {
        lines.push(`  ${name}: ${entry.level} (${entry.xp.toLocaleString()} xp)`);
      }
      return { content: [{ type: "text" as const, text: lines.join("\n") }] };
    }
  );

  server.tool(
    "get_my_quests",
    "Get the player's synced quest completion status from locally saved data.",
    {
      username: z.string().describe("Player username"),
      state: z
        .enum(["NOT_STARTED", "IN_PROGRESS", "FINISHED"])
        .optional()
        .describe("Filter by quest state"),
      search: z.string().optional().describe("Search term to filter by quest name"),
    },
    async ({ username, state, search }) => {
      const data = await getPlayerSyncData(username);
      if (!data)
        return { content: [{ type: "text" as const, text: `No synced data found for "${username}".` }] };
      if (!data.quests)
        return { content: [{ type: "text" as const, text: `No quest data synced for "${username}".` }] };
      let quests = data.quests;
      if (state) quests = quests.filter((q: any) => q.state === state);
      if (search) {
        const term = search.toLowerCase();
        quests = quests.filter((q: any) => q.displayName.toLowerCase().includes(term));
      }
      if (quests.length === 0) return { content: [{ type: "text" as const, text: "No matching quests found." }] };
      const lines = [`# ${username}'s Quests — ${quests.length} results`];
      for (const q of quests) {
        const icon =
          q.state === "FINISHED"
            ? "[Done]"
            : q.state === "IN_PROGRESS"
              ? "[In Progress]"
              : "[Not Started]";
        lines.push(`  ${icon} ${q.displayName}`);
      }
      return { content: [{ type: "text" as const, text: lines.join("\n") }] };
    }
  );

  server.tool(
    "get_my_equipment",
    "Get the player's equipped items from locally saved data. For live equipment, use live_equipment.",
    { username: z.string().describe("Player username") },
    async ({ username }) => {
      const data = await getPlayerSyncData(username);
      if (!data)
        return { content: [{ type: "text" as const, text: `No synced data found for "${username}".` }] };
      if (!data.equipment)
        return { content: [{ type: "text" as const, text: `No equipment data synced for "${username}".` }] };
      const lines = [`# ${username}'s Equipment`];
      for (const [slot, item] of Object.entries(data.equipment) as [string, any][]) {
        lines.push(
          item.itemId === -1 ? `  ${slot}: (empty)` : `  ${slot}: ${item.name} (ID: ${item.itemId})`
        );
      }
      return { content: [{ type: "text" as const, text: lines.join("\n") }] };
    }
  );

  server.tool(
    "get_my_inventory",
    "Get the player's inventory from locally saved data. For live inventory, use live_inventory.",
    { username: z.string().describe("Player username") },
    async ({ username }) => {
      const data = await getPlayerSyncData(username);
      if (!data)
        return { content: [{ type: "text" as const, text: `No synced data found for "${username}".` }] };
      if (!data.inventory)
        return { content: [{ type: "text" as const, text: `No inventory data synced for "${username}".` }] };
      const items = data.inventory.filter((i: any) => i.itemId !== -1);
      if (items.length === 0)
        return { content: [{ type: "text" as const, text: `${username}'s inventory is empty.` }] };
      const lines = [`# ${username}'s Inventory — ${items.length} items`];
      for (const item of items) {
        const qty = item.quantity > 1 ? ` x${item.quantity.toLocaleString()}` : "";
        lines.push(`  [Slot ${item.slot}] ${item.name}${qty}`);
      }
      return { content: [{ type: "text" as const, text: lines.join("\n") }] };
    }
  );

  server.tool(
    "get_my_diaries",
    "Get the player's achievement diary completion status from locally saved data.",
    {
      username: z.string().describe("Player username"),
      region: z.string().optional().describe("Specific diary region"),
    },
    async ({ username, region }) => {
      const data = await getPlayerSyncData(username);
      if (!data)
        return { content: [{ type: "text" as const, text: `No synced data found for "${username}".` }] };
      if (!data.achievementDiaries)
        return { content: [{ type: "text" as const, text: `No diary data synced for "${username}".` }] };
      let diaries = Object.entries(data.achievementDiaries) as [string, any][];
      if (region) {
        const key = region.toUpperCase();
        diaries = diaries.filter(([r]) => r.toUpperCase() === key);
        if (diaries.length === 0)
          return {
            content: [
              {
                type: "text" as const,
                text: `Region "${region}" not found. Available: ${Object.keys(data.achievementDiaries).join(", ")}`,
              },
            ],
          };
      }
      const lines = [`# ${username}'s Achievement Diaries`];
      for (const [name, diary] of diaries) {
        const check = (v: boolean) => (v ? "Done" : "---");
        lines.push(
          `  ${name}: Easy=${check(diary.easy)} | Med=${check(diary.medium)} | Hard=${check(diary.hard)} | Elite=${check(diary.elite)}`
        );
      }
      return { content: [{ type: "text" as const, text: lines.join("\n") }] };
    }
  );

  server.tool(
    "get_my_combat_achievements",
    "Get the player's combat achievement completion status from locally saved data.",
    {
      username: z.string().describe("Player username"),
      search: z.string().optional().describe("Search term to filter by task name"),
    },
    async ({ username, search }) => {
      const data = await getPlayerSyncData(username);
      if (!data)
        return { content: [{ type: "text" as const, text: `No synced data found for "${username}".` }] };
      if (!data.combatAchievements)
        return {
          content: [
            { type: "text" as const, text: `No combat achievement data synced for "${username}".` },
          ],
        };
      const ca = data.combatAchievements;
      const lines = [`# ${username}'s Combat Achievements`];
      lines.push(
        `Easy: ${ca.easyComplete ? "Complete" : "Incomplete"} | Medium: ${ca.mediumComplete ? "Complete" : "Incomplete"} | Hard: ${ca.hardComplete ? "Complete" : "Incomplete"} | Elite: ${ca.eliteComplete ? "Complete" : "Incomplete"}`
      );
      lines.push(`Completed tasks: ${ca.completedTasks.length}`);
      let tasks = ca.completedTasks;
      if (search) {
        const term = search.toLowerCase();
        tasks = tasks.filter((t: string) => t.toLowerCase().includes(term));
        lines.push(`\nMatching "${search}": ${tasks.length} tasks`);
      }
      if (tasks.length > 0 && tasks.length <= 100) {
        lines.push("");
        for (const task of tasks) lines.push(`  [Done] ${task}`);
      } else if (tasks.length > 100) {
        lines.push(`\nToo many tasks to display (${tasks.length}). Use search to filter.`);
      }
      return { content: [{ type: "text" as const, text: lines.join("\n") }] };
    }
  );
}
