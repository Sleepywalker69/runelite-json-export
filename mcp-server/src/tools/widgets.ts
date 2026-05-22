import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { apiGet, isApiError, fuzzyMatch, stripColorTags } from "../api.js";

/** Known container interface groups and their item child indices */
const CONTAINER_INTERFACES: Record<number, { name: string; itemChild?: number }> = {
  12: { name: "Bank", itemChild: 13 },
  149: { name: "Inventory", itemChild: 0 },
  270: { name: "Shop", itemChild: 15 },
  300: { name: "Quest Journal" },
  334: { name: "Trade Partner", itemChild: 0 },
  387: { name: "Equipment", itemChild: 28 },
  399: { name: "Grand Exchange" },
  467: { name: "Grand Exchange Collection" },
  621: { name: "Seed Vault", itemChild: 1 },
};

export function registerWidgetTools(server: McpServer) {
  server.tool(
    "interfaces",
    "List all currently open/visible game interfaces. Use this when the user asks 'what's open', 'what menu is this', 'what interface am I looking at', or 'what windows are open'.",
    {},
    async () => {
      try {
        const data = await apiGet("/api/interfaces");
        const interfaces = data.interfaces || [];
        if (interfaces.length === 0)
          return { content: [{ type: "text" as const, text: "No interfaces currently open." }] };
        const lines = [`# Open Interfaces — ${interfaces.length}`];
        for (const iface of interfaces) {
          const name = iface.name ? ` — ${iface.name}` : "";
          lines.push(`  Group ${iface.groupId}${name}${iface.visible ? "" : " (hidden)"}`);
        }
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "inspect_widget",
    "Read a specific widget group's children, items, text, and actions. Use this to examine the contents of a specific game interface by group ID and optional child index.",
    {
      group: z.number().describe("Widget group ID (e.g. 270 for shop, 12 for bank)"),
      child: z.number().optional().describe("Optional child widget index within the group"),
    },
    async ({ group, child }) => {
      try {
        let path = `/api/widget?group=${group}`;
        if (child !== undefined) path += `&child=${child}`;
        const data = await apiGet(path);
        return {
          content: [
            {
              type: "text" as const,
              text: `# Widget ${group}${child !== undefined ? `:${child}` : ""}\n\`\`\`json\n${JSON.stringify(data, null, 2)}\n\`\`\``,
            },
          ],
        };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "find_item_in_interface",
    "Find an item by name in any open container interface (shop, bank, trade, inventory, etc). Accepts partial item names — the user does not need to type the exact name. Use this when the user asks to find, buy, sell, or locate any item in a game interface. Examples: 'find the knife in the shop', 'where is the rune pickaxe', 'do they sell dragon scimitars'. Supports fuzzy matching: 'd scim' matches 'Dragon scimitar'.",
    {
      item_name: z.string().describe("Item name to search for (supports partial matching)"),
      interface_name: z
        .string()
        .optional()
        .describe("Optional: limit search to a specific interface ('shop', 'bank', 'trade', 'inventory')"),
    },
    async ({ item_name, interface_name }) => {
      try {
        // Step 1: Get all visible interfaces
        const ifaceData = await apiGet("/api/interfaces");
        const visibleInterfaces = (ifaceData.interfaces || []).filter((i: any) => i.visible);

        // Step 2: Determine which container interfaces to search
        let containersToSearch: { groupId: number; name: string; itemChild?: number }[] = [];

        for (const iface of visibleInterfaces) {
          const known = CONTAINER_INTERFACES[iface.groupId];
          if (known) {
            if (
              !interface_name ||
              known.name.toLowerCase().includes(interface_name.toLowerCase())
            ) {
              containersToSearch.push({ groupId: iface.groupId, ...known });
            }
          }
        }

        // If user specified an interface but we didn't find it, search all visible ones
        if (containersToSearch.length === 0 && interface_name) {
          for (const iface of visibleInterfaces) {
            const name = iface.name || `Interface ${iface.groupId}`;
            if (name.toLowerCase().includes(interface_name.toLowerCase())) {
              containersToSearch.push({ groupId: iface.groupId, name });
            }
          }
        }

        // Fall back to all known containers if nothing matched
        if (containersToSearch.length === 0) {
          for (const iface of visibleInterfaces) {
            if (CONTAINER_INTERFACES[iface.groupId]) {
              containersToSearch.push({
                groupId: iface.groupId,
                ...CONTAINER_INTERFACES[iface.groupId],
              });
            }
          }
        }

        if (containersToSearch.length === 0) {
          return {
            content: [
              {
                type: "text" as const,
                text: `No container interfaces are currently open. Open a shop, bank, or other interface first.`,
              },
            ],
          };
        }

        // Step 3: Search each container for the item
        const results: any[] = [];

        for (const container of containersToSearch) {
          try {
            // Try the known item child first, then fall back to scanning children
            const childrenToCheck: number[] = [];
            if (container.itemChild !== undefined) {
              childrenToCheck.push(container.itemChild);
            }
            // Also check a few common children
            for (let c = 0; c <= 30; c++) {
              if (!childrenToCheck.includes(c)) childrenToCheck.push(c);
            }

            for (const childIdx of childrenToCheck) {
              try {
                const widget = await apiGet(
                  `/api/widget?group=${container.groupId}&child=${childIdx}`
                );

                // Check if this widget has item children
                const children = widget.children || [];
                if (children.length === 0 && widget.itemId && widget.itemId > 0) {
                  // Single item widget
                  const itemName = stripColorTags(widget.itemName || widget.name || "");
                  if (fuzzyMatch(itemName, item_name)) {
                    results.push({
                      interface: container.name,
                      groupId: container.groupId,
                      childIndex: childIdx,
                      slotIndex: 0,
                      itemId: widget.itemId,
                      itemName,
                      quantity: widget.itemQuantity || 1,
                      actions: widget.actions || [],
                    });
                  }
                }

                // Scan children for items
                for (let i = 0; i < children.length; i++) {
                  const child = children[i];
                  if (!child) continue;
                  const itemId = child.itemId ?? child.id;
                  if (!itemId || itemId <= 0) continue;
                  const itemName = stripColorTags(
                    child.itemName || child.name || child.text || ""
                  );
                  if (fuzzyMatch(itemName, item_name)) {
                    results.push({
                      interface: container.name,
                      groupId: container.groupId,
                      childIndex: childIdx,
                      slotIndex: i,
                      itemId,
                      itemName,
                      quantity: child.itemQuantity || child.quantity || 1,
                      actions: child.actions || [],
                    });
                  }
                }

                // If we found results in the known item child, stop checking others
                if (results.length > 0 && childIdx === container.itemChild) break;
              } catch {
                // Widget child doesn't exist, skip
                continue;
              }
            }
          } catch {
            // Interface query failed, skip
            continue;
          }
        }

        if (results.length === 0) {
          const searchedNames = containersToSearch.map((c) => c.name).join(", ");
          return {
            content: [
              {
                type: "text" as const,
                text: `Item "${item_name}" not found in any open interface. Searched: ${searchedNames}`,
              },
            ],
          };
        }

        const lines = [`# Found "${item_name}" — ${results.length} match(es)`];
        for (const r of results) {
          lines.push(
            `\n## ${r.interface} (Group ${r.groupId}, Child ${r.childIndex}, Slot ${r.slotIndex})`
          );
          lines.push(`  Item: ${r.itemName} (ID: ${r.itemId})`);
          lines.push(`  Quantity: ${r.quantity.toLocaleString()}`);
          if (r.actions?.length > 0) {
            lines.push(`  Actions: ${r.actions.filter(Boolean).join(", ")}`);
          }
        }

        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );
}
