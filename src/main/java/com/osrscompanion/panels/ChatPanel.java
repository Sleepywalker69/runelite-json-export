package com.osrscompanion.panels;

import static com.osrscompanion.UiScale.*;

import com.osrscompanion.GameStateServer;
import com.osrscompanion.OsrsCompanionPlugin;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Chat tab — real-time chat message viewer with type filtering and auto-scroll.
 * Uses JTextPane + monospace font for native text selection. Matches mockup's .chat-feed.
 */
public class ChatPanel extends JPanel
{
	private final OsrsCompanionPlugin plugin;

	private final JComboBox<String> typeFilter;
	private final JTextField searchField;
	private final JCheckBox autoScrollCb;
	private final JTextPane textPane;
	private final JLabel footerLabel = new JLabel("—");
	private final JLabel subtitleLabel;

	private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

	// Style names
	private static final String S_TIME     = "time";
	private static final String S_SENDER   = "sender";
	private static final String S_BODY     = "body";
	private static final String S_BADGE_GAME   = "b_game";
	private static final String S_BADGE_PUB    = "b_pub";
	private static final String S_BADGE_PRIV   = "b_priv";
	private static final String S_BADGE_CLAN   = "b_clan";
	private static final String S_BADGE_FILTER = "b_filter";
	private static final String S_BADGE_XP     = "b_xp";
	private static final String S_BADGE_DEF    = "b_def";

