package com.osrscompanion;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.osrscompanion.model.PlayerSyncData;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemStack;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;

@Slf4j
@PluginDescriptor(
	name = "OSRS MCP Companion",
	description = "Exposes live game data via a local HTTP API for use with AI assistants via MCP",
	tags = {"sync", "data", "export", "mcp", "ai", "api"}
)
public class OsrsCompanionPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private OsrsCompanionConfig config;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Gson gson;

	@Inject
	private ClientThread clientThread;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ClientToolbar clientToolbar;

	private OsrsCompanionPanel panel;
	private NavigationButton navButton;
	private OsrsCompanionFrame guiFrame;
	private volatile long lastSaveMs = 0L;
	private PlayerDataCollector collector;
	private PlayerDataWriter writer;
	private GameStateServer apiServer;
	private TickStateBuffer tickBuffer;
	private LogCaptureAppender logCaptureAppender;
	private ActionTracker actionTracker;
	private boolean dirty = false;
	private int tickCounter = 0;
	private int syncTickThreshold = 100;
	private boolean initialCollectionDone = false;
	private volatile ClientSnapshot latestSnapshot;

	// Var change tracking
	private int[] oldVarps = null;
	private Map<Integer, List<Integer>> varpToVarbits = null; // varp index -> list of varbit IDs

	@Override
	protected void startUp()
	{
		collector = new PlayerDataCollector(client);
		writer = new PlayerDataWriter(gson);
		tickBuffer = new TickStateBuffer(600); // ~10 min at 600ms/tick
		actionTracker = new ActionTracker(500);
		recalcSyncThreshold();

		// Attach log capture appender to Logback root logger
		try
		{
			logCaptureAppender = new LogCaptureAppender();
			LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
			Logger rootLogger = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
			logCaptureAppender.setContext(ctx);
			logCaptureAppender.start();
			rootLogger.addAppender(logCaptureAppender);
		}
		catch (Exception e)
		{
			log.warn("Failed to attach log capture appender", e);
			logCaptureAppender = null;
		}

		if (config.enableApiServer())
		{
			startApiServer();
		}

		// Create sidebar panel and toolbar icon
		panel = new OsrsCompanionPanel(client, config, this);
		navButton = NavigationButton.builder()
			.tooltip("OSRS MCP Companion")
			.icon(ImageUtil.loadImageResource(getClass(), "/icon.png"))
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		// GUI frame is created lazily in openGui() so the sidebar panel is
		// in RuneLite's component hierarchy (needed for parent window lookup).

		log.info("OSRS Companion started — saving to ~/.runelite/osrs-companion/");
	}

	@Override
	protected void shutDown()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			doSave();
		}

		// Detach log capture appender
		if (logCaptureAppender != null)
		{
			try
			{
				LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
				Logger rootLogger = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
				rootLogger.detachAppender(logCaptureAppender);
				logCaptureAppender.stop();
			}
			catch (Exception ignored) {}
			logCaptureAppender = null;
		}

		stopApiServer();

		// Remove sidebar panel
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		if (panel != null)
		{
			panel.dispose();
		}
		panel = null;

		// Dispose standalone GUI window
		SwingUtilities.invokeLater(() -> {
			if (guiFrame != null)
			{
				guiFrame.shutdown();
				guiFrame = null;
			}
		});

		collector = null;
		writer = null;
		log.info("OSRS Companion stopped");
	}

	private void startApiServer()
	{
		try
		{
			apiServer = new GameStateServer(client, clientThread, gson, pluginManager, configManager, drawManager, tickBuffer, logCaptureAppender, actionTracker);
			apiServer.start(config.apiPort());
		}
		catch (Exception e)
		{
			log.warn("OSRS Companion: Failed to start API server on port {}", config.apiPort(), e);
			apiServer = null;
		}
	}

	private void stopApiServer()
	{
		if (apiServer != null)
		{
			apiServer.stop();
			apiServer = null;
		}
	}

	// --- Public accessors for the sidebar panel ---

	public GameStateServer getApiServer()
	{
		return apiServer;
	}

	public ClientSnapshot getLatestSnapshot()
	{
		return latestSnapshot;
	}

	public TickStateBuffer getTickBuffer()
	{
		return tickBuffer;
	}

	public ActionTracker getActionTracker()
	{
		return actionTracker;
	}

	public LogCaptureAppender getLogAppender()
	{
		return logCaptureAppender;
	}

	/**
	 * Trigger an immediate save to disk (called from panel button).
	 */
	public void triggerSave()
	{
		dirty = true;
		doSave();
	}

	/** Opens (or focuses) the standalone GUI window. */
	public void openGui()
	{
		SwingUtilities.invokeLater(() -> {
			if (guiFrame == null)
			{
				java.awt.Window owner = SwingUtilities.getWindowAncestor(panel);
				guiFrame = new OsrsCompanionFrame(owner, config, this);
			}
			guiFrame.open();
		});
	}

	/** Hook the frame calls when its window is closed. */
	public void onGuiClosed()
	{
		// No-op for now; reserved for future "GUI is open" indicator on the sidebar button.
	}

	/** Used by the frame's footer to render "Saved Ns ago". */
	public long getLastSaveMs()
	{
		return lastSaveMs;
	}

	@Provides
	OsrsCompanionConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsCompanionConfig.class);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			tickCounter = -10;
			initialCollectionDone = false;
			// Initialize var tracking — snapshot current varps for change detection
			oldVarps = null; // Will be initialized on first VarbitChanged event
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			doSave();
			initialCollectionDone = false;
			oldVarps = null;
			varpToVarbits = null;
			if (apiServer != null)
			{
				apiServer.clearNameCaches();
			}
		}

		if (apiServer != null && (apiServer.hasSseClients() || apiServer.isRecording()))
		{
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("tick", client.getTickCount());
			data.put("timestamp", System.currentTimeMillis());
			data.put("state", event.getGameState().name());
			if (apiServer.hasSseClients()) apiServer.broadcastEvent("game_state_changed", data);
			if (apiServer.isRecording()) apiServer.addRecordingEvent("game_state_changed", data);
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (collector != null && config.syncSkills())
		{
			collector.updateSkill(event.getSkill(), event.getLevel(), event.getXp());
			dirty = true;
		}

		if (apiServer != null && (apiServer.hasSseClients() || apiServer.isRecording()))
		{
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("tick", client.getTickCount());
			data.put("timestamp", System.currentTimeMillis());
			data.put("skill", event.getSkill().name());
			data.put("level", event.getLevel());
			data.put("boostedLevel", client.getBoostedSkillLevel(event.getSkill()));
			data.put("xp", event.getXp());

			if (apiServer.hasSseClients()) apiServer.broadcastEvent("stat_changed", data);
			if (apiServer.isRecording()) apiServer.addRecordingEvent("stat_changed", data);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (collector == null)
		{
			return;
		}

		int containerId = event.getContainerId();
		String containerName = null;

		if (containerId == InventoryID.BANK.getId() && config.syncBank())
		{
			collector.updateBank(event.getItemContainer());
			dirty = true;
			containerName = "BANK";
		}
		else if (containerId == InventoryID.INVENTORY.getId() && config.syncInventory())
		{
			collector.updateInventory(event.getItemContainer());
			dirty = true;
			containerName = "INVENTORY";
		}
		else if (containerId == InventoryID.EQUIPMENT.getId() && config.syncEquipment())
		{
			collector.updateEquipment(event.getItemContainer());
			dirty = true;
			containerName = "EQUIPMENT";
		}

		if (apiServer != null && (apiServer.hasSseClients() || apiServer.isRecording()) && containerName != null)
		{
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("tick", client.getTickCount());
			data.put("timestamp", System.currentTimeMillis());
			data.put("container", containerName);
			data.put("containerId", containerId);

			if (apiServer.hasSseClients()) apiServer.broadcastEvent("item_changed", data);
			if (apiServer.isRecording()) apiServer.addRecordingEvent("item_changed", data);
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		dirty = true;

		if (apiServer == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		int[] currentVarps = client.getVarps();
		if (oldVarps == null)
		{
			oldVarps = new int[currentVarps.length];
			System.arraycopy(currentVarps, 0, oldVarps, 0, currentVarps.length);
			return;
		}

		int varpIndex = event.getIndex();
		if (varpIndex < 0 || varpIndex >= oldVarps.length)
		{
			return;
		}

		int oldVal = oldVarps[varpIndex];
		int newVal = currentVarps[varpIndex];

		if (oldVal != newVal)
		{
			// Build varp->varbit index lazily (once per login session)
			if (varpToVarbits == null)
			{
				buildVarpToVarbitIndex();
			}

			// Check affected varbits first (more useful than raw varp changes)
			boolean anyVarbitChanged = false;
			if (varpToVarbits != null)
			{
				List<Integer> affectedVarbits = varpToVarbits.get(varpIndex);
				if (affectedVarbits != null)
				{
					for (int varbitId : affectedVarbits)
					{
						try
						{
							int oldVarbitVal = client.getVarbitValue(oldVarps, varbitId);
							int newVarbitVal = client.getVarbitValue(currentVarps, varbitId);
							if (oldVarbitVal != newVarbitVal)
							{
								anyVarbitChanged = true;
								Map<String, Object> vbEntry = new LinkedHashMap<>();
								vbEntry.put("tick", client.getTickCount());
								vbEntry.put("timestamp", System.currentTimeMillis());
								vbEntry.put("type", "varbit");
								vbEntry.put("id", varbitId);
								vbEntry.put("varpIndex", varpIndex);
								vbEntry.put("oldValue", oldVarbitVal);
								vbEntry.put("newValue", newVarbitVal);
								apiServer.addVarChange(vbEntry);
							}
						}
						catch (Exception ignored)
						{
						}
					}
				}
			}

			// Also record varp-level change if no varbit was found (or as fallback)
			if (!anyVarbitChanged)
			{
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("tick", client.getTickCount());
				entry.put("timestamp", System.currentTimeMillis());
				entry.put("type", "varp");
				entry.put("id", varpIndex);
				entry.put("oldValue", oldVal);
				entry.put("newValue", newVal);
				apiServer.addVarChange(entry);
			}

			// Broadcast SSE event and/or record
			if (apiServer.hasSseClients() || apiServer.isRecording())
			{
				Map<String, Object> sseData = new LinkedHashMap<>();
				sseData.put("tick", client.getTickCount());
				sseData.put("timestamp", System.currentTimeMillis());
				sseData.put("varpIndex", varpIndex);
				sseData.put("oldValue", oldVal);
				sseData.put("newValue", newVal);
				if (apiServer.hasSseClients()) apiServer.broadcastEvent("var_changed", sseData);
				if (apiServer.isRecording()) apiServer.addRecordingEvent("var_changed", sseData);
			}

			System.arraycopy(currentVarps, 0, oldVarps, 0, oldVarps.length);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (apiServer != null)
		{
			apiServer.addChatMessage(
				event.getType().name(),
				event.getName(),
				event.getMessage()
			);

			if (apiServer.hasSseClients() || apiServer.isRecording())
			{
				Map<String, Object> data = new LinkedHashMap<>();
				data.put("tick", client.getTickCount());
				data.put("timestamp", System.currentTimeMillis());
				data.put("messageType", event.getType().name());
				data.put("sender", event.getName());
				data.put("message", event.getMessage());

				if (apiServer.hasSseClients()) apiServer.broadcastEvent("chat_message", data);
				if (apiServer.isRecording()) apiServer.addRecordingEvent("chat_message", data);
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN || collector == null)
		{
			return;
		}

		tickCounter++;

		if (!initialCollectionDone && tickCounter >= 0)
		{
			doFullCollection();
			initialCollectionDone = true;
			dirty = true;
			doSave();

			if (apiServer != null)
			{
				apiServer.startSession();
				for (Skill skill : Skill.values())
				{
					if (skill == Skill.OVERALL)
					{
						continue;
					}
					apiServer.setXpBaseline(skill.name(), client.getSkillExperience(skill));
				}
			}
		}

		if (initialCollectionDone && tickCounter % 30 == 0)
		{
			if (config.syncQuests())
			{
				collector.pollQuests();
			}
			if (config.syncDiaries())
			{
				collector.pollDiaries();
			}
			if (config.syncCombatAchievements())
			{
				collector.pollCombatAchievements();
			}
		}

		if (dirty && tickCounter >= syncTickThreshold)
		{
			tickCounter = 0;
			doSave();
		}

		if (apiServer != null && initialCollectionDone && (apiServer.hasSseClients() || apiServer.isRecording()))
		{
			Map<String, Object> tickData = buildGameTickData();

			if (apiServer.hasSseClients())
			{
				apiServer.broadcastEvent("game_tick", tickData);
			}

			// Recording: check auto-stop, then record game_tick every 3rd tick
			apiServer.checkRecordingAutoStop(client.getTickCount());
			if (apiServer.shouldRecordGameTick())
			{
				apiServer.addRecordingEvent("game_tick", tickData);
			}

			Set<Integer> activeInterfaces = new HashSet<>();
			for (int groupId = 0; groupId < 800; groupId++)
			{
				net.runelite.api.widgets.Widget w = client.getWidget(groupId, 0);
				if (w != null && !w.isSelfHidden())
				{
					activeInterfaces.add(groupId);
				}
			}
			apiServer.updateActiveInterfaces(activeInterfaces);
		}

		// Capture tick snapshot for the state buffer
		if (tickBuffer != null && initialCollectionDone)
		{
			captureTickSnapshot();
		}

		// Check for state changes without menu clicks (action inference)
		if (actionTracker != null && initialCollectionDone)
		{
			Player local = client.getLocalPlayer();
			int posX = 0, posY = 0, plane = 0;
			if (local != null)
			{
				WorldPoint wp = local.getWorldLocation();
				if (wp != null)
				{
					posX = wp.getX();
					posY = wp.getY();
					plane = wp.getPlane();
				}
			}

			int[] invIds = null;
			int[] invQtys = null;
			ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
			if (inv != null)
			{
				Item[] items = inv.getItems();
				invIds = new int[items.length];
				invQtys = new int[items.length];
				for (int i = 0; i < items.length; i++)
				{
					invIds[i] = items[i].getId();
					invQtys[i] = items[i].getQuantity();
				}
			}

			int[] equipIds = null;
			ItemContainer equip = client.getItemContainer(InventoryID.EQUIPMENT);
			if (equip != null)
			{
				Item[] items = equip.getItems();
				equipIds = new int[items.length];
				for (int i = 0; i < items.length; i++)
				{
					equipIds[i] = items[i].getId();
				}
			}

			actionTracker.checkStateChanges(
				client.getTickCount(),
				invIds, invQtys, equipIds,
				posX, posY, plane
			);
		}

		// Refresh sidebar panel and GUI window every 3 ticks (~1.8s)
		if (client.getTickCount() % 3 == 0)
		{
			final ClientSnapshot snap = ClientSnapshot.capture(client);
			latestSnapshot = snap;
			SwingUtilities.invokeLater(() -> {
				if (panel != null)
				{
					panel.refresh();
				}
				if (guiFrame != null && guiFrame.isVisible())
				{
					guiFrame.refreshActiveTab(snap);
				}
			});
		}
	}

	private void captureTickSnapshot()
	{
		int tick = client.getTickCount();
		long timestamp = System.currentTimeMillis();

		Player local = client.getLocalPlayer();

		// Player state
		Map<String, Object> playerState = null;
		WorldPoint playerPos = null;
		if (local != null)
		{
			playerState = new LinkedHashMap<>();
			playerPos = local.getWorldLocation();
			if (playerPos != null)
			{
				Map<String, Object> pos = new LinkedHashMap<>();
				pos.put("x", playerPos.getX());
				pos.put("y", playerPos.getY());
				pos.put("plane", playerPos.getPlane());
				playerState.put("position", pos);
			}
			playerState.put("animation", local.getAnimation());
			playerState.put("health", client.getBoostedSkillLevel(Skill.HITPOINTS));
			playerState.put("maxHealth", client.getRealSkillLevel(Skill.HITPOINTS));
			playerState.put("prayer", client.getBoostedSkillLevel(Skill.PRAYER));
			playerState.put("maxPrayer", client.getRealSkillLevel(Skill.PRAYER));
			playerState.put("runEnergy", client.getEnergy() / 100.0);
			playerState.put("specialAttack", client.getVarpValue(48) / 10.0);

			Actor interacting = local.getInteracting();
			if (interacting != null)
			{
				playerState.put("interacting", interacting.getName());
			}
		}

		// NPCs (within 15 tiles)
		List<Map<String, Object>> npcs = new ArrayList<>();
		if (local != null && playerPos != null)
		{
			for (NPC npc : client.getNpcs())
			{
				if (npc == null || npc.getName() == null) continue;
				WorldPoint npcPos = npc.getWorldLocation();
				if (npcPos == null) continue;
				if (playerPos.distanceTo(npcPos) > 15) continue;

				Map<String, Object> n = new LinkedHashMap<>();
				n.put("index", npc.getIndex());
				n.put("id", npc.getId());
				n.put("name", npc.getName());
				n.put("x", npcPos.getX());
				n.put("y", npcPos.getY());
				n.put("anim", npc.getAnimation());
				int ratio = npc.getHealthRatio();
				int scale = npc.getHealthScale();
				if (ratio >= 0 && scale > 0)
				{
					n.put("hpRatio", ratio);
					n.put("hpScale", scale);
				}
				Actor target = npc.getInteracting();
				if (target != null)
				{
					n.put("interacting", target.getName());
				}
				npcs.add(n);
			}
		}

		// Other players (within 15 tiles)
		List<Map<String, Object>> otherPlayers = new ArrayList<>();
		if (local != null && playerPos != null)
		{
			for (Player p : client.getPlayers())
			{
				if (p == null || p == local || p.getName() == null) continue;
				WorldPoint pPos = p.getWorldLocation();
				if (pPos == null) continue;
				if (playerPos.distanceTo(pPos) > 15) continue;

				Map<String, Object> pl = new LinkedHashMap<>();
				pl.put("name", p.getName());
				pl.put("x", pPos.getX());
				pl.put("y", pPos.getY());
				pl.put("anim", p.getAnimation());
				pl.put("combatLevel", p.getCombatLevel());
				Actor target = p.getInteracting();
				if (target != null)
				{
					pl.put("interacting", target.getName());
				}
				otherPlayers.add(pl);
			}
		}

		// Skills
		Skill[] skills = Skill.values();
		int[] skillXp = new int[skills.length];
		int[] skillRealLevel = new int[skills.length];
		int[] skillBoostedLevel = new int[skills.length];
		for (Skill skill : skills)
		{
			if (skill == Skill.OVERALL) continue;
			int ord = skill.ordinal();
			skillXp[ord] = client.getSkillExperience(skill);
			skillRealLevel[ord] = client.getRealSkillLevel(skill);
			skillBoostedLevel[ord] = client.getBoostedSkillLevel(skill);
		}

		// Drain pending hitsplats
		List<Map<String, Object>> hits = tickBuffer.drainPendingHits();

		// We skip objects and ground items from the per-tick buffer to keep it lightweight.
		// The /api/objects and /api/ground-items endpoints are still available for on-demand queries.

		TickStateBuffer.TickSnapshot snapshot = new TickStateBuffer.TickSnapshot(
			tick, timestamp, playerState, npcs,
			null, // objects (omitted for performance)
			null, // ground items (omitted for performance)
			otherPlayers,
			skillXp, skillRealLevel, skillBoostedLevel,
			hits
		);
		tickBuffer.add(snapshot);
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Actor target = event.getActor();
		Hitsplat hitsplat = event.getHitsplat();

		// Buffer hitsplat for the tick state buffer (always active)
		if (tickBuffer != null)
		{
			String actorName = target != null ? target.getName() : "Unknown";
			String kind = "other";
			int actorId = -1;
			if (target instanceof NPC)
			{
				kind = "npc";
				actorId = ((NPC) target).getId();
			}
			else if (target instanceof Player)
			{
				kind = target == client.getLocalPlayer() ? "local" : "player";
			}
			tickBuffer.addPendingHit(new TickStateBuffer.PendingHit(
				actorName, kind, actorId,
				hitsplat.getAmount(), hitsplat.getHitsplatType(), hitsplat.isMine()
			));
		}

		if (apiServer == null || (!apiServer.hasSseClients() && !apiServer.isRecording()))
		{
			return;
		}

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tick", client.getTickCount());
		data.put("timestamp", System.currentTimeMillis());
		data.put("target", serializeActorBrief(target));
		data.put("amount", hitsplat.getAmount());
		data.put("hitsplatType", hitsplat.getHitsplatType());
		data.put("isMine", hitsplat.isMine());
		data.put("isOthers", hitsplat.isOthers());
		data.put("disappearsOnGameCycle", hitsplat.getDisappearsOnGameCycle());

		if (apiServer.hasSseClients()) apiServer.broadcastEvent("hitsplat", data);
		if (apiServer.isRecording()) apiServer.addRecordingEvent("hitsplat", data);
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (apiServer == null || (!apiServer.hasSseClients() && !apiServer.isRecording()))
		{
			return;
		}

		Actor actor = event.getActor();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tick", client.getTickCount());
		data.put("timestamp", System.currentTimeMillis());
		data.put("actor", serializeActorBrief(actor));
		data.put("animation", actor.getAnimation());

		if (apiServer.hasSseClients()) apiServer.broadcastEvent("animation_changed", data);
		if (apiServer.isRecording()) apiServer.addRecordingEvent("animation_changed", data);
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		if (apiServer == null || (!apiServer.hasSseClients() && !apiServer.isRecording()))
		{
			return;
		}

		NPC npc = event.getNpc();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tick", client.getTickCount());
		data.put("timestamp", System.currentTimeMillis());
		data.put("npc", serializeNpcBrief(npc));

		if (apiServer.hasSseClients()) apiServer.broadcastEvent("npc_spawned", data);
		if (apiServer.isRecording()) apiServer.addRecordingEvent("npc_spawned", data);
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		if (apiServer == null || (!apiServer.hasSseClients() && !apiServer.isRecording()))
		{
			return;
		}

		NPC npc = event.getNpc();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tick", client.getTickCount());
		data.put("timestamp", System.currentTimeMillis());
		data.put("npc", serializeNpcBrief(npc));

		if (apiServer.hasSseClients()) apiServer.broadcastEvent("npc_despawned", data);
		if (apiServer.isRecording()) apiServer.addRecordingEvent("npc_despawned", data);
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (apiServer == null || (!apiServer.hasSseClients() && !apiServer.isRecording()))
		{
			return;
		}

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tick", client.getTickCount());
		data.put("timestamp", System.currentTimeMillis());
		data.put("actor", serializeActorBrief(event.getActor()));

		if (apiServer.hasSseClients()) apiServer.broadcastEvent("actor_death", data);
		if (apiServer.isRecording()) apiServer.addRecordingEvent("actor_death", data);
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (apiServer == null || (!apiServer.hasSseClients() && !apiServer.isRecording()))
		{
			return;
		}

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tick", client.getTickCount());
		data.put("timestamp", System.currentTimeMillis());
		data.put("source", serializeActorBrief(event.getSource()));
		Actor target = event.getTarget();
		data.put("target", target != null ? serializeActorBrief(target) : null);

		if (apiServer.hasSseClients()) apiServer.broadcastEvent("interacting_changed", data);
		if (apiServer.isRecording()) apiServer.addRecordingEvent("interacting_changed", data);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (apiServer == null)
		{
			return;
		}

		String cleanedTarget = event.getMenuTarget().replaceAll("<[^>]+>", "").trim();

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tick", client.getTickCount());
		data.put("timestamp", System.currentTimeMillis());
		data.put("option", event.getMenuOption());
		data.put("target", cleanedTarget);
		data.put("rawTarget", event.getMenuTarget());
		data.put("id", event.getId());
		data.put("menuAction", event.getMenuAction().name());
		data.put("param0", event.getParam0());
		data.put("param1", event.getParam1());

		Player local = client.getLocalPlayer();
		if (local != null)
		{
			WorldPoint wp = local.getWorldLocation();
			if (wp != null)
			{
				Map<String, Object> pos = new LinkedHashMap<>();
				pos.put("x", wp.getX());
				pos.put("y", wp.getY());
				pos.put("plane", wp.getPlane());
				data.put("playerPosition", pos);
			}
		}

		String actionName = event.getMenuAction().name();

		if (actionName.startsWith("NPC_"))
		{
			int npcIndex = event.getId();
			for (NPC npc : client.getNpcs())
			{
				if (npc.getIndex() == npcIndex)
				{
					data.put("npc", serializeNpcBrief(npc));
					break;
				}
			}
		}
		else if (actionName.startsWith("GAME_OBJECT_"))
		{
			try
			{
				ObjectComposition def = client.getObjectDefinition(event.getId());
				if (def != null)
				{
					Map<String, Object> obj = new LinkedHashMap<>();
					obj.put("objectId", event.getId());
					obj.put("name", def.getName());

					int sceneX = event.getParam0();
					int sceneY = event.getParam1();
					if (sceneX > 0 && sceneY > 0)
					{
						Map<String, Object> objPos = new LinkedHashMap<>();
						objPos.put("x", client.getBaseX() + sceneX);
						objPos.put("y", client.getBaseY() + sceneY);
						objPos.put("plane", client.getPlane());
						obj.put("position", objPos);
					}

					data.put("object", obj);
				}
			}
			catch (Exception ignored)
			{
			}
		}
		else if (actionName.startsWith("PLAYER_"))
		{
			for (Player p : client.getPlayers())
			{
				if (p != null && p.getName() != null && cleanedTarget.contains(p.getName()))
				{
					Map<String, Object> playerInfo = new LinkedHashMap<>();
					playerInfo.put("name", p.getName());
					playerInfo.put("combatLevel", p.getCombatLevel());
					WorldPoint pwp = p.getWorldLocation();
					if (pwp != null)
					{
						Map<String, Object> ppos = new LinkedHashMap<>();
						ppos.put("x", pwp.getX());
						ppos.put("y", pwp.getY());
						ppos.put("plane", pwp.getPlane());
						playerInfo.put("position", ppos);
					}
					data.put("clickedPlayer", playerInfo);
					break;
				}
			}
		}

		// Add world coordinates for walk/movement clicks
		if ("WALK".equals(actionName))
		{
			int sceneX = event.getParam0();
			int sceneY = event.getParam1();
			Map<String, Object> clickedTile = new LinkedHashMap<>();
			clickedTile.put("x", client.getBaseX() + sceneX);
			clickedTile.put("y", client.getBaseY() + sceneY);
			clickedTile.put("plane", client.getPlane());
			data.put("clickedTile", clickedTile);
		}

		// Always log to interaction history (not gated by SSE clients)
		Map<String, Object> interaction = new LinkedHashMap<>();
		interaction.put("tick", client.getTickCount());
		interaction.put("timestamp", System.currentTimeMillis());
		interaction.put("type", "click");
		interaction.put("action", event.getMenuOption());
		interaction.put("target", cleanedTarget);
		interaction.put("id", event.getId());
		interaction.put("menuAction", event.getMenuAction().name());
		apiServer.addInteraction(interaction);

		// Feed into multi-layer action tracker
		if (actionTracker != null)
		{
			Map<String, Object> actionDetails = new LinkedHashMap<>();
			actionDetails.put("id", event.getId());
			actionDetails.put("param0", event.getParam0());
			actionDetails.put("param1", event.getParam1());
			actionTracker.addMenuAction(
				client.getTickCount(),
				event.getMenuOption(),
				cleanedTarget,
				event.getMenuAction().name(),
				actionDetails
			);
		}

		// Broadcast SSE and/or record
		if (apiServer.hasSseClients()) apiServer.broadcastEvent("menu_clicked", data);
		if (apiServer.isRecording()) apiServer.addRecordingEvent("menu_clicked", data);
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (actionTracker == null)
		{
			return;
		}

		String name = event.getEventName();
		// Filter to interesting callbacks (skip very noisy ones)
		if (name == null || name.isEmpty()) return;

		// Only track action-related callbacks, skip rendering/UI noise
		if (name.startsWith("cc_") || name.startsWith("if_") ||
			name.contains("action") || name.contains("click") ||
			name.contains("button") || name.contains("op"))
		{
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("eventName", name);
			// Script args can be read from the client's intStack/stringStack
			// but accessing them here is fragile; we log the event name for now
			actionTracker.addScriptAction(client.getTickCount(), name, details);
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (apiServer == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		// Read the top menu entry to detect what the user is hovering over
		MenuEntry[] menuEntries = client.getMenuEntries();
		if (menuEntries == null || menuEntries.length == 0)
		{
			return;
		}

		// Top entry is the last element in the array
		MenuEntry top = menuEntries[menuEntries.length - 1];
		String option = top.getOption();
		String target = top.getTarget();
		if (target == null)
		{
			target = "";
		}
		String cleanedTarget = target.replaceAll("<[^>]+>", "").trim();

		// Build a unique key from option + target to detect changes
		String hoverKey = option + "|" + cleanedTarget;

		// Skip walk/cancel/default entries — they're noise
		if ("Walk here".equals(option) || "Cancel".equals(option))
		{
			return;
		}

		// Only log when hover target actually changes
		Map<String, Object> hoverEntry = new LinkedHashMap<>();
		hoverEntry.put("tick", client.getTickCount());
		hoverEntry.put("timestamp", System.currentTimeMillis());
		hoverEntry.put("type", "hover");
		hoverEntry.put("action", option);
		hoverEntry.put("target", cleanedTarget);
		hoverEntry.put("id", top.getIdentifier());
		apiServer.addHoverIfChanged(hoverKey, hoverEntry);
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (apiServer == null)
		{
			return;
		}

		NPC npc = event.getNpc();
		Map<String, Object> drop = new LinkedHashMap<>();
		drop.put("npcName", npc.getName());
		drop.put("npcId", npc.getId());
		drop.put("npcCombatLevel", npc.getCombatLevel());
		drop.put("tick", client.getTickCount());
		drop.put("timestamp", System.currentTimeMillis());

		List<Map<String, Object>> items = new ArrayList<>();
		for (ItemStack item : event.getItems())
		{
			Map<String, Object> itemData = new LinkedHashMap<>();
			itemData.put("itemId", item.getId());
			try
			{
				ItemComposition def = client.getItemDefinition(item.getId());
				itemData.put("name", def != null ? def.getName() : null);
			}
			catch (Exception e)
			{
				itemData.put("name", null);
			}
			itemData.put("quantity", item.getQuantity());
			items.add(itemData);
		}
		drop.put("items", items);
		apiServer.addLootDrop(drop);

		if (apiServer.hasSseClients())
		{
			apiServer.broadcastEvent("loot_received", drop);
		}
		if (apiServer.isRecording())
		{
			apiServer.addRecordingEvent("loot_received", drop);
		}
	}

	@Subscribe
	public void onSoundEffectPlayed(SoundEffectPlayed event)
	{
		if (apiServer == null || (!apiServer.hasSseClients() && !apiServer.isRecording()))
		{
			return;
		}

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tick", client.getTickCount());
		data.put("timestamp", System.currentTimeMillis());
		data.put("soundId", event.getSoundId());
		data.put("source", "EFFECT");
		if (apiServer.hasSseClients()) apiServer.broadcastEvent("sound_effect", data);
		if (apiServer.isRecording()) apiServer.addRecordingEvent("sound_effect", data);
	}

	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event)
	{
		if (apiServer == null || (!apiServer.hasSseClients() && !apiServer.isRecording()))
		{
			return;
		}

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tick", client.getTickCount());
		data.put("timestamp", System.currentTimeMillis());
		data.put("soundId", event.getSoundId());
		data.put("sceneX", event.getSceneX());
		data.put("sceneY", event.getSceneY());
		data.put("range", event.getRange());
		data.put("source", "AREA");
		if (apiServer.hasSseClients()) apiServer.broadcastEvent("sound_effect", data);
		if (apiServer.isRecording()) apiServer.addRecordingEvent("sound_effect", data);
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		if (apiServer == null || (!apiServer.hasSseClients() && !apiServer.isRecording()))
		{
			return;
		}

		GameObject go = event.getGameObject();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tick", client.getTickCount());
		data.put("timestamp", System.currentTimeMillis());
		data.put("objectId", go.getId());
		try
		{
			ObjectComposition def = client.getObjectDefinition(go.getId());
			data.put("objectName", def != null ? def.getName() : null);
		}
		catch (Exception e)
		{
			data.put("objectName", null);
		}
		WorldPoint wp = go.getWorldLocation();
		if (wp != null)
		{
			Map<String, Object> pos = new LinkedHashMap<>();
			pos.put("x", wp.getX());
			pos.put("y", wp.getY());
			pos.put("plane", wp.getPlane());
			data.put("position", pos);
		}
		data.put("sizeX", go.sizeX());
		data.put("sizeY", go.sizeY());

		if (apiServer.hasSseClients()) apiServer.broadcastEvent("object_spawned", data);
		if (apiServer.isRecording()) apiServer.addRecordingEvent("object_spawned", data);
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		if (apiServer == null || (!apiServer.hasSseClients() && !apiServer.isRecording()))
		{
			return;
		}

		GameObject go = event.getGameObject();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tick", client.getTickCount());
		data.put("timestamp", System.currentTimeMillis());
		data.put("objectId", go.getId());
		try
		{
			ObjectComposition def = client.getObjectDefinition(go.getId());
			data.put("objectName", def != null ? def.getName() : null);
		}
		catch (Exception e)
		{
			data.put("objectName", null);
		}
		WorldPoint wp = go.getWorldLocation();
		if (wp != null)
		{
			Map<String, Object> pos = new LinkedHashMap<>();
			pos.put("x", wp.getX());
			pos.put("y", wp.getY());
			pos.put("plane", wp.getPlane());
			data.put("position", pos);
		}

		if (apiServer.hasSseClients()) apiServer.broadcastEvent("object_despawned", data);
		if (apiServer.isRecording()) apiServer.addRecordingEvent("object_despawned", data);
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		if (apiServer == null || (!apiServer.hasSseClients() && !apiServer.isRecording()))
		{
			return;
		}

		Projectile proj = event.getProjectile();

		// Only record on first cycle (spawn), not every movement tick
		if (client.getGameCycle() > proj.getStartCycle())
		{
			return;
		}

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tick", client.getTickCount());
		data.put("timestamp", System.currentTimeMillis());
		data.put("projectileId", proj.getId());
		data.put("startCycle", proj.getStartCycle());
		data.put("endCycle", proj.getEndCycle());
		data.put("remainingCycles", proj.getRemainingCycles());

		Actor target = proj.getInteracting();
		if (target != null)
		{
			data.put("targetActor", serializeActorBrief(target));
		}

		WorldPoint targetPoint = null;
		// If no actor target, compute the area target from the position
		if (target == null)
		{
			try
			{
				int targetX = (int) proj.getX1();
				int targetY = (int) proj.getY1();
				LocalPoint lp = new LocalPoint(targetX, targetY);
				targetPoint = WorldPoint.fromLocal(client, lp);
			}
			catch (Exception ignored) {}
		}
		if (targetPoint != null)
		{
			Map<String, Object> tp = new LinkedHashMap<>();
			tp.put("x", targetPoint.getX());
			tp.put("y", targetPoint.getY());
			tp.put("plane", targetPoint.getPlane());
			data.put("targetPoint", tp);
		}

		if (apiServer.hasSseClients()) apiServer.broadcastEvent("projectile_spawned", data);
		if (apiServer.isRecording()) apiServer.addRecordingEvent("projectile_spawned", data);
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated event)
	{
		if (apiServer == null || (!apiServer.hasSseClients() && !apiServer.isRecording()))
		{
			return;
		}

		GraphicsObject gfx = event.getGraphicsObject();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tick", client.getTickCount());
		data.put("timestamp", System.currentTimeMillis());
		data.put("graphicsId", gfx.getId());
		data.put("startCycle", gfx.getStartCycle());

		LocalPoint lp = gfx.getLocation();
		if (lp != null)
		{
			Map<String, Object> pos = new LinkedHashMap<>();
			pos.put("worldX", client.getBaseX() + lp.getSceneX());
			pos.put("worldY", client.getBaseY() + lp.getSceneY());
			pos.put("plane", client.getPlane());
			data.put("position", pos);
		}

		if (apiServer.hasSseClients()) apiServer.broadcastEvent("gfx_created", data);
		if (apiServer.isRecording()) apiServer.addRecordingEvent("gfx_created", data);
	}

	private void buildVarpToVarbitIndex()
	{
		try
		{
			IndexDataBase indexConfig = client.getIndexConfig();
			final int[] varbitIds = indexConfig.getFileIds(14); // VARBITS_ARCHIVE_ID = 14
			if (varbitIds == null)
			{
				return;
			}

			Map<Integer, List<Integer>> map = new HashMap<>();
			for (int varbitId : varbitIds)
			{
				VarbitComposition varbit = client.getVarbit(varbitId);
				if (varbit != null)
				{
					map.computeIfAbsent(varbit.getIndex(), k -> new ArrayList<>()).add(varbitId);
				}
			}
			varpToVarbits = map;
		}
		catch (Exception e)
		{
			log.debug("Failed to build varp->varbit index", e);
		}
	}

	private void doFullCollection()
	{
		if (config.syncSkills())
		{
			collector.pollAllSkills();
		}

		if (config.syncBank())
		{
			ItemContainer bank = client.getItemContainer(InventoryID.BANK);
			if (bank != null)
			{
				collector.updateBank(bank);
			}
		}

		if (config.syncInventory())
		{
			ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
			if (inv != null)
			{
				collector.updateInventory(inv);
			}
		}

		if (config.syncEquipment())
		{
			ItemContainer equip = client.getItemContainer(InventoryID.EQUIPMENT);
			if (equip != null)
			{
				collector.updateEquipment(equip);
			}
		}

		if (config.syncQuests())
		{
			collector.pollQuests();
		}

		if (config.syncDiaries())
		{
			collector.pollDiaries();
		}

		if (config.syncCombatAchievements())
		{
			collector.pollCombatAchievements();
		}
	}

	private void doSave()
	{
		if (collector == null || writer == null || !dirty)
		{
			return;
		}

		PlayerSyncData snapshot = collector.buildSnapshot();
		if (snapshot.player == null)
		{
			return;
		}

		dirty = false;

		executor.submit(() ->
		{
			try
			{
				writer.write(snapshot);
				lastSaveMs = System.currentTimeMillis();
			}
			catch (Exception e)
			{
				log.warn("OSRS Companion: Save error", e);
			}
		});
	}

	private void recalcSyncThreshold()
	{
		int seconds = Math.max(30, config.syncIntervalSeconds());
		syncTickThreshold = (int) (seconds / 0.6);
	}

	private Map<String, Object> buildGameTickData()
	{
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tick", client.getTickCount());
		data.put("timestamp", System.currentTimeMillis());
		data.put("fps", client.getFPS());

		Player local = client.getLocalPlayer();
		if (local != null)
		{
			Map<String, Object> player = new LinkedHashMap<>();
			WorldPoint wp = local.getWorldLocation();
			if (wp != null)
			{
				Map<String, Object> pos = new LinkedHashMap<>();
				pos.put("x", wp.getX());
				pos.put("y", wp.getY());
				pos.put("plane", wp.getPlane());
				player.put("position", pos);
			}
			player.put("animation", local.getAnimation());
			player.put("health", client.getBoostedSkillLevel(Skill.HITPOINTS));
			player.put("prayer", client.getBoostedSkillLevel(Skill.PRAYER));
			player.put("runEnergy", client.getEnergy() / 100.0);
			player.put("specialAttack", client.getVarpValue(48) / 10.0);
			player.put("isIdle", local.getAnimation() == -1 && local.getInteracting() == null);

			Actor interacting = local.getInteracting();
			if (interacting != null)
			{
				player.put("interacting", serializeActorBrief(interacting));
			}

			// Active prayers
			List<String> activePrayers = new ArrayList<>();
			for (Prayer prayer : Prayer.values())
			{
				if (client.isPrayerActive(prayer))
				{
					activePrayers.add(prayer.name());
				}
			}
			if (!activePrayers.isEmpty())
			{
				player.put("activePrayers", activePrayers);
			}

			data.put("player", player);
		}

		// Nearby NPCs (compact: within 15 tiles)
		if (local != null)
		{
			WorldPoint playerPos = local.getWorldLocation();
			List<Map<String, Object>> nearbyNpcs = new ArrayList<>();
			for (NPC npc : client.getNpcs())
			{
				if (npc == null || npc.getName() == null) continue;
				WorldPoint npcPos = npc.getWorldLocation();
				if (npcPos == null) continue;
				if (playerPos.distanceTo(npcPos) <= 15)
				{
					Map<String, Object> n = new LinkedHashMap<>();
					n.put("id", npc.getId());
					n.put("name", npc.getName());
					Map<String, Object> pos = new LinkedHashMap<>();
					pos.put("x", npcPos.getX());
					pos.put("y", npcPos.getY());
					n.put("pos", pos);
					n.put("anim", npc.getAnimation());
					int ratio = npc.getHealthRatio();
					int scale = npc.getHealthScale();
					if (ratio >= 0 && scale > 0)
					{
						n.put("hpRatio", ratio);
						n.put("hpScale", scale);
					}
					Actor npcTarget = npc.getInteracting();
					if (npcTarget != null)
					{
						n.put("interacting", npcTarget.getName());
					}
					try
					{
						short[] spriteIds = npc.getOverheadSpriteIds();
						if (spriteIds != null && spriteIds.length > 0)
						{
							List<Integer> icons = new ArrayList<>();
							for (short s : spriteIds)
							{
								icons.add((int) s);
							}
							n.put("overheadSpriteIds", icons);
						}
					}
					catch (Exception ignored) {}
					nearbyNpcs.add(n);
				}
			}
			if (!nearbyNpcs.isEmpty())
			{
				data.put("nearbyNpcs", nearbyNpcs);
			}
		}

		return data;
	}

	private Map<String, Object> serializeActorBrief(Actor actor)
	{
		if (actor == null)
		{
			return null;
		}

		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", actor.getName());
		if (actor instanceof NPC)
		{
			m.put("type", "NPC");
			m.put("id", ((NPC) actor).getId());
			m.put("index", ((NPC) actor).getIndex());
		}
		else if (actor instanceof Player)
		{
			m.put("type", "PLAYER");
		}
		WorldPoint wp = actor.getWorldLocation();
		if (wp != null)
		{
			Map<String, Object> pos = new LinkedHashMap<>();
			pos.put("x", wp.getX());
			pos.put("y", wp.getY());
			pos.put("plane", wp.getPlane());
			m.put("position", pos);
		}
		return m;
	}

	private Map<String, Object> serializeNpcBrief(NPC npc)
	{
		if (npc == null)
		{
			return null;
		}

		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", npc.getId());
		m.put("name", npc.getName());
		m.put("index", npc.getIndex());
		m.put("combatLevel", npc.getCombatLevel());
		WorldPoint wp = npc.getWorldLocation();
		if (wp != null)
		{
			Map<String, Object> pos = new LinkedHashMap<>();
			pos.put("x", wp.getX());
			pos.put("y", wp.getY());
			pos.put("plane", wp.getPlane());
			m.put("position", pos);
		}
		return m;
	}
}
