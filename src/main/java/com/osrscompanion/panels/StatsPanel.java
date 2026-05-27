package com.osrscompanion.panels;

import com.osrscompanion.GameStateServer;
import com.osrscompanion.OsrsCompanionPlugin;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Map;

/**
 * Stats tab — XP tracker with per-skill gains and rates, plus loot log.
 */
public class StatsPanel extends JPanel
{
	private final Client client;
	private final OsrsCompanionPlugin plugin;

	private final JPanel xpPanel;
	private final JPanel lootPanel;
	private final JLabel totalXpLabel = new JLabel("—");
	private final JLabel sessionTimeLabel = new JLabel("—");
	private final JLabel lootCountLabel = new JLabel("—");

	public StatsPanel(Client client, OsrsCompanionPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(12, 32, 12, 32));

		// === XP Section ===
		JPanel xpHeaderRow = new JPanel(new BorderLayout());
		xpHeaderRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		xpHeaderRow.setMaximumSize(new Dimension(600, 22));
		xpHeaderRow.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR));
		JLabel xpHeaderLabel = new JLabel("XP Tracker");
		xpHeaderLabel.setForeground(ColorScheme.BRAND_ORANGE);
		xpHeaderLabel.setFont(xpHeaderLabel.getFont().deriveFont(Font.BOLD, 11f));
		xpHeaderRow.add(xpHeaderLabel, BorderLayout.WEST);

		JButton copyStatsBtn = new JButton("Copy");
		copyStatsBtn.setFont(copyStatsBtn.getFont().deriveFont(Font.PLAIN, 10f));
		copyStatsBtn.setFocusPainted(false);
		copyStatsBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		copyStatsBtn.setForeground(Color.WHITE);
		copyStatsBtn.setBorder(new EmptyBorder(2, 8, 2, 8));
		copyStatsBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		copyStatsBtn.addActionListener(e -> copyStats());
		xpHeaderRow.add(copyStatsBtn, BorderLayout.EAST);
		add(xpHeaderRow);

		JPanel summaryRow = new JPanel(new BorderLayout());
		summaryRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		summaryRow.setMaximumSize(new Dimension(600, 18));
		JLabel totalLabel = new JLabel("Total XP:");
		totalLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		totalLabel.setFont(totalLabel.getFont().deriveFont(11f));
		totalXpLabel.setForeground(Color.WHITE);
		totalXpLabel.setFont(totalXpLabel.getFont().deriveFont(Font.BOLD, 11f));
		summaryRow.add(totalLabel, BorderLayout.WEST);
		summaryRow.add(totalXpLabel, BorderLayout.EAST);
		add(summaryRow);

		JPanel timeRow = new JPanel(new BorderLayout());
		timeRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		timeRow.setMaximumSize(new Dimension(600, 18));
		JLabel timeLabel = new JLabel("Session:");
		timeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		timeLabel.setFont(timeLabel.getFont().deriveFont(11f));
		sessionTimeLabel.setForeground(Color.WHITE);
		sessionTimeLabel.setFont(sessionTimeLabel.getFont().deriveFont(11f));
		timeRow.add(timeLabel, BorderLayout.WEST);
		timeRow.add(sessionTimeLabel, BorderLayout.EAST);
		add(timeRow);

		add(Box.createVerticalStrut(4));

		xpPanel = new JPanel();
		xpPanel.setLayout(new BoxLayout(xpPanel, BoxLayout.Y_AXIS));
		xpPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane xpScroll = new JScrollPane(xpPanel);
		xpScroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		xpScroll.setBorder(null);
		xpScroll.setPreferredSize(new Dimension(0, 150));
		xpScroll.setMaximumSize(new Dimension(600, 200));
		xpScroll.getVerticalScrollBar().setUnitIncrement(16);
		add(xpScroll);

		add(Box.createVerticalStrut(8));

		// === Loot Section ===
		add(sectionHeader("Loot Log"));

		JPanel lootSummaryRow = new JPanel(new BorderLayout());
		lootSummaryRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		lootSummaryRow.setMaximumSize(new Dimension(600, 18));
		JLabel dropsLabel = new JLabel("Drops:");
		dropsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		dropsLabel.setFont(dropsLabel.getFont().deriveFont(11f));
		lootCountLabel.setForeground(Color.WHITE);
		lootCountLabel.setFont(lootCountLabel.getFont().deriveFont(11f));
		lootSummaryRow.add(dropsLabel, BorderLayout.WEST);
		lootSummaryRow.add(lootCountLabel, BorderLayout.EAST);
		add(lootSummaryRow);

		add(Box.createVerticalStrut(4));

		lootPanel = new JPanel();
		lootPanel.setLayout(new BoxLayout(lootPanel, BoxLayout.Y_AXIS));
		lootPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane lootScroll = new JScrollPane(lootPanel);
		lootScroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		lootScroll.setBorder(null);
		lootScroll.setPreferredSize(new Dimension(0, 150));
		lootScroll.setMaximumSize(new Dimension(600, 250));
		lootScroll.getVerticalScrollBar().setUnitIncrement(16);
		add(lootScroll);

		add(Box.createVerticalGlue());
	}

	public void refresh()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null || client.getGameState() != GameState.LOGGED_IN)
		{
			totalXpLabel.setText("—");
			sessionTimeLabel.setText("—");
			lootCountLabel.setText("—");
			xpPanel.removeAll();
			lootPanel.removeAll();
			xpPanel.revalidate();
			lootPanel.revalidate();
			return;
		}

		// Session time
		long sessionMs = server.getSessionStartMs();
		long elapsed = sessionMs > 0 ? System.currentTimeMillis() - sessionMs : 0;
		long hours = elapsed / 3600000;
		long mins = (elapsed / 60000) % 60;
		sessionTimeLabel.setText(String.format("%dh %dm", hours, mins));

		// XP gains
		Map<String, Integer> baselines = server.getXpBaselinesCopy();
		int totalGained = 0;

		xpPanel.removeAll();
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue;
			}
			String name = skill.name();
			Integer baseline = baselines.get(name);
			if (baseline == null)
			{
				continue;
			}

			int currentXp = client.getSkillExperience(skill);
			int gained = currentXp - baseline;

			if (gained > 0)
			{
				totalGained += gained;

				// Calculate XP/hr
				long xpPerHour = elapsed > 0 ? (long) gained * 3600000 / elapsed : 0;

				JPanel row = new JPanel(new BorderLayout());
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				row.setMaximumSize(new Dimension(600, 20));
				row.setBorder(new EmptyBorder(1, 4, 1, 4));

				JLabel skillLabel = new JLabel(capitalize(name));
				skillLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				skillLabel.setFont(skillLabel.getFont().deriveFont(10f));
				row.add(skillLabel, BorderLayout.WEST);

				String xpText = String.format("+%,d (%,d/hr)", gained, xpPerHour);
				JLabel xpLabel = new JLabel(xpText);
				xpLabel.setForeground(new Color(76, 175, 80));
				xpLabel.setFont(xpLabel.getFont().deriveFont(Font.PLAIN, 10f));
				row.add(xpLabel, BorderLayout.EAST);

				xpPanel.add(row);
			}
		}

		totalXpLabel.setText(String.format("+%,d", totalGained));

		if (xpPanel.getComponentCount() == 0)
		{
			JLabel noXp = new JLabel("  No XP gained yet");
			noXp.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			noXp.setFont(noXp.getFont().deriveFont(10f));
			xpPanel.add(noXp);
		}

		// Loot log
		List<Map<String, Object>> loot = server.getLootLogCopy();
		lootCountLabel.setText(loot.size() + " drops");

		lootPanel.removeAll();
		// Show most recent drops first, cap at 50
		int start = Math.max(0, loot.size() - 50);
		for (int i = loot.size() - 1; i >= start; i--)
		{
			Map<String, Object> drop = loot.get(i);
			lootPanel.add(createLootRow(drop));
		}

		if (lootPanel.getComponentCount() == 0)
		{
			JLabel noLoot = new JLabel("  No loot received yet");
			noLoot.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			noLoot.setFont(noLoot.getFont().deriveFont(10f));
			lootPanel.add(noLoot);
		}

		xpPanel.revalidate();
		xpPanel.repaint();
		lootPanel.revalidate();
		lootPanel.repaint();
	}

	@SuppressWarnings("unchecked")
	private JPanel createLootRow(Map<String, Object> drop)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		row.setBorder(new EmptyBorder(1, 4, 1, 4));

		String npcName = String.valueOf(drop.getOrDefault("npcName", "Unknown"));
		JLabel npcLabel = new JLabel(npcName);
		npcLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		npcLabel.setFont(npcLabel.getFont().deriveFont(10f));
		row.add(npcLabel, BorderLayout.WEST);

		// Summarize items
		List<Map<String, Object>> items = (List<Map<String, Object>>) drop.get("items");
		if (items != null && !items.isEmpty())
		{
			StringBuilder sb = new StringBuilder();
			for (int j = 0; j < Math.min(items.size(), 3); j++)
			{
				if (j > 0)
				{
					sb.append(", ");
				}
				Map<String, Object> item = items.get(j);
				String itemName = String.valueOf(item.getOrDefault("name", "ID:" + item.get("itemId")));
				int qty = item.containsKey("quantity") ? ((Number) item.get("quantity")).intValue() : 1;
				sb.append(itemName);
				if (qty > 1)
				{
					sb.append(" x").append(qty);
				}
			}
			if (items.size() > 3)
			{
				sb.append(" +").append(items.size() - 3).append(" more");
			}

			JLabel itemsLabel = new JLabel(sb.toString());
			itemsLabel.setForeground(new Color(255, 193, 7));
			itemsLabel.setFont(itemsLabel.getFont().deriveFont(Font.PLAIN, 10f));
			row.add(itemsLabel, BorderLayout.EAST);
		}

		return row;
	}

	private static String capitalize(String s)
	{
		if (s == null || s.isEmpty())
		{
			return s;
		}
		return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
	}

	@SuppressWarnings("unchecked")
	private void copyStats()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null) return;

		Map<String, Integer> baselines = server.getXpBaselinesCopy();
		long sessionMs = server.getSessionStartMs();
		long elapsed = sessionMs > 0 ? System.currentTimeMillis() - sessionMs : 0;

		StringBuilder sb = new StringBuilder();
		sb.append("=== XP Gains ===\n");

		int totalGained = 0;
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL) continue;
			Integer baseline = baselines.get(skill.name());
			if (baseline == null) continue;
			int gained = client.getSkillExperience(skill) - baseline;
			if (gained > 0)
			{
				totalGained += gained;
				long xpPerHour = elapsed > 0 ? (long) gained * 3600000 / elapsed : 0;
				sb.append(capitalize(skill.name())).append(": +")
				  .append(String.format("%,d", gained))
				  .append(" (").append(String.format("%,d", xpPerHour)).append("/hr)\n");
			}
		}
		sb.append("Total: +").append(String.format("%,d", totalGained)).append("\n\n");

		List<Map<String, Object>> loot = server.getLootLogCopy();
		sb.append("=== Loot (").append(loot.size()).append(" drops) ===\n");
		for (Map<String, Object> drop : loot)
		{
			sb.append(drop.getOrDefault("npcName", "Unknown")).append(": ");
			List<Map<String, Object>> items = (List<Map<String, Object>>) drop.get("items");
			if (items != null)
			{
				for (int j = 0; j < items.size(); j++)
				{
					if (j > 0) sb.append(", ");
					Map<String, Object> item = items.get(j);
					sb.append(item.getOrDefault("name", "ID:" + item.get("itemId")));
					int qty = item.containsKey("quantity") ? ((Number) item.get("quantity")).intValue() : 1;
					if (qty > 1) sb.append(" x").append(qty);
				}
			}
			sb.append("\n");
		}

		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
			new StringSelection(sb.toString()), null);
	}

	private static JPanel sectionHeader(String title)
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setMaximumSize(new Dimension(600, 22));
		header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR));
		JLabel label = new JLabel(title);
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
		header.add(label, BorderLayout.WEST);
		return header;
	}
}
