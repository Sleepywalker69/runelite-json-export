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
 * Chat tab — real-time chat message viewer with type filtering and auto-scroll.
 */
public class ChatPanel extends JPanel
{
	private final OsrsCompanionPlugin plugin;

	private final JComboBox<String> typeFilter;
	private final JTextField searchField;
	private final JCheckBox autoScrollCb;
	private final JPanel listPanel;
	private final JScrollPane scrollPane;
	private final JLabel footerLabel = new JLabel("—");

	private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

	// Chat type colors
	private static final Color GAME_COLOR = new Color(244, 67, 54);
	private static final Color PUBLIC_COLOR = Color.WHITE;
	private static final Color PRIVATE_COLOR = new Color(0, 188, 212);
	private static final Color CLAN_COLOR = new Color(76, 175, 80);
	private static final Color TRADE_COLOR = new Color(156, 39, 176);
	private static final Color DEFAULT_COLOR = ColorScheme.LIGHT_GRAY_COLOR;

	public ChatPanel(OsrsCompanionPlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(12, 32, 12, 32));

		// === Filter Bar ===
		JPanel filterBar = new JPanel(new BorderLayout(4, 0));
		filterBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filterBar.setBorder(new EmptyBorder(0, 0, 4, 0));

		typeFilter = new JComboBox<>(new String[]{
			"All", "GAMEMESSAGE", "PUBLICCHAT", "PRIVATECHAT",
			"PRIVATECHATOUT", "FRIENDSCHAT", "CLAN_CHAT", "TRADE"
		});
		typeFilter.setFont(typeFilter.getFont().deriveFont(10f));
		typeFilter.setPreferredSize(new Dimension(90, 22));
		typeFilter.addActionListener(e -> refresh());
		filterBar.add(typeFilter, BorderLayout.WEST);

		searchField = new JTextField();
		searchField.setFont(searchField.getFont().deriveFont(10f));
		searchField.setToolTipText("Search messages...");
		searchField.addActionListener(e -> refresh());
		filterBar.add(searchField, BorderLayout.CENTER);

		JButton copyBtn = new JButton("Copy");
		copyBtn.setFont(copyBtn.getFont().deriveFont(Font.PLAIN, 10f));
		copyBtn.setFocusPainted(false);
		copyBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		copyBtn.setForeground(Color.WHITE);
		copyBtn.setBorder(new EmptyBorder(2, 8, 2, 8));
		copyBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		copyBtn.addActionListener(e -> copyMessages());
		filterBar.add(copyBtn, BorderLayout.EAST);

		JPanel topPanel = new JPanel(new BorderLayout());
		topPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		topPanel.add(filterBar, BorderLayout.CENTER);

		autoScrollCb = new JCheckBox("Auto-scroll", true);
		autoScrollCb.setFont(autoScrollCb.getFont().deriveFont(9f));
		autoScrollCb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		autoScrollCb.setBackground(ColorScheme.DARK_GRAY_COLOR);
		autoScrollCb.setFocusPainted(false);
		topPanel.add(autoScrollCb, BorderLayout.SOUTH);

		add(topPanel, BorderLayout.NORTH);

		// === Message List ===
		listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		scrollPane = new JScrollPane(listPanel);
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

		List<Map<String, Object>> messages = server.getChatBufferCopy();

		// Apply filters
		String typeFilterVal = (String) typeFilter.getSelectedItem();
		String searchVal = searchField.getText().trim().toLowerCase();

		listPanel.removeAll();
		int shown = 0;

		for (Map<String, Object> msg : messages)
		{
			String type = String.valueOf(msg.getOrDefault("type", ""));
			String sender = String.valueOf(msg.getOrDefault("sender", ""));
			String message = String.valueOf(msg.getOrDefault("message", ""));

			// Type filter
			if (!"All".equals(typeFilterVal) && !type.equals(typeFilterVal))
			{
				continue;
			}

			// Search filter
			if (!searchVal.isEmpty()
				&& !message.toLowerCase().contains(searchVal)
				&& !sender.toLowerCase().contains(searchVal))
			{
				continue;
			}

			listPanel.add(createMessageRow(msg));
			shown++;
		}

		footerLabel.setText(shown + " / " + messages.size() + " messages");

