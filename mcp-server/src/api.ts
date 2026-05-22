/**
 * HTTP client for the RuneLite OSRS Companion plugin API.
 * The plugin runs a local HTTP server (default: http://localhost:8085).
 */

const API_BASE = process.env.OSRS_API_URL || "http://localhost:8085";

export async function apiGet(path: string): Promise<any> {
  const url = `${API_BASE}${path}`;
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(5000) });
    if (!res.ok) {
      const body = await res.text().catch(() => "");
      throw new Error(`API ${path}: ${res.status} ${body}`);
    }
    return res.json();
  } catch (err: any) {
    if (err?.name === "TimeoutError" || err?.code === "ECONNREFUSED") {
      throw new Error(
        `Cannot reach the OSRS Companion RuneLite plugin API at ${API_BASE}. ` +
          `Make sure RuneLite is running with the OSRS MCP Companion plugin enabled.`
      );
    }
    throw err;
  }
}

export function isApiError(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}

// ── Fuzzy matching utilities ──────────────────────────────────────────

/** Strip RuneLite HTML color tags like <col=ff9040> and </col> */
export function stripColorTags(text: string): string {
  return text.replace(/<\/?col[^>]*>/gi, "").trim();
}

/** Normalize a string for fuzzy matching: lowercase, strip tags, collapse whitespace */
export function normalize(text: string): string {
  return stripColorTags(text).toLowerCase().replace(/\s+/g, " ").trim();
}

/**
 * Fuzzy match: checks if query terms appear as substrings in the target.
 * Supports partial matching: "d scim" matches "Dragon scimitar".
 */
export function fuzzyMatch(target: string, query: string): boolean {
  const normalTarget = normalize(target);
  const normalQuery = normalize(query);

  // Exact substring match
  if (normalTarget.includes(normalQuery)) return true;

  // Split query into words and check if all appear in target
  const words = normalQuery.split(" ").filter((w) => w.length > 0);
  return words.every((word) => normalTarget.includes(word));
}
