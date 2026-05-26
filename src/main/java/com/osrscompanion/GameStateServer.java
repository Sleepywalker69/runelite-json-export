package com.osrscompanion;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import com.google.inject.Provides;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.DrawManager;

import java.lang.reflect.Method;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import javax.imageio.ImageIO;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
public class GameStateServer
{
	private final Client client;
	private final ClientThread clientThread;
	private final Gson gson;
	private final PluginManager pluginManager;
	private final ConfigManager configManager;
	private final DrawManager drawManager;
	private final TickStateBuffer tickBuffer;
	private final LogCaptureAppender logCaptureAppender;
	private final ActionTracker actionTracker;
	private HttpServer server;

	private final List<Map<String, Object>> chatBuffer = Collections.synchronizedList(new LinkedList<>());
	private static final int MAX_CHAT_BUFFER = 200;
	private static final int DEFAULT_RADIUS = 15;
	private static final int MAX_RADIUS = 50;
	private static final int SCENE_SIZE = 104;

	private final List<SseClient> sseClients = new CopyOnWriteArrayList<>();
	private final Gson compactGson;
	private ExecutorService serverExecutor;
	private final OkHttpClient httpClient;

	private static final String WIKI_API_BASE = "https://oldschool.runescape.wiki/api.php";
	private static final String WIKI_USER_AGENT = "osrs-companion-runelite-plugin/1.0 (RuneLite plugin; local-proxy)";

	private volatile Map<String, List<Integer>> itemNameIndex;
	private volatile Map<String, List<Integer>> npcNameIndex;
	private volatile Map<String, List<Integer>> objNameIndex;

	private long sessionStartMs;
	private final Map<String, Integer> xpBaselines = Collections.synchronizedMap(new LinkedHashMap<>());
	private final List<Map<String, Object>> lootLog = Collections.synchronizedList(new ArrayList<>());

	private final Set<Integer> previousInterfaces = new HashSet<>();

	private final List<Map<String, Object>> varHistory = Collections.synchronizedList(new ArrayList<>());
	private static final int MAX_VAR_HISTORY = 200;
	private final List<Map<String, Object>> interactionHistory = Collections.synchronizedList(new ArrayList<>());
	private static final int MAX_INTERACTION_HISTORY = 200;
	private String lastHoverTarget = "";

	// Recording system
	private volatile boolean isRecording = false;
	private int recordingStartTick = 0;
	private int recordingMaxTicks = 500;
	private final List<Map<String, Object>> recordingBuffer = Collections.synchronizedList(new ArrayList<>());
	private static final int MAX_RECORDING_ENTRIES = 10_000;
	private int recordingTickCounter = 0; // for throttling game_tick recordings
	private Set<String> recordingEventFilter = null; // null = record all types

	private static final Map<Integer, String> KNOWN_INTERFACES;
	static
	{
		Map<Integer, String> m = new LinkedHashMap<>();
		m.put(12, "Bank");
		m.put(13, "Bank Pin");
		m.put(69, "Deposit Box");
		m.put(149, "Inventory");
		m.put(160, "Trade Screen");
		m.put(162, "Chatbox");
		m.put(182, "Logout Panel");
		m.put(187, "World Switcher");
		m.put(218, "Standard Spellbook");
		m.put(239, "Collection Log");
		m.put(261, "Settings");
		m.put(270, "Shop");
		m.put(300, "Quest Journal");
		m.put(320, "Skills Tab");
		m.put(334, "Trade Partner");
		m.put(383, "Adventure Log");
		m.put(387, "Equipment");
		m.put(399, "Grand Exchange");
		m.put(429, "Prayer Tab");
		m.put(430, "Music Tab");
		m.put(432, "Emote Tab");
		m.put(541, "Combat Options");
		m.put(593, "Ancient Spellbook");
		m.put(621, "Seed Vault");
		KNOWN_INTERFACES = Collections.unmodifiableMap(m);
	}

	public GameStateServer(Client client, ClientThread clientThread, Gson gson,
		PluginManager pluginManager, ConfigManager configManager,
		DrawManager drawManager, TickStateBuffer tickBuffer,
		LogCaptureAppender logCaptureAppender, ActionTracker actionTracker)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.gson = gson.newBuilder().setPrettyPrinting().create();
		this.compactGson = new Gson();
		this.pluginManager = pluginManager;
		this.configManager = configManager;
		this.drawManager = drawManager;
		this.tickBuffer = tickBuffer;
		this.logCaptureAppender = logCaptureAppender;
		this.actionTracker = actionTracker;
		this.httpClient = new OkHttpClient.Builder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(15, TimeUnit.SECONDS)
			.build();
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
		server.createContext("/api/dialog", this::handleDialog);
		server.createContext("/api/screenshot", this::handleScreenshot);
		server.createContext("/api/buffer", this::handleBuffer);
		server.createContext("/api/logs", this::handleLogs);
		server.createContext("/api/actions", this::handleActions);
		server.createContext("/api/scene", this::handleScene);
		server.createContext("/api/tile", this::handleTile);
		server.createContext("/api/plugins", this::handlePlugins);
		server.createContext("/api/plugin-config", this::handlePluginConfig);
		server.createContext("/api/xp-tracker", this::handleXpTracker);
		server.createContext("/api/loot", this::handleLoot);
		server.createContext("/api/interfaces", this::handleInterfaces);
		server.createContext("/api/menu-entries", this::handleMenuEntries);
		server.createContext("/api/events", this::handleEvents);
		server.createContext("/api/projectiles", this::handleProjectiles);
		server.createContext("/api/camera", this::handleCamera);
		server.createContext("/api/wiki/search", this::handleWikiSearch);
		server.createContext("/api/wiki/page-info", this::handleWikiPageInfo);
		server.createContext("/api/wiki/parse", this::handleWikiParse);
		server.createContext("/api/varbit-def", this::handleVarbitDef);
		server.createContext("/api/struct-def", this::handleStructDef);
		server.createContext("/api/enum-def", this::handleEnumDef);
		server.createContext("/api/player-appearance", this::handlePlayerAppearance);
		server.createContext("/api/var-history", this::handleVarHistory);
		server.createContext("/api/interaction-history", this::handleInteractionHistory);
		server.createContext("/api/graphics-objects", this::handleGraphicsObjects);
		server.createContext("/api/prayers", this::handlePrayers);
		server.createContext("/api/recording/start", this::handleRecordingStart);
		server.createContext("/api/recording/stop", this::handleRecordingStop);
		server.createContext("/api/recording/status", this::handleRecordingStatus);
		server.createContext("/api/recording/data", this::handleRecordingData);
		server.createContext("/", this::handleIndex);