		listPanel.revalidate();
		listPanel.repaint();

		// Auto-scroll to bottom
		if (autoScrollCb.isSelected())
		{
			SwingUtilities.invokeLater(() -> {
				JScrollBar vBar = scrollPane.getVerticalScrollBar();
				vBar.setValue(vBar.getMaximum());
			});
		}
	}

	private JPanel createMessageRow(Map<String, Object> msg)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(2, 4, 2, 4)
		));
		row.setMaximumSize(new Dimension(600, 32));

		String type = String.valueOf(msg.getOrDefault("type", ""));
		String sender = String.valueOf(msg.getOrDefault("sender", ""));
		String message = String.valueOf(msg.getOrDefault("message", ""));
		long timestamp = msg.containsKey("timestamp") ? ((Number) msg.get("timestamp")).longValue() : 0;

		Color msgColor = getColorForType(type);

		// Time + type badge
		String timeStr = timestamp > 0 ? TIME_FORMAT.format(new Date(timestamp)) : "??:??:??";
		JLabel timeLabel = new JLabel(timeStr);
		timeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		timeLabel.setFont(timeLabel.getFont().deriveFont(Font.PLAIN, 9f));
		row.add(timeLabel, BorderLayout.WEST);

		// Message text
		String displayText;
		if (sender != null && !sender.isEmpty())
		{
			displayText = "<" + sender + "> " + message;
		}
		else
		{
			displayText = message;
		}

		// Strip color tags
		displayText = displayText.replaceAll("<col=[^>]*>", "").replaceAll("</col>", "");

		if (displayText.length() > 80)
		{
			displayText = displayText.substring(0, 77) + "...";
		}

		JLabel msgLabel = new JLabel(" " + displayText);
		msgLabel.setForeground(msgColor);
		msgLabel.setFont(msgLabel.getFont().deriveFont(Font.PLAIN, 10f));
		msgLabel.setToolTipText(type + ": " + message);
		row.add(msgLabel, BorderLayout.CENTER);

		return row;
	}

	private void copyMessages()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null) return;

		List<Map<String, Object>> messages = server.getChatBufferCopy();
		String typeFilterVal = (String) typeFilter.getSelectedItem();
		String searchVal = searchField.getText().trim().toLowerCase();

		StringBuilder sb = new StringBuilder();
		sb.append("=== Chat Messages ===\n");

		for (Map<String, Object> msg : messages)
		{
			String type = String.valueOf(msg.getOrDefault("type", ""));
			String sender = String.valueOf(msg.getOrDefault("sender", ""));
			String message = String.valueOf(msg.getOrDefault("message", ""));

			if (!"All".equals(typeFilterVal) && !type.equals(typeFilterVal)) continue;
			if (!searchVal.isEmpty()
				&& !message.toLowerCase().contains(searchVal)
				&& !sender.toLowerCase().contains(searchVal)) continue;

			long ts = msg.containsKey("timestamp") ? ((Number) msg.get("timestamp")).longValue() : 0;
			String time = ts > 0 ? TIME_FORMAT.format(new Date(ts)) : "??:??:??";
			sb.append("[").append(time).append("] [").append(type).append("] ");
			if (!sender.isEmpty()) sb.append("<").append(sender).append("> ");
			sb.append(message).append("\n");
		}

		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
			new StringSelection(sb.toString()), null);
	}

	private Color getColorForType(String type)
	{
		if (type == null)
		{
			return DEFAULT_COLOR;
		}

		switch (type)
		{
			case "GAMEMESSAGE":
			case "ENGINE":
			case "SPAM":
				return GAME_COLOR;
			case "PUBLICCHAT":
			case "MODCHAT":
				return PUBLIC_COLOR;
			case "PRIVATECHAT":
			case "PRIVATECHATOUT":
				return PRIVATE_COLOR;
			case "FRIENDSCHAT":
			case "CLAN_CHAT":
			case "CLAN_GUEST_CHAT":
			case "CLAN_GIM_CHAT":
				return CLAN_COLOR;
			case "TRADE":
			case "TRADE_SENT":
				return TRADE_COLOR;
			default:
				return DEFAULT_COLOR;
		}
	}
}
