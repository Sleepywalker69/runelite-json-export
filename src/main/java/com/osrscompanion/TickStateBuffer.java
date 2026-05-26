package com.osrscompanion;

import java.util.*;

/**
 * Ring buffer of per-tick game state snapshots. Backs the /api/buffer endpoint
 * which supports both absolute tick queries and delta-encoded recent history.
 *
 * <p>Capture happens on the client thread; reads (from HTTP threads) are synchronized.
 * Snapshot maps are treated as immutable after insertion.
 */
public class TickStateBuffer
{
	/**
	 * A single tick's worth of game state.
	 */
	public static final class TickSnapshot
	{
		public final int tick;
		public final long timestampMs;
		public final Map<String, Object> player;          // local player state
		public final List<Map<String, Object>> npcs;       // nearby NPCs
		public final List<Map<String, Object>> objects;    // nearby game objects
		public final List<Map<String, Object>> groundItems;// nearby ground items
		public final List<Map<String, Object>> otherPlayers;// visible other players
		public final int[] skillXp;                        // per-skill total XP (indexed by ordinal)
		public final int[] skillRealLevel;                 // per-skill real level
		public final int[] skillBoostedLevel;              // per-skill boosted level
		public final List<Map<String, Object>> hits;       // hitsplats applied this tick

		public TickSnapshot(int tick, long timestampMs,
			Map<String, Object> player,
			List<Map<String, Object>> npcs,
			List<Map<String, Object>> objects,
			List<Map<String, Object>> groundItems,
			List<Map<String, Object>> otherPlayers,
			int[] skillXp, int[] skillRealLevel, int[] skillBoostedLevel,
			List<Map<String, Object>> hits)
		{
			this.tick = tick;
			this.timestampMs = timestampMs;
			this.player = player;
			this.npcs = npcs != null ? npcs : Collections.emptyList();
			this.objects = objects != null ? objects : Collections.emptyList();
			this.groundItems = groundItems != null ? groundItems : Collections.emptyList();
			this.otherPlayers = otherPlayers != null ? otherPlayers : Collections.emptyList();
			this.skillXp = skillXp;
			this.skillRealLevel = skillRealLevel;
			this.skillBoostedLevel = skillBoostedLevel;
			this.hits = hits != null ? hits : Collections.emptyList();
		}
	}

	/**
	 * A hitsplat waiting to be attached to the next tick snapshot.
	 */
	public static final class PendingHit
	{
		public final String actorName;
		public final String kind; // "npc", "player", "local"
		public final int actorId; // NPC id or -1
		public final int amount;
		public final int type;
		public final boolean isMine;

		public PendingHit(String actorName, String kind, int actorId,
			int amount, int type, boolean isMine)
		{
			this.actorName = actorName;
			this.kind = kind;
			this.actorId = actorId;
			this.amount = amount;
			this.type = type;
			this.isMine = isMine;
		}

		public Map<String, Object> toMap()
		{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("actor", actorName);
			m.put("kind", kind);
			if (actorId >= 0)
			{
				m.put("actorId", actorId);
			}
			m.put("amount", amount);
			m.put("type", type);
			m.put("isMine", isMine);
			return m;
		}
	}

	private final TickSnapshot[] buffer;
	private int head = 0;   // next write position
	private int count = 0;  // valid entries

	private final Object lock = new Object();
	private final Object hitLock = new Object();
	private final List<PendingHit> pendingHits = new ArrayList<>();

	public TickStateBuffer(int capacity)
	{
		this.buffer = new TickSnapshot[capacity];
	}

	/**
	 * Add a hitsplat to be included in the next tick snapshot.
	 * Thread-safe — called from the event bus.
	 */
	public void addPendingHit(PendingHit hit)
	{
		synchronized (hitLock)
		{
			pendingHits.add(hit);
		}
	}

	/**
	 * Drain pending hitsplats and return them as maps.
	 * Called on the client thread during snapshot capture.
	 */
	public List<Map<String, Object>> drainPendingHits()
	{
		synchronized (hitLock)
		{
			if (pendingHits.isEmpty())
			{
				return Collections.emptyList();
			}
			List<Map<String, Object>> result = new ArrayList<>();
			for (PendingHit h : pendingHits)
			{
				result.add(h.toMap());
			}
			pendingHits.clear();
			return result;
		}
	}

	/**
	 * Store a snapshot. Called on the client thread every GameTick.
	 */
	public void add(TickSnapshot snapshot)
	{
		synchronized (lock)
		{
			buffer[head] = snapshot;
			head = (head + 1) % buffer.length;
			if (count < buffer.length)
			{
				count++;
			}
		}
	}

	/**
	 * Get snapshot at a specific tick number, or null if not in buffer.
	 */
	public TickSnapshot getByTick(int tick)
	{
		synchronized (lock)
		{
			for (int i = 0; i < count; i++)
			{
				int idx = ((head - count + i) % buffer.length + buffer.length) % buffer.length;
				if (buffer[idx] != null && buffer[idx].tick == tick)
				{
					return buffer[idx];
				}
			}
			return null;
		}
	}

	/**
	 * Get the last N snapshots, oldest first.
	 */
	public List<TickSnapshot> getLastN(int n)
	{
		synchronized (lock)
		{
			int actual = Math.min(n, count);
			List<TickSnapshot> result = new ArrayList<>(actual);
			for (int i = count - actual; i < count; i++)
			{
				int idx = ((head - count + i) % buffer.length + buffer.length) % buffer.length;
				if (buffer[idx] != null)
				{
					result.add(buffer[idx]);
				}
			}
			return result;
		}
	}

	/**
	 * Get the most recent snapshot, or null if empty.
	 */
	public TickSnapshot latest()
	{
		synchronized (lock)
		{
			if (count == 0) return null;
			int idx = ((head - 1) % buffer.length + buffer.length) % buffer.length;
			return buffer[idx];
		}
	}

	/**
	 * Get the tick range [oldest, newest], or null if empty.
	 */
	public int[] range()
	{
		synchronized (lock)
		{
			if (count == 0) return null;
			int oldestIdx = ((head - count) % buffer.length + buffer.length) % buffer.length;
			int newestIdx = ((head - 1) % buffer.length + buffer.length) % buffer.length;
			return new int[]{buffer[oldestIdx].tick, buffer[newestIdx].tick};
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
