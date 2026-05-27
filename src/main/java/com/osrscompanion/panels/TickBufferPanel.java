package com.osrscompanion.panels;

import static com.osrscompanion.UiScale.*;

import com.osrscompanion.OsrsCompanionPlugin;
import com.osrscompanion.TickStateBuffer;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.Map;

/**
 * Tick Buffer tab — timeline viewer for the 600-tick state buffer with delta highlights.
 * Shows changes between consecutive ticks: entity spawns/despawns, HP changes, skill gains.
 */
public class TickBufferPanel extends JPanel
{
	private final OsrsCompanionPlugin plugin;

	private final JSpinner tickCountSpinner;
	private final JCheckBox showNpcs;
	private final JCheckBox showPlayers;
	private final JCheckBox showSkills;
	private final JCheckBox showHits;
	private final JPanel deltaPanel;
	private final JLabel footerLabel = new JLabel("—");

	private static final Color ADDED_COLOR = new Color(76, 175, 80);    // green
	private static final Color REMOVED_COLOR = new Color(244, 67, 54);  // red
	private static final Color CHANGED_COLOR = new Color(255, 193, 7);  // yellow

	public TickBufferPanel(OsrsCompanionPlugin plugin)
	{
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(px(12), px(32), px(12), px(32)));

		// === Controls ===
		JPanel controlPanel = new JPanel();
		controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
		controlPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Tick count
		JPanel tickRow = new JPanel(new BorderLayout());
		tickRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tickRow.setMaximumSize(dim(600, 24));
		JLabel tickLabel = new JLabel("Last N ticks:");
		tickLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		tickLabel.setFont(tickLabel.getFont().deriveFont(fontSize(11f)));
		tickCountSpinner = new JSpinner(new SpinnerNumberModel(10, 2, 100, 5));
		tickCountSpinner.setPreferredSize(dim(60, 22));
		tickRow.add(tickLabel, BorderLayout.WEST);
		tickRow.add(tickCountSpinner, BorderLayout.EAST);
		controlPanel.add(tickRow);

