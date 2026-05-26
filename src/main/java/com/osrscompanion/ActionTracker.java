package com.osrscompanion;

import java.util.*;

/**
 * Multi-layer action tracker that captures game actions from three sources:
 * <ul>
 *   <li><b>menu</b> — Standard MenuOptionClicked events (user clicks, plugin menu actions)</li>
 *   <li><b>script</b> — CS2 script callbacks (ScriptCallbackEvent) that trigger game actions</li>
 *   <li><b>inferred</b> — State changes detected without a corresponding menu click (inventory,
 *       equipment, or position changes that occurred "silently")</li>
 * </ul>
 *
 * <p>This goes beyond what runelite-dev-mcp's ActionLog can capture, which only tracks
 * MenuOptionClicked events and explicitly cannot see raw packet sends.
 */
public class ActionTracker
{
	public static final class TrackedAction
	{
		public final int tick;
		public final long timestamp;
		public final String source; // "menu", "script", "inferred"
		public final String action;
		public final String target;
		public final Map<String, Object> details;

		public TrackedAction(int tick, long timestamp, String source,
			String action, String target, Map<String, Object> details)
		{
			this.tick = tick;
			this.timestamp = timestamp;
			this.source = source;
			this.action = action;
			this.target = target;
			this.details = details != null ? details : Collections.emptyMap();
		}

		public Map<String, Object> toMap()
		{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("tick", tick);
			m.put("timestamp", timestamp);
			m.put("source", source);
			m.put("action", action);
			m.put("target", target);
			if (!details.isEmpty())
			{
				m.put("details", details);
			}
			return m;
		}
	}

	private final TrackedAction[] buffer;
	private int head = 0;
	private int count = 0;
	private final Object lock = new Object();

	// Previous state for inference
	private int[] lastInventoryItemIds;
	private int[] lastInventoryQuantities;
	private int[] lastEquipmentItemIds;
	private int lastPositionX = -1;
	private int lastPositionY = -1;
	private int lastPositionPlane = -1;

	// Track which ticks had menu actions (for inference)
	private final Set<Integer> ticksWithMenuAction = Collections.synchronizedSet(new HashSet<>());
	private static final int TICK_CLEANUP_THRESHOLD = 50;

	public ActionTracker(int capacity)
	{
		this.buffer = new TrackedAction[capacity];
	}

	/**
	 * Record a menu action (from MenuOptionClicked).
	 */
	public void addMenuAction(int tick, String option, String target,
		String menuAction, Map<String, Object> details)
	{
		ticksWithMenuAction.add(tick);
		add(new TrackedAction(tick, System.currentTimeMillis(), "menu",
			option + " → " + target, menuAction, details));
	}

	/**
	 * Record a CS2 script callback.
	 */
	public void addScriptAction(int tick, String scriptName, Map<String, Object> details)
	{
		add(new TrackedAction(tick, System.currentTimeMillis(), "script",
			scriptName, "CS2 callback", details));
	}

