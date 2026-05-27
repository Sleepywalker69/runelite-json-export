package com.osrscompanion.panels;

import com.osrscompanion.GameStateServer;
import com.osrscompanion.OsrsCompanionPlugin;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.*;
import java.util.List;

/**
 * Recording tab — start/stop recording, choose duration and event types, view status.
 */
public class RecordingPanel extends JPanel
{
	private final OsrsCompanionPlugin plugin;

	private final JButton toggleButton = new JButton("Start Recording");
	private final JSpinner durationSpinner;
	private final JLabel statusLabel = new JLabel("Idle");
	private final JLabel eventsLabel = new JLabel("0 events");
	private final JLabel timeLabel = new JLabel("—");
	private final JProgressBar progressBar = new JProgressBar(0, 100);

	// Event type presets
	private static final String[] ALL_EVENT_TYPES = {
		"game_tick", "hitsplat", "animation_changed", "npc_spawned", "npc_despawned",
		"actor_death", "var_changed", "menu_clicked", "stat_changed", "item_changed",
		"interacting_changed", "object_spawned", "object_despawned", "projectile_spawned",
		"gfx_created", "chat_message", "sound_effect", "loot_received", "game_state_changed"
	};

	private static final Map<String, String[]> PRESETS = new LinkedHashMap<>();
	static
	{
		PRESETS.put("All", null); // null = no filter
		PRESETS.put("Boss", new String[]{
			"game_tick", "hitsplat", "animation_changed", "npc_spawned", "npc_despawned",
			"actor_death", "menu_clicked", "object_spawned", "object_despawned",
			"projectile_spawned", "gfx_created", "sound_effect", "chat_message",
			"loot_received", "game_state_changed"
		});
		PRESETS.put("Combat", new String[]{
			"game_tick", "hitsplat", "npc_spawned", "npc_despawned", "actor_death",
			"menu_clicked", "object_spawned", "object_despawned", "projectile_spawned",
			"gfx_created", "sound_effect"
		});
		PRESETS.put("Lite", new String[]{
			"game_tick", "hitsplat", "actor_death", "menu_clicked", "projectile_spawned"
		});
		PRESETS.put("Vars", new String[]{"var_changed", "game_tick"});
		PRESETS.put("Clicks", new String[]{"menu_clicked", "game_tick"});
	}

	private final Map<String, JCheckBox> eventCheckboxes = new LinkedHashMap<>();
	private String activePreset = "All";

	// Recording data viewer
	private final JPanel eventsPanel;
	private final JLabel eventsFooterLabel = new JLabel("—");

	public RecordingPanel(OsrsCompanionPlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(12, 32, 12, 32));

		// === Controls ===
		add(sectionHeader("Controls"));

		// Duration
		JPanel durationRow = new JPanel(new BorderLayout());
		durationRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		durationRow.setMaximumSize(new Dimension(600, 26));
		JLabel durLabel = new JLabel("Duration (sec):");
		durLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		durLabel.setFont(durLabel.getFont().deriveFont(11f));
		durationSpinner = new JSpinner(new SpinnerNumberModel(180, 30, 600, 30));
		durationSpinner.setPreferredSize(new Dimension(70, 22));
		durationRow.add(durLabel, BorderLayout.WEST);
		durationRow.add(durationSpinner, BorderLayout.EAST);
		add(durationRow);

		add(Box.createVerticalStrut(4));

		// Start/Stop button
		toggleButton.setFont(toggleButton.getFont().deriveFont(Font.BOLD, 12f));
		toggleButton.setFocusPainted(false);
		toggleButton.setBackground(new Color(76, 175, 80));
		toggleButton.setForeground(Color.WHITE);
		toggleButton.setMaximumSize(new Dimension(600, 32));
		toggleButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		toggleButton.addActionListener(e -> toggleRecording());
		add(toggleButton);

		add(Box.createVerticalStrut(8));

		// === Status ===
		add(sectionHeader("Status"));
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
		statusLabel.setAlignmentX(LEFT_ALIGNMENT);
		add(statusLabel);