		// Entity type filters
		JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, px(4), px(2)));
		filterRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filterRow.setMaximumSize(dim(600, 24));

		showNpcs = checkbox("NPCs", true);
		showPlayers = checkbox("Players", true);
		showSkills = checkbox("Skills", true);
		showHits = checkbox("Hits", true);

		filterRow.add(showNpcs);
		filterRow.add(showPlayers);
		filterRow.add(showSkills);
		filterRow.add(showHits);
		controlPanel.add(filterRow);

		// Refresh button
		JButton refreshBtn = new JButton("Refresh");
		refreshBtn.setFont(refreshBtn.getFont().deriveFont(Font.PLAIN, fontSize(10f)));
		refreshBtn.setFocusPainted(false);
		refreshBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		refreshBtn.setForeground(Color.WHITE);
		refreshBtn.setMaximumSize(dim(600, 24));
		refreshBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		refreshBtn.addActionListener(e -> refresh());
		controlPanel.add(refreshBtn);

		controlPanel.add(Box.createVerticalStrut(px(4)));
		add(controlPanel, BorderLayout.NORTH);

		// === Delta Display ===
		deltaPanel = new JPanel();
		deltaPanel.setLayout(new BoxLayout(deltaPanel, BoxLayout.Y_AXIS));
		deltaPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane scrollPane = new JScrollPane(deltaPanel);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(px(16));
		add(scrollPane, BorderLayout.CENTER);

		// === Footer ===
		footerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		footerLabel.setFont(footerLabel.getFont().deriveFont(Font.PLAIN, fontSize(10f)));
		footerLabel.setBorder(new EmptyBorder(px(4), 0, 0, 0));
		add(footerLabel, BorderLayout.SOUTH);
	}

	@SuppressWarnings("unchecked")
	public void refresh()
	{
		TickStateBuffer buffer = plugin.getTickBuffer();
		if (buffer == null)
		{
			deltaPanel.removeAll();
			footerLabel.setText("Tick buffer not available");
			deltaPanel.revalidate();
			deltaPanel.repaint();
			return;
		}

		int count = (int) tickCountSpinner.getValue();
		List<TickStateBuffer.TickSnapshot> snapshots = buffer.getLastN(count);

		deltaPanel.removeAll();

		if (snapshots.size() < 2)
		{
			JLabel noData = new JLabel("  Need at least 2 ticks of data");
			noData.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			noData.setFont(noData.getFont().deriveFont(fontSize(10f)));
			deltaPanel.add(noData);
			footerLabel.setText(buffer.filled() + " / " + buffer.capacity() + " ticks buffered");
			deltaPanel.revalidate();
			deltaPanel.repaint();
			return;
		}

		int deltaCount = 0;

		for (int i = 1; i < snapshots.size(); i++)
		{
			TickStateBuffer.TickSnapshot prev = snapshots.get(i - 1);
			TickStateBuffer.TickSnapshot curr = snapshots.get(i);

			boolean anyChange = false;

			// Player HP/Prayer changes
			if (showSkills.isSelected())
			{
				Map<String, Object> prevPlayer = prev.player;
				Map<String, Object> currPlayer = curr.player;
				if (prevPlayer != null && currPlayer != null)
				{
					int prevHp = getInt(prevPlayer, "health", -1);
					int currHp = getInt(currPlayer, "health", -1);
					if (prevHp != currHp && prevHp >= 0 && currHp >= 0)
					{
						int diff = currHp - prevHp;
						String sign = diff > 0 ? "+" : "";
						addDelta(curr.tick, "~", "Player HP: " + prevHp + " -> " + currHp + " (" + sign + diff + ")", CHANGED_COLOR);
						anyChange = true;
					}

					int prevPray = getInt(prevPlayer, "prayer", -1);
					int currPray = getInt(currPlayer, "prayer", -1);
					if (prevPray != currPray && prevPray >= 0 && currPray >= 0)
					{
						int diff = currPray - prevPray;
						String sign = diff > 0 ? "+" : "";
						addDelta(curr.tick, "~", "Prayer: " + prevPray + " -> " + currPray + " (" + sign + diff + ")", CHANGED_COLOR);
						anyChange = true;
					}
				}
			}

			// Skill XP changes
			if (showSkills.isSelected() && prev.skillXp != null && curr.skillXp != null)
			{
				for (int s = 0; s < Math.min(prev.skillXp.length, curr.skillXp.length); s++)
				{
					if (prev.skillXp[s] != curr.skillXp[s])
					{
						int gained = curr.skillXp[s] - prev.skillXp[s];
						if (gained > 0)
						{
							addDelta(curr.tick, "+", "Skill " + s + " XP: +" + String.format("%,d", gained), ADDED_COLOR);
							anyChange = true;
						}
					}
				}
			}

			// NPC changes
			if (showNpcs.isSelected())
			{
				deltaCount += diffEntities(prev.npcs, curr.npcs, curr.tick, "NPC");
				anyChange = anyChange || deltaCount > 0;
			}

			// Other player changes
			if (showPlayers.isSelected())
			{
				deltaCount += diffEntities(prev.otherPlayers, curr.otherPlayers, curr.tick, "Player");
				anyChange = anyChange || deltaCount > 0;
			}

			// Hitsplat events
			if (showHits.isSelected() && curr.hits != null)
			{
				for (Map<String, Object> hit : curr.hits)
				{
					String actor = String.valueOf(hit.getOrDefault("actor", "?"));
					int amount = getInt(hit, "amount", 0);
					int type = getInt(hit, "type", -1);
					boolean mine = Boolean.TRUE.equals(hit.get("isMine"));
					String hitDesc = String.format("Hit %d on %s (type=%d, %s)",
						amount, actor, type, mine ? "ours" : "other");
					addDelta(curr.tick, "!", hitDesc, REMOVED_COLOR);
					anyChange = true;
					deltaCount++;
				}
			}

			if (anyChange)
			{
				deltaCount++;
			}
		}

		if (deltaPanel.getComponentCount() == 0)
		{
			JLabel noChanges = new JLabel("  No changes in last " + count + " ticks");
			noChanges.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			noChanges.setFont(noChanges.getFont().deriveFont(fontSize(10f)));
			deltaPanel.add(noChanges);
		}

		footerLabel.setText(buffer.filled() + " / " + buffer.capacity() + " ticks | " + deltaCount + " changes");

		deltaPanel.revalidate();
		deltaPanel.repaint();
	}

	@SuppressWarnings("unchecked")
	private int diffEntities(List<Map<String, Object>> prevList, List<Map<String, Object>> currList, int tick, String entityType)
	{
		if (prevList == null || currList == null)
		{
			return 0;
		}

		int changes = 0;

		// Build ID sets for quick diff
		java.util.Set<String> prevIds = new java.util.HashSet<>();
		java.util.Map<String, Map<String, Object>> prevMap = new java.util.HashMap<>();
		for (Map<String, Object> e : prevList)
		{
			String key = getEntityKey(e);
			prevIds.add(key);
			prevMap.put(key, e);
		}

		java.util.Set<String> currIds = new java.util.HashSet<>();
		java.util.Map<String, Map<String, Object>> currMap = new java.util.HashMap<>();
		for (Map<String, Object> e : currList)
		{
			String key = getEntityKey(e);
			currIds.add(key);
			currMap.put(key, e);
		}

		// Spawned (in curr but not prev)
		for (String key : currIds)
		{
			if (!prevIds.contains(key))
			{
				Map<String, Object> e = currMap.get(key);
				String name = String.valueOf(e.getOrDefault("name", "?"));
				addDelta(tick, "+", entityType + " spawned: " + name + " (" + key + ")", ADDED_COLOR);
				changes++;
			}
		}

		// Despawned (in prev but not curr)
		for (String key : prevIds)
		{
			if (!currIds.contains(key))
			{
				Map<String, Object> e = prevMap.get(key);
				String name = String.valueOf(e.getOrDefault("name", "?"));
				addDelta(tick, "-", entityType + " despawned: " + name + " (" + key + ")", REMOVED_COLOR);
				changes++;
			}
		}

		// Changed (HP ratio changes)
		for (String key : currIds)
		{
			if (prevIds.contains(key))
			{
				Map<String, Object> pe = prevMap.get(key);
				Map<String, Object> ce = currMap.get(key);

				int prevHp = getInt(pe, "hpRatio", -1);
				int currHp = getInt(ce, "hpRatio", -1);
				if (prevHp != currHp && prevHp >= 0 && currHp >= 0)
				{
					String name = String.valueOf(ce.getOrDefault("name", "?"));
					addDelta(tick, "~", entityType + " " + name + " HP: " + prevHp + " -> " + currHp, CHANGED_COLOR);
					changes++;
				}
			}
		}

		// Cap to avoid flooding
		return Math.min(changes, 20);
	}

	private String getEntityKey(Map<String, Object> entity)
	{
		Object index = entity.get("index");
		Object id = entity.get("id");
		Object name = entity.get("name");
		if (index != null)
		{
			return "idx:" + index;
		}
		if (name != null && id != null)
		{
			return name + "#" + id;
		}
		return String.valueOf(entity.hashCode());
	}

	private void addDelta(int tick, String marker, String text, Color color)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setMaximumSize(dim(600, 18));
		row.setBorder(new EmptyBorder(px(1), px(4), px(1), px(4)));

		JLabel tickLabel = new JLabel("[" + tick + "] ");
		tickLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		tickLabel.setFont(tickLabel.getFont().deriveFont(Font.PLAIN, fontSize(9f)));

		JLabel markerLabel = new JLabel(marker + " ");
		markerLabel.setForeground(color);
		markerLabel.setFont(markerLabel.getFont().deriveFont(Font.BOLD, fontSize(10f)));

		JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		leftPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		leftPanel.add(tickLabel);
		leftPanel.add(markerLabel);
		row.add(leftPanel, BorderLayout.WEST);

		String displayText = text.length() > 70 ? text.substring(0, 67) + "..." : text;
		JLabel textLabel = new JLabel(displayText);
		textLabel.setForeground(color);
		textLabel.setFont(textLabel.getFont().deriveFont(Font.PLAIN, fontSize(10f)));
		textLabel.setToolTipText(text);
		row.add(textLabel, BorderLayout.CENTER);

		deltaPanel.add(row);
	}

	private static int getInt(Map<String, Object> map, String key, int defaultVal)
	{
		Object val = map.get(key);
		if (val instanceof Number)
		{
			return ((Number) val).intValue();
		}
		return defaultVal;
	}

	private static JCheckBox checkbox(String label, boolean selected)
	{
		JCheckBox cb = new JCheckBox(label, selected);
		cb.setFont(cb.getFont().deriveFont(Font.PLAIN, fontSize(9f)));
		cb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		cb.setBackground(ColorScheme.DARK_GRAY_COLOR);
		cb.setFocusPainted(false);
		return cb;
	}
}