	/**
	 * Compare current game state with previous state and flag changes
	 * that occurred without a menu click on this tick.
	 *
	 * @param tick current game tick
	 * @param invItemIds current inventory item IDs (28 slots, 0 or -1 for empty)
	 * @param invQuantities current inventory item quantities
	 * @param equipItemIds current equipment item IDs (indexed by equipment slot)
	 * @param posX player world X
	 * @param posY player world Y
	 * @param plane player plane
	 */
	public void checkStateChanges(int tick, int[] invItemIds, int[] invQuantities,
		int[] equipItemIds, int posX, int posY, int plane)
	{
		boolean hadMenuAction = ticksWithMenuAction.contains(tick);

		// Inventory changes
		if (lastInventoryItemIds != null && invItemIds != null && !hadMenuAction)
		{
			for (int i = 0; i < Math.min(invItemIds.length, lastInventoryItemIds.length); i++)
			{
				int oldId = lastInventoryItemIds[i];
				int newId = invItemIds[i];
				int oldQty = i < lastInventoryQuantities.length ? lastInventoryQuantities[i] : 0;
				int newQty = i < invQuantities.length ? invQuantities[i] : 0;

				if (oldId != newId || oldQty != newQty)
				{
					Map<String, Object> details = new LinkedHashMap<>();
					details.put("slot", i);
					details.put("oldItemId", oldId);
					details.put("newItemId", newId);
					details.put("oldQuantity", oldQty);
					details.put("newQuantity", newQty);

					String desc;
					if (oldId <= 0 && newId > 0)
					{
						desc = "Item appeared in slot " + i + " (id=" + newId + ", qty=" + newQty + ")";
					}
					else if (oldId > 0 && newId <= 0)
					{
						desc = "Item removed from slot " + i + " (was id=" + oldId + ")";
					}
					else if (oldQty != newQty)
					{
						desc = "Quantity changed in slot " + i + " (id=" + newId + ": " + oldQty + " → " + newQty + ")";
					}
					else
					{
						desc = "Item swapped in slot " + i + " (id=" + oldId + " → " + newId + ")";
					}

					add(new TrackedAction(tick, System.currentTimeMillis(), "inferred",
						desc, "Inventory change without menu click", details));
				}
			}
		}

		// Equipment changes
		if (lastEquipmentItemIds != null && equipItemIds != null && !hadMenuAction)
		{
			for (int i = 0; i < Math.min(equipItemIds.length, lastEquipmentItemIds.length); i++)
			{
				if (equipItemIds[i] != lastEquipmentItemIds[i])
				{
					Map<String, Object> details = new LinkedHashMap<>();
					details.put("equipSlot", i);
					details.put("oldItemId", lastEquipmentItemIds[i]);
					details.put("newItemId", equipItemIds[i]);

					String desc = "Equipment slot " + i + " changed (id=" +
						lastEquipmentItemIds[i] + " → " + equipItemIds[i] + ")";

					add(new TrackedAction(tick, System.currentTimeMillis(), "inferred",
						desc, "Equipment change without menu click", details));
				}
			}
		}

		// Update last known state
		if (invItemIds != null)
		{
			lastInventoryItemIds = invItemIds.clone();
			lastInventoryQuantities = invQuantities != null ? invQuantities.clone() : new int[0];
		}
		if (equipItemIds != null)
		{
			lastEquipmentItemIds = equipItemIds.clone();
		}
		lastPositionX = posX;
		lastPositionY = posY;
		lastPositionPlane = plane;

		// Cleanup old tick records to prevent memory growth
		if (ticksWithMenuAction.size() > TICK_CLEANUP_THRESHOLD)
		{
			int threshold = tick - TICK_CLEANUP_THRESHOLD;
			ticksWithMenuAction.removeIf(t -> t < threshold);
		}
	}

	private void add(TrackedAction action)
	{
		synchronized (lock)
		{
			buffer[head] = action;
			head = (head + 1) % buffer.length;
			if (count < buffer.length)
			{
				count++;
			}
		}
	}

	/**
	 * Get the last N actions, oldest first. Optionally filter by source.
	 */
	public List<TrackedAction> getActions(int last, String sourceFilter, String searchFilter)
	{
		synchronized (lock)
		{
			List<TrackedAction> result = new ArrayList<>();
			int start = Math.max(0, count - last * 4); // over-fetch for filtering

			for (int i = start; i < count; i++)
			{
				int idx = ((head - count + i) % buffer.length + buffer.length) % buffer.length;
				TrackedAction a = buffer[idx];
				if (a == null) continue;

				if (sourceFilter != null && !sourceFilter.equalsIgnoreCase(a.source)) continue;
				if (searchFilter != null)
				{
					String lower = searchFilter.toLowerCase();
					if (!a.action.toLowerCase().contains(lower) &&
						!a.target.toLowerCase().contains(lower))
					{
						continue;
					}
				}

				result.add(a);
			}

			// Trim to requested count
			if (result.size() > last)
			{
				result = result.subList(result.size() - last, result.size());
			}
			return result;
		}
	}

	public int capacity()
	{
		return buffer.length;
	}

	public int filled()
	{
		synchronized (lock)
		{
			return count;
		}
	}
}