		serverExecutor = Executors.newCachedThreadPool(r ->
		{
			Thread t = new Thread(r, "osrs-companion-http");
			t.setDaemon(true);
			return t;
		});
		server.setExecutor(serverExecutor);
		server.start();
		log.info("OSRS Companion API server started on http://127.0.0.1:{}", port);
	}

	public void stop()
	{
		for (SseClient c : sseClients)
		{
			c.alive = false;
			c.close();
		}
		sseClients.clear();

		if (server != null)
		{
			server.stop(1);
			server = null;
			log.info("OSRS Companion API server stopped");
		}

		if (serverExecutor != null)
		{
			serverExecutor.shutdownNow();
			serverExecutor = null;
		}

		httpClient.dispatcher().executorService().shutdown();
		httpClient.connectionPool().evictAll();
	}

	public void clearNameCaches()
	{
		itemNameIndex = null;
		npcNameIndex = null;
		objNameIndex = null;
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

	public void startSession()
	{
		sessionStartMs = System.currentTimeMillis();
		xpBaselines.clear();
		lootLog.clear();
	}

	public void setXpBaseline(String skill, int xp)
	{
		xpBaselines.put(skill, xp);
	}

	public void addLootDrop(Map<String, Object> drop)
	{
		lootLog.add(drop);
	}

	public void updateActiveInterfaces(Set<Integer> current)
	{
		if (!hasSseClients())
		{
			previousInterfaces.clear();
			previousInterfaces.addAll(current);
			return;
		}

		for (int groupId : current)
		{
			if (!previousInterfaces.contains(groupId))
			{
				Map<String, Object> data = new LinkedHashMap<>();
				data.put("tick", client.getTickCount());
				data.put("timestamp", System.currentTimeMillis());
				data.put("groupId", groupId);
				data.put("name", KNOWN_INTERFACES.get(groupId));
				data.put("action", "opened");
				broadcastEvent("interface_changed", data);
			}
		}

		for (int groupId : previousInterfaces)
		{
			if (!current.contains(groupId))
			{
				Map<String, Object> data = new LinkedHashMap<>();
				data.put("tick", client.getTickCount());
				data.put("timestamp", System.currentTimeMillis());
				data.put("groupId", groupId);
				data.put("name", KNOWN_INTERFACES.get(groupId));
				data.put("action", "closed");
				broadcastEvent("interface_changed", data);
			}
		}

		previousInterfaces.clear();
		previousInterfaces.addAll(current);
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
					try
					{
						params.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
					}
					catch (UnsupportedEncodingException e)
					{
						params.put(kv[0], kv[1]);
					}
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

	private void sendRawJson(HttpExchange exchange, int code, String json) throws IOException
	{
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

	// ==================== SSE Event Stream ====================

	private static class SseClient
	{
		final OutputStream output;
		final Set<String> filters;
		volatile boolean alive = true;

		SseClient(OutputStream output, Set<String> filters)
		{
			this.output = output;
			this.filters = filters;
		}

		synchronized boolean send(byte[] data)
		{
			if (!alive)
			{
				return false;
			}
			try
			{
				output.write(data);
				output.flush();
				return true;
			}
			catch (IOException e)
			{
				alive = false;
				return false;
			}
		}

		void close()
		{
			alive = false;
			try
			{
				output.close();
			}
			catch (Exception ignored)
			{
			}
		}
	}

	public boolean hasSseClients()
	{
		return !sseClients.isEmpty();
	}

	public void broadcastEvent(String type, Map<String, Object> data)
	{
		if (sseClients.isEmpty())
		{
			return;
		}

		byte[] message = formatSseMessage(type, data);

		for (SseClient client : sseClients)
		{
			if (client.filters != null && !client.filters.contains(type))
			{
				continue;
			}
			if (!client.send(message))
			{
				sseClients.remove(client);
			}
		}
	}

	private byte[] formatSseMessage(String type, Object data)
	{
		String json = compactGson.toJson(data);
		return ("event: " + type + "\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
	}

	private void handleEvents(HttpExchange exchange) throws IOException
	{
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		Set<String> filters = null;
		String filterParam = params.get("filter");
		if (filterParam != null && !filterParam.isEmpty())
		{
			filters = new HashSet<>(Arrays.asList(filterParam.split(",")));
		}

		exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
		exchange.getResponseHeaders().set("Cache-Control", "no-cache");
		exchange.getResponseHeaders().set("Connection", "keep-alive");
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		exchange.sendResponseHeaders(200, 0);

		OutputStream os = exchange.getResponseBody();
		SseClient client = new SseClient(os, filters);
		sseClients.add(client);

		Map<String, Object> connData = new LinkedHashMap<>();
		connData.put("message", "Connected to OSRS Companion event stream");
		connData.put("availableEvents", Arrays.asList(
			"game_tick", "stat_changed", "hitsplat", "animation_changed",
			"npc_spawned", "npc_despawned", "actor_death", "interacting_changed",
			"chat_message", "game_state_changed", "menu_clicked", "item_changed",
			"loot_received", "sound_effect", "interface_changed"
		));
		connData.put("filter", filterParam != null ? filterParam : "all");
		connData.put("timestamp", System.currentTimeMillis());
		client.send(formatSseMessage("connected", connData));

		try
		{
			while (client.alive && !Thread.currentThread().isInterrupted())
			{
				Thread.sleep(15000);
				if (!client.send(": keepalive\n\n".getBytes(StandardCharsets.UTF_8)))
				{
					break;
				}
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		finally
		{
			sseClients.remove(client);
			client.close();
		}
	}

	// ==================== New Endpoints ====================

	private void handleProjectiles(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			Object data = onClientThread(() ->
			{
				List<Map<String, Object>> projectiles = new ArrayList<>();
				for (Projectile p : client.getProjectiles())
				{
					Map<String, Object> proj = new LinkedHashMap<>();
					proj.put("id", p.getId());
					proj.put("floor", p.getFloor());
					proj.put("startX", (int) p.getX1());
					proj.put("startY", (int) p.getY1());
					proj.put("startHeight", p.getHeight());
					proj.put("endHeight", p.getEndHeight());
					proj.put("startCycle", p.getStartCycle());
					proj.put("endCycle", p.getEndCycle());
					proj.put("remainingCycles", p.getRemainingCycles());
					proj.put("slope", p.getSlope());

					Actor target = p.getInteracting();
					if (target != null)
					{
						Map<String, Object> targetInfo = new LinkedHashMap<>();
						targetInfo.put("name", target.getName());
						if (target instanceof NPC)
						{
							targetInfo.put("type", "NPC");
							targetInfo.put("id", ((NPC) target).getId());
							targetInfo.put("index", ((NPC) target).getIndex());
						}
						else if (target instanceof Player)
						{
							targetInfo.put("type", "PLAYER");
						}
						targetInfo.put("position", worldPoint(target.getWorldLocation()));
						proj.put("target", targetInfo);
					}

					projectiles.add(proj);
				}

				Map<String, Object> result = new LinkedHashMap<>();
				result.put("tick", client.getTickCount());
				result.put("count", projectiles.size());
				result.put("projectiles", projectiles);
				return result;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleCamera(HttpExchange exchange) throws IOException
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
				m.put("x", client.getCameraX());
				m.put("y", client.getCameraY());
				m.put("z", client.getCameraZ());
				m.put("pitch", client.getCameraPitch());
				m.put("yaw", client.getCameraYaw());
				return m;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	// ==================== Plugin Introspection ====================

	private void handlePlugins(HttpExchange exchange) throws IOException
	{
		try
		{
			List<Map<String, Object>> plugins = new ArrayList<>();
			for (net.runelite.client.plugins.Plugin plugin : pluginManager.getPlugins())
			{
				Map<String, Object> info = new LinkedHashMap<>();
				PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
				if (descriptor != null)
				{
					info.put("name", descriptor.name());
					info.put("description", descriptor.description());
					info.put("tags", Arrays.asList(descriptor.tags()));
				}
				else
				{
					info.put("name", plugin.getClass().getSimpleName());
					info.put("description", "");
					info.put("tags", Collections.emptyList());
				}
				info.put("enabled", pluginManager.isPluginEnabled(plugin));
				info.put("className", plugin.getClass().getSimpleName());

				String configGroup = findConfigGroup(plugin);
				if (configGroup != null)
				{
					info.put("configGroup", configGroup);
				}

				plugins.add(info);
			}

			plugins.sort((a, b) -> String.valueOf(a.get("name"))
				.compareToIgnoreCase(String.valueOf(b.get("name"))));

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("count", plugins.size());
			result.put("plugins", plugins);
			sendJson(exchange, 200, result);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handlePluginConfig(HttpExchange exchange) throws IOException
	{
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		String group = params.get("group");
		String pluginName = params.get("plugin");

		if (group == null && pluginName == null)
		{
			sendError(exchange, 400, "Required: ?group=configGroupName or ?plugin=PluginName");
			return;
		}

		try
		{
			if (group == null)
			{
				for (net.runelite.client.plugins.Plugin plugin : pluginManager.getPlugins())
				{
					PluginDescriptor desc = plugin.getClass().getAnnotation(PluginDescriptor.class);
					String name = desc != null ? desc.name() : plugin.getClass().getSimpleName();
					if (name.equalsIgnoreCase(pluginName))
					{
						group = findConfigGroup(plugin);
						break;
					}
				}
				if (group == null)
				{
					sendError(exchange, 404, "Plugin not found or has no config: " + pluginName);
					return;
				}
			}

			List<String> fullKeys = configManager.getConfigurationKeys(group + ".");
			Map<String, Object> config = new LinkedHashMap<>();
			if (fullKeys != null)
			{
				for (String fullKey : fullKeys)
				{
					String key = fullKey.substring(group.length() + 1);
					String value = configManager.getConfiguration(group, key);
					config.put(key, value);
				}
			}

			Map<String, Object> result = new LinkedHashMap<>();
			if (pluginName != null)
			{
				result.put("plugin", pluginName);
			}
			result.put("configGroup", group);
			result.put("configCount", config.size());
			result.put("config", config);
			sendJson(exchange, 200, result);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private String findConfigGroup(net.runelite.client.plugins.Plugin plugin)
	{
		try
		{
			for (Method m : plugin.getClass().getDeclaredMethods())
			{
				if (m.isAnnotationPresent(Provides.class))
				{
					Class<?> returnType = m.getReturnType();
					ConfigGroup cg = returnType.getAnnotation(ConfigGroup.class);
					if (cg != null)
					{
						return cg.value();
					}
				}
			}
		}
		catch (Exception ignored)
		{
		}
		return null;
	}

	// ==================== Session Tracking ====================

	private void handleXpTracker(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			Object data = onClientThread(() ->
			{
				Map<String, Object> result = new LinkedHashMap<>();
				long now = System.currentTimeMillis();
				long durationMs = sessionStartMs > 0 ? now - sessionStartMs : 0;
				result.put("sessionStartMs", sessionStartMs);
				result.put("sessionDurationMs", durationMs);
				result.put("sessionDurationFormatted", formatDuration(durationMs));

				int totalGained = 0;
				Map<String, Object> skills = new LinkedHashMap<>();
				for (Skill skill : Skill.values())
				{
					if (skill == Skill.OVERALL)
					{
						continue;
					}
					int currentXp = client.getSkillExperience(skill);
					int startXp = xpBaselines.getOrDefault(skill.name(), currentXp);
					int gained = currentXp - startXp;
					totalGained += gained;

					Map<String, Object> skillData = new LinkedHashMap<>();
					skillData.put("level", client.getRealSkillLevel(skill));
					skillData.put("boostedLevel", client.getBoostedSkillLevel(skill));
					skillData.put("startXp", startXp);
					skillData.put("currentXp", currentXp);
					skillData.put("gained", gained);
					if (durationMs > 60000 && gained > 0)
					{
						skillData.put("xpPerHour", (int) (gained * 3600000.0 / durationMs));
					}
					skills.put(skill.name(), skillData);
				}

				result.put("totalXpGained", totalGained);
				if (durationMs > 60000 && totalGained > 0)
				{
					result.put("totalXpPerHour", (int) (totalGained * 3600000.0 / durationMs));
				}
				result.put("skills", skills);
				return result;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleLoot(HttpExchange exchange) throws IOException
	{
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		String npcFilter = params.get("npc");
		int last = intParam(params, "last", 0);

		try
		{
			List<Map<String, Object>> drops;
			synchronized (lootLog)
			{
				drops = new ArrayList<>(lootLog);
			}

			if (npcFilter != null && !npcFilter.isEmpty())
			{
				drops.removeIf(d -> !npcFilter.equalsIgnoreCase((String) d.get("npcName")));
			}

			if (last > 0 && drops.size() > last)
			{
				drops = drops.subList(drops.size() - last, drops.size());
			}

			Map<String, Map<String, Object>> summary = new LinkedHashMap<>();
			synchronized (lootLog)
			{
				for (Map<String, Object> drop : lootLog)
				{
					String npcName = (String) drop.get("npcName");
					Map<String, Object> s = summary.computeIfAbsent(npcName, k ->
					{
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("kills", 0);
						return m;
					});
					s.put("kills", (int) s.get("kills") + 1);
				}
			}

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("totalDrops", lootLog.size());
			result.put("summary", summary);
			result.put("drops", drops);
			sendJson(exchange, 200, result);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private String formatDuration(long ms)
	{
		long totalSeconds = ms / 1000;
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;
		if (hours > 0)
		{
			return String.format("%dh %dm %ds", hours, minutes, seconds);
		}
		return String.format("%dm %ds", minutes, seconds);
	}

	// ==================== Interface & Menu Inspection ====================

	private void handleInterfaces(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			Object data = onClientThread(() ->
			{
				List<Map<String, Object>> interfaces = new ArrayList<>();
				for (int groupId = 0; groupId < 800; groupId++)
				{
					Widget w = client.getWidget(groupId, 0);
					if (w == null)
					{
						continue;
					}
					boolean hidden = w.isSelfHidden();
					Map<String, Object> iface = new LinkedHashMap<>();
					iface.put("groupId", groupId);
					String name = KNOWN_INTERFACES.get(groupId);
					if (name != null)
					{
						iface.put("name", name);
					}
					iface.put("hidden", hidden);

					if (!hidden)
					{
						String text = w.getText();
						if (text != null && !text.isEmpty())
						{
							iface.put("rootText", cleanName(text));
						}
						String widgetName = w.getName();
						if (widgetName != null && !widgetName.isEmpty())
						{
							iface.put("rootName", cleanName(widgetName));
						}
					}

					interfaces.add(iface);
				}

				Map<String, Object> result = new LinkedHashMap<>();
				result.put("totalLoaded", interfaces.size());

				List<Map<String, Object>> visible = new ArrayList<>();
				for (Map<String, Object> iface : interfaces)
				{
					if (!Boolean.TRUE.equals(iface.get("hidden")))
					{
						visible.add(iface);
					}
				}
				result.put("visibleCount", visible.size());
				result.put("interfaces", interfaces);
				return result;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleMenuEntries(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			Object data = onClientThread(() ->
			{
				MenuEntry[] entries = client.getMenuEntries();
				List<Map<String, Object>> menuList = new ArrayList<>();
				if (entries != null)
				{
					for (MenuEntry entry : entries)
					{
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("option", entry.getOption());
						m.put("target", cleanName(entry.getTarget()));
						m.put("rawTarget", entry.getTarget());
						m.put("type", entry.getType().name());
						m.put("identifier", entry.getIdentifier());
						m.put("param0", entry.getParam0());
						m.put("param1", entry.getParam1());
						m.put("isForceLeftClick", entry.isForceLeftClick());
						menuList.add(m);
					}
				}

				Map<String, Object> result = new LinkedHashMap<>();
				result.put("count", menuList.size());
				result.put("entries", menuList);
				return result;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	// ==================== Endpoints ====================

	private void handleIndex(HttpExchange exchange) throws IOException
	{
		Map<String, Object> index = new LinkedHashMap<>();
		index.put("name", "OSRS MCP Companion API");
		index.put("version", 4);

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
		endpoints.add(ep("GET /api/item-def?id=X", "Item definition by ID. Also: ?name=X for search (?limit=N, ?exact=true)"));
		endpoints.add(ep("GET /api/npc-def?id=X", "NPC definition by ID. Also: ?name=X for search (?limit=N, ?exact=true)"));
		endpoints.add(ep("GET /api/obj-def?id=X", "Object definition by ID. Also: ?name=X for search (?limit=N, ?exact=true)"));
		endpoints.add(ep("GET /api/chat", "Recent chat messages (last 200). ?type=X to filter, ?last=N for recent"));
		endpoints.add(ep("GET /api/dialog", "Current NPC/player dialogue state: text, speaker, options, continue button"));
		endpoints.add(ep("GET /api/screenshot", "Capture game viewport as PNG image (Content-Type: image/png)"));
		endpoints.add(ep("GET /api/buffer", "Tick-level state buffer. ?t=N (pos=absolute tick, neg=last N deltas, default -5). ?types=npc,player,skills,hits. ?names=X &ids=X &tile=x,y,plane &area=x1,y1,x2,y2,plane"));
		endpoints.add(ep("GET /api/logs", "RuneLite console logs. ?level=INFO|WARN|ERROR &logger=X &search=X &last=N (default 100)"));
		endpoints.add(ep("GET /api/actions", "Multi-layer action tracker. ?last=N &source=menu|script|inferred &search=X"));
		endpoints.add(ep("GET /api/scene", "Scene info: base coords, map regions, plane"));
		endpoints.add(ep("GET /api/tile?x=X&y=Y&plane=Z", "Tile info and collision flags at world coordinates"));
		endpoints.add(ep("GET /api/projectiles", "Active projectiles in flight with targets and timing"));
		endpoints.add(ep("GET /api/camera", "Camera position and angles"));
		endpoints.add(ep("GET /api/plugins", "List all loaded RuneLite plugins with enabled state and config group"));
		endpoints.add(ep("GET /api/plugin-config?plugin=X", "Read config for a plugin by name. Also accepts ?group=configGroupName"));
		endpoints.add(ep("GET /api/xp-tracker", "Session XP tracking: gains, rates, and per-skill breakdown"));
		endpoints.add(ep("GET /api/loot", "Session loot log. ?npc=X to filter, ?last=N for recent drops"));
		endpoints.add(ep("GET /api/interfaces", "All active widget groups/interfaces with visibility state"));
		endpoints.add(ep("GET /api/menu-entries", "Current right-click menu entries (context-sensitive)"));
		endpoints.add(ep("GET /api/events", "SSE event stream for real-time game events. ?filter=game_tick,hitsplat,... to subscribe to specific types"));
		endpoints.add(ep("GET /api/wiki/search?q=X", "Search OSRS Wiki. Optional: &limit=N (default 10, max 50)"));
		endpoints.add(ep("GET /api/wiki/page-info?title=X", "Get Wiki page metadata, categories, and links"));
		endpoints.add(ep("GET /api/wiki/parse?title=X", "Parse full Wiki page content. Optional: &section=N"));
		endpoints.add(ep("GET /api/varbit-def?id=X", "Varbit definition: underlying varp index, LSB/MSB bit positions, current value"));
		endpoints.add(ep("GET /api/struct-def?id=X", "Struct definition: all param key-value pairs"));
		endpoints.add(ep("GET /api/enum-def?id=X", "Enum definition: keys and int/string/long value arrays"));
		endpoints.add(ep("GET /api/player-appearance", "Player appearance: equipment, kit, colors, gender. Optional: ?name=X"));
		endpoints.add(ep("GET /api/var-history", "Recent var changes (last 200). ?type=varbit|varp to filter, ?last=N for recent"));
		endpoints.add(ep("GET /api/interaction-history", "Recent clicks and hovers (last 200). ?last=N for recent"));
		endpoints.add(ep("GET /api/graphics-objects", "Active graphics objects (spell effects, visual FX)"));
		endpoints.add(ep("GET /api/prayers", "Currently active prayers and prayer points"));
		endpoints.add(ep("POST /api/recording/start?duration=N&types=X,Y", "Start recording game events. duration=seconds (default 180, max 600). types=comma-separated filter (default: all)"));
		endpoints.add(ep("POST /api/recording/stop", "Stop recording, keep buffered events"));
		endpoints.add(ep("GET /api/recording/status", "Recording state: active, ticks elapsed, events logged"));
		endpoints.add(ep("GET /api/recording/data", "Get recorded events. ?types=game_tick,hitsplat &from_tick=X &to_tick=Y &last=N"));
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

					ItemComposition def = client.getItemDefinition(item.getId());
					entry.put("name", def != null ? def.getName() : null);
					entry.put("quantity", item.getQuantity());

					if (def != null)
					{
						String[] invActions = def.getInventoryActions();
						if (invActions != null)
						{
							entry.put("actions", filterActions(invActions));
						}
					}

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
					entry.put("poseAnimation", npc.getPoseAnimation());
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
					entry.put("poseAnimation", player.getPoseAnimation());
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
				List<Map<String, Object>> groups = new ArrayList<>();
				for (int groupId = 0; groupId < 800; groupId++)
				{
					Widget root = client.getWidget(groupId, 0);
					if (root != null)
					{
						Map<String, Object> group = new LinkedHashMap<>();
						group.put("groupId", groupId);
						groups.add(group);
					}
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
				if (childId >= 0)
				{
					Widget w = client.getWidget(groupId, childId);
					if (w == null)
					{
						Map<String, Object> err = new LinkedHashMap<>();
						err.put("error", "Widget " + groupId + ":" + childId + " not found");
						return err;
					}
					return serializeWidget(w, true, 0);
				}

				List<Map<String, Object>> childList = new ArrayList<>();
				for (int i = 0; i < 500; i++)
				{
					Widget w = client.getWidget(groupId, i);
					if (w == null)
					{
						break;
					}
					childList.add(serializeWidget(w, false, 0));
				}

				if (childList.isEmpty())
				{
					Map<String, Object> err = new LinkedHashMap<>();
					err.put("error", "Widget group " + groupId + " not found or not active");
					return err;
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
		String nameQuery = params.get("name");

		if (nameQuery != null && !nameQuery.isEmpty())
		{
			int limit = Math.min(intParam(params, "limit", 25), 100);
			boolean exact = "true".equals(params.get("exact"));
			try
			{
				if (itemNameIndex == null)
				{
					itemNameIndex = buildItemNameIndex();
				}
				List<Integer> matchIds = searchNameIndex(itemNameIndex, nameQuery, exact, limit);
				Object data = onClientThread(() ->
				{
					List<Map<String, Object>> results = new ArrayList<>();
					for (int matchId : matchIds)
					{
						ItemComposition comp = client.getItemDefinition(matchId);
						if (comp != null)
						{
							results.add(serializeItemDef(matchId, comp));
						}
					}
					Map<String, Object> response = new LinkedHashMap<>();
					response.put("query", nameQuery);
					response.put("matchType", exact ? "exact" : "substring");
					response.put("count", results.size());
					response.put("results", results);
					return response;
				});
				sendJson(exchange, 200, data);
			}
			catch (Exception e)
			{
				sendError(exchange, 500, e.getMessage());
			}
			return;
		}

		int id = intParam(params, "id", -1);
		if (id < 0)
		{
			sendError(exchange, 400, "Missing parameter: id or name");
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
				return serializeItemDef(id, comp);
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
		String nameQuery = params.get("name");

		if (nameQuery != null && !nameQuery.isEmpty())
		{
			int limit = Math.min(intParam(params, "limit", 25), 100);
			boolean exact = "true".equals(params.get("exact"));
			try
			{
				if (npcNameIndex == null)
				{
					npcNameIndex = buildNpcNameIndex();
				}
				List<Integer> matchIds = searchNameIndex(npcNameIndex, nameQuery, exact, limit);
				Object data = onClientThread(() ->
				{
					List<Map<String, Object>> results = new ArrayList<>();
					for (int matchId : matchIds)
					{
						NPCComposition comp = client.getNpcDefinition(matchId);
						if (comp != null)
						{
							results.add(serializeNpcDef(matchId, comp));
						}
					}
					Map<String, Object> response = new LinkedHashMap<>();
					response.put("query", nameQuery);
					response.put("matchType", exact ? "exact" : "substring");
					response.put("count", results.size());
					response.put("results", results);
					return response;
				});
				sendJson(exchange, 200, data);
			}
			catch (Exception e)
			{
				sendError(exchange, 500, e.getMessage());
			}
			return;
		}

		int id = intParam(params, "id", -1);
		if (id < 0)
		{
			sendError(exchange, 400, "Missing parameter: id or name");
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
				return serializeNpcDef(id, comp);
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
		String nameQuery = params.get("name");

		if (nameQuery != null && !nameQuery.isEmpty())
		{
			int limit = Math.min(intParam(params, "limit", 25), 100);
			boolean exact = "true".equals(params.get("exact"));
			try
			{
				if (objNameIndex == null)
				{
					objNameIndex = buildObjNameIndex();
				}
				List<Integer> matchIds = searchNameIndex(objNameIndex, nameQuery, exact, limit);
				Object data = onClientThread(() ->
				{
					List<Map<String, Object>> results = new ArrayList<>();
					for (int matchId : matchIds)
					{
						ObjectComposition comp = client.getObjectDefinition(matchId);
						if (comp != null)
						{
							results.add(serializeObjDef(matchId, comp));
						}
					}
					Map<String, Object> response = new LinkedHashMap<>();
					response.put("query", nameQuery);
					response.put("matchType", exact ? "exact" : "substring");
					response.put("count", results.size());
					response.put("results", results);
					return response;
				});
				sendJson(exchange, 200, data);
			}
			catch (Exception e)
			{
				sendError(exchange, 500, e.getMessage());
			}
			return;
		}

		int id = intParam(params, "id", -1);
		if (id < 0)
		{
			sendError(exchange, 400, "Missing parameter: id or name");
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
				return serializeObjDef(id, comp);
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
		int last = intParam(params, "last", 0);

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

		if (last > 0 && messages.size() > last)
		{
			messages = messages.subList(messages.size() - last, messages.size());
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("count", messages.size());
		result.put("messages", messages);
		sendJson(exchange, 200, result);
	}

	private void handleDialog(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			Object data = onClientThread(() ->
			{
				Map<String, Object> result = new LinkedHashMap<>();

				// NPC dialogue text (group 231, child 4)
				Widget npcDialog = client.getWidget(231, 4);
				// Player dialogue text (group 217, child 4)
				Widget playerDialog = client.getWidget(217, 4);
				// Options container (group 233, child 0)
				Widget optionsWidget = client.getWidget(233, 0);
				// Continue button (group 231, child 5)
				Widget continueWidget = client.getWidget(231, 5);
				// Speaker name (group 231, child 3)
				Widget speakerWidget = client.getWidget(231, 3);

				boolean npcOpen = npcDialog != null && !npcDialog.isHidden();
				boolean playerOpen = playerDialog != null && !playerDialog.isHidden();
				boolean optionsOpen = optionsWidget != null && !optionsWidget.isHidden();
				boolean open = npcOpen || playerOpen || optionsOpen;

				result.put("open", open);
				if (!open)
				{
					result.put("canContinue", false);
					result.put("hasOptions", false);
					return result;
				}

				boolean canContinue = continueWidget != null && !continueWidget.isHidden();
				result.put("canContinue", canContinue);

				// Text content
				String text = null;
				if (npcOpen)
				{
					text = npcDialog.getText();
				}
				else if (playerOpen)
				{
					text = playerDialog.getText();
				}
				if (text != null)
				{
					text = text.replaceAll("<[^>]+>", "").trim();
				}
				result.put("text", text);

				// Speaker name
				String speaker = null;
				if (speakerWidget != null && !speakerWidget.isHidden())
				{
					speaker = speakerWidget.getText();
					if (speaker != null)
					{
						speaker = speaker.replaceAll("<[^>]+>", "").trim();
					}
				}
				result.put("speaker", speaker);

				// Options
				List<String> options = new ArrayList<>();
				if (optionsOpen)
				{
					Widget[] children = optionsWidget.getDynamicChildren();
					if (children != null)
					{
						for (Widget child : children)
						{
							if (child != null)
							{
								String optText = child.getText();
								if (optText != null && !optText.isEmpty())
								{
									options.add(optText.replaceAll("<[^>]+>", "").trim());
								}
							}
						}
					}
				}
				result.put("hasOptions", !options.isEmpty());
				result.put("options", options);

				return result;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleScreenshot(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		try
		{
			CompletableFuture<BufferedImage> future = new CompletableFuture<>();
			drawManager.requestNextFrameListener(image ->
			{
				try
				{
					BufferedImage bi;
					if (image instanceof BufferedImage)
					{
						bi = (BufferedImage) image;
					}
					else
					{
						bi = new BufferedImage(image.getWidth(null), image.getHeight(null),
							BufferedImage.TYPE_INT_RGB);
						java.awt.Graphics2D g = bi.createGraphics();
						try
						{
							g.drawImage(image, 0, 0, null);
						}
						finally
						{
							g.dispose();
						}
					}
					future.complete(bi);
				}
				catch (Throwable t)
				{
					future.completeExceptionally(t);
				}
			});

			BufferedImage img = future.get(5, TimeUnit.SECONDS);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(img, "png", baos);
			byte[] pngBytes = baos.toByteArray();

			exchange.getResponseHeaders().set("Content-Type", "image/png");
			exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
			exchange.sendResponseHeaders(200, pngBytes.length);
			try (OutputStream os = exchange.getResponseBody())
			{
				os.write(pngBytes);
			}
		}
		catch (Exception e)
		{
			sendError(exchange, 500, "Screenshot failed: " + e.getMessage());
		}
	}

	private void handleBuffer(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		if (tickBuffer == null)
		{
			sendError(exchange, 503, "Tick buffer not initialized");
			return;
		}

		Map<String, String> params = parseQuery(exchange.getRequestURI());
		int t = intParam(params, "t", -5);

		// Parse filters
		String typesParam = params.get("types");
		Set<String> typeFilter = null;
		if (typesParam != null && !typesParam.isEmpty())
		{
			typeFilter = new HashSet<>(Arrays.asList(typesParam.toLowerCase().split(",")));
		}

		String namesParam = params.get("names");
		Set<String> nameFilter = null;
		if (namesParam != null && !namesParam.isEmpty())
		{
			nameFilter = new HashSet<>();
			for (String n : namesParam.split(","))
			{
				nameFilter.add(n.trim().toLowerCase());
			}
		}

		String idsParam = params.get("ids");
		Set<Integer> idFilter = null;
		if (idsParam != null && !idsParam.isEmpty())
		{
			idFilter = new HashSet<>();
			for (String id : idsParam.split(","))
			{
				try { idFilter.add(Integer.parseInt(id.trim())); } catch (NumberFormatException ignored) {}
			}
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("bufferCapacity", tickBuffer.capacity());
		result.put("bufferFilled", tickBuffer.filled());
		int[] range = tickBuffer.range();
		if (range != null)
		{
			Map<String, Object> rangeMap = new LinkedHashMap<>();
			rangeMap.put("from", range[0]);
			rangeMap.put("to", range[1]);
			result.put("bufferRange", rangeMap);
		}

		if (t > 0)
		{
			// Absolute mode: full snapshot at specific tick
			result.put("type", "absolute");
			result.put("tick", t);

			TickStateBuffer.TickSnapshot snap = tickBuffer.getByTick(t);
			if (snap == null)
			{
				result.put("found", false);
				sendJson(exchange, 200, result);
				return;
			}
			result.put("found", true);
			result.put("timestampMs", snap.timestampMs);

			boolean includeAll = typeFilter == null;

			if ((includeAll || typeFilter.contains("player")) && snap.player != null)
			{
				result.put("player", snap.player);
			}
			if ((includeAll || typeFilter.contains("npc")) && !snap.npcs.isEmpty())
			{
				result.put("npcs", filterEntities(snap.npcs, nameFilter, idFilter));
			}
			if ((includeAll || typeFilter.contains("otherplayer")) && !snap.otherPlayers.isEmpty())
			{
				result.put("otherPlayers", filterByName(snap.otherPlayers, nameFilter));
			}
			if ((includeAll || typeFilter.contains("skills")) && snap.skillXp != null)
			{
				result.put("skills", buildSkillsMap(snap));
			}
			if ((includeAll || typeFilter.contains("hits")) && !snap.hits.isEmpty())
			{
				result.put("hits", snap.hits);
			}

			sendJson(exchange, 200, result);
		}
		else
		{
			// Delta mode: last |t| ticks with sparse changes
			int lastN = Math.abs(t);
			result.put("type", "delta");
			result.put("requestedTicks", lastN);

			List<TickStateBuffer.TickSnapshot> snapshots = tickBuffer.getLastN(lastN);
			if (snapshots.isEmpty())
			{
				result.put("ticks", Collections.emptyList());
				sendJson(exchange, 200, result);
				return;
			}

			boolean includeAll = typeFilter == null;
			List<Map<String, Object>> tickDeltas = new ArrayList<>();

			for (int i = 0; i < snapshots.size(); i++)
			{
				TickStateBuffer.TickSnapshot curr = snapshots.get(i);
				TickStateBuffer.TickSnapshot prev = i > 0 ? snapshots.get(i - 1) : null;

				Map<String, Object> tickEntry = new LinkedHashMap<>();
				tickEntry.put("tick", curr.tick);
				tickEntry.put("timestampMs", curr.timestampMs);

				Map<String, Object> deltas = new LinkedHashMap<>();

				// Player delta
				if (includeAll || typeFilter.contains("player"))
				{
					if (prev == null)
					{
						if (curr.player != null)
						{
							deltas.put("player", curr.player);
						}
					}
					else if (curr.player != null)
					{
						Map<String, Object> pDelta = diffMaps(prev.player, curr.player);
						if (!pDelta.isEmpty())
						{
							deltas.put("player", pDelta);
						}
					}
				}

				// NPC delta
				if (includeAll || typeFilter.contains("npc"))
				{
					Map<String, Object> npcDelta = diffEntityList(
						prev != null ? prev.npcs : Collections.emptyList(),
						curr.npcs,
						"index", nameFilter, idFilter
					);
					if (!npcDelta.isEmpty())
					{
						deltas.put("npcs", npcDelta);
					}
				}

				// Other players delta
				if (includeAll || typeFilter.contains("otherplayer"))
				{
					Map<String, Object> playerDelta = diffEntityList(
						prev != null ? prev.otherPlayers : Collections.emptyList(),
						curr.otherPlayers,
						"name", nameFilter, null
					);
					if (!playerDelta.isEmpty())
					{
						deltas.put("otherPlayers", playerDelta);
					}
				}

				// Skills delta
				if (includeAll || typeFilter.contains("skills"))
				{
					if (prev != null && curr.skillXp != null && prev.skillXp != null)
					{
						Map<String, Object> skillDelta = diffSkills(prev, curr);
						if (!skillDelta.isEmpty())
						{
							deltas.put("skills", skillDelta);
						}
					}
					else if (prev == null && curr.skillXp != null)
					{
						deltas.put("skills", buildSkillsMap(curr));
					}
				}

				// Hits (always "added", they're events not persistent state)
				if ((includeAll || typeFilter.contains("hits")) && !curr.hits.isEmpty())
				{
					deltas.put("hits", curr.hits);
				}

				if (!deltas.isEmpty())
				{
					tickEntry.put("deltas", deltas);
				}
				tickDeltas.add(tickEntry);
			}

			result.put("ticks", tickDeltas);
			sendJson(exchange, 200, result);
		}
	}

	// ── Buffer helper methods ──────────────────────────────────────

	private List<Map<String, Object>> filterEntities(List<Map<String, Object>> entities,
		Set<String> nameFilter, Set<Integer> idFilter)
	{
		if (nameFilter == null && idFilter == null) return entities;

		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> e : entities)
		{
			boolean match = true;
			if (nameFilter != null)
			{
				String name = String.valueOf(e.getOrDefault("name", "")).toLowerCase();
				boolean nameMatch = false;
				for (String filter : nameFilter)
				{
					if (name.contains(filter)) { nameMatch = true; break; }
				}
				if (!nameMatch) match = false;
			}
			if (match && idFilter != null)
			{
				Object id = e.get("id");
				if (id instanceof Number && !idFilter.contains(((Number) id).intValue()))
				{
					match = false;
				}
			}
			if (match) result.add(e);
		}
		return result;
	}

	private List<Map<String, Object>> filterByName(List<Map<String, Object>> entities,
		Set<String> nameFilter)
	{
		if (nameFilter == null) return entities;
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> e : entities)
		{
			String name = String.valueOf(e.getOrDefault("name", "")).toLowerCase();
			for (String filter : nameFilter)
			{
				if (name.contains(filter)) { result.add(e); break; }
			}
		}
		return result;
	}

	private Map<String, Object> diffMaps(Map<String, Object> prev, Map<String, Object> curr)
	{
		if (prev == null) return curr != null ? new LinkedHashMap<>(curr) : new LinkedHashMap<>();
		if (curr == null) return new LinkedHashMap<>();

		Map<String, Object> delta = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : curr.entrySet())
		{
			Object oldVal = prev.get(entry.getKey());
			Object newVal = entry.getValue();
			if (!Objects.equals(oldVal, newVal))
			{
				delta.put(entry.getKey(), newVal);
			}
		}
		// Check for removed keys
		for (String key : prev.keySet())
		{
			if (!curr.containsKey(key))
			{
				delta.put(key, null);
			}
		}
		return delta;
	}

	private Map<String, Object> diffEntityList(
		List<Map<String, Object>> prevList,
		List<Map<String, Object>> currList,
		String keyField,
		Set<String> nameFilter,
		Set<Integer> idFilter)
	{
		// Build keyed maps
		Map<String, Map<String, Object>> prevMap = new LinkedHashMap<>();
		for (Map<String, Object> e : prevList)
		{
			String key = String.valueOf(e.get(keyField));
			prevMap.put(key, e);
		}
		Map<String, Map<String, Object>> currMap = new LinkedHashMap<>();
		for (Map<String, Object> e : currList)
		{
			String key = String.valueOf(e.get(keyField));
			currMap.put(key, e);
		}

		List<Map<String, Object>> added = new ArrayList<>();
		List<Map<String, Object>> removed = new ArrayList<>();
		List<Map<String, Object>> changed = new ArrayList<>();

		// Find added and changed
		for (Map.Entry<String, Map<String, Object>> entry : currMap.entrySet())
		{
			Map<String, Object> curr = entry.getValue();
			if (!matchesFilters(curr, nameFilter, idFilter)) continue;

			Map<String, Object> prev = prevMap.get(entry.getKey());
			if (prev == null)
			{
				added.add(curr);
			}
			else
			{
				Map<String, Object> delta = diffMaps(prev, curr);
				if (!delta.isEmpty())
				{
					delta.put(keyField, entry.getKey()); // include key for identification
					changed.add(delta);
				}
			}
		}

		// Find removed
		for (Map.Entry<String, Map<String, Object>> entry : prevMap.entrySet())
		{
			if (!currMap.containsKey(entry.getKey()))
			{
				Map<String, Object> prev = entry.getValue();
				if (matchesFilters(prev, nameFilter, idFilter))
				{
					Map<String, Object> ref = new LinkedHashMap<>();
					ref.put(keyField, entry.getKey());
					if (prev.containsKey("name")) ref.put("name", prev.get("name"));
					removed.add(ref);
				}
			}
		}

		Map<String, Object> result = new LinkedHashMap<>();
		if (!added.isEmpty()) result.put("added", added);
		if (!removed.isEmpty()) result.put("removed", removed);
		if (!changed.isEmpty()) result.put("changed", changed);
		return result;
	}

	private boolean matchesFilters(Map<String, Object> entity,
		Set<String> nameFilter, Set<Integer> idFilter)
	{
		if (nameFilter != null)
		{
			String name = String.valueOf(entity.getOrDefault("name", "")).toLowerCase();
			boolean found = false;
			for (String filter : nameFilter)
			{
				if (name.contains(filter)) { found = true; break; }
			}
			if (!found) return false;
		}
		if (idFilter != null)
		{
			Object id = entity.get("id");
			if (id instanceof Number && !idFilter.contains(((Number) id).intValue()))
			{
				return false;
			}
		}
		return true;
	}

	private Map<String, Object> diffSkills(TickStateBuffer.TickSnapshot prev,
		TickStateBuffer.TickSnapshot curr)
	{
		Map<String, Object> delta = new LinkedHashMap<>();
		Skill[] skills = Skill.values();
		for (Skill skill : skills)
		{
			if (skill == Skill.OVERALL) continue;
			int ord = skill.ordinal();
			if (ord >= curr.skillXp.length || ord >= prev.skillXp.length) continue;

			int xpDiff = curr.skillXp[ord] - prev.skillXp[ord];
			int realDiff = curr.skillRealLevel[ord] - prev.skillRealLevel[ord];
			int boostedDiff = curr.skillBoostedLevel[ord] - prev.skillBoostedLevel[ord];

			if (xpDiff != 0 || realDiff != 0 || boostedDiff != 0)
			{
				Map<String, Object> s = new LinkedHashMap<>();
				if (xpDiff != 0) s.put("xpGained", xpDiff);
				if (realDiff != 0) s.put("levelChange", realDiff);
				if (boostedDiff != 0) s.put("boostedChange", boostedDiff);
				s.put("xp", curr.skillXp[ord]);
				s.put("level", curr.skillRealLevel[ord]);
				s.put("boosted", curr.skillBoostedLevel[ord]);
				delta.put(skill.getName(), s);
			}
		}
		return delta;
	}

	private Map<String, Object> buildSkillsMap(TickStateBuffer.TickSnapshot snap)
	{
		Map<String, Object> skills = new LinkedHashMap<>();
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL) continue;
			int ord = skill.ordinal();
			if (ord >= snap.skillXp.length) continue;

			Map<String, Object> s = new LinkedHashMap<>();
			s.put("xp", snap.skillXp[ord]);
			s.put("level", snap.skillRealLevel[ord]);
			s.put("boosted", snap.skillBoostedLevel[ord]);
			skills.put(skill.getName(), s);
		}
		return skills;
	}

	private void handleLogs(HttpExchange exchange) throws IOException
	{
		if (logCaptureAppender == null)
		{
			sendError(exchange, 503, "Log capture not initialized");
			return;
		}

		Map<String, String> params = parseQuery(exchange.getRequestURI());
		String levelFilter = params.get("level");
		String loggerFilter = params.get("logger");
		String searchFilter = params.get("search");
		int last = intParam(params, "last", 100);

		List<Map<String, Object>> entries = logCaptureAppender.getEntries();

		// Filter by minimum level
		if (levelFilter != null)
		{
			int minLevel = levelOrdinal(levelFilter.toUpperCase());
			entries.removeIf(e -> levelOrdinal((String) e.get("level")) < minLevel);
		}

		// Filter by logger name
		if (loggerFilter != null)
		{
			String lowerFilter = loggerFilter.toLowerCase();
			entries.removeIf(e ->
			{
				String logger = (String) e.get("loggerName");
				return logger == null || !logger.toLowerCase().contains(lowerFilter);
			});
		}

		// Filter by message content
		if (searchFilter != null)
		{
			String lowerSearch = searchFilter.toLowerCase();
			entries.removeIf(e ->
			{
				String msg = (String) e.get("message");
				return msg == null || !msg.toLowerCase().contains(lowerSearch);
			});
		}

		// Truncate to last N
		if (last > 0 && entries.size() > last)
		{
			entries = entries.subList(entries.size() - last, entries.size());
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("count", entries.size());
		result.put("entries", entries);
		sendJson(exchange, 200, result);
	}

	private static int levelOrdinal(String level)
	{
		switch (level)
		{
			case "TRACE": return 0;
			case "DEBUG": return 1;
			case "INFO": return 2;
			case "WARN": return 3;
			case "ERROR": return 4;
			default: return 0;
		}
	}

	private void handleActions(HttpExchange exchange) throws IOException
	{
		if (actionTracker == null)
		{
			sendError(exchange, 503, "Action tracker not initialized");
			return;
		}

		Map<String, String> params = parseQuery(exchange.getRequestURI());
		int last = intParam(params, "last", 50);
		String source = params.get("source");
		String search = params.get("search");

		List<ActionTracker.TrackedAction> actions = actionTracker.getActions(last, source, search);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("capacity", actionTracker.capacity());
		result.put("filled", actionTracker.filled());
		result.put("returned", actions.size());

		List<Map<String, Object>> actionList = new ArrayList<>();
		for (ActionTracker.TrackedAction a : actions)
		{
			actionList.add(a.toMap());
		}
		result.put("actions", actionList);

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

	// --- Var / Interaction history ---

	public void addVarChange(Map<String, Object> entry)
	{
		synchronized (varHistory)
		{
			varHistory.add(entry);
			while (varHistory.size() > MAX_VAR_HISTORY)
			{
				varHistory.remove(0);
			}
		}
	}

	public void addInteraction(Map<String, Object> entry)
	{
		synchronized (interactionHistory)
		{
			interactionHistory.add(entry);
			while (interactionHistory.size() > MAX_INTERACTION_HISTORY)
			{
				interactionHistory.remove(0);
			}
		}
	}

	public void addHoverIfChanged(String target, Map<String, Object> entry)
	{
		if (target.equals(lastHoverTarget))
		{
			return;
		}
		lastHoverTarget = target;
		addInteraction(entry);
	}

	private void handleVarHistory(HttpExchange exchange) throws IOException
	{
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		String typeFilter = params.get("type");
		int last = intParam(params, "last", 0);

		List<Map<String, Object>> result;
		synchronized (varHistory)
		{
			result = new ArrayList<>(varHistory);
		}

		if (typeFilter != null && !typeFilter.isEmpty())
		{
			result.removeIf(e -> !typeFilter.equalsIgnoreCase((String) e.get("type")));
		}
		if (last > 0 && result.size() > last)
		{
			result = result.subList(result.size() - last, result.size());
		}

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("count", result.size());
		response.put("changes", result);
		sendJson(exchange, 200, response);
	}

	private void handleInteractionHistory(HttpExchange exchange) throws IOException
	{
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		int last = intParam(params, "last", 0);

		List<Map<String, Object>> result;
		synchronized (interactionHistory)
		{
			result = new ArrayList<>(interactionHistory);
		}

		if (last > 0 && result.size() > last)
		{
			result = result.subList(result.size() - last, result.size());
		}

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("count", result.size());
		response.put("interactions", result);
		sendJson(exchange, 200, response);
	}

	private void handleGraphicsObjects(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}

		try
		{
			Object data = onClientThread(() ->
			{
				List<Map<String, Object>> objects = new ArrayList<>();
				for (GraphicsObject go : client.getTopLevelWorldView().getGraphicsObjects())
				{
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", go.getId());
					m.put("startCycle", go.getStartCycle());
					m.put("finished", go.finished());

					LocalPoint lp = go.getLocation();
					if (lp != null)
					{
						Map<String, Object> loc = new LinkedHashMap<>();
						loc.put("localX", lp.getX());
						loc.put("localY", lp.getY());

						int baseX = client.getBaseX();
						int baseY = client.getBaseY();
						loc.put("worldX", baseX + (lp.getSceneX()));
						loc.put("worldY", baseY + (lp.getSceneY()));
						m.put("location", loc);
					}

					objects.add(m);
				}

				Map<String, Object> result = new LinkedHashMap<>();
				result.put("count", objects.size());
				result.put("graphicsObjects", objects);
				return result;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	// --- Prayer endpoint ---

	private void handlePrayers(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}

		try
		{
			Object data = onClientThread(() ->
			{
				Map<String, Object> result = new LinkedHashMap<>();
				List<String> active = new ArrayList<>();
				for (Prayer prayer : Prayer.values())
				{
					if (client.isPrayerActive(prayer))
					{
						active.add(prayer.name());
					}
				}
				result.put("active", active);
				result.put("activeCount", active.size());
				result.put("prayerPoints", client.getBoostedSkillLevel(Skill.PRAYER));
				result.put("maxPrayer", client.getRealSkillLevel(Skill.PRAYER));
				return result;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	// --- Recording system ---

	public boolean isRecording()
	{
		return isRecording;
	}

	public void addRecordingEvent(String eventType, Map<String, Object> data)
	{
		if (!isRecording)
		{
			return;
		}
		if (recordingEventFilter != null && !recordingEventFilter.contains(eventType))
		{
			return;
		}
		if (recordingBuffer.size() >= MAX_RECORDING_ENTRIES)
		{
			isRecording = false;
			log.info("Recording auto-stopped: max entries ({}) reached", MAX_RECORDING_ENTRIES);
			return;
		}

		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("eventType", eventType);
		entry.put("tick", client.getTickCount());
		entry.put("ticksElapsed", client.getTickCount() - recordingStartTick);
		entry.put("timestamp", System.currentTimeMillis());
		entry.putAll(data);
		recordingBuffer.add(entry);
	}

	private void handleRecordingStart(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}

		try
		{
			Map<String, String> params = parseQuery(exchange.getRequestURI());
			int durationSeconds = intParam(params, "duration", 180);
			if (durationSeconds < 1) durationSeconds = 1;
			if (durationSeconds > 600) durationSeconds = 600;

			// Convert seconds to ticks (1 tick ≈ 0.6s)
			final int maxTicks = (int) Math.ceil(durationSeconds / 0.6);

			int startTick = onClientThread(() -> client.getTickCount());

			// Parse optional event type filter
			String typesParam = params.get("types");
			if (typesParam != null && !typesParam.isEmpty())
			{
				recordingEventFilter = new HashSet<>(Arrays.asList(typesParam.split(",")));
			}
			else
			{
				recordingEventFilter = null; // record everything
			}

			synchronized (recordingBuffer)
			{
				recordingBuffer.clear();
			}
			recordingStartTick = startTick;
			recordingMaxTicks = maxTicks;
			recordingTickCounter = 0;
			isRecording = true;

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("status", "recording");
			result.put("durationSeconds", durationSeconds);
			result.put("maxTicks", maxTicks);
			result.put("startTick", startTick);
			result.put("eventFilter", recordingEventFilter != null ? recordingEventFilter : "all");
			sendJson(exchange, 200, result);

			log.info("Recording started: {} seconds ({} ticks), filter: {}", durationSeconds, maxTicks,
				recordingEventFilter != null ? recordingEventFilter : "all");
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleRecordingStop(HttpExchange exchange) throws IOException
	{
		boolean wasRecording = isRecording;
		isRecording = false;

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("status", "stopped");
		result.put("wasRecording", wasRecording);
		result.put("eventsLogged", recordingBuffer.size());
		sendJson(exchange, 200, result);

		if (wasRecording)
		{
			log.info("Recording stopped: {} events captured", recordingBuffer.size());
		}
	}

	private void handleRecordingStatus(HttpExchange exchange) throws IOException
	{
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("recording", isRecording);
		result.put("eventsLogged", recordingBuffer.size());
		result.put("maxEntries", MAX_RECORDING_ENTRIES);
		result.put("maxTicks", recordingMaxTicks);
		result.put("eventFilter", recordingEventFilter != null ? recordingEventFilter : "all");

		if (isRecording)
		{
			try
			{
				int currentTick = onClientThread(() -> client.getTickCount());
				result.put("ticksElapsed", currentTick - recordingStartTick);
				result.put("secondsElapsed", (int) ((currentTick - recordingStartTick) * 0.6));
				result.put("secondsRemaining", Math.max(0, (int) ((recordingMaxTicks - (currentTick - recordingStartTick)) * 0.6)));
			}
			catch (Exception ignored) {}
		}
		sendJson(exchange, 200, result);
	}

	private void handleRecordingData(HttpExchange exchange) throws IOException
	{
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		String typesFilter = params.get("types");
		int fromTick = intParam(params, "from_tick", 0);
		int toTick = intParam(params, "to_tick", 0);
		int last = intParam(params, "last", 0);

		List<Map<String, Object>> result;
		synchronized (recordingBuffer)
		{
			result = new ArrayList<>(recordingBuffer);
		}

		// Filter by event types
		if (typesFilter != null && !typesFilter.isEmpty())
		{
			Set<String> types = new HashSet<>(Arrays.asList(typesFilter.split(",")));
			result.removeIf(e -> !types.contains(e.get("eventType")));
		}

		// Filter by tick range (using ticksElapsed)
		if (fromTick > 0)
		{
			result.removeIf(e ->
			{
				Object te = e.get("ticksElapsed");
				return te instanceof Number && ((Number) te).intValue() < fromTick;
			});
		}
		if (toTick > 0)
		{
			result.removeIf(e ->
			{
				Object te = e.get("ticksElapsed");
				return te instanceof Number && ((Number) te).intValue() > toTick;
			});
		}

		// Last N events
		if (last > 0 && result.size() > last)
		{
			result = result.subList(result.size() - last, result.size());
		}

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("recording", isRecording);
		response.put("totalEvents", recordingBuffer.size());
		response.put("filteredEvents", result.size());
		response.put("events", result);
		sendJson(exchange, 200, response);
	}

	/**
	 * Check if recording should auto-stop (called from plugin on each game tick).
	 * Returns true if recording just auto-stopped.
	 */
	public boolean checkRecordingAutoStop(int currentTick)
	{
		if (!isRecording)
		{
			return false;
		}
		if (currentTick - recordingStartTick >= recordingMaxTicks)
		{
			isRecording = false;
			log.info("Recording auto-stopped after {} ticks ({} events)", recordingMaxTicks, recordingBuffer.size());
			return true;
		}
		return false;
	}

	/**
	 * Should a game_tick event be recorded this tick? Throttles to every 3rd tick.
	 */
	public boolean shouldRecordGameTick()
	{
		if (!isRecording) return false;
		recordingTickCounter++;
		return recordingTickCounter % 3 == 0;
	}

	// --- Name search infrastructure ---

	private Map<String, List<Integer>> buildItemNameIndex() throws Exception
	{
		return onClientThread(() ->
		{
			Map<String, List<Integer>> index = new HashMap<>();
			int count = client.getItemCount();
			for (int id = 0; id < count; id++)
			{
				try
				{
					ItemComposition comp = client.getItemDefinition(id);
					if (comp == null)
					{
						continue;
					}
					String name = comp.getName();
					if (name == null || name.equals("null") || name.isEmpty())
					{
						continue;
					}
					index.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(id);
				}
				catch (Exception ignored)
				{
				}
			}
			return index;
		});
	}

	private Map<String, List<Integer>> buildNpcNameIndex() throws Exception
	{
		return onClientThread(() ->
		{
			Map<String, List<Integer>> index = new HashMap<>();
			for (int id = 0; id < 15000; id++)
			{
				try
				{
					NPCComposition comp = client.getNpcDefinition(id);
					if (comp == null)
					{
						continue;
					}
					String name = comp.getName();
					if (name == null || name.equals("null") || name.isEmpty())
					{
						continue;
					}
					index.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(id);
				}
				catch (Exception ignored)
				{
				}
			}
			return index;
		});
	}

	private Map<String, List<Integer>> buildObjNameIndex() throws Exception
	{
		return onClientThread(() ->
		{
			Map<String, List<Integer>> index = new HashMap<>();
			for (int id = 0; id < 50000; id++)
			{
				try
				{
					ObjectComposition comp = client.getObjectDefinition(id);
					if (comp == null)
					{
						continue;
					}
					String name = comp.getName();
					if (name == null || name.equals("null") || name.isEmpty())
					{
						continue;
					}
					index.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(id);
				}
				catch (Exception ignored)
				{
				}
			}
			return index;
		});
	}

	private List<Integer> searchNameIndex(Map<String, List<Integer>> index, String query, boolean exact, int limit)
	{
		String lower = query.toLowerCase();
		List<Integer> results = new ArrayList<>();

		if (exact)
		{
			List<Integer> ids = index.get(lower);
			if (ids != null)
			{
				results.addAll(ids);
			}
		}
		else
		{
			for (Map.Entry<String, List<Integer>> entry : index.entrySet())
			{
				if (entry.getKey().contains(lower))
				{
					results.addAll(entry.getValue());
					if (results.size() >= limit)
					{
						break;
					}
				}
			}
		}

		if (results.size() > limit)
		{
			return results.subList(0, limit);
		}
		return results;
	}

	private Map<String, Object> serializeItemDef(int id, ItemComposition comp)
	{
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
	}

	private Map<String, Object> serializeNpcDef(int id, NPCComposition comp)
	{
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
	}

	private Map<String, Object> serializeObjDef(int id, ObjectComposition comp)
	{
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
	}

	// --- Wiki API proxy ---

	private void proxyWikiRequest(HttpExchange exchange, HttpUrl url) throws IOException
	{
		Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", WIKI_USER_AGENT)
			.header("Accept", "application/json")
			.get()
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			String body = response.body() != null ? response.body().string() : "{}";
			if (response.isSuccessful())
			{
				sendRawJson(exchange, 200, body);
			}
			else
			{
				sendError(exchange, 502, "Wiki API returned status " + response.code());
			}
		}
		catch (IOException e)
		{
			sendError(exchange, 502, "Failed to reach Wiki API: " + e.getMessage());
		}
	}

	private void handleWikiSearch(HttpExchange exchange) throws IOException
	{
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		String query = params.get("q");
		if (query == null || query.isEmpty())
		{
			sendError(exchange, 400, "Missing parameter: q");
			return;
		}
		int limit = Math.min(intParam(params, "limit", 10), 50);

		HttpUrl url = HttpUrl.parse(WIKI_API_BASE).newBuilder()
			.addQueryParameter("action", "query")
			.addQueryParameter("list", "search")
			.addQueryParameter("srsearch", query)
			.addQueryParameter("srlimit", String.valueOf(limit))
			.addQueryParameter("format", "json")
			.build();

		proxyWikiRequest(exchange, url);
	}

	private void handleWikiPageInfo(HttpExchange exchange) throws IOException
	{
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		String title = params.get("title");
		if (title == null || title.isEmpty())
		{
			sendError(exchange, 400, "Missing parameter: title");
			return;
		}

		HttpUrl url = HttpUrl.parse(WIKI_API_BASE).newBuilder()
			.addQueryParameter("action", "query")
			.addQueryParameter("titles", title)
			.addQueryParameter("prop", "info|categories|links")
			.addQueryParameter("format", "json")
			.build();

		proxyWikiRequest(exchange, url);
	}

	private void handleWikiParse(HttpExchange exchange) throws IOException
	{
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		String title = params.get("title");
		if (title == null || title.isEmpty())
		{
			sendError(exchange, 400, "Missing parameter: title");
			return;
		}

		HttpUrl.Builder urlBuilder = HttpUrl.parse(WIKI_API_BASE).newBuilder()
			.addQueryParameter("action", "parse")
			.addQueryParameter("page", title)
			.addQueryParameter("format", "json");

		String section = params.get("section");
		if (section != null)
		{
			urlBuilder.addQueryParameter("section", section);
		}

		proxyWikiRequest(exchange, urlBuilder.build());
	}

	// --- New definition endpoints ---

	private void handleVarbitDef(HttpExchange exchange) throws IOException
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
				VarbitComposition comp = client.getVarbit(id);
				if (comp == null)
				{
					return Collections.singletonMap("error", "Varbit not found");
				}

				Map<String, Object> m = new LinkedHashMap<>();
				m.put("varbitId", id);
				m.put("varpIndex", comp.getIndex());
				m.put("leastSignificantBit", comp.getLeastSignificantBit());
				m.put("mostSignificantBit", comp.getMostSignificantBit());
				try
				{
					m.put("currentValue", client.getVarbitValue(id));
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

	private void handleStructDef(HttpExchange exchange) throws IOException
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
				StructComposition comp = client.getStructComposition(id);
				if (comp == null)
				{
					return Collections.singletonMap("error", "Struct not found");
				}

				Map<String, Object> m = new LinkedHashMap<>();
				m.put("structId", comp.getId());

				Map<String, Object> paramMap = new LinkedHashMap<>();
				IterableHashTable<Node> rawParams = comp.getParams();
				if (rawParams != null)
				{
					for (Node node : rawParams)
					{
						int key = (int) node.getHash();
						Map<String, Object> pEntry = new LinkedHashMap<>();
						if (node instanceof IntegerNode)
						{
							pEntry.put("intValue", ((IntegerNode) node).getValue());
						}
						try
						{
							String sv = comp.getStringValue(key);
							if (sv != null)
							{
								pEntry.put("stringValue", sv);
							}
						}
						catch (Exception ignored)
						{
						}
						paramMap.put(String.valueOf(key), pEntry);
					}
				}
				m.put("params", paramMap);
				return m;
			});
			sendJson(exchange, 200, data);
		}
		catch (Exception e)
		{
			sendError(exchange, 500, e.getMessage());
		}
	}

	private void handleEnumDef(HttpExchange exchange) throws IOException
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
				EnumComposition comp = client.getEnum(id);
				if (comp == null)
				{
					return Collections.singletonMap("error", "Enum not found");
				}

				Map<String, Object> m = new LinkedHashMap<>();
				m.put("enumId", id);
				m.put("size", comp.size());

				int[] keys = comp.getKeys();
				if (keys != null)
				{
					List<Integer> keyList = new ArrayList<>();
					for (int k : keys)
					{
						keyList.add(k);
					}
					m.put("keys", keyList);
				}

				int[] intVals = comp.getIntVals();
				if (intVals != null)
				{
					List<Integer> ivList = new ArrayList<>();
					for (int v : intVals)
					{
						ivList.add(v);
					}
					m.put("intValues", ivList);
				}

				String[] strVals = comp.getStringVals();
				if (strVals != null)
				{
					m.put("stringValues", Arrays.asList(strVals));
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

	private void handlePlayerAppearance(HttpExchange exchange) throws IOException
	{
		if (!requireLoggedIn(exchange))
		{
			return;
		}
		Map<String, String> params = parseQuery(exchange.getRequestURI());
		String name = params.get("name");

		try
		{
			Object data = onClientThread(() ->
			{
				Player target;
				if (name != null && !name.isEmpty())
				{
					target = null;
					for (Player p : client.getPlayers())
					{
						if (p != null && name.equalsIgnoreCase(p.getName()))
						{
							target = p;
							break;
						}
					}
					if (target == null)
					{
						return Collections.singletonMap("error", "Player not found or not visible: " + name);
					}
				}
				else
				{
					target = client.getLocalPlayer();
					if (target == null)
					{
						return Collections.singletonMap("error", "No local player");
					}
				}

				PlayerComposition comp = target.getPlayerComposition();
				if (comp == null)
				{
					return Collections.singletonMap("error", "Player composition unavailable");
				}

				Map<String, Object> m = new LinkedHashMap<>();
				m.put("playerName", target.getName());
				m.put("isFemale", comp.isFemale());
				m.put("gender", comp.getGender());
				m.put("transformedNpcId", comp.getTransformedNpcId());

				Map<String, Integer> equipment = new LinkedHashMap<>();
				for (net.runelite.api.kit.KitType kit : net.runelite.api.kit.KitType.values())
				{
					equipment.put(kit.name(), comp.getEquipmentId(kit));
				}
				m.put("equipment", equipment);

				Map<String, Integer> kits = new LinkedHashMap<>();
				for (net.runelite.api.kit.KitType kit : net.runelite.api.kit.KitType.values())
				{
					kits.put(kit.name(), comp.getKitId(kit));
				}
				m.put("kits", kits);

				int[] colors = comp.getColors();
				if (colors != null)
				{
					List<Integer> colorList = new ArrayList<>();
					for (int c : colors)
					{
						colorList.add(c);
					}
					m.put("colors", colorList);
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
