package com.osrscompanion.panels;

import com.osrscompanion.GameStateServer;
import com.osrscompanion.OsrsCompanionPlugin;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Var History tab — timeline viewer for varbit/varp changes.
 * Shows recent var changes with type, ID, old/new values, and tick number.
 */
public class VarHistoryPanel extends JPanel
{
	private final OsrsCompanionPlugin plugin;

	private final JComboBox<String> typeFilter;
	private final JTextField searchField;
	private final JPanel listPanel;
	private final JLabel footerLabel = new JLabel("—");

	private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

	private static final Color VARBIT_COLOR = new Color(255, 152, 0);  // orange
	private static final Color VARP_COLOR = new Color(33, 150, 243);   // blue

	public VarHistoryPanel(OsrsCompanionPlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(12, 32, 12, 32));

		// === Filter Bar ===
		JPanel filterBar = new JPanel(new BorderLayout(4, 0));
		filterBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filterBar.setBorder(new EmptyBorder(0, 0, 4, 0));

		typeFilter = new JComboBox<>(new String[]{"All", "varbit", "varp"});
		typeFilter.setFont(typeFilter.getFont().deriveFont(10f));
		typeFilter.setPreferredSize(new Dimension(70, 22));
		typeFilter.addActionListener(e -> refresh());
		filterBar.add(typeFilter, BorderLayout.WEST);

		searchField = new JTextField();
		searchField.setFont(searchField.getFont().deriveFont(10f));
		searchField.setToolTipText("Search by ID...");
		searchField.addActionListener(e -> refresh());
		filterBar.add(searchField, BorderLayout.CENTER);

		JButton copyBtn = new JButton("Copy");
		copyBtn.setFont(copyBtn.getFont().deriveFont(Font.PLAIN, 10f));
		copyBtn.setFocusPainted(false);
		copyBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		copyBtn.setForeground(Color.WHITE);
		copyBtn.setBorder(new EmptyBorder(2, 8, 2, 8));
		copyBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		copyBtn.addActionListener(e -> copyVarHistory());
		filterBar.add(copyBtn, BorderLayout.EAST);

		add(filterBar, BorderLayout.NORTH);

		// === Var Change List ===
		listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane scrollPane = new JScrollPane(listPanel);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		add(scrollPane, BorderLayout.CENTER);

