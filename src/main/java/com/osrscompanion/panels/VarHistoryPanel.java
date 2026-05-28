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
 * Var History tab — timeline viewer for varbit/varp changes.
 * Uses JTextPane for native text selection. Semi-transparent type badges.
 */
public class VarHistoryPanel extends JPanel
{
	private final OsrsCompanionPlugin plugin;

	private final JComboBox<String> typeFilter;
	private final JTextField searchField;
	private final JTextPane textPane;
	private final JLabel footerLabel = new JLabel("—");

	private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

	// Style names
	private static final String S_TIME       = "time";
	private static final String S_CHANGE     = "change";
	private static final String S_TICK       = "tick";
	private static final String S_BADGE_VARBIT = "b_varbit";
	private static final String S_BADGE_VARP   = "b_varp";

	public VarHistoryPanel(OsrsCompanionPlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(PanelUtils.PAGE_BG);
		setBorder(new EmptyBorder(px(16), px(20), px(16), px(20)));

		// Panel header
		JPanel head = PanelUtils.panelHead("Vars", "varbit & varp change history");
		head.setAlignmentX(LEFT_ALIGNMENT);
		add(head);
		add(PanelUtils.vgap(10));

		// ── Filter Bar ──────────────────────────────────────────────
		JPanel filterBar = new JPanel(new BorderLayout(px(8), 0));
		filterBar.setOpaque(false);
		filterBar.setAlignmentX(LEFT_ALIGNMENT);
		filterBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(30)));

		typeFilter = new JComboBox<>(new String[]{"All", "varbit", "varp"});
		typeFilter.setFont(typeFilter.getFont().deriveFont(fontSize(10f)));
		typeFilter.setPreferredSize(new Dimension(px(75), px(24)));
		typeFilter.addActionListener(e -> refresh());
		filterBar.add(typeFilter, BorderLayout.WEST);

		searchField = new JTextField();
		searchField.setFont(searchField.getFont().deriveFont(fontSize(11f)));
		searchField.setToolTipText("Search by ID...");
		searchField.addActionListener(e -> refresh());
		filterBar.add(searchField, BorderLayout.CENTER);

		JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, px(4), 0));
		rightBtns.setOpaque(false);
		JButton copyBtn = PanelUtils.btn("Copy");
		copyBtn.addActionListener(e -> copyVarHistory());
		rightBtns.add(copyBtn);
		filterBar.add(rightBtns, BorderLayout.EAST);

		add(filterBar);
		add(PanelUtils.vgap(10));

		// ── Var changes card ────────────────────────────────────────
		JPanel card = PanelUtils.card();
		card.setLayout(new BorderLayout());
		card.setAlignmentX(LEFT_ALIGNMENT);

		textPane = new JTextPane();
		textPane.setEditable(false);
		textPane.setBackground(PanelUtils.CARD_BG);
		textPane.setFont(PanelUtils.monoFont(11f));
		textPane.setForeground(Color.WHITE);
		initStyles(textPane.getStyledDocument());
		PanelUtils.installTextPopup(textPane);

		JScrollPane scroll = new JScrollPane(textPane);
		scroll.setBorder(null);
		scroll.setBackground(PanelUtils.CARD_BG);
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

		Style change = doc.addStyle(S_CHANGE, def);
		StyleConstants.setForeground(change, Color.WHITE);
		StyleConstants.setFontFamily(change, mono.getFamily());
		StyleConstants.setFontSize(change, (int) fontSize(11f));

		Style tick = doc.addStyle(S_TICK, def);
		StyleConstants.setForeground(tick, ColorScheme.LIGHT_GRAY_COLOR);
		StyleConstants.setFontFamily(tick, mono.getFamily());
		StyleConstants.setFontSize(tick, (int) fontSize(9f));

		// Badge styles
		addBadge(doc, S_BADGE_VARBIT, PanelUtils.BADGE_ORANGE);
		addBadge(doc, S_BADGE_VARP,   PanelUtils.BADGE_BLUE);
	}

	private void addBadge(StyledDocument doc, String name, PanelUtils.BadgeColor bc)
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
		GameStateServer server = plugin.getApiServer();
		if (server == null)
		{
			textPane.setText("");
			footerLabel.setText("API server not available");
			return;
		}

		List<Map<String, Object>> changes = server.getVarHistoryCopy();
		String typeFilterVal = (String) typeFilter.getSelectedItem();
		String searchVal = searchField.getText().trim();

		StyledDocument doc = textPane.getStyledDocument();
		textPane.setText("");

		int shown = 0;
		try
		{
			for (int i = changes.size() - 1; i >= 0; i--)
			{
				Map<String, Object> entry = changes.get(i);
				String type = String.valueOf(entry.getOrDefault("type", ""));
				String idStr = String.valueOf(entry.getOrDefault("id", ""));

				if (!"All".equals(typeFilterVal) && !type.equals(typeFilterVal))
					continue;
				if (!searchVal.isEmpty() && !idStr.contains(searchVal))
					continue;

				insertVarRow(doc, entry);
				shown++;
				if (shown >= 200) break;
			}
		}
		catch (BadLocationException ignored) {}

		footerLabel.setText(shown + " / " + changes.size() + " var changes");
		textPane.setCaretPosition(0);
	}

	private void insertVarRow(StyledDocument doc, Map<String, Object> entry) throws BadLocationException
	{
		String type = String.valueOf(entry.getOrDefault("type", "?"));
		int tick = entry.containsKey("tick") ? ((Number) entry.get("tick")).intValue() : 0;
		int id = entry.containsKey("id") ? ((Number) entry.get("id")).intValue() : 0;
		int oldVal = entry.containsKey("oldValue") ? ((Number) entry.get("oldValue")).intValue() : 0;
		int newVal = entry.containsKey("newValue") ? ((Number) entry.get("newValue")).intValue() : 0;
		long timestamp = entry.containsKey("timestamp") ? ((Number) entry.get("timestamp")).longValue() : 0;

		// Time
		String timeStr = timestamp > 0 ? TIME_FORMAT.format(new Date(timestamp)) : "??:??:??";
		doc.insertString(doc.getLength(), String.format("%-10s", timeStr), doc.getStyle(S_TIME));

		// Type badge
		boolean isVarbit = "varbit".equals(type);
		String badgeStyle = isVarbit ? S_BADGE_VARBIT : S_BADGE_VARP;
		String badgeText = " " + type + " ";
		doc.insertString(doc.getLength(), String.format("%-8s", badgeText), doc.getStyle(badgeStyle));
		doc.insertString(doc.getLength(), "  ", doc.getStyle(S_TIME));

		// Change text
		doc.insertString(doc.getLength(),
			String.format("%s %d: %d → %d", type, id, oldVal, newVal),
			doc.getStyle(S_CHANGE));

		// Tick
		doc.insertString(doc.getLength(), "  T" + tick, doc.getStyle(S_TICK));
		doc.insertString(doc.getLength(), "\n", doc.getStyle(S_TIME));
	}

	private void copyVarHistory()
	{
		String selected = textPane.getSelectedText();
		if (selected != null && !selected.isEmpty())
		{
			textPane.copy();
			return;
		}

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
