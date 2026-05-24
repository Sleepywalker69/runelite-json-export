import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { apiGet, apiPost, isApiError } from "../api.js";

export function registerDebugTools(server: McpServer) {
  server.tool(
    "start_recording",
    "Start recording game events for later analysis. Use this when the user says 'record the next X minutes', 'watch what happens', 'capture what's going on', or wants to debug a script over time. Records game events into a buffer that can be reviewed later.\n\nUse the 'types' parameter to filter which events are captured — this prevents the 10,000-entry buffer from filling up with noise.\n\nCommon presets:\n- Combat: game_tick,hitsplat,npc_spawned,npc_despawned,actor_death,menu_clicked\n- Vars only: var_changed,game_tick\n- Clicks/movement: menu_clicked,game_tick\n- Full (default): omit types to record everything",
    {
      duration: z
        .number()
        .min(1)
        .max(600)
        .default(180)
        .describe("Recording duration in seconds (default 180, max 600)"),
      types: z
        .string()
        .optional()
        .describe(
          "Comma-separated event types to record. Only these types will be captured. " +
          "Available: game_tick, hitsplat, animation_changed, npc_spawned, npc_despawned, " +
          "actor_death, var_changed, menu_clicked, stat_changed, item_changed, interacting_changed. " +
          "Omit to record all types."
        ),
    },
    async ({ duration, types }) => {
      try {
        let url = `/api/recording/start?duration=${duration}`;
        if (types) url += `&types=${encodeURIComponent(types)}`;
        const data = await apiPost(url);
        const filter = data.eventFilter;
        const filterDisplay = filter === "all"
          ? "All event types"
          : Array.isArray(filter) ? filter.join(", ") : String(filter);
        const lines = [
          "# Recording Started",
          `Duration: ${duration} seconds (~${Math.ceil(duration / 0.6)} game ticks)`,
          `Start tick: ${data.startTick}`,
          `Event filter: ${filterDisplay}`,
          "",
          "Use **stop_recording** to stop early, or it will auto-stop after the duration.",
          "Use **get_recording** to retrieve and analyze the captured events.",
        ];
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "stop_recording",
    "Stop an active recording session. The recorded data is kept and can be retrieved with get_recording.",
    {},
    async () => {
      try {
        const data = await apiPost("/api/recording/stop");
        if (!data.wasRecording) {
          return {
            content: [
              { type: "text" as const, text: "No recording was active." },
            ],
          };
        }
        return {
          content: [
            {
              type: "text" as const,
              text: `# Recording Stopped\n\nCaptured **${data.eventsLogged}** events. Use **get_recording** to review them.`,
            },
          ],
        };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "recording_status",
    "Check if a recording is currently active and how many events have been captured.",
    {},
    async () => {
      try {
        const data = await apiGet("/api/recording/status");
        if (!data.recording) {
          return {
            content: [
              {
                type: "text" as const,
                text: `No recording active. ${data.eventsLogged} events in buffer from last recording.`,
              },
            ],
          };
        }
        const filter = data.eventFilter;
        const filterDisplay = filter === "all"
          ? "All event types"
          : Array.isArray(filter) ? filter.join(", ") : String(filter);
        const lines = [
          "# Recording In Progress",
          `Events captured: ${data.eventsLogged}`,
          `Ticks elapsed: ${data.ticksElapsed}`,
          `Time elapsed: ~${data.secondsElapsed}s`,
          `Time remaining: ~${data.secondsRemaining}s`,
          `Event filter: ${filterDisplay}`,
        ];
        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "get_recording",
    "Get recorded game events from a recording session. Use this after stop_recording or when recording auto-completes. Returns a timeline of all game events captured during the session. Use this to analyze what went wrong over time — find where the bot got stuck, missed an NPC, or failed a mechanic. Filter by event types or tick range to focus on specific moments.",
    {
      types: z
        .string()
        .optional()
        .describe(
          "Comma-separated event types to filter: game_tick, hitsplat, animation_changed, npc_spawned, npc_despawned, actor_death, var_changed, menu_clicked, stat_changed, item_changed, interacting_changed"
        ),
      from_tick: z
        .number()
        .optional()
        .describe("Start of tick range (ticksElapsed from recording start)"),
      to_tick: z
        .number()
        .optional()
        .describe("End of tick range (ticksElapsed from recording start)"),
      last: z
        .number()
        .optional()
        .describe("Only return the last N events"),
    },
    async ({ types, from_tick, to_tick, last }) => {
      try {
        const params: string[] = [];
        if (types) params.push(`types=${encodeURIComponent(types)}`);
        if (from_tick !== undefined) params.push(`from_tick=${from_tick}`);
        if (to_tick !== undefined) params.push(`to_tick=${to_tick}`);
        if (last !== undefined) params.push(`last=${last}`);
        const query = params.length ? `?${params.join("&")}` : "";
        const data = await apiGet(`/api/recording/data${query}`);

        const events = data.events || [];
        if (events.length === 0) {
          return {
            content: [
              {
                type: "text" as const,
                text: data.totalEvents === 0
                  ? "No events recorded. Start a recording first with start_recording."
                  : "No events match the filters. Try different types or tick range.",
              },
            ],
          };
        }

        const lines = [
          `# Recording Data — ${data.filteredEvents} of ${data.totalEvents} events${data.recording ? " (still recording)" : ""}`,
          "",
        ];

        // Group events by type for a summary header
        const typeCounts: Record<string, number> = {};
        for (const e of events) {
          typeCounts[e.eventType] = (typeCounts[e.eventType] || 0) + 1;
        }
        lines.push(
          "## Event Summary",
          ...Object.entries(typeCounts).map(
            ([t, c]) => `  ${t}: ${c}`
          ),
          ""
        );

        // Timeline
        lines.push("## Timeline");
        for (const e of events) {
          const tick = e.ticksElapsed ?? e.tick;
          const type = e.eventType;

          switch (type) {
            case "game_tick": {
              const p = e.player;
              const prayers = p?.activePrayers?.length
                ? ` prayers=[${p.activePrayers.join(",")}]`
                : "";
              const interacting = p?.interacting
                ? ` → ${p.interacting.name}`
                : "";
              const npcCount = e.nearbyNpcs?.length ?? 0;
              lines.push(
                `  [+${tick}] TICK — HP:${p?.health} Pray:${p?.prayer} Pos:(${p?.position?.x},${p?.position?.y}) Anim:${p?.animation}${prayers}${interacting} idle=${p?.isIdle} npcs=${npcCount}`
              );
              break;
            }
            case "hitsplat": {
              const target = e.target?.name || "?";
              const mine = e.isMine ? " (mine)" : "";
              lines.push(
                `  [+${tick}] HIT — ${target} ${e.amount} dmg (type ${e.type})${mine}`
              );
              break;
            }
            case "animation_changed": {
              const actor = e.actor?.name || "?";
              lines.push(
                `  [+${tick}] ANIM — ${actor} → ${e.animation}`
              );
              break;
            }
            case "npc_spawned": {
              const npc = e.npc;
              lines.push(
                `  [+${tick}] NPC+ — ${npc?.name} (id:${npc?.id}) at (${npc?.position?.x},${npc?.position?.y})`
              );
              break;
            }
            case "npc_despawned": {
              const npc = e.npc;
              lines.push(
                `  [+${tick}] NPC- — ${npc?.name} (id:${npc?.id})`
              );
              break;
            }
            case "actor_death": {
              lines.push(
                `  [+${tick}] DEATH — ${e.actor?.name || "?"}`
              );
              break;
            }
            case "var_changed": {
              lines.push(
                `  [+${tick}] VAR — varp[${e.varpIndex}] ${e.oldValue} → ${e.newValue}`
              );
              break;
            }
            case "menu_clicked": {
              lines.push(
                `  [+${tick}] CLICK — ${e.option} → ${e.target} [${e.menuAction}]`
              );
              break;
            }
            case "stat_changed": {
              lines.push(
                `  [+${tick}] STAT — ${e.skill} lvl ${e.level} (boosted: ${e.boostedLevel}) xp: ${e.xp}`
              );
              break;
            }
            case "item_changed": {
              lines.push(
                `  [+${tick}] ITEMS — ${e.container} changed`
              );
              break;
            }
            case "interacting_changed": {
              const src = e.source?.name || "?";
              const tgt = e.target?.name || "nothing";
              lines.push(
                `  [+${tick}] TARGET — ${src} → ${tgt}`
              );
              break;
            }
            default:
              lines.push(
                `  [+${tick}] ${type} — ${JSON.stringify(e).substring(0, 120)}`
              );
          }
        }

        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );

  server.tool(
    "debug_snapshot",
    "Take an instant snapshot of all combat-relevant game state in a single call. Use this automatically when the user asks 'why is it stuck', 'what's happening', 'debug this', 'what went wrong', or any troubleshooting question about in-game behavior. Returns player state, nearby NPCs, active prayers, inventory, equipment, recent interactions, and active effects — everything needed to diagnose issues.",
    {},
    async () => {
      try {
        // Fetch all combat-relevant data in parallel
        const [
          player,
          npcs,
          prayers,
          inventory,
          equipment,
          interactions,
          gfx,
        ] = await Promise.all([
          apiGet("/api/player").catch(() => null),
          apiGet("/api/npcs").catch(() => null),
          apiGet("/api/prayers").catch(() => null),
          apiGet("/api/inventory").catch(() => null),
          apiGet("/api/equipment").catch(() => null),
          apiGet("/api/interaction-history?last=10").catch(() => null),
          apiGet("/api/graphics-objects").catch(() => null),
        ]);

        const lines = ["# Debug Snapshot", ""];

        // Player state
        if (player) {
          lines.push("## Player");
          lines.push(`  Name: ${player.name}`);
          lines.push(
            `  HP: ${player.currentHealth}/${player.maxHealth} | Prayer: ${player.currentPrayer}/${player.maxPrayer}`
          );
          lines.push(
            `  Position: (${player.position?.x}, ${player.position?.y}, plane ${player.position?.plane})`
          );
          lines.push(
            `  Animation: ${player.animation} | Pose: ${player.animationPose}`
          );
          lines.push(
            `  Run Energy: ${player.runEnergy} | Special: ${player.specialAttackEnergy}%`
          );
          if (player.interacting) {
            lines.push(`  Interacting: ${player.interacting.name}`);
          }
          lines.push(`  Idle: ${player.animation === -1 && !player.interacting}`);
        }

        // Prayers
        if (prayers) {
          lines.push("");
          lines.push("## Active Prayers");
          const active = prayers.active || [];
          if (active.length === 0) {
            lines.push("  None active");
          } else {
            for (const p of active) {
              lines.push(`  ✦ ${p}`);
            }
          }
        }

        // Nearby NPCs
        if (npcs) {
          const npcList = npcs.npcs || [];
          lines.push("");
          lines.push(`## Nearby NPCs (${npcList.length})`);
          for (const npc of npcList.slice(0, 20)) {
            const hp =
              npc.healthRatio !== undefined && npc.healthScale
                ? ` HP:${Math.round((npc.healthRatio / npc.healthScale) * 100)}%`
                : "";
            const target = npc.interacting
              ? ` → ${npc.interacting}`
              : "";
            lines.push(
              `  ${npc.name} (id:${npc.id}) at (${npc.position?.x},${npc.position?.y}) anim:${npc.animation}${hp}${target}`
            );
          }
          if (npcList.length > 20) {
            lines.push(`  ... and ${npcList.length - 20} more`);
          }
        }

        // Inventory summary
        if (inventory) {
          const items = inventory.items || [];
          const nonEmpty = items.filter((i: any) => i.id > 0);
          lines.push("");
          lines.push(`## Inventory (${nonEmpty.length}/28 slots used)`);
          for (const item of nonEmpty) {
            const qty = item.quantity > 1 ? ` x${item.quantity}` : "";
            lines.push(`  ${item.name}${qty}`);
          }
        }

        // Equipment summary
        if (equipment) {
          const slots = equipment.slots || equipment.equipment || [];
          const equipped = Array.isArray(slots)
            ? slots.filter((s: any) => s.itemId > 0)
            : Object.entries(slots)
                .filter(([, v]: [string, any]) => v.itemId > 0)
                .map(([k, v]: [string, any]) => ({ slot: k, ...v }));
          lines.push("");
          lines.push("## Equipment");
          for (const item of equipped) {
            lines.push(`  ${item.slot || ""}: ${item.name || `ID:${item.itemId}`}`);
          }
        }

        // Recent interactions
        if (interactions) {
          const entries =
            interactions.interactions || interactions.history || interactions.entries || [];
          if (entries.length > 0) {
            lines.push("");
            lines.push("## Recent Interactions (last 10)");
            for (const e of entries) {
              const icon = e.type === "click" ? "🖱️" : "👁️";
              lines.push(
                `  ${icon} [tick ${e.tick}] ${e.action} → ${e.target}`
              );
            }
          }
        }

        // Graphics objects
        if (gfx) {
          const objects = gfx.graphicsObjects || [];
          if (objects.length > 0) {
            lines.push("");
            lines.push(`## Active Visual Effects (${objects.length})`);
            for (const obj of objects) {
              lines.push(`  GFX ${obj.id} at (${obj.location?.worldX},${obj.location?.worldY})`);
            }
          }
        }

        return { content: [{ type: "text" as const, text: lines.join("\n") }] };
      } catch (err) {
        return { content: [{ type: "text" as const, text: isApiError(err) }] };
      }
    }
  );
}
