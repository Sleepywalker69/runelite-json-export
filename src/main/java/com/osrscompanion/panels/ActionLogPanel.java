package com.osrscompanion.panels;

import static com.osrscompanion.UiScale.*;

import com.osrscompanion.ActionTracker;
import com.osrscompanion.OsrsCompanionPlugin;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;

/**
 * Action Log tab — scrollable, filterable view of the ActionTracker ring buffer.
 * Uses JTextPane + StyledDocument for native text selection and Ctrl+C.
 * Visual style matches mockup's action-table with semi-transparent source badges.
 */
public class ActionLogPanel extends JPanel
{
	private final OsrsCompanionPlugin plugin;

	private final JComboBox<String> sourceFilter;
	private final JTextField searchField;
	private final JTextPane textPane;
	private final JLabel footerLabel = new JLabel("—");
	private final JLabel subtitleLabel;

	// StyledDocument styles
	private static final String STYLE_TICK    = "tick";
	private static final String STYLE_ACTION  = "action";
	private static final String STYLE_TARGET  = "target";
	private static final String STYLE_DETAIL  = "detail";
	private static final String STYLE_BADGE_MENU = "badge_menu";
	private static final String STYLE_BADGE_SCRIPT = "badge_script";
	private static final String STYLE_BADGE_INFER  = "badge_infer";
	private static final String STYLE_HEADER  = "header";