	public ChatPanel(OsrsCompanionPlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(PanelUtils.PAGE_BG);
		setBorder(new EmptyBorder(px(16), px(20), px(16), px(20)));

		// Panel header
		subtitleLabel = new JLabel("0 messages · live tail");
		subtitleLabel.setForeground(PanelUtils.SUBTITLE_FG);
		subtitleLabel.setFont(subtitleLabel.getFont().deriveFont(Font.PLAIN, fontSize(11f)));
		JPanel head = PanelUtils.panelHead("Chat", "");
		head.remove(1);
		head.add(subtitleLabel, BorderLayout.EAST);
		head.setAlignmentX(LEFT_ALIGNMENT);
		add(head);
		add(PanelUtils.vgap(10));

		// ── Filter Bar ──────────────────────────────────────────────
		JPanel filterBar = new JPanel(new BorderLayout(px(8), 0));
		filterBar.setOpaque(false);
		filterBar.setAlignmentX(LEFT_ALIGNMENT);
		filterBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(30)));

		typeFilter = new JComboBox<>(new String[]{
			"All", "GAMEMESSAGE", "PUBLICCHAT", "PRIVATECHAT",
			"PRIVATECHATOUT", "FRIENDSCHAT", "CLAN_CHAT", "TRADE"
		});
		typeFilter.setFont(typeFilter.getFont().deriveFont(fontSize(10f)));
		typeFilter.setPreferredSize(new Dimension(px(100), px(24)));
		typeFilter.addActionListener(e -> refresh());
		filterBar.add(typeFilter, BorderLayout.WEST);

		searchField = new JTextField();
		searchField.setFont(searchField.getFont().deriveFont(fontSize(11f)));
		searchField.setToolTipText("Search messages...");
		searchField.addActionListener(e -> refresh());
		filterBar.add(searchField, BorderLayout.CENTER);

		JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, px(4), 0));
		rightBtns.setOpaque(false);
		JButton copyBtn = PanelUtils.btn("Copy");
		copyBtn.addActionListener(e -> copyMessages());
		rightBtns.add(copyBtn);
		filterBar.add(rightBtns, BorderLayout.EAST);

		add(filterBar);

		autoScrollCb = new JCheckBox("Auto-scroll", true);
		autoScrollCb.setFont(autoScrollCb.getFont().deriveFont(fontSize(9f)));
		autoScrollCb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		autoScrollCb.setBackground(PanelUtils.PAGE_BG);
		autoScrollCb.setFocusPainted(false);
		autoScrollCb.setAlignmentX(LEFT_ALIGNMENT);
		add(autoScrollCb);
		add(PanelUtils.vgap(10));

		// ── Chat feed card ──────────────────────────────────────────
		JPanel card = PanelUtils.card();
		card.setLayout(new BorderLayout());
		card.setAlignmentX(LEFT_ALIGNMENT);

		textPane = new JTextPane();
		textPane.setEditable(false);
		textPane.setBackground(PanelUtils.FEED_BG);
		textPane.setFont(PanelUtils.monoFont(11f));
		textPane.setForeground(Color.WHITE);
		initStyles(textPane.getStyledDocument());
		PanelUtils.installTextPopup(textPane);

		JScrollPane scroll = new JScrollPane(textPane);
		scroll.setBorder(null);
		scroll.setBackground(PanelUtils.FEED_BG);
		scroll.getVerticalScrollBar().setUnitIncrement(px(16));
		card.add(scroll, BorderLayout.CENTER);

		add(card);
		add(PanelUtils.vgap(4));

		footerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		footerLabel.setFont(footerLabel.getFont().deriveFont(Font.PLAIN, fontSize(10f)));
		footerLabel.setAlignmentX(LEFT_ALIGNMENT);
		add(footerLabel);
	}

	private void initStyles(StyledDocument doc)
	{
		Style def = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
		Font mono = PanelUtils.monoFont(11f);

		Style time = doc.addStyle(S_TIME, def);
		StyleConstants.setForeground(time, new Color(0x55, 0x55, 0x55));
		StyleConstants.setFontFamily(time, mono.getFamily());
		StyleConstants.setFontSize(time, (int) fontSize(10f));

		Style sender = doc.addStyle(S_SENDER, def);
		StyleConstants.setForeground(sender, PanelUtils.GOLD);
		StyleConstants.setBold(sender, true);
		StyleConstants.setFontFamily(sender, mono.getFamily());
		StyleConstants.setFontSize(sender, (int) fontSize(11f));

		Style body = doc.addStyle(S_BODY, def);
		StyleConstants.setForeground(body, new Color(0xdd, 0xdd, 0xdd));
		StyleConstants.setFontFamily(body, mono.getFamily());
		StyleConstants.setFontSize(body, (int) fontSize(11f));

		// Badge styles per chat type
		addBadgeStyle(doc, S_BADGE_GAME,   PanelUtils.CHAT_GAME);
		addBadgeStyle(doc, S_BADGE_PUB,    PanelUtils.CHAT_PUBLIC);
		addBadgeStyle(doc, S_BADGE_PRIV,   PanelUtils.CHAT_PRIV);
		addBadgeStyle(doc, S_BADGE_CLAN,   PanelUtils.CHAT_CLAN);
		addBadgeStyle(doc, S_BADGE_FILTER, PanelUtils.CHAT_FILTER);
		addBadgeStyle(doc, S_BADGE_XP,     PanelUtils.CHAT_XP);
		addBadgeStyle(doc, S_BADGE_DEF,    PanelUtils.BADGE_GRAY);
	}

	private void addBadgeStyle(StyledDocument doc, String name, PanelUtils.BadgeColor bc)
	{
		Style def = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
		Style s = doc.addStyle(name, def);
		StyleConstants.setForeground(s, bc.fg);
		StyleConstants.setBackground(s, bc.bg);
		StyleConstants.setBold(s, true);
		StyleConstants.setFontSize(s, (int) fontSize(9f));
	}

	@SuppressWarnings("unchecked")
	public void refresh()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null)
		{
			textPane.setText("");
			footerLabel.setText("API server not available");
			subtitleLabel.setText("0 messages");
			return;
		}

		List<Map<String, Object>> messages = server.getChatBufferCopy();
		String typeFilterVal = (String) typeFilter.getSelectedItem();
		String search = searchField.getText().trim().toLowerCase();

		StyledDocument doc = textPane.getStyledDocument();
		textPane.setText("");

		int shown = 0;
		try
		{
			for (Map<String, Object> msg : messages)
			{
				String type = String.valueOf(msg.getOrDefault("type", ""));
				String text = String.valueOf(msg.getOrDefault("message", ""));
				String senderName = msg.containsKey("sender") ? String.valueOf(msg.get("sender")) : null;
				long timestamp = msg.containsKey("timestamp") ? ((Number) msg.get("timestamp")).longValue() : 0;

				// Type filter
				if (!"All".equals(typeFilterVal) && !type.equals(typeFilterVal))
					continue;

				// Search filter
				if (!search.isEmpty() && !text.toLowerCase().contains(search)
					&& (senderName == null || !senderName.toLowerCase().contains(search)))
					continue;

				// Time
				String timeStr = timestamp > 0 ? TIME_FORMAT.format(new Date(timestamp)) : "??:??:??";
				doc.insertString(doc.getLength(), String.format("%-10s", timeStr), doc.getStyle(S_TIME));

				// Type badge
				String badgeText = " " + shortType(type) + " ";
				String badgeStyle = getBadgeStyle(type);
				doc.insertString(doc.getLength(), String.format("%-8s", badgeText), doc.getStyle(badgeStyle));
				doc.insertString(doc.getLength(), " ", doc.getStyle(S_TIME));

				// Sender
				if (senderName != null && !senderName.isEmpty())
				{
					doc.insertString(doc.getLength(), senderName + ": ", doc.getStyle(S_SENDER));
				}

				// Body
				doc.insertString(doc.getLength(), text + "\n", doc.getStyle(S_BODY));
				shown++;
			}
		}
		catch (BadLocationException ignored) {}

		subtitleLabel.setText(shown + " messages · live tail");
		footerLabel.setText(shown + " / " + messages.size() + " messages");

		if (autoScrollCb.isSelected() && doc.getLength() > 0)
		{
			textPane.setCaretPosition(doc.getLength());
		}
	}

	private String shortType(String type)
	{
		switch (type)
		{
			case "GAMEMESSAGE":   return "GAME";
			case "PUBLICCHAT":    return "PUBLIC";
			case "PRIVATECHAT":   return "PRIV";
			case "PRIVATECHATOUT": return "PRIV";
			case "FRIENDSCHAT":   return "FRIEND";
			case "CLAN_CHAT":     return "CLAN";
			case "TRADE":         return "TRADE";
			default:
				if (type.contains("XP") || type.contains("xp")) return "XP";
				if (type.contains("SPAM") || type.contains("FILTER")) return "FILTER";
				return type.length() > 6 ? type.substring(0, 6) : type;
		}
	}

	private String getBadgeStyle(String type)
	{
		switch (type)
		{
			case "GAMEMESSAGE":   return S_BADGE_GAME;
			case "PUBLICCHAT":    return S_BADGE_PUB;
			case "PRIVATECHAT":
			case "PRIVATECHATOUT": return S_BADGE_PRIV;
			case "CLAN_CHAT":
			case "FRIENDSCHAT":   return S_BADGE_CLAN;
			case "TRADE":         return S_BADGE_FILTER;
			default:
				if (type.contains("XP") || type.contains("xp")) return S_BADGE_XP;
				if (type.contains("SPAM") || type.contains("FILTER")) return S_BADGE_FILTER;
				return S_BADGE_DEF;
		}
	}

	@SuppressWarnings("unchecked")
	private void copyMessages()
	{
		String selected = textPane.getSelectedText();
		if (selected != null && !selected.isEmpty())
		{
			textPane.copy();
			return;
		}

		GameStateServer server = plugin.getApiServer();
		if (server == null) return;

		List<Map<String, Object>> messages = server.getChatBufferCopy();
		StringBuilder sb = new StringBuilder();
		sb.append("=== Chat Log (").append(messages.size()).append(") ===\n");
		for (Map<String, Object> msg : messages)
		{
			String type = String.valueOf(msg.getOrDefault("type", ""));
			String text = String.valueOf(msg.getOrDefault("message", ""));
			String sender = msg.containsKey("sender") ? String.valueOf(msg.get("sender")) : "";
			long ts = msg.containsKey("timestamp") ? ((Number) msg.get("timestamp")).longValue() : 0;
			String time = ts > 0 ? TIME_FORMAT.format(new Date(ts)) : "??:??:??";
			sb.append("[").append(time).append("] [").append(type).append("] ");
			if (!sender.isEmpty()) sb.append(sender).append(": ");
			sb.append(text).append("\n");
		}
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
			new StringSelection(sb.toString()), null);
	}
}
