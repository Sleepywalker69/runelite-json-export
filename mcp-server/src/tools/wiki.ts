import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";

const WIKI_API = "https://oldschool.runescape.wiki/api.php";
const PRICES_API = "https://prices.runescape.wiki/api/v1/osrs";
const USER_AGENT =
  "osrs-companion/2.0 (Node.js; RuneLite MCP plugin)";
const WIKI_ATTRIBUTION =
  "\n\n---\nContent from the [Old School RuneScape Wiki](https://oldschool.runescape.wiki), licensed under [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/).";

function pageUrl(title: string): string {
  return `https://oldschool.runescape.wiki/w/${encodeURIComponent(title.replace(/ /g, "_"))}`;
}

function stripHtml(text: string): string {
  return text
    .replace(/<[^>]+>/g, "")
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, "&");
}

async function wikiFetch(params: Record<string, string>): Promise<any> {
  const url = `${WIKI_API}?${new URLSearchParams({ format: "json", ...params })}`;
  const res = await fetch(url, { headers: { "User-Agent": USER_AGENT } });
  if (!res.ok) throw new Error(`Wiki API returned ${res.status}`);
  return res.json();
}

async function pricesFetch(path: string): Promise<any> {
  const res = await fetch(`${PRICES_API}/${path}`, {
    headers: { "User-Agent": USER_AGENT },
  });
  if (!res.ok) throw new Error(`Prices API returned ${res.status}`);
  return res.json();
}

// ── Item Mapping Cache ──────────────────────────────────────────────
let itemMappingCache: Record<string, string> | null = null;
let itemMappingExpiry = 0;
const CACHE_TTL = 1000 * 60 * 60;

async function getItemMapping(): Promise<Record<string, string>> {
  if (itemMappingCache && Date.now() < itemMappingExpiry) return itemMappingCache;
  const data = await pricesFetch("mapping");
  const mapping: Record<string, string> = {};
  if (Array.isArray(data)) {
    for (const item of data) mapping[String(item.id)] = item.name;
  }
  itemMappingCache = mapping;
  itemMappingExpiry = Date.now() + CACHE_TTL;
  return mapping;
}

async function findItemId(name: string): Promise<string | null> {
  const mapping = await getItemMapping();
  const lower = name.toLowerCase();
  for (const [id, itemName] of Object.entries(mapping)) {
    if (itemName.toLowerCase() === lower) return id;
  }
  for (const [id, itemName] of Object.entries(mapping)) {
    if (itemName.toLowerCase().includes(lower)) return id;
  }
  return null;
}

function formatTimeAgo(unixSeconds: number): string {
  const diff = Math.floor(Date.now() / 1000) - unixSeconds;
  if (diff < 60) return "just now";
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
  return `${Math.floor(diff / 86400)}d ago`;
}

export function registerWikiTools(server: McpServer) {
  server.tool(
    "search",
    "Search the Old School RuneScape Wiki for articles matching a query. Use this when the user asks about game mechanics, items, monsters, quests, or any OSRS topic.",
    {
      query: z.string().describe("Search query (e.g. 'dragon scimitar', 'Zulrah')"),
      limit: z.number().min(1).max(50).default(10).describe("Max results (1-50)"),
    },
    async ({ query, limit }) => {
      const data = await wikiFetch({
        action: "query",
        list: "search",
        srsearch: query,
        srlimit: String(limit),
      });
      const results = data.query?.search ?? [];
      if (!results.length) {
        return { content: [{ type: "text" as const, text: `No results found for "${query}"` }] };
      }
      const lines = results.map((item: any, i: number) => {
        const snippet = stripHtml(item.snippet);
        return `${i + 1}. **${item.title}**\n   ${snippet}\n   ${pageUrl(item.title)}`;
      });
      return {
        content: [
          {
            type: "text" as const,
            text: `Found ${results.length} results:\n\n${lines.join("\n\n")}${WIKI_ATTRIBUTION}`,
          },
        ],
      };
    }
  );

  server.tool(
    "summary",
    "Get the introductory summary of an OSRS Wiki page. Use this to learn about specific items, monsters, skills, or game mechanics.",
    {
      title: z.string().describe("Exact page title (e.g. 'Abyssal whip', 'Farming')"),
    },
    async ({ title }) => {
      const data = await wikiFetch({
        action: "query",
        prop: "extracts",
        exintro: "1",
        explaintext: "1",
        formatversion: "2",
        titles: title,
      });
      const page = data.query?.pages?.[0];
      if (!page || page.missing) {
        return { content: [{ type: "text" as const, text: `Page not found: "${title}"` }] };
      }
      const extract = page.extract?.trim();
      if (!extract) {
        return {
          content: [{ type: "text" as const, text: `No summary available for "${page.title}"` }],
        };
      }
      return {
        content: [
          {
            type: "text" as const,
            text: `# ${page.title}\n\n${extract}\n\n${pageUrl(page.title)}${WIKI_ATTRIBUTION}`,
          },
        ],
      };
    }
  );

  server.tool(
    "price",
    "Look up the current Grand Exchange price for an item. Use this when the user asks how much something costs, what an item is worth, or for price checks.",
    {
      item: z.string().describe("Item name (e.g. 'Abyssal whip', 'Dragon bones')"),
    },
    async ({ item }) => {
      const itemId = await findItemId(item);
      if (!itemId) {
        return {
          content: [
            { type: "text" as const, text: `Item not found: "${item}". Try the exact in-game name.` },
          ],
        };
      }
      const data = await pricesFetch(`latest?id=${itemId}`);
      const price = data.data?.[itemId];
      if (!price) {
        return {
          content: [{ type: "text" as const, text: `No price data available for "${item}"` }],
        };
      }
      const mapping = await getItemMapping();
      const name = mapping[itemId] ?? item;
      const lines = [`# ${name} — Grand Exchange Price`];
      if (price.high != null) {
        const ago = price.highTime ? ` (${formatTimeAgo(price.highTime)})` : "";
        lines.push(`Buy (instant): ${price.high.toLocaleString()} gp${ago}`);
      }
      if (price.low != null) {
        const ago = price.lowTime ? ` (${formatTimeAgo(price.lowTime)})` : "";
        lines.push(`Sell (instant): ${price.low.toLocaleString()} gp${ago}`);
      }
      lines.push("", pageUrl(name));
      return { content: [{ type: "text" as const, text: lines.join("\n") }] };
    }
  );
}