		// === Footer ===
		footerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		footerLabel.setFont(footerLabel.getFont().deriveFont(Font.PLAIN, 10f));
		footerLabel.setBorder(new EmptyBorder(4, 0, 0, 0));
		add(footerLabel, BorderLayout.SOUTH);
	}

	public void refresh()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null)
		{
			listPanel.removeAll();
			footerLabel.setText("API server not available");
			listPanel.revalidate();
			listPanel.repaint();
			return;
		}

		List<Map<String, Object>> changes = server.getVarHistoryCopy();

		String typeFilterVal = (String) typeFilter.getSelectedItem();
		String searchVal = searchField.getText().trim();

		listPanel.removeAll();
		int shown = 0;

		// Show newest first
		for (int i = changes.size() - 1; i >= 0; i--)
		{
			Map<String, Object> entry = changes.get(i);
			String type = String.valueOf(entry.getOrDefault("type", ""));
			String id = String.valueOf(entry.getOrDefault("id", ""));

			// Type filter
			if (!"All".equals(typeFilterVal) && !type.equals(typeFilterVal))
			{
				continue;
			}

			// Search filter
			if (!searchVal.isEmpty() && !id.contains(searchVal))
			{
				continue;
			}

			listPanel.add(createVarRow(entry));
			shown++;

			// Cap at 200 for performance
			if (shown >= 200)
			{
				break;
			}
		}

		footerLabel.setText(shown + " / " + changes.size() + " var changes");

		listPanel.revalidate();
		listPanel.repaint();
	}

	private JPanel createVarRow(Map<String, Object> entry)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(2, 4, 2, 4)
		));
		row.setMaximumSize(new Dimension(600, 28));

		String type = String.valueOf(entry.getOrDefault("type", "?"));
		int tick = entry.containsKey("tick") ? ((Number) entry.get("tick")).intValue() : 0;
		int id = entry.containsKey("id") ? ((Number) entry.get("id")).intValue() : 0;
		int oldVal = entry.containsKey("oldValue") ? ((Number) entry.get("oldValue")).intValue() : 0;
		int newVal = entry.containsKey("newValue") ? ((Number) entry.get("newValue")).intValue() : 0;
		long timestamp = entry.containsKey("timestamp") ? ((Number) entry.get("timestamp")).longValue() : 0;

		boolean isVarbit = "varbit".equals(type);
		Color typeColor = isVarbit ? VARBIT_COLOR : VARP_COLOR;

		// Left: time + type badge
		String timeStr = timestamp > 0 ? TIME_FORMAT.format(new Date(timestamp)) : "??:??:??";
		JLabel timeLabel = new JLabel(timeStr + " ");
		timeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		timeLabel.setFont(timeLabel.getFont().deriveFont(Font.PLAIN, 9f));

		JLabel typeBadge = new JLabel(" " + type.substring(0, Math.min(6, type.length())) + " ");
		typeBadge.setOpaque(true);
		typeBadge.setBackground(typeColor);
		typeBadge.setForeground(Color.WHITE);
		typeBadge.setFont(typeBadge.getFont().deriveFont(Font.BOLD, 9f));
		typeBadge.setBorder(new EmptyBorder(1, 3, 1, 3));

		JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
		leftPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		leftPanel.add(timeLabel);
		leftPanel.add(typeBadge);
		row.add(leftPanel, BorderLayout.WEST);

		// Center: ID + change
		String changeText = String.format("%s %d: %d → %d", type, id, oldVal, newVal);
		JLabel changeLabel = new JLabel(" " + changeText);
		changeLabel.setForeground(Color.WHITE);
		changeLabel.setFont(changeLabel.getFont().deriveFont(Font.PLAIN, 10f));

		StringBuilder tooltip = new StringBuilder();
		tooltip.append("[tick ").append(tick).append("] ").append(type).append(" ").append(id);
		tooltip.append(": ").append(oldVal).append(" -> ").append(newVal);
		if (entry.containsKey("varpIndex"))
		{
			tooltip.append(" (varp ").append(entry.get("varpIndex")).append(")");
		}
		changeLabel.setToolTipText(tooltip.toString());
		row.add(changeLabel, BorderLayout.CENTER);

		// Right: tick
		JLabel tickLabel = new JLabel("T" + tick + " ");
		tickLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		tickLabel.setFont(tickLabel.getFont().deriveFont(Font.PLAIN, 9f));
		row.add(tickLabel, BorderLayout.EAST);

		return row;
	}

	private void copyVarHistory()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null) return;

		List<Map<String, Object>> changes = server.getVarHistoryCopy();
		String typeFilterVal = (String) typeFilter.getSelectedItem();
		String searchVal = searchField.getText().trim();

		StringBuilder sb = new StringBuilder();
		sb.append("=== Var History (").append(changes.size()).append(") ===\n");

		for (Map<String, Object> entry : changes)
		{
			String type = String.valueOf(entry.getOrDefault("type", ""));
			String idStr = String.valueOf(entry.getOrDefault("id", ""));

			if (!"All".equals(typeFilterVal) && !type.equals(typeFilterVal)) continue;
			if (!searchVal.isEmpty() && !idStr.contains(searchVal)) continue;

			int tick = entry.containsKey("tick") ? ((Number) entry.get("tick")).intValue() : 0;
			int id = entry.containsKey("id") ? ((Number) entry.get("id")).intValue() : 0;
			int oldVal = entry.containsKey("oldValue") ? ((Number) entry.get("oldValue")).intValue() : 0;
			int newVal = entry.containsKey("newValue") ? ((Number) entry.get("newValue")).intValue() : 0;

			sb.append("[").append(tick).append("] ").append(type).append(" ")
			  .append(id).append(": ").append(oldVal).append(" -> ").append(newVal).append("\n");
		}

		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
			new StringSelection(sb.toString()), null);
	}
}
