package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.model.PlayerSyncData;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Writes player data as JSON to a local file in ~/.runelite/osrs-companion/.
 */
@Slf4j
public class PlayerDataWriter
{
	private final Gson gson;
	private final File syncDir;

	public PlayerDataWriter(Gson gson)
	{
		this.gson = gson.newBuilder().setPrettyPrinting().create();
		this.syncDir = new File(System.getProperty("user.home"), ".runelite/osrs-companion");
	}

	/**
	 * Write player data to a local JSON file.
	 * File is written to ~/.runelite/osrs-companion/{username}.json
	 *
	 * @return true if write succeeded, false otherwise
	 */
	public boolean write(PlayerSyncData data)
	{
		if (data.player == null || data.player.username == null)
		{
			log.debug("OSRS Companion: No player data available, skipping write");
			return false;
		}

		if (!syncDir.exists() && !syncDir.mkdirs())
		{
			log.warn("OSRS Companion: Failed to create sync directory: {}", syncDir.getAbsolutePath());
			return false;
		}

		String filename = data.player.username.toLowerCase().replaceAll("[^a-z0-9_-]", "_") + ".json";
		File outputFile = new File(syncDir, filename);
		String json = gson.toJson(data);

		try (FileWriter writer = new FileWriter(outputFile))
		{
			writer.write(json);
			log.debug("OSRS Companion: Saved data for {} to {}", data.player.username, outputFile.getAbsolutePath());
			return true;
		}
		catch (IOException e)
		{
			log.warn("OSRS Companion: Failed to write data file", e);
			return false;
		}
	}
}
