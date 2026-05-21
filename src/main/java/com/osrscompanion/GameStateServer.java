package com.osrscompanion;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class GameStateServer
{
	private final Client client;
	private final ClientThread clientThread;
	private final Gson gson;
	private HttpServer server;

	private final List<Map<String, Object>> chatBuffer = Collections.synchronizedList(new LinkedList<>());
	private static final int MAX_CHAT_BUFFER = 200;
	private static final int DEFAULT_RADIUS = 15;
	private static final int MAX_RADIUS = 50;
	private static final int SCENE_SIZE = 104;

	public GameStateServer(Client client, ClientThread clientThread, Gson gson)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.gson = gson.newBuilder().setPrettyPrinting().create();
	}

	public void start(int port) throws IOException
	{
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

		server.createContext("/api/game-state", this::handleGameState);
		server.createContext("/api/player", this::handlePlayer);
		server.createContext("/api/location", this::handleLocation);
		server.createContext("/api/skills", this::handleSkills);
		server.createContext("/api/inventory", this::handleInventory);
		server.createContext("/api/equipment", this::handleEquipment);
		server.createContext("/api/bank", this::handleBank);
		server.createContext("/api/quests", this::handleQuests);
		server.createContext("/api/npcs", this::handleNpcs);
		server.createContext("/api/players", this::handlePlayers);
		server.createContext("/api/widgets", this::handleWidgets);
		server.createContext("/api/widget", this::handleWidget);
		server.createContext("/api/objects", this::handleObjects);
		server.createContext("/api/ground-items", this::handleGroundItems);
		server.createContext("/api/varbit", this::handleVarbit);
		server.createContext("/api/varp", this::handleVarp);
		server.createContext("/api/item-def", this::handleItemDef);
		server.createContext("/api/npc-def", this::handleNpcDef);
		server.createContext("/api/obj-def", this::handleObjDef);
		server.createContext("/api/chat", this::handleChat);
		server.createContext("/api/scene", this::handleScene);
		server.createContext("/api/tile", this::handleTile);
		server.createContext("/", this::handleIndex);

		server.setExecutor(null);
		server.start();
		log.info("OSRS Companion API server started on http://127.0.0.1:{}", port);
	}

	public void stop()
	{
		if (server != null)
		{
			server.stop(0);
			server = null;
			log.info("OSRS Companion API server stopped");
		}
	}

	public void addChatMessage(String type, String sender, String message)
	{
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("type", type);
		entry.put("sender", sender);
		entry.put("message", message);
		entry.put("timestamp", System.currentTimeMillis());
		chatBuffer.add(entry);
		while (chatBuffer.size() > MAX_CHAT_BUFFER)
		{
			chatBuffer.remove(0);
		}
	}

	// ==================== Helpers ====================

	@FunctionalInterface
	interface ClientTask<T>
	{
		T run();
	}

	private <T> T onClientThread(ClientTask<T> task) throws Exception
	{
		CompletableFuture<T> future = new CompletableFuture<>();
		clientThread.invokeLater(() ->
		{
			try
			{
				future.complete(task.run());
			}
			catch (Exception e)
			{
				future.completeExceptionally(e);
			}
		});
		return future.get(5, TimeUnit.SECONDS);
	}

	private Map<String, String> parseQuery(URI uri)
	{
		Map<String, String> params = new HashMap<>();
		String query = uri.getQuery();
		if (query != null)
		{
			for (String pair : query.split("&"))
			{
				String[] kv = pair.split("=", 2);
				if (kv.length == 2)
				{
					params.put(kv[0], kv[1]);
				}
			}
		}
		return params;
	}

	private int intParam(Map<String, String> params, String key, int defaultVal)
	{
		String val = params.get(key);
		if (val == null)
		{
			return defaultVal;
		}
		try
		{
			return Integer.parseInt(val);
		}
		catch (NumberFormatException e)
		{
			return defaultVal;
		}
	}

	private void sendJson(HttpExchange exchange, int code, Object body) throws IOException
	{
		String json = gson.toJson(body);
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		exchange.sendResponseHeaders(code, bytes.length);
		try (OutputStream os = exchange.getResponseBody())
		{
			os.write(bytes);
		}
	}

	private void sendError(HttpExchange exchange, int code, String message) throws IOException
	{
		Map<String, Object> err = new LinkedHashMap<>();
		err.put("error", message);
		sendJson(exchange, code, err);
	}

	private boolean requireLoggedIn(HttpExchange exchange) throws IOException
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			sendError(exchange, 503, "Not logged in");
			return false;
		}
		return true;
	}

	private Map<String, Object> worldPoint(WorldPoint wp)
	{
		if (wp == null)
		{
			return null;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("x", wp.getX());
		m.put("y", wp.getY());
		m.put("plane", wp.getPlane());
		return m;
	}

	private String itemName(int itemId)
	{
		try
		{
			ItemComposition def = client.getItemDefinition(itemId);
			return def != null ? def.getName() : null;
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private String cleanName(String name)
	{
		if (name == null)
		{
			return null;
		}
		return name.replaceAll("<[^>]+>", "").trim();
	}

	private List<String> filterActions(String[] actions)
	{
		if (actions == null)
		{
			return Collections.emptyList();
		}
		List<String> list = new ArrayList<>();
		for (String a : actions)
		{
			if (a != null && !a.isEmpty())
			{
				list.add(a);
			}
		}
		return list;
	}

	// ==================== Endpoints ====================

	private void handleIndex(HttpExchange exchange) throws IOException
	{
		Map<String, Object> index = new LinkedHashMap<>();
		index.put("name", "OSRS MCP Companion API");
		index.put("version", 2);

		List<Map<String, String>> endpoints = new ArrayList<>();
		endpoints.add(ep("GET /api/game-state", "Login state, game tick count"));
		endpoints.add(ep("GET /api/player", "Current player: position, animation, health, prayer, run energy"));
		endpoints.add(ep("GET /api/location", "Detailed location: world point, region IDs, instance info"));
		endpoints.add(ep("GET /api/skills", "All skill levels and XP"));
		endpoints.add(ep("GET /api/inventory", "Inventory contents with item IDs, names, quantities, slots"));
		endpoints.add(ep("GET /api/equipment", "Equipment slots with item IDs and names"));
		endpoints.add(ep("GET /api/bank", "Bank contents (available after opening bank)"));
		endpoints.add(ep("GET /api/quests", "All quest states"));
		endpoints.add(ep("GET /api/npcs", "All loaded NPCs with IDs, names, positions, animations. ?name=X or ?id=X to filter"));
		endpoints.add(ep("GET /api/players", "All visible players with names, positions, animations"));
		endpoints.add(ep("GET /api/widgets", "List active widget group IDs and child counts"));
		endpoints.add(ep("GET /api/widget?group=X", "All children in a widget group"));
		endpoints.add(ep("GET /api/widget?group=X&child=Y", "Specific widget with full details and nested children"));
		endpoints.add(ep("GET /api/objects?radius=N", "Game objects near player (default radius 15)"));
		endpoints.add(ep("GET /api/ground-items?radius=N", "Ground items near player (default radius 15)"));
		endpoints.add(ep("GET /api/varbit?id=X", "Get varbit value. Use ?from=X&to=Y for a range"));
		endpoints.add(ep("GET /api/varp?id=X", "Get VarPlayer value. Use ?from=X&to=Y for a range"));
		endpoints.add(ep("GET /api/item-def?id=X", "Item definition lookup by ID"));
		endpoints.add(ep("GET /api/npc-def?id=X", "NPC definition lookup by ID"));
		endpoints.add(ep("GET /api/obj-def?id=X", "Object definition lookup by ID"));
		endpoints.add(ep("GET /api/chat", "Recent chat messages (last 200). ?type=X to filter"));
		endpoints.add(ep("GET /api/scene", "Scene info: base coords, map regions, plane"));
		endpoints.add(ep("GET /api/tile?x=X&y=Y&plane=Z", "Tile info and collision flags at world coordinates"));
		index.put("endpoints", endpoints);

		sendJson(exchange, 200, index);
	}

	private Map<String, String> ep(String path, String description)
	{
		Map<String, String> m = new LinkedHashMap<>();
		m.put("endpoint", path);
		m.put("description", description);
		return m;
	}

	private void handleGameState(HttpExchange exchange) throws IOException
	{
		try
		{
			Object data = onClientThread(() ->
			{
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("gameState", client.getGameState().name());
				m.put("isLoggedIn", client.getGameState() == GameState.LOGGED_IN);

				if (client.getGameState() == GameState.LOGGED_IN)
				{
					m.put("tickCount", client.getTickCount());
					m.put("fps", client.getFPS());
					m.put("world", client.getWorld());
					m.put("isMembers", client.getWorldType().contains(WorldType.MEMBERS));
				}
				return m;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handlePlayer(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			Object data = onClientThread(() ->
			{
				Player local = client.getLocalPlayer();
				if (local == null)
				{
					return Collections.singletonMap("error", "No local player");
				}

				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", local.getName());
				m.put("combatLevel", local.getCombatLevel());
				m.put("world", client.getWorld());
				m.put("position", worldPoint(local.getWorldLocation()));
				m.put("animation", local.getAnimation());
				m.put("animationPose", local.getPoseAnimation());
				m.put("graphic", local.getGraphic());
				m.put("orientation", local.getOrientation());

				m.put("currentHealth", client.getBoostedSkillLevel(Skill.HITPOINTS));
				m.put("maxHealth", client.getRealSkillLevel(Skill.HITPOINTS));
				m.put("currentPrayer", client.getBoostedSkillLevel(Skill.PRAYER));
				m.put("maxPrayer", client.getRealSkillLevel(Skill.PRAYER));
				m.put("runEnergy", client.getEnergy() / 100.0);
				m.put("specialAttackPercent", client.getVarpValue(48) / 10.0);

				Actor interacting = local.getInteracting();
				if (interacting != null)
				{
					Map<String, Object> target = new LinkedHashMap<>();
					target.put("name", interacting.getName());
					if (interacting instanceof NPC)
					{
						target.put("type", "NPC");
						target.put("id", ((NPC) interacting).getId());
						target.put("index", ((NPC) interacting).getIndex());
					}
					else if (interacting instanceof Player)
					{
						target.put("type", "Player");
					}
					target.put("position", worldPoint(interacting.getWorldLocation()));
					m.put("interacting", target);
				}

				m.put("isIdle", local.getAnimation() == -1 && local.getInteracting() == null);

				try
				{
					m.put("skullIcon", local.getSkullIcon());
				}
				catch (Exception ignored)
				{
				}
				try
				{
					m.put("overheadIcon", local.getOverheadIcon());
				}
				catch (Exception ignored)
				{
				}

				return m;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleLocation(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			Object data = onClientThread(() ->
			{
				Player local = client.getLocalPlayer();
				if (local == null)
				{
					return Collections.singletonMap("error", "No local player");
				}

				Map<String, Object> m = new LinkedHashMap<>();
				WorldPoint wp = local.getWorldLocation();
				m.put("worldPoint", worldPoint(wp));
				m.put("plane", client.getPlane());
				m.put("baseX", client.getBaseX());
				m.put("baseY", client.getBaseY());

				LocalPoint lp = local.getLocalLocation();
				if (lp != null)
				{
					Map<String, Object> localPt = new LinkedHashMap<>();
					localPt.put("x", lp.getX());
					localPt.put("y", lp.getY());
					localPt.put("sceneX", lp.getSceneX());
					localPt.put("sceneY", lp.getSceneY());
					m.put("localPoint", localPt);
				}

				int[] regions = client.getMapRegions();
				if (regions != null)
				{
					List<Integer> regionList = new ArrayList<>();
					for (int r : regions)
					{
						regionList.add(r);
					}
					m.put("mapRegions", regionList);
				}

				m.put("isInInstancedRegion", client.isInInstancedRegion());

				return m;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleSkills(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			Object data = onClientThread(() ->
			{
				Map<String, Object> skills = new LinkedHashMap<>();
				int totalLevel = 0;
				long totalXp = 0;

				for (Skill skill : Skill.values())
				{
					if (skill == Skill.OVERALL)
					{
						continue;
					}
					Map<String, Object> entry = new LinkedHashMap<>();
					int real = client.getRealSkillLevel(skill);
					int boosted = client.getBoostedSkillLevel(skill);
					int xp = client.getSkillExperience(skill);
					entry.put("level", real);
					entry.put("boostedLevel", boosted);
					entry.put("xp", xp);
					skills.put(skill.getName(), entry);
					totalLevel += real;
					totalXp += xp;
				}

				Map<String, Object> overall = new LinkedHashMap<>();
				overall.put("level", totalLevel);
				overall.put("xp", totalXp);
				skills.put("Overall", overall);

				return skills;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleInventory(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			Object data = onClientThread(() ->
			{
				ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
				if (container == null)
				{
					return Collections.singletonMap("items", Collections.emptyList());
				}

				Item[] items = container.getItems();
				List<Map<String, Object>> list = new ArrayList<>();
				for (int slot = 0; slot < items.length; slot++)
				{
					Item item = items[slot];
					if (item.getId() <= 0)
					{
						continue;
					}
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("slot", slot);
					entry.put("itemId", item.getId());
					entry.put("name", itemName(item.getId()));
					entry.put("quantity", item.getQuantity());
					list.add(entry);
				}

				Map<String, Object> result = new LinkedHashMap<>();
				result.put("count", list.size());
				result.put("items", list);
				return result;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleEquipment(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			Object data = onClientThread(() ->
			{
				ItemContainer container = client.getItemContainer(InventoryID.EQUIPMENT);
				Map<String, Object> slots = new LinkedHashMap<>();

				String[] slotNames = {
					"HEAD", "CAPE", "AMULET", "WEAPON", "BODY",
					"SHIELD", "LEGS", "GLOVES", "BOOTS", "RING", "AMMO"
				};

				if (container != null)
				{
					Item[] items = container.getItems();
					for (int i = 0; i < slotNames.length && i < items.length; i++)
					{
						Item item = items[i];
						if (item.getId() > 0)
						{
							Map<String, Object> entry = new LinkedHashMap<>();
							entry.put("itemId", item.getId());
							entry.put("name", itemName(item.getId()));
							entry.put("quantity", item.getQuantity());
							slots.put(slotNames[i], entry);
						}
					}
				}

				return slots;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleBank(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			Object data = onClientThread(() ->
			{
				ItemContainer container = client.getItemContainer(InventoryID.BANK);
				if (container == null)
				{
					Map<String, Object> empty = new LinkedHashMap<>();
					empty.put("available", false);
					empty.put("message", "Bank not open or no cached data");
					return empty;
				}

				Item[] items = container.getItems();
				List<Map<String, Object>> bankItems = new ArrayList<>();
				List<List<Map<String, Object>>> tabs = new ArrayList<>();
				List<Map<String, Object>> currentTab = new ArrayList<>();
				int tabIndex = 0;

				for (Item item : items)
				{
					if (item.getId() == -1)
					{
						if (!currentTab.isEmpty() || tabIndex == 0)
						{
							tabs.add(currentTab);
							currentTab = new ArrayList<>();
							tabIndex++;
						}
						continue;
					}
					if (item.getId() > 0 && item.getQuantity() > 0)
					{
						Map<String, Object> entry = new LinkedHashMap<>();
						entry.put("itemId", item.getId());
						entry.put("name", itemName(item.getId()));
						entry.put("quantity", item.getQuantity());
						currentTab.add(entry);
						bankItems.add(entry);
					}
				}
				if (!currentTab.isEmpty())
				{
					tabs.add(currentTab);
				}

				Map<String, Object> result = new LinkedHashMap<>();
				result.put("available", true);
				result.put("totalItems", bankItems.size());
				result.put("tabCount", tabs.size());
				result.put("tabs", tabs);
				return result;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleQuests(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			Object data = onClientThread(() ->
			{
				List<Map<String, Object>> questList = new ArrayList<>();
				for (Quest quest : Quest.values())
				{
					try
					{
						QuestState state = quest.getState(client);
						Map<String, Object> entry = new LinkedHashMap<>();
						entry.put("id", quest.name());
						entry.put("name", quest.getName());
						entry.put("state", state.name());
						questList.add(entry);
					}
					catch (Exception ignored)
					{
					}
				}
				return questList;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleNpcs(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		String filterName = params.get("name");
		int filterId = intParam(params, "id", -1);

		try
		{
			Object data = onClientThread(() ->
			{
				List<Map<String, Object>> npcList = new ArrayList<>();
				for (NPC npc : client.getNpcs())
				{
					if (npc == null)
					{
						continue;
					}

					if (filterId >= 0 && npc.getId() != filterId)
					{
						continue;
					}
					if (filterName != null && (npc.getName() == null ||
						!npc.getName().toLowerCase().contains(filterName.toLowerCase())))
					{
						continue;
					}

					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("index", npc.getIndex());
					entry.put("id", npc.getId());
					entry.put("name", npc.getName());
					entry.put("combatLevel", npc.getCombatLevel());
					entry.put("position", worldPoint(npc.getWorldLocation()));
					entry.put("animation", npc.getAnimation());
					entry.put("graphic", npc.getGraphic());
					entry.put("orientation", npc.getOrientation());
					entry.put("healthRatio", npc.getHealthRatio());
					entry.put("healthScale", npc.getHealthScale());
					entry.put("isDead", npc.isDead());

					Actor interacting = npc.getInteracting();
					if (interacting != null)
					{
						Map<String, Object> target = new LinkedHashMap<>();
						target.put("name", interacting.getName());
						if (interacting instanceof Player)
						{
							target.put("type", "Player");
						}
						else if (interacting instanceof NPC)
						{
							target.put("type", "NPC");
							target.put("id", ((NPC) interacting).getId());
						}
						entry.put("interacting", target);
					}

					try
					{
						NPCComposition comp = client.getNpcDefinition(npc.getId());
						if (comp != null)
						{
							entry.put("size", comp.getSize());
							entry.put("actions", filterActions(comp.getActions()));
						}
					}
					catch (Exception ignored)
					{
					}

					npcList.add(entry);
				}
				return npcList;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handlePlayers(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			Object data = onClientThread(() ->
			{
				List<Map<String, Object>> playerList = new ArrayList<>();
				for (Player player : client.getPlayers())
				{
					if (player == null)
					{
						continue;
					}

					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("name", player.getName());
					entry.put("combatLevel", player.getCombatLevel());
					entry.put("position", worldPoint(player.getWorldLocation()));
					entry.put("animation", player.getAnimation());
					entry.put("graphic", player.getGraphic());
					entry.put("orientation", player.getOrientation());
					entry.put("healthRatio", player.getHealthRatio());
					entry.put("healthScale", player.getHealthScale());
					entry.put("isLocalPlayer", player == client.getLocalPlayer());

					try
					{
						entry.put("overheadText", player.getOverheadText());
					}
					catch (Exception ignored)
					{
					}

					Actor interacting = player.getInteracting();
					if (interacting != null)
					{
						Map<String, Object> target = new LinkedHashMap<>();
						target.put("name", interacting.getName());
						if (interacting instanceof NPC)
						{
							target.put("type", "NPC");
							target.put("id", ((NPC) interacting).getId());
						}
						else if (interacting instanceof Player)
						{
							target.put("type", "Player");
						}
						entry.put("interacting", target);
					}

					playerList.add(entry);
				}
				return playerList;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleWidgets(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			Object data = onClientThread(() ->
			{
				Widget[][] widgets = client.getWidgets();
				if (widgets == null)
				{
					return Collections.singletonMap("groups", Collections.emptyList());
				}

				List<Map<String, Object>> groups = new ArrayList<>();
				for (int groupId = 0; groupId < widgets.length; groupId++)
				{
					Widget[] children = widgets[groupId];
					if (children == null)
					{
						continue;
					}
					int nonNullCount = 0;
					for (Widget w : children)
					{
						if (w != null)
						{
							nonNullCount++;
						}
					}
					if (nonNullCount == 0)
					{
						continue;
					}

					Map<String, Object> group = new LinkedHashMap<>();
					group.put("groupId", groupId);
					group.put("childCount", nonNullCount);
					groups.add(group);
				}

				Map<String, Object> result = new LinkedHashMap<>();
				result.put("activeGroupCount", groups.size());
				result.put("groups", groups);
				return result;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleWidget(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		if (!params.containsKey("group"))
		{
			sendError(exchange, 400, "Missing required parameter: group");
			return;
		}
		int groupId = intParam(params, "group", -1);
		int childId = intParam(params, "child", -1);

		if (groupId < 0)
		{
			sendError(exchange, 400, "Invalid group ID");
			return;
		}

		try
		{
			Object data = onClientThread(() ->
			{
				Widget[][] widgets = client.getWidgets();
				if (widgets == null || groupId >= widgets.length || widgets[groupId] == null)
				{
					Map<String, Object> err = new LinkedHashMap<>();
					err.put("error", "Widget group " + groupId + " not found or not active");
					return err;
				}

				if (childId >= 0)
				{
					Widget[] children = widgets[groupId];
					if (childId >= children.length || children[childId] == null)
					{
						Map<String, Object> err = new LinkedHashMap<>();
						err.put("error", "Widget " + groupId + ":" + childId + " not found");
						return err;
					}
					return serializeWidget(children[childId], true, 0);
				}

				Widget[] children = widgets[groupId];
				List<Map<String, Object>> childList = new ArrayList<>();
				for (int i = 0; i < children.length; i++)
				{
					Widget w = children[i];
					if (w == null)
					{
						continue;
					}
					childList.add(serializeWidget(w, false, 0));
				}

				Map<String, Object> result = new LinkedHashMap<>();
				result.put("groupId", groupId);
				result.put("childCount", childList.size());
				result.put("children", childList);
				return result;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private Map<String, Object> serializeWidget(Widget widget, boolean deep, int depth)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		int packedId = widget.getId();
		m.put("id", packedId);
		m.put("groupId", packedId >> 16);
		m.put("childIndex", packedId & 0xFFFF);
		m.put("type", widget.getType());
		m.put("contentType", widget.getContentType());

		String text = widget.getText();
		if (text != null && !text.isEmpty())
		{
			m.put("text", text);
		}

		String name = widget.getName();
		if (name != null && !name.isEmpty())
		{
			m.put("name", cleanName(name));
			m.put("rawName", name);
		}

		int itemId = widget.getItemId();
		if (itemId > 0)
		{
			m.put("itemId", itemId);
			m.put("itemName", itemName(itemId));
			m.put("itemQuantity", widget.getItemQuantity());
		}

		int spriteId = widget.getSpriteId();
		if (spriteId >= 0)
		{
			m.put("spriteId", spriteId);
		}

		m.put("width", widget.getWidth());
		m.put("height", widget.getHeight());
		m.put("relativeX", widget.getRelativeX());
		m.put("relativeY", widget.getRelativeY());
		m.put("isHidden", widget.isHidden());
		m.put("isSelfHidden", widget.isSelfHidden());

		String[] actions = widget.getActions();
		if (actions != null)
		{
			List<String> actionList = filterActions(actions);
			if (!actionList.isEmpty())
			{
				m.put("actions", actionList);
			}
		}

		if (deep && depth < 4)
		{
			try
			{
				m.put("opacity", widget.getOpacity());
				m.put("modelId", widget.getModelId());
				m.put("animationId", widget.getAnimationId());
				m.put("scrollX", widget.getScrollX());
				m.put("scrollY", widget.getScrollY());
				m.put("scrollWidth", widget.getScrollWidth());
				m.put("scrollHeight", widget.getScrollHeight());
				m.put("textColor", widget.getTextColor());
				m.put("fontId", widget.getFontId());
				m.put("parentId", widget.getParentId());
			}
			catch (Exception ignored)
			{
			}

			Widget[] children = widget.getChildren();
			if (children != null && children.length > 0)
			{
				List<Map<String, Object>> childList = new ArrayList<>();
				for (Widget child : children)
				{
					if (child != null)
					{
						childList.add(serializeWidget(child, true, depth + 1));
					}
				}
				if (!childList.isEmpty())
				{
					m.put("children", childList);
				}
			}

			try
			{
				Widget[] dynChildren = widget.getDynamicChildren();
				if (dynChildren != null && dynChildren.length > 0)
				{
					List<Map<String, Object>> dList = new ArrayList<>();
					for (Widget child : dynChildren)
					{
						if (child != null)
						{
							dList.add(serializeWidget(child, true, depth + 1));
						}
					}
					if (!dList.isEmpty())
					{
						m.put("dynamicChildren", dList);
					}
				}
			}
			catch (Exception ignored)
			{
			}

			try
			{
				Widget[] statChildren = widget.getStaticChildren();
				if (statChildren != null && statChildren.length > 0)
				{
					List<Map<String, Object>> sList = new ArrayList<>();
					for (Widget child : statChildren)
					{
						if (child != null)
						{
							sList.add(serializeWidget(child, true, depth + 1));
						}
					}
					if (!sList.isEmpty())
					{
						m.put("staticChildren", sList);
					}
				}
			}
			catch (Exception ignored)
			{
			}

			try
			{
				Widget[] nested = widget.getNestedChildren();
				if (nested != null && nested.length > 0)
				{
					List<Map<String, Object>> nList = new ArrayList<>();
					for (Widget child : nested)
					{
						if (child != null)
						{
							nList.add(serializeWidget(child, true, depth + 1));
						}
					}
					if (!nList.isEmpty())
					{
						m.put("nestedChildren", nList);
					}
				}
			}
			catch (Exception ignored)
			{
			}
		}

		return m;
	}

	private void handleObjects(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		int radius = Math.min(intParam(params, "radius", DEFAULT_RADIUS), MAX_RADIUS);

		try
		{
			Object data = onClientThread(() ->
			{
				Player local = client.getLocalPlayer();
				if (local == null)
				{
					return Collections.singletonMap("error", "No local player");
				}

				LocalPoint lp = local.getLocalLocation();
				int centerX = lp.getSceneX();
				int centerY = lp.getSceneY();
				int plane = client.getPlane();

				Scene scene = client.getScene();
				Tile[][][] tiles = scene.getTiles();

				List<Map<String, Object>> objects = new ArrayList<>();

				for (int dx = -radius; dx <= radius; dx++)
				{
					for (int dy = -radius; dy <= radius; dy++)
					{
						int sx = centerX + dx;
						int sy = centerY + dy;
						if (sx < 0 || sx >= SCENE_SIZE || sy < 0 || sy >= SCENE_SIZE)
						{
							continue;
						}

						Tile tile = tiles[plane][sx][sy];
						if (tile == null)
						{
							continue;
						}

						int worldX = client.getBaseX() + sx;
						int worldY = client.getBaseY() + sy;

						GameObject[] gameObjects = tile.getGameObjects();
						if (gameObjects != null)
						{
							for (GameObject obj : gameObjects)
							{
								if (obj == null)
								{
									continue;
								}
								objects.add(serializeSceneObject(obj.getId(), "GAME_OBJECT", worldX, worldY, plane));
							}
						}

						WallObject wall = tile.getWallObject();
						if (wall != null)
						{
							objects.add(serializeSceneObject(wall.getId(), "WALL_OBJECT", worldX, worldY, plane));
						}

						GroundObject ground = tile.getGroundObject();
						if (ground != null)
						{
							objects.add(serializeSceneObject(ground.getId(), "GROUND_OBJECT", worldX, worldY, plane));
						}

						DecorativeObject deco = tile.getDecorativeObject();
						if (deco != null)
						{
							objects.add(serializeSceneObject(deco.getId(), "DECORATIVE_OBJECT", worldX, worldY, plane));
						}
					}
				}

				Map<String, Object> result = new LinkedHashMap<>();
				result.put("radius", radius);
				result.put("count", objects.size());
				result.put("objects", objects);
				return result;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private Map<String, Object> serializeSceneObject(int id, String type, int worldX, int worldY, int plane)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("type", type);

		try
		{
			ObjectComposition comp = client.getObjectDefinition(id);
			if (comp != null)
			{
				String name = comp.getName();
				if (name != null && !name.equals("null"))
				{
					m.put("name", name);
				}
				m.put("actions", filterActions(comp.getActions()));

				try
				{
					ObjectComposition impostor = comp.getImpostor();
					if (impostor != null && impostor.getId() != id)
					{
						m.put("impostorId", impostor.getId());
						m.put("impostorName", impostor.getName());
					}
				}
				catch (Exception ignored)
				{
				}
			}
		}
		catch (Exception ignored)
		{
		}

		Map<String, Object> pos = new LinkedHashMap<>();
		pos.put("x", worldX);
		pos.put("y", worldY);
		pos.put("plane", plane);
		m.put("position", pos);

		return m;
	}

	private void handleGroundItems(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		int radius = Math.min(intParam(params, "radius", DEFAULT_RADIUS), MAX_RADIUS);

		try
		{
			Object data = onClientThread(() ->
			{
				Player local = client.getLocalPlayer();
				if (local == null)
				{
					return Collections.singletonMap("error", "No local player");
				}

				LocalPoint lp = local.getLocalLocation();
				int centerX = lp.getSceneX();
				int centerY = lp.getSceneY();
				int plane = client.getPlane();

				Scene scene = client.getScene();
				Tile[][][] tiles = scene.getTiles();

				List<Map<String, Object>> items = new ArrayList<>();

				for (int dx = -radius; dx <= radius; dx++)
				{
					for (int dy = -radius; dy <= radius; dy++)
					{
						int sx = centerX + dx;
						int sy = centerY + dy;
						if (sx < 0 || sx >= SCENE_SIZE || sy < 0 || sy >= SCENE_SIZE)
						{
							continue;
						}

						Tile tile = tiles[plane][sx][sy];
						if (tile == null)
						{
							continue;
						}

						int worldX = client.getBaseX() + sx;
						int worldY = client.getBaseY() + sy;

						try
						{
							List<TileItem> groundItems = tile.getGroundItems();
							if (groundItems != null)
							{
								for (TileItem tileItem : groundItems)
								{
									if (tileItem == null)
									{
										continue;
									}
									Map<String, Object> entry = new LinkedHashMap<>();
									entry.put("itemId", tileItem.getId());
									entry.put("name", itemName(tileItem.getId()));
									entry.put("quantity", tileItem.getQuantity());
									Map<String, Object> pos = new LinkedHashMap<>();
									pos.put("x", worldX);
									pos.put("y", worldY);
									pos.put("plane", plane);
									entry.put("position", pos);
									items.add(entry);
								}
							}
						}
						catch (Exception ignored)
						{
						}
					}
				}

				Map<String, Object> result = new LinkedHashMap<>();
				result.put("radius", radius);
				result.put("count", items.size());
				result.put("items", items);
				return result;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleVarbit(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		Map<String, String> params = parseQuery(exchange.getRequestURI());

		if (params.containsKey("from") && params.containsKey("to"))
		{
			int from = intParam(params, "from", 0);
			int to = intParam(params, "to", 0);
			if (to < from || to - from > 500)
			{
				sendError(exchange, 400, "Range too large (max 500) or invalid");
				return;
			}
			try
			{
				Object data = onClientThread(() ->
				{
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("from", from);
					result.put("to", to);
					Map<Integer, Integer> values = new LinkedHashMap<>();
					for (int i = from; i <= to; i++)
					{
						try
						{
							values.put(i, client.getVarbitValue(i));
						}
						catch (Exception ignored)
						{
						}
					}
					result.put("values", values);
					return result;
				});
				sendJson(exchange, 200, data);
			}
			catch (Exception e)
			{
				sendError(exchange, 500, e.getMessage());
			}
		}
		else if (params.containsKey("id"))
		{
			int id = intParam(params, "id", -1);
			if (id < 0)
			{
				sendError(exchange, 400, "Invalid varbit ID");
				return;
			}
			try
			{
				Object data = onClientThread(() ->
				{
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("varbitId", id);
					result.put("value", client.getVarbitValue(id));
					return result;
				});
				sendJson(exchange, 200, data);
			}
			catch (Exception e)
			{
				sendError(exchange, 500, e.getMessage());
			}
		}
		else
		{
			sendError(exchange, 400, "Missing parameter: id or from/to");
		}
	}

	private void handleVarp(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		Map<String, String> params = parseQuery(exchange.getRequestURI());

		if (params.containsKey("from") && params.containsKey("to"))
		{
			int from = intParam(params, "from", 0);
			int to = intParam(params, "to", 0);
			if (to < from || to - from > 500)
			{
				sendError(exchange, 400, "Range too large (max 500) or invalid");
				return;
			}
			try
			{
				Object data = onClientThread(() ->
				{
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("from", from);
					result.put("to", to);
					Map<Integer, Integer> values = new LinkedHashMap<>();
					for (int i = from; i <= to; i++)
					{
						try
						{
							values.put(i, client.getVarpValue(i));
						}
						catch (Exception ignored)
						{
						}
					}
					result.put("values", values);
					return result;
				});
				sendJson(exchange, 200, data);
			}
			catch (Exception e)
			{
				sendError(exchange, 500, e.getMessage());
			}
		}
		else if (params.containsKey("id"))
		{
			int id = intParam(params, "id", -1);
			if (id < 0)
			{
				sendError(exchange, 400, "Invalid varp ID");
				return;
			}
			try
			{
				Object data = onClientThread(() ->
				{
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("varpId", id);
					result.put("value", client.getVarpValue(id));
					return result;
				});
				sendJson(exchange, 200, data);
			}
			catch (Exception e)
			{
				sendError(exchange, 500, e.getMessage());
			}
		}
		else
		{
			sendError(exchange, 400, "Missing parameter: id or from/to");
		}
	}

	private void handleItemDef(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		int id = intParam(params, "id", -1);
		if (id < 0)
		{
			sendError(exchange, 400, "Missing or invalid parameter: id");
			return;
		}

		try
		{
			Object data = onClientThread(() ->
			{
				ItemComposition comp = client.getItemDefinition(id);
				if (comp == null)
				{
					return Collections.singletonMap("error", "Item not found");
				}

				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", id);
				m.put("name", comp.getName());
				m.put("isMembers", comp.isMembers());
				m.put("isStackable", comp.isStackable());
				m.put("haPrice", comp.getHaPrice());
				m.put("price", comp.getPrice());
				m.put("note", comp.getNote());
				m.put("linkedNoteId", comp.getLinkedNoteId());
				m.put("placeholderTemplateId", comp.getPlaceholderTemplateId());
				m.put("placeholderId", comp.getPlaceholderId());

				try
				{
					m.put("inventoryActions", filterActions(comp.getInventoryActions()));
				}
				catch (Exception ignored)
				{
				}

				return m;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleNpcDef(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		int id = intParam(params, "id", -1);
		if (id < 0)
		{
			sendError(exchange, 400, "Missing or invalid parameter: id");
			return;
		}

		try
		{
			Object data = onClientThread(() ->
			{
				NPCComposition comp = client.getNpcDefinition(id);
				if (comp == null)
				{
					return Collections.singletonMap("error", "NPC not found");
				}

				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", comp.getId());
				m.put("name", comp.getName());
				m.put("combatLevel", comp.getCombatLevel());
				m.put("size", comp.getSize());
				m.put("isMinimapVisible", comp.isMinimapVisible());
				m.put("actions", filterActions(comp.getActions()));

				try
				{
					int[] configs = comp.getConfigs();
					if (configs != null && configs.length > 0)
					{
						List<Integer> configList = new ArrayList<>();
						for (int c : configs)
						{
							configList.add(c);
						}
						m.put("configs", configList);
					}
				}
				catch (Exception ignored)
				{
				}

				try
				{
					NPCComposition transformed = comp.transform();
					if (transformed != null && transformed.getId() != id)
					{
						Map<String, Object> t = new LinkedHashMap<>();
						t.put("id", transformed.getId());
						t.put("name", transformed.getName());
						t.put("combatLevel", transformed.getCombatLevel());
						m.put("transformed", t);
					}
				}
				catch (Exception ignored)
				{
				}

				return m;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleObjDef(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		int id = intParam(params, "id", -1);
		if (id < 0)
		{
			sendError(exchange, 400, "Missing or invalid parameter: id");
			return;
		}

		try
		{
			Object data = onClientThread(() ->
			{
				ObjectComposition comp = client.getObjectDefinition(id);
				if (comp == null)
				{
					return Collections.singletonMap("error", "Object not found");
				}

				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", comp.getId());
				m.put("name", comp.getName());
				m.put("actions", filterActions(comp.getActions()));
				m.put("mapSceneId", comp.getMapSceneId());

				try
				{
					int[] impostorIds = comp.getImpostorIds();
					if (impostorIds != null && impostorIds.length > 0)
					{
						List<Integer> idList = new ArrayList<>();
						for (int impId : impostorIds)
						{
							idList.add(impId);
						}
						m.put("impostorIds", idList);
					}
				}
				catch (Exception ignored)
				{
				}

				try
				{
					ObjectComposition impostor = comp.getImpostor();
					if (impostor != null && impostor.getId() != id)
					{
						Map<String, Object> imp = new LinkedHashMap<>();
						imp.put("id", impostor.getId());
						imp.put("name", impostor.getName());
						imp.put("actions", filterActions(impostor.getActions()));
						m.put("impostor", imp);
					}
				}
				catch (Exception ignored)
				{
				}

				return m;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleChat(HttpExchange exchange) throws IOException
	{
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		String filterType = params.get("type");

		List<Map<String, Object>> messages;
		if (filterType != null)
		{
			messages = new ArrayList<>();
			for (Map<String, Object> msg : chatBuffer)
			{
				if (filterType.equalsIgnoreCase((String) msg.get("type")))
				{
					messages.add(msg);
				}
			}
		}
		else
		{
			messages = new ArrayList<>(chatBuffer);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("count", messages.size());
		result.put("messages", messages);
		sendJson(exchange, 200, result);
	}

	private void handleScene(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			Object data = onClientThread(() ->
			{
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("baseX", client.getBaseX());
				m.put("baseY", client.getBaseY());
				m.put("plane", client.getPlane());
				m.put("isInInstancedRegion", client.isInInstancedRegion());

				int[] regions = client.getMapRegions();
				if (regions != null)
				{
					List<Integer> regionList = new ArrayList<>();
					for (int r : regions)
					{
						regionList.add(r);
					}
					m.put("mapRegions", regionList);
				}

				m.put("sceneSize", SCENE_SIZE);

				Player local = client.getLocalPlayer();
				if (local != null)
				{
					m.put("playerSceneX", local.getLocalLocation().getSceneX());
					m.put("playerSceneY", local.getLocalLocation().getSceneY());
				}

				return m;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleTile(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		if (!params.containsKey("x") || !params.containsKey("y"))
		{
			sendError(exchange, 400, "Missing parameters: x, y (world coordinates)");
			return;
		}
		int worldX = intParam(params, "x", 0);
		int worldY = intParam(params, "y", 0);
		int plane = intParam(params, "plane", -1);

		try
		{
			Object data = onClientThread(() ->
			{
				int usePlane = plane >= 0 ? plane : client.getPlane();
				int sceneX = worldX - client.getBaseX();
				int sceneY = worldY - client.getBaseY();

				if (sceneX < 0 || sceneX >= SCENE_SIZE || sceneY < 0 || sceneY >= SCENE_SIZE)
				{
					return Collections.singletonMap("error", "Tile out of loaded scene range");
				}

				Map<String, Object> m = new LinkedHashMap<>();
				m.put("worldX", worldX);
				m.put("worldY", worldY);
				m.put("plane", usePlane);
				m.put("sceneX", sceneX);
				m.put("sceneY", sceneY);

				try
				{
					CollisionData[] collisionData = client.getCollisionMaps();
					if (collisionData != null && collisionData[usePlane] != null)
					{
						int[][] flags = collisionData[usePlane].getFlags();
						int flag = flags[sceneX][sceneY];
						m.put("collisionFlags", flag);

						List<String> blocked = new ArrayList<>();
						if ((flag & 0x1) != 0)
						{
							blocked.add("WEST");
						}
						if ((flag & 0x2) != 0)
						{
							blocked.add("EAST");
						}
						if ((flag & 0x4) != 0)
						{
							blocked.add("SOUTH");
						}
						if ((flag & 0x8) != 0)
						{
							blocked.add("NORTH");
						}
						if ((flag & 0x100) != 0)
						{
							blocked.add("FULL_BLOCK");
						}
						if ((flag & 0x20000) != 0)
						{
							blocked.add("FLOOR_DECORATION");
						}
						if ((flag & 0x200000) != 0)
						{
							blocked.add("OBJECT");
						}
						if ((flag & 0x40000000) != 0)
						{
							blocked.add("FLOOR");
						}
						m.put("blockedDirections", blocked);
					}
				}
				catch (Exception ignored)
				{
				}

				Scene scene = client.getScene();
				Tile tile = scene.getTiles()[usePlane][sceneX][sceneY];
				if (tile != null)
				{
					List<Map<String, Object>> tileObjects = new ArrayList<>();

					GameObject[] gameObjects = tile.getGameObjects();
					if (gameObjects != null)
					{
						for (GameObject obj : gameObjects)
						{
							if (obj != null)
							{
								tileObjects.add(serializeSceneObject(obj.getId(), "GAME_OBJECT", worldX, worldY, usePlane));
							}
						}
					}

					WallObject wall = tile.getWallObject();
					if (wall != null)
					{
						tileObjects.add(serializeSceneObject(wall.getId(), "WALL_OBJECT", worldX, worldY, usePlane));
					}

					GroundObject ground = tile.getGroundObject();
					if (ground != null)
					{
						tileObjects.add(serializeSceneObject(ground.getId(), "GROUND_OBJECT", worldX, worldY, usePlane));
					}

					DecorativeObject deco = tile.getDecorativeObject();
					if (deco != null)
					{
						tileObjects.add(serializeSceneObject(deco.getId(), "DECORATIVE_OBJECT", worldX, worldY, usePlane));
					}

					if (!tileObjects.isEmpty())
					{
						m.put("objects", tileObjects);
					}

					try
					{
						List<TileItem> groundItems = tile.getGroundItems();
						if (groundItems != null && !groundItems.isEmpty())
						{
							List<Map<String, Object>> items = new ArrayList<>();
							for (TileItem item : groundItems)
							{
								if (item != null)
								{
									Map<String, Object> itemData = new LinkedHashMap<>();
									itemData.put("itemId", item.getId());
									itemData.put("name", itemName(item.getId()));
									itemData.put("quantity", item.getQuantity());
									items.add(itemData);
								}
							}
							m.put("groundItems", items);
						}
					}
					catch (Exception ignored)
					{
					}
				}

				return m;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}
}
