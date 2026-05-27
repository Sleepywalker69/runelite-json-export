package com.osrscompanion.panels;

import static com.osrscompanion.UiScale.*;

import com.osrscompanion.LogCaptureAppender;
import com.osrscompanion.OsrsCompanionPlugin;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Log tab — RuneLite console log viewer with level filtering and search.
 */
public class LogPanel extends JPanel
{
	private final OsrsCompanionPlugin plugin;

	private final JComboBox<String> levelFilter;
	private final JTextField searchField;
	private final JCheckBox autoScrollCb;
	private final JPanel listPanel;
	private final JScrollPane scrollPane;
	private final JLabel footerLabel = new JLabel("—");

	private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

	private static final Color ERROR_COLOR = new Color(244, 67, 54);
	private static final Color WARN_COLOR = new Color(255, 193, 7);
	private static final Color INFO_COLOR = Color.WHITE;
	private static final Color DEBUG_COLOR = new Color(158, 158, 158);

	public LogPanel(OsrsCompanionPlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(px(12), px(32), px(12), px(32)));

		// === Filter Bar ===
		JPanel filterBar = new JPanel(new BorderLayout(px(4), 0));
		filterBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filterBar.setBorder(new EmptyBorder(0, 0, px(4), 0));

		levelFilter = new JComboBox<>(new String[]{"ALL", "ERROR", "WARN", "INFO", "DEBUG"});
		levelFilter.setFont(levelFilter.getFont().deriveFont(fontSize(10f)));
		levelFilter.setPreferredSize(dim(65, 22));
		levelFilter.addActionListener(e -> refresh());
		filterBar.add(levelFilter, BorderLayout.WEST);

		searchField = new JTextField();
		searchField.setFont(searchField.getFont().deriveFont(fontSize(10f)));
		searchField.setToolTipText("Search logs...");
		searchField.addActionListener(e -> refresh());
		filterBar.add(searchField, BorderLayout.CENTER);

		JPanel topPanel = new JPanel(new BorderLayout());
		topPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		topPanel.add(filterBar, BorderLayout.CENTER);

		autoScrollCb = new JCheckBox("Auto-scroll", true);
		autoScrollCb.setFont(autoScrollCb.getFont().deriveFont(fontSize(9f)));
		autoScrollCb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		autoScrollCb.setBackground(ColorScheme.DARK_GRAY_COLOR);
		autoScrollCb.setFocusPainted(false);
		topPanel.add(autoScrollCb, BorderLayout.SOUTH);

		add(topPanel, BorderLayout.NORTH);

		// === Log List ===
		listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		scrollPane = new JScrollPane(listPanel);
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

	public void refresh()
	{
		LogCaptureAppender appender = plugin.getLogAppender();
		if (appender == null)
		{
			listPanel.removeAll();
			footerLabel.setText("Log appender not available");
			listPanel.revalidate();
			listPanel.repaint();
			return;
		}

		List<Map<String, Object>> entries = appender.getEntries();

		String level = (String) levelFilter.getSelectedItem();
		String search = searchField.getText().trim().toLowerCase();

		listPanel.removeAll();
		int shown = 0;

		// Show last 200 entries max, newest at bottom
		int start = Math.max(0, entries.size() - 200);
		for (int i = start; i < entries.size(); i++)
		{
			Map<String, Object> entry = entries.get(i);
			String entryLevel = String.valueOf(entry.getOrDefault("level", "INFO"));

			// Level filter
			if (!"ALL".equals(level) && !passesLevelFilter(entryLevel, level))
			{
				continue;
			}

			// Search filter
			if (!search.isEmpty())
			{
				String msg = String.valueOf(entry.getOrDefault("message", ""));
				String logger = String.valueOf(entry.getOrDefault("loggerName", ""));
				if (!msg.toLowerCase().contains(search) && !logger.toLowerCase().contains(search))
				{
					continue;
				}
			}

			listPanel.add(createLogRow(entry));
			shown++;

			// Cap display at 150 rows for performance
			if (shown >= 150)
			{
				break;
			}
		}

		footerLabel.setText(shown + " shown | " + entries.size() + " total");

		listPanel.revalidate();
		listPanel.repaint();

		if (autoScrollCb.isSelected())
		{
			SwingUtilities.invokeLater(() -> {
				JScrollBar vBar = scrollPane.getVerticalScrollBar();
				vBar.setValue(vBar.getMaximum());
			});
		}
	}

	private boolean passesLevelFilter(String entryLevel, String filterLevel)
	{
		int entryOrd = levelOrdinal(entryLevel);
		int filterOrd = levelOrdinal(filterLevel);
		return entryOrd >= filterOrd;
	}

	private int levelOrdinal(String level)
	{
		switch (level)
		{
			case "ERROR":
				return 4;
			case "WARN":
				return 3;
			case "INFO":
				return 2;
			case "DEBUG":
				return 1;
			default:
				return 0;
		}
	}

	private JPanel createLogRow(Map<String, Object> entry)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(px(2), px(4), px(2), px(4))
		));
		row.setMaximumSize(dim(600, 32));

		String level = String.valueOf(entry.getOrDefault("level", "INFO"));
		String logger = String.valueOf(entry.getOrDefault("loggerName", ""));
		String message = String.valueOf(entry.getOrDefault("message", ""));
		long timestamp = entry.containsKey("timestamp") ? ((Number) entry.get("timestamp")).longValue() : 0;
		String throwable = entry.containsKey("throwable") ? String.valueOf(entry.get("throwable")) : null;

		Color levelColor;
		switch (level)
		{
			case "ERROR":
				levelColor = ERROR_COLOR;
				break;
			case "WARN":
				levelColor = WARN_COLOR;
				break;
			case "DEBUG":
				levelColor = DEBUG_COLOR;
				break;
			default:
				levelColor = INFO_COLOR;
		}

		// Time
		String timeStr = timestamp > 0 ? TIME_FORMAT.format(new Date(timestamp)) : "??:??:??";
		JLabel timeLabel = new JLabel(timeStr + " ");
		timeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		timeLabel.setFont(timeLabel.getFont().deriveFont(Font.PLAIN, fontSize(9f)));

		// Level badge
		JLabel levelLabel = new JLabel(level.substring(0, Math.min(4, level.length())));
		levelLabel.setForeground(levelColor);
		levelLabel.setFont(levelLabel.getFont().deriveFont(Font.BOLD, fontSize(9f)));

		JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, px(2), 0));
		leftPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		leftPanel.add(timeLabel);
		leftPanel.add(levelLabel);
		row.add(leftPanel, BorderLayout.WEST);

		// Shorten logger name
		String shortLogger = logger;
		int lastDot = logger.lastIndexOf('.');
		if (lastDot > 0)
		{
			shortLogger = logger.substring(lastDot + 1);
		}

		String displayText = shortLogger + " — " + message;
		if (displayText.length() > 70)
		{
			displayText = displayText.substring(0, 67) + "...";
		}

		JLabel msgLabel = new JLabel(" " + displayText);
		msgLabel.setForeground(levelColor);
		msgLabel.setFont(msgLabel.getFont().deriveFont(Font.PLAIN, fontSize(10f)));

		String tooltip = "[" + level + "] " + logger + "\n" + message;
		if (throwable != null)
		{
			tooltip += "\n\n" + throwable;
		}
		msgLabel.setToolTipText("<html><pre>" + tooltip.replace("<", "&lt;").replace("\n", "<br>") + "</pre></html>");
		row.add(msgLabel, BorderLayout.CENTER);

		return row;
	}
}
