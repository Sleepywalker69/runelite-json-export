package com.osrscompanion.panels;

import static com.osrscompanion.UiScale.*;

import com.osrscompanion.ActionTracker;
import com.osrscompanion.OsrsCompanionPlugin;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;

/**
 * Action Log tab — scrollable, filterable view of the ActionTracker ring buffer.
 */
public class ActionLogPanel extends JPanel
{
	private final OsrsCompanionPlugin plugin;

	private final JComboBox<String> sourceFilter;
	private final JTextField searchField;
	private final JPanel listPanel;
	private final JLabel footerLabel = new JLabel("—");

	private static final Color MENU_COLOR = new Color(76, 175, 80);   // green
	private static final Color SCRIPT_COLOR = new Color(33, 150, 243);  // blue
	private static final Color INFERRED_COLOR = new Color(255, 193, 7); // yellow

	public ActionLogPanel(OsrsCompanionPlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(px(12), px(32), px(12), px(32)));

		// === Filter Bar ===
		JPanel filterBar = new JPanel(new BorderLayout(px(4), 0));
		filterBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filterBar.setBorder(new EmptyBorder(0, 0, px(4), 0));

		sourceFilter = new JComboBox<>(new String[]{"All", "menu", "script", "inferred"});
		sourceFilter.setFont(sourceFilter.getFont().deriveFont(fontSize(10f)));
		sourceFilter.setPreferredSize(dim(75, 22));
		sourceFilter.addActionListener(e -> refresh());
		filterBar.add(sourceFilter, BorderLayout.WEST);

		searchField = new JTextField();
		searchField.setFont(searchField.getFont().deriveFont(fontSize(10f)));
		searchField.setToolTipText("Search actions...");
		searchField.addActionListener(e -> refresh());
		filterBar.add(searchField, BorderLayout.CENTER);

		JButton copyBtn = new JButton("Copy");
		copyBtn.setFont(copyBtn.getFont().deriveFont(Font.PLAIN, fontSize(10f)));
		copyBtn.setFocusPainted(false);
		copyBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		copyBtn.setForeground(Color.WHITE);
		copyBtn.setBorder(new EmptyBorder(px(2), px(8), px(2), px(8)));
		copyBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		copyBtn.addActionListener(e -> copyActions());
		filterBar.add(copyBtn, BorderLayout.EAST);

		add(filterBar, BorderLayout.NORTH);

		// === Action List ===
		listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane scrollPane = new JScrollPane(listPanel);
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
		ActionTracker tracker = plugin.getActionTracker();
		if (tracker == null)
		{
			listPanel.removeAll();
			footerLabel.setText("Action tracker not available");
			listPanel.revalidate();
			listPanel.repaint();
			return;
		}

		String source = (String) sourceFilter.getSelectedItem();
		String sourceArg = "All".equals(source) ? null : source;
		String search = searchField.getText().trim();
		String searchArg = search.isEmpty() ? null : search;

		List<ActionTracker.TrackedAction> actions = tracker.getActions(100, sourceArg, searchArg);

		listPanel.removeAll();

		// Show newest first
		for (int i = actions.size() - 1; i >= 0; i--)
		{
			ActionTracker.TrackedAction action = actions.get(i);
			listPanel.add(createActionRow(action));
		}

		footerLabel.setText(String.format("%d shown | %d / %d in buffer",
			actions.size(), tracker.filled(), tracker.capacity()));

		listPanel.revalidate();
		listPanel.repaint();
	}

	private JPanel createActionRow(ActionTracker.TrackedAction action)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(px(2), px(4), px(2), px(4))
		));
		row.setMaximumSize(dim(600, 36));

		// Source badge + tick
		Color badgeColor;
		switch (action.source)
		{
			case "menu":
				badgeColor = MENU_COLOR;
				break;
			case "script":
				badgeColor = SCRIPT_COLOR;
				break;
			case "inferred":
				badgeColor = INFERRED_COLOR;
				break;
			default:
				badgeColor = Color.GRAY;
		}

		JLabel badge = new JLabel(" " + action.source.substring(0, 1).toUpperCase() + " ");
		badge.setOpaque(true);
		badge.setBackground(badgeColor);
		badge.setForeground(Color.WHITE);
		badge.setFont(badge.getFont().deriveFont(Font.BOLD, fontSize(9f)));
		badge.setBorder(new EmptyBorder(px(1), px(3), px(1), px(3)));

		JLabel tickLabel = new JLabel(" +" + action.tick + " ");
		tickLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		tickLabel.setFont(tickLabel.getFont().deriveFont(Font.PLAIN, fontSize(9f)));

		JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, px(2), 0));
		leftPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		leftPanel.add(badge);
		leftPanel.add(tickLabel);
		row.add(leftPanel, BorderLayout.WEST);

		// Action text
		String actionText = action.action;
		if (actionText.length() > 60)
		{
			actionText = actionText.substring(0, 57) + "...";
		}
		JLabel actionLabel = new JLabel(actionText);
		actionLabel.setForeground(Color.WHITE);
		actionLabel.setFont(actionLabel.getFont().deriveFont(Font.PLAIN, fontSize(10f)));
		actionLabel.setToolTipText(action.action + " | " + action.target);
		row.add(actionLabel, BorderLayout.CENTER);

		return row;
	}

	private void copyActions()
	{
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