		eventsLabel.setForeground(Color.WHITE);
		eventsLabel.setFont(eventsLabel.getFont().deriveFont(11f));
		eventsLabel.setAlignmentX(LEFT_ALIGNMENT);
		add(eventsLabel);

		timeLabel.setForeground(Color.WHITE);
		timeLabel.setFont(timeLabel.getFont().deriveFont(11f));
		timeLabel.setAlignmentX(LEFT_ALIGNMENT);
		add(timeLabel);

		add(Box.createVerticalStrut(2));
		progressBar.setMaximumSize(new Dimension(600, 14));
		progressBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		progressBar.setForeground(ColorScheme.BRAND_ORANGE);
		progressBar.setBorderPainted(false);
		progressBar.setStringPainted(true);
		progressBar.setFont(progressBar.getFont().deriveFont(9f));
		add(progressBar);

		add(Box.createVerticalStrut(8));

		// === Presets ===
		add(sectionHeader("Event Presets"));
		JPanel presetRow = new JPanel(new GridLayout(2, 3, 2, 2));
		presetRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		presetRow.setMaximumSize(new Dimension(600, 50));

		for (String preset : PRESETS.keySet())
		{
			JButton btn = new JButton(preset);
			btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 9f));
			btn.setFocusPainted(false);
			btn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			btn.setForeground(Color.WHITE);
			btn.setBorder(new EmptyBorder(2, 4, 2, 4));
			btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			btn.addActionListener(e -> applyPreset(preset));
			presetRow.add(btn);
		}
		add(presetRow);

		add(Box.createVerticalStrut(8));

		// === Event Types ===
		add(sectionHeader("Event Types"));
		JPanel checkboxPanel = new JPanel();
		checkboxPanel.setLayout(new BoxLayout(checkboxPanel, BoxLayout.Y_AXIS));
		checkboxPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		for (String type : ALL_EVENT_TYPES)
		{
			JCheckBox cb = new JCheckBox(type, true);
			cb.setFont(cb.getFont().deriveFont(Font.PLAIN, 10f));
			cb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			cb.setBackground(ColorScheme.DARK_GRAY_COLOR);
			cb.setFocusPainted(false);
			eventCheckboxes.put(type, cb);
			checkboxPanel.add(cb);
		}

		JScrollPane checkScroll = new JScrollPane(checkboxPanel);
		checkScroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		checkScroll.setBorder(null);
		checkScroll.setPreferredSize(new Dimension(0, 160));
		checkScroll.setMaximumSize(new Dimension(600, 160));
		checkScroll.getVerticalScrollBar().setUnitIncrement(16);
		add(checkScroll);

		add(Box.createVerticalStrut(10));

		// === Recorded Events Viewer ===
		add(sectionHeader("Recorded Events"));

		JPanel eventsControlRow = new JPanel(new BorderLayout(4, 0));
		eventsControlRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		eventsControlRow.setMaximumSize(new Dimension(600, 26));

		JButton viewEventsBtn = new JButton("Load Events");
		viewEventsBtn.setFont(viewEventsBtn.getFont().deriveFont(Font.PLAIN, 10f));
		viewEventsBtn.setFocusPainted(false);
		viewEventsBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		viewEventsBtn.setForeground(Color.WHITE);
		viewEventsBtn.setBorder(new EmptyBorder(2, 8, 2, 8));
		viewEventsBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		viewEventsBtn.addActionListener(e -> loadRecordedEvents());
		eventsControlRow.add(viewEventsBtn, BorderLayout.WEST);

		JButton copyEventsBtn = new JButton("Copy All");
		copyEventsBtn.setFont(copyEventsBtn.getFont().deriveFont(Font.PLAIN, 10f));
		copyEventsBtn.setFocusPainted(false);
		copyEventsBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		copyEventsBtn.setForeground(Color.WHITE);
		copyEventsBtn.setBorder(new EmptyBorder(2, 8, 2, 8));
		copyEventsBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		copyEventsBtn.addActionListener(e -> copyRecordedEvents());
		eventsControlRow.add(copyEventsBtn, BorderLayout.EAST);

		add(eventsControlRow);
		add(Box.createVerticalStrut(4));

		eventsPanel = new JPanel();
		eventsPanel.setLayout(new BoxLayout(eventsPanel, BoxLayout.Y_AXIS));
		eventsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane eventsScroll = new JScrollPane(eventsPanel);
		eventsScroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		eventsScroll.setBorder(null);
		eventsScroll.setPreferredSize(new Dimension(0, 200));
		eventsScroll.setMaximumSize(new Dimension(600, 300));
		eventsScroll.getVerticalScrollBar().setUnitIncrement(16);
		add(eventsScroll);

		eventsFooterLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		eventsFooterLabel.setFont(eventsFooterLabel.getFont().deriveFont(Font.PLAIN, 10f));
		eventsFooterLabel.setAlignmentX(LEFT_ALIGNMENT);
		add(eventsFooterLabel);

		add(Box.createVerticalGlue());
	}

	public void refresh()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null)
		{
			statusLabel.setText("API server not running");
			statusLabel.setForeground(new Color(244, 67, 54));
			eventsLabel.setText("—");
			timeLabel.setText("—");
			progressBar.setValue(0);
			progressBar.setString("—");
			return;
		}

		boolean recording = server.isRecording();
		if (recording)
		{
			statusLabel.setText("RECORDING");
			statusLabel.setForeground(new Color(244, 67, 54));
			toggleButton.setText("Stop Recording");
			toggleButton.setBackground(new Color(244, 67, 54));

			int eventCount = server.getRecordingEventCount();
			eventsLabel.setText(String.format("%,d / %,d events", eventCount, 10_000));

			int[] tickInfo = server.getRecordingTickInfo();
			if (tickInfo != null)
			{
				int elapsed = tickInfo[0];
				int max = tickInfo[1];
				int pct = max > 0 ? (elapsed * 100 / max) : 0;
				progressBar.setValue(pct);
				int secsLeft = (int) ((max - elapsed) * 0.6);
				timeLabel.setText(String.format("%ds remaining", Math.max(0, secsLeft)));
				progressBar.setString(pct + "%");
			}
		}
		else
		{
			statusLabel.setText("Idle");
			statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			toggleButton.setText("Start Recording");
			toggleButton.setBackground(new Color(76, 175, 80));

			int eventCount = server.getRecordingEventCount();
			if (eventCount > 0)
			{
				eventsLabel.setText(String.format("%,d events recorded", eventCount));
			}
			else
			{
				eventsLabel.setText("No events");
			}
			timeLabel.setText("—");
			progressBar.setValue(0);
			progressBar.setString("—");
		}
	}

	private void toggleRecording()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null)
		{
			JOptionPane.showMessageDialog(this, "API server not running", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		if (server.isRecording())
		{
			server.stopRecordingFromPanel();
		}
		else
		{
			int duration = (int) durationSpinner.getValue();
			Set<String> filter = getSelectedEventTypes();
			server.startRecordingFromPanel(duration, filter);
		}

		refresh();
	}

	private void applyPreset(String preset)
	{
		activePreset = preset;
		String[] types = PRESETS.get(preset);

		if (types == null)
		{
			// "All" — check everything
			for (JCheckBox cb : eventCheckboxes.values())
			{
				cb.setSelected(true);
			}
		}
		else
		{
			Set<String> typeSet = new HashSet<>(Arrays.asList(types));
			for (Map.Entry<String, JCheckBox> entry : eventCheckboxes.entrySet())
			{
				entry.getValue().setSelected(typeSet.contains(entry.getKey()));
			}
		}
	}

	private Set<String> getSelectedEventTypes()
	{
		Set<String> selected = new LinkedHashSet<>();
		boolean allSelected = true;

		for (Map.Entry<String, JCheckBox> entry : eventCheckboxes.entrySet())
		{
			if (entry.getValue().isSelected())
			{
				selected.add(entry.getKey());
			}
			else
			{
				allSelected = false;
			}
		}

		// If all selected, return null (no filter)
		return allSelected ? null : selected;
	}

	@SuppressWarnings("unchecked")
	private void loadRecordedEvents()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null)
		{
			eventsPanel.removeAll();
			eventsFooterLabel.setText("API server not running");
			eventsPanel.revalidate();
			eventsPanel.repaint();
			return;
		}

		List<Map<String, Object>> events = server.getRecordingBufferCopy();
		eventsPanel.removeAll();

		int shown = 0;
		int max = Math.min(events.size(), 500); // Cap at 500 for perf
		for (int i = 0; i < max; i++)
		{
			Map<String, Object> evt = events.get(i);
			String type = String.valueOf(evt.getOrDefault("type", "?"));
			int tick = evt.containsKey("tick") ? ((Number) evt.get("tick")).intValue() : 0;

			String summary = buildEventSummary(type, evt);

			JPanel row = new JPanel(new BorderLayout());
			row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			row.setMaximumSize(new Dimension(600, 20));
			row.setBorder(new EmptyBorder(1, 4, 1, 4));

			JLabel tickLabel = new JLabel("[" + tick + "] ");
			tickLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			tickLabel.setFont(tickLabel.getFont().deriveFont(Font.PLAIN, 9f));
			row.add(tickLabel, BorderLayout.WEST);

			JLabel summaryLabel = new JLabel(summary);
			summaryLabel.setForeground(getEventColor(type));
			summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.PLAIN, 10f));
			summaryLabel.setToolTipText(type + ": " + evt.toString());
			row.add(summaryLabel, BorderLayout.CENTER);

			eventsPanel.add(row);
			shown++;
		}

		eventsFooterLabel.setText(shown + " / " + events.size() + " events loaded");

		eventsPanel.revalidate();
		eventsPanel.repaint();
	}

	private void copyRecordedEvents()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null) return;

		List<Map<String, Object>> events = server.getRecordingBufferCopy();
		StringBuilder sb = new StringBuilder();
		sb.append("=== Recorded Events (").append(events.size()).append(") ===\n");

		for (Map<String, Object> evt : events)
		{
			String type = String.valueOf(evt.getOrDefault("type", "?"));
			int tick = evt.containsKey("tick") ? ((Number) evt.get("tick")).intValue() : 0;
			sb.append("[").append(tick).append("] ").append(type).append(": ");
			sb.append(buildEventSummary(type, evt)).append("\n");
		}

		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
			new StringSelection(sb.toString()), null);
	}

	private String buildEventSummary(String type, Map<String, Object> evt)
	{
		switch (type)
		{
			case "hitsplat":
			{
				Object target = evt.get("target");
				String name = "?";
				if (target instanceof Map)
				{
					Object n = ((Map<?, ?>) target).get("name");
					if (n != null) name = String.valueOf(n);
				}
				int amount = evt.containsKey("amount") ? ((Number) evt.get("amount")).intValue() : 0;
				return "Hit " + amount + " on " + name;
			}
			case "animation_changed":
			{
				Object actor = evt.get("actor");
				String name = "?";
				if (actor instanceof Map)
				{
					Object n = ((Map<?, ?>) actor).get("name");
					if (n != null) name = String.valueOf(n);
				}
				int anim = evt.containsKey("animation") ? ((Number) evt.get("animation")).intValue() : -1;
				return name + " anim=" + anim;
			}
			case "menu_clicked":
			{
				Object option = evt.get("option");
				Object target = evt.get("target");
				return (option != null ? option : "") + " " + (target != null ? target : "");
			}
			case "npc_spawned":
			case "npc_despawned":
			{
				Object npc = evt.get("npc");
				if (npc instanceof Map)
				{
					Object n = ((Map<?, ?>) npc).get("name");
					return n != null ? String.valueOf(n) : "?";
				}
				return "?";
			}
			case "chat_message":
			{
				Object msg = evt.get("message");
				return msg != null ? String.valueOf(msg) : "";
			}
			default:
				return type;
		}
	}

	private Color getEventColor(String type)
	{
		switch (type)
		{
			case "hitsplat": return new Color(244, 67, 54);
			case "menu_clicked": return new Color(76, 175, 80);
			case "animation_changed": return new Color(33, 150, 243);
			case "chat_message": return new Color(0, 188, 212);
			case "npc_spawned": return new Color(255, 193, 7);
			case "npc_despawned": return new Color(255, 152, 0);
			case "game_tick": return ColorScheme.LIGHT_GRAY_COLOR;
			default: return Color.WHITE;
		}
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