	public ActionLogPanel(OsrsCompanionPlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(PanelUtils.PAGE_BG);
		setBorder(new EmptyBorder(px(16), px(20), px(16), px(20)));

		// Panel header
		subtitleLabel = new JLabel("— / — in ring buffer");
		subtitleLabel.setForeground(PanelUtils.SUBTITLE_FG);
		subtitleLabel.setFont(subtitleLabel.getFont().deriveFont(Font.PLAIN, fontSize(11f)));

		JPanel head = PanelUtils.panelHead("Actions", "");
		// Replace the subtitle with our dynamic one
		head.remove(1); // remove the static subtitle
		head.add(subtitleLabel, BorderLayout.EAST);
		head.setAlignmentX(LEFT_ALIGNMENT);
		add(head);
		add(PanelUtils.vgap(10));

		// ── Filter Bar ──────────────────────────────────────────────
		JPanel filterBar = new JPanel(new BorderLayout(px(8), 0));
		filterBar.setOpaque(false);
		filterBar.setAlignmentX(LEFT_ALIGNMENT);
		filterBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(30)));

		// Source filter buttons
		JPanel sourceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, px(4), 0));
		sourceRow.setOpaque(false);

		sourceFilter = new JComboBox<>(new String[]{"All", "menu", "script", "inferred"});
		sourceFilter.setFont(sourceFilter.getFont().deriveFont(fontSize(10f)));
		sourceFilter.setPreferredSize(new Dimension(px(85), px(24)));
		sourceFilter.addActionListener(e -> refresh());
		sourceRow.add(sourceFilter);

		filterBar.add(sourceRow, BorderLayout.WEST);

		searchField = new JTextField();
		searchField.setFont(searchField.getFont().deriveFont(fontSize(11f)));
		searchField.setToolTipText("filter…");
		searchField.addActionListener(e -> refresh());
		filterBar.add(searchField, BorderLayout.CENTER);

		JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, px(4), 0));
		rightBtns.setOpaque(false);
		JButton copyBtn = PanelUtils.btn("Copy");
		copyBtn.addActionListener(e -> copyActions());
		rightBtns.add(copyBtn);
		filterBar.add(rightBtns, BorderLayout.EAST);

		add(filterBar);
		add(PanelUtils.vgap(10));

		// ── Action table in a card ──────────────────────────────────
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

		// ── Footer ──────────────────────────────────────────────────
		footerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		footerLabel.setFont(footerLabel.getFont().deriveFont(Font.PLAIN, fontSize(10f)));
		footerLabel.setAlignmentX(LEFT_ALIGNMENT);
		add(footerLabel);
	}

	private void initStyles(StyledDocument doc)
	{
		Style def = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);

		Style tick = doc.addStyle(STYLE_TICK, def);
		StyleConstants.setForeground(tick, PanelUtils.MUTED);
		StyleConstants.setFontFamily(tick, PanelUtils.monoFont(11f).getFamily());
		StyleConstants.setFontSize(tick, (int) fontSize(11f));

		Style action = doc.addStyle(STYLE_ACTION, def);
		StyleConstants.setForeground(action, new Color(0xe0, 0xe0, 0xe0));
		StyleConstants.setFontSize(action, (int) fontSize(11f));

		Style target = doc.addStyle(STYLE_TARGET, def);
		StyleConstants.setForeground(target, Color.WHITE);
		StyleConstants.setFontSize(target, (int) fontSize(11f));

		Style detail = doc.addStyle(STYLE_DETAIL, def);
		StyleConstants.setForeground(detail, PanelUtils.MUTED);
		StyleConstants.setFontSize(detail, (int) fontSize(11f));

		// Badge styles with semi-transparent backgrounds
		Style menuBadge = doc.addStyle(STYLE_BADGE_MENU, def);
		StyleConstants.setForeground(menuBadge, PanelUtils.BADGE_GREEN.fg);
		StyleConstants.setBackground(menuBadge, PanelUtils.BADGE_GREEN.bg);
		StyleConstants.setBold(menuBadge, true);
		StyleConstants.setFontSize(menuBadge, (int) fontSize(9f));

		Style scriptBadge = doc.addStyle(STYLE_BADGE_SCRIPT, def);
		StyleConstants.setForeground(scriptBadge, PanelUtils.BADGE_BLUE.fg);
		StyleConstants.setBackground(scriptBadge, PanelUtils.BADGE_BLUE.bg);
		StyleConstants.setBold(scriptBadge, true);
		StyleConstants.setFontSize(scriptBadge, (int) fontSize(9f));

		Style inferBadge = doc.addStyle(STYLE_BADGE_INFER, def);
		StyleConstants.setForeground(inferBadge, PanelUtils.BADGE_YELLOW.fg);
		StyleConstants.setBackground(inferBadge, PanelUtils.BADGE_YELLOW.bg);
		StyleConstants.setBold(inferBadge, true);
		StyleConstants.setFontSize(inferBadge, (int) fontSize(9f));

		// Header row style
		Style header = doc.addStyle(STYLE_HEADER, def);
		StyleConstants.setForeground(header, ColorScheme.BRAND_ORANGE);
		StyleConstants.setBold(header, true);
		StyleConstants.setFontSize(header, (int) fontSize(10f));
	}

	public void refresh()
	{
		ActionTracker tracker = plugin.getActionTracker();
		if (tracker == null)
		{
			textPane.setText("");
			footerLabel.setText("Action tracker not available");
			subtitleLabel.setText("— / — in ring buffer");
			return;
		}

		String source = (String) sourceFilter.getSelectedItem();
		String sourceArg = "All".equals(source) ? null : source;
		String search = searchField.getText().trim();
		String searchArg = search.isEmpty() ? null : search;

		List<ActionTracker.TrackedAction> actions = tracker.getActions(100, sourceArg, searchArg);

		subtitleLabel.setText(tracker.filled() + " / " + tracker.capacity() + " in ring buffer");

		StyledDocument doc = textPane.getStyledDocument();
		textPane.setText("");

		try
		{
			// Header row
			doc.insertString(doc.getLength(), "TICK    SRC       ACTION              TARGET              DETAIL\n",
				doc.getStyle(STYLE_HEADER));

			// Action rows (newest first)
			for (int i = actions.size() - 1; i >= 0; i--)
			{
				ActionTracker.TrackedAction a = actions.get(i);
				insertActionRow(doc, a);
			}
		}
		catch (BadLocationException ignored) {}

		footerLabel.setText(String.format("%d shown | %d / %d in buffer",
			actions.size(), tracker.filled(), tracker.capacity()));

		textPane.setCaretPosition(0);
	}

	private void insertActionRow(StyledDocument doc, ActionTracker.TrackedAction a) throws BadLocationException
	{
		// Tick
		doc.insertString(doc.getLength(), String.format("%-7d ", a.tick), doc.getStyle(STYLE_TICK));

		// Source badge
		String badgeStyle;
		switch (a.source)
		{
			case "menu":     badgeStyle = STYLE_BADGE_MENU;   break;
			case "script":   badgeStyle = STYLE_BADGE_SCRIPT; break;
			case "inferred": badgeStyle = STYLE_BADGE_INFER;  break;
			default:         badgeStyle = STYLE_TICK;
		}
		String srcLabel = String.format(" %-6s ", a.source);
		doc.insertString(doc.getLength(), srcLabel, doc.getStyle(badgeStyle));
		doc.insertString(doc.getLength(), "  ", doc.getStyle(STYLE_TICK));

		// Action
		String actionText = a.action != null ? a.action : "";
		if (actionText.length() > 20) actionText = actionText.substring(0, 17) + "...";
		doc.insertString(doc.getLength(), String.format("%-20s", actionText), doc.getStyle(STYLE_ACTION));

		// Target
		String targetText = a.target != null ? a.target : "—";
		if (targetText.length() > 20) targetText = targetText.substring(0, 17) + "...";
		doc.insertString(doc.getLength(), String.format("%-20s", targetText), doc.getStyle(STYLE_TARGET));

		// Detail (remaining info from details map)
		String detail = "";
		if (a.details != null && !a.details.isEmpty())
		{
			detail = a.details.toString();
		}
		if (detail.length() > 30) detail = detail.substring(0, 27) + "...";
		doc.insertString(doc.getLength(), detail, doc.getStyle(STYLE_DETAIL));

		doc.insertString(doc.getLength(), "\n", doc.getStyle(STYLE_TICK));
	}

	private void copyActions()
	{
		// If there's selected text, copy just that
		String selected = textPane.getSelectedText();
		if (selected != null && !selected.isEmpty())
		{
			textPane.copy();
			return;
		}

		// Otherwise copy all as plain text
		ActionTracker tracker = plugin.getActionTracker();
		if (tracker == null) return;

		String source = (String) sourceFilter.getSelectedItem();
		String sourceArg = "All".equals(source) ? null : source;
		String search = searchField.getText().trim();
		String searchArg = search.isEmpty() ? null : search;

		List<ActionTracker.TrackedAction> actions = tracker.getActions(100, sourceArg, searchArg);
		StringBuilder sb = new StringBuilder();
		sb.append("=== Action Log (").append(actions.size()).append(") ===\n");
		for (int i = actions.size() - 1; i >= 0; i--)
		{
			ActionTracker.TrackedAction a = actions.get(i);
			sb.append("[+").append(a.tick).append("] [").append(a.source).append("] ")
				.append(a.action).append(" | ").append(a.target).append("\n");
		}
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
			new StringSelection(sb.toString()), null);
	}
}
