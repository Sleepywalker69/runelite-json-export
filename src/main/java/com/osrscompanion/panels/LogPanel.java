package com.osrscompanion.panels;

import static com.osrscompanion.UiScale.*;

import com.osrscompanion.LogCaptureAppender;
import com.osrscompanion.OsrsCompanionPlugin;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Log tab — RuneLite console log viewer with level filtering and search.
 * Uses JTextPane + monospace font for native text selection. Matches mockup's .log-pane.
 */
public class LogPanel extends JPanel
{
	private final OsrsCompanionPlugin plugin;

	private final JComboBox<String> levelFilter;
	private final JTextField searchField;
	private final JCheckBox autoScrollCb;
	private final JTextPane textPane;
	private final JLabel footerLabel = new JLabel("—");

	private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");

	// Style names
	private static final String S_TIME   = "time";
	private static final String S_SOURCE = "source";
	private static final String S_MSG    = "msg";
	private static final String S_LVL_DEBUG = "lvl_debug";
	private static final String S_LVL_INFO  = "lvl_info";
	private static final String S_LVL_WARN  = "lvl_warn";
	private static final String S_LVL_ERROR = "lvl_error";

	public LogPanel(OsrsCompanionPlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(PanelUtils.PAGE_BG);
		setBorder(new EmptyBorder(px(16), px(20), px(16), px(20)));

		// Panel header
		JPanel head = PanelUtils.panelHead("Logs", "log4j tail · INFO and above");
		head.setAlignmentX(LEFT_ALIGNMENT);
		add(head);
		add(PanelUtils.vgap(10));

		// ── Filter Bar ──────────────────────────────────────────────
		JPanel filterBar = new JPanel(new BorderLayout(px(8), 0));
		filterBar.setOpaque(false);
		filterBar.setAlignmentX(LEFT_ALIGNMENT);
		filterBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(30)));

		levelFilter = new JComboBox<>(new String[]{"ALL", "ERROR", "WARN", "INFO", "DEBUG"});
		levelFilter.setFont(levelFilter.getFont().deriveFont(fontSize(10f)));
		levelFilter.setPreferredSize(new Dimension(px(70), px(24)));
		levelFilter.addActionListener(e -> refresh());
		filterBar.add(levelFilter, BorderLayout.WEST);

		searchField = new JTextField();
		searchField.setFont(searchField.getFont().deriveFont(fontSize(11f)));
		searchField.setToolTipText("grep…");
		searchField.addActionListener(e -> refresh());
		filterBar.add(searchField, BorderLayout.CENTER);

		add(filterBar);

		autoScrollCb = new JCheckBox("auto-scroll", true);
		autoScrollCb.setFont(autoScrollCb.getFont().deriveFont(fontSize(9f)));
		autoScrollCb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		autoScrollCb.setBackground(PanelUtils.PAGE_BG);
		autoScrollCb.setFocusPainted(false);
		autoScrollCb.setAlignmentX(LEFT_ALIGNMENT);
		add(autoScrollCb);
		add(PanelUtils.vgap(10));

		// ── Log pane card ───────────────────────────────────────────
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

		Style source = doc.addStyle(S_SOURCE, def);
		StyleConstants.setForeground(source, PanelUtils.SOURCE_BLUE);
		StyleConstants.setFontFamily(source, mono.getFamily());
		StyleConstants.setFontSize(source, (int) fontSize(11f));

		Style msg = doc.addStyle(S_MSG, def);
		StyleConstants.setForeground(msg, new Color(0xdd, 0xdd, 0xdd));
		StyleConstants.setFontFamily(msg, mono.getFamily());
		StyleConstants.setFontSize(msg, (int) fontSize(11f));

		// Level badge styles
		addLvlStyle(doc, S_LVL_DEBUG, PanelUtils.LOG_DEBUG);
		addLvlStyle(doc, S_LVL_INFO,  PanelUtils.LOG_INFO);
		addLvlStyle(doc, S_LVL_WARN,  PanelUtils.LOG_WARN);
		addLvlStyle(doc, S_LVL_ERROR, PanelUtils.LOG_ERROR);
	}

	private void addLvlStyle(StyledDocument doc, String name, PanelUtils.BadgeColor bc)
	{
		Style def = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
		Style s = doc.addStyle(name, def);
		StyleConstants.setForeground(s, bc.fg);
		StyleConstants.setBackground(s, bc.bg);
		StyleConstants.setBold(s, true);
		StyleConstants.setFontSize(s, (int) fontSize(9f));
	}

	public void refresh()
	{
		LogCaptureAppender appender = plugin.getLogAppender();
		if (appender == null)
		{
			textPane.setText("");
			footerLabel.setText("Log appender not available");
			return;
		}

		List<Map<String, Object>> entries = appender.getEntries();
		String level = (String) levelFilter.getSelectedItem();
		String search = searchField.getText().trim().toLowerCase();

		StyledDocument doc = textPane.getStyledDocument();
		textPane.setText("");

		int shown = 0;
		int start = Math.max(0, entries.size() - 200);

		try
		{
			for (int i = start; i < entries.size(); i++)
			{
				Map<String, Object> entry = entries.get(i);
				String entryLevel = String.valueOf(entry.getOrDefault("level", "INFO"));

				if (!"ALL".equals(level) && !passesLevelFilter(entryLevel, level))
					continue;

				if (!search.isEmpty())
				{
					String logMsg = String.valueOf(entry.getOrDefault("message", ""));
					String logger = String.valueOf(entry.getOrDefault("loggerName", ""));
					if (!logMsg.toLowerCase().contains(search) && !logger.toLowerCase().contains(search))
						continue;
				}

				insertLogRow(doc, entry);
				shown++;

				if (shown >= 150) break;
			}
		}
		catch (BadLocationException ignored) {}

		footerLabel.setText(shown + " shown | " + entries.size() + " total");

		if (autoScrollCb.isSelected() && doc.getLength() > 0)
		{
			textPane.setCaretPosition(doc.getLength());
		}
	}

	private void insertLogRow(StyledDocument doc, Map<String, Object> entry) throws BadLocationException
	{
		String entryLevel = String.valueOf(entry.getOrDefault("level", "INFO"));
		String logger = String.valueOf(entry.getOrDefault("loggerName", ""));
		String message = String.valueOf(entry.getOrDefault("message", ""));
		long timestamp = entry.containsKey("timestamp") ? ((Number) entry.get("timestamp")).longValue() : 0;

		// Time (with milliseconds)
		String timeStr = timestamp > 0 ? TIME_FORMAT.format(new Date(timestamp)) : "??:??:??.???";
		doc.insertString(doc.getLength(), String.format("%-14s", timeStr), doc.getStyle(S_TIME));

		// Level badge
		String lvlStyle;
		switch (entryLevel)
		{
			case "ERROR": lvlStyle = S_LVL_ERROR; break;
			case "WARN":  lvlStyle = S_LVL_WARN;  break;
			case "DEBUG": lvlStyle = S_LVL_DEBUG;  break;
			default:      lvlStyle = S_LVL_INFO;
		}
		String lvlText = " " + entryLevel.substring(0, Math.min(5, entryLevel.length())) + " ";
		doc.insertString(doc.getLength(), String.format("%-7s", lvlText), doc.getStyle(lvlStyle));
		doc.insertString(doc.getLength(), " ", doc.getStyle(S_TIME));

		// Source (shortened)
		String shortLogger = logger;
		int lastDot = logger.lastIndexOf('.');
		if (lastDot > 0) shortLogger = logger.substring(lastDot + 1);
		if (shortLogger.length() > 24) shortLogger = shortLogger.substring(0, 21) + "...";
		doc.insertString(doc.getLength(), String.format("%-25s", shortLogger), doc.getStyle(S_SOURCE));

		// Message
		if (message.length() > 120) message = message.substring(0, 117) + "...";
		doc.insertString(doc.getLength(), message + "\n", doc.getStyle(S_MSG));
	}

	private boolean passesLevelFilter(String entryLevel, String filterLevel)
	{
		return levelOrdinal(entryLevel) >= levelOrdinal(filterLevel);
	}

	private int levelOrdinal(String level)
	{
		switch (level)
		{
			case "ERROR": return 4;
			case "WARN":  return 3;
			case "INFO":  return 2;
			case "DEBUG": return 1;
			default:      return 0;
		}
	}
}
