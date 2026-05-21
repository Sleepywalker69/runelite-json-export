package com.osrscompanion;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.osrscompanion.model.PlayerSyncData;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.util.concurrent.ScheduledExecutorService;

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

	private PlayerDataCollector collector;
	private PlayerDataWriter writer;
	private GameStateServer apiServer;
	private boolean dirty = false;
	private int tickCounter = 0;
	private int syncTickThreshold = 100;
	private boolean initialCollectionDone = false;

	@Override
	protected void startUp()
	{
		collector = new PlayerDataCollector(client);
		writer = new PlayerDataWriter(gson);
		recalcSyncThreshold();

		if (config.enableApiServer())
		{
			startApiServer();
		}

		log.info("OSRS Companion started — saving to ~/.runelite/osrs-companion/");
	}

	@Override
	protected void shutDown()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			doSave();
		}

		stopApiServer();

		collector = null;
		writer = null;
		log.info("OSRS Companion stopped");
	}

	private void startApiServer()
	{
		try
		{
			apiServer = new GameStateServer(client, clientThread, gson);
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
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			doSave();
			initialCollectionDone = false;
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
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (collector == null)
		{
			return;
		}

		int containerId = event.getContainerId();

		if (containerId == InventoryID.BANK.getId() && config.syncBank())
		{
			collector.updateBank(event.getItemContainer());
			dirty = true;
		}
		else if (containerId == InventoryID.INVENTORY.getId() && config.syncInventory())
		{
			collector.updateInventory(event.getItemContainer());
			dirty = true;
		}
		else if (containerId == InventoryID.EQUIPMENT.getId() && config.syncEquipment())
		{
			collector.updateEquipment(event.getItemContainer());
			dirty = true;
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		dirty = true;
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
			return;
		}

		if (tickCounter % 30 == 0)
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
}
