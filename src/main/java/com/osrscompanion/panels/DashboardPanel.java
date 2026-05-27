package com.osrscompanion.panels;

import static com.osrscompanion.UiScale.*;

import com.osrscompanion.GameStateServer;
import com.osrscompanion.OsrsCompanionConfig;
import com.osrscompanion.OsrsCompanionPlugin;
import com.osrscompanion.TickStateBuffer;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * Dashboard tab — at-a-glance player status, API status, quick actions, session summary.
 */
public class DashboardPanel extends JPanel
{
	private final Client client;
	private final OsrsCompanionConfig config;
	private final OsrsCompanionPlugin plugin;

	// Player status labels
	private final JLabel playerNameLabel = styledLabel("Not logged in");
	private final JLabel worldLabel = styledLabel("—");
	private final JLabel positionLabel = styledLabel("—");
	private final JLabel combatLabel = styledLabel("—");

	// Bars
	private final StatusBar hpBar = new StatusBar(new Color(220, 50, 50), "HP");
	private final StatusBar prayerBar = new StatusBar(new Color(50, 180, 220), "Prayer");
	private final StatusBar runBar = new StatusBar(new Color(220, 190, 50), "Run");
	private final StatusBar specBar = new StatusBar(new Color(50, 220, 100), "Spec");

	// API status
	private final JLabel apiStatusLabel = styledLabel("—");
	private final JLabel sseClientsLabel = styledLabel("—");

	// Session summary
	private final JLabel sessionTimeLabel = styledLabel("—");
	private final JLabel totalXpLabel = styledLabel("—");
	private final JLabel tickBufferLabel = styledLabel("—");
	private final JLabel actionBufferLabel = styledLabel("—");

	public DashboardPanel(Client client, OsrsCompanionConfig config, OsrsCompanionPlugin plugin)
	{
		this.client = client;
		this.config = config;
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(px(12), px(32), px(12), px(32)));

		// Two-column layout for wider windows
		JPanel columns = new JPanel(new GridLayout(1, 2, px(24), 0));
		columns.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// === Left Column: Player Vitals ===
		JPanel leftCol = new JPanel();
		leftCol.setLayout(new BoxLayout(leftCol, BoxLayout.Y_AXIS));
		leftCol.setBackground(ColorScheme.DARK_GRAY_COLOR);

		leftCol.add(sectionHeader("Player"));
		leftCol.add(labelRow("Name:", playerNameLabel));
		leftCol.add(labelRow("World:", worldLabel));
		leftCol.add(labelRow("Combat:", combatLabel));
		leftCol.add(labelRow("Position:", positionLabel));
		leftCol.add(Box.createVerticalStrut(px(6)));
		leftCol.add(hpBar);
		leftCol.add(Box.createVerticalStrut(px(3)));
		leftCol.add(prayerBar);
		leftCol.add(Box.createVerticalStrut(px(3)));
		leftCol.add(runBar);
		leftCol.add(Box.createVerticalStrut(px(3)));
		leftCol.add(specBar);
		leftCol.add(Box.createVerticalGlue());

		// === Right Column: Status + Actions ===
		JPanel rightCol = new JPanel();
		rightCol.setLayout(new BoxLayout(rightCol, BoxLayout.Y_AXIS));
		rightCol.setBackground(ColorScheme.DARK_GRAY_COLOR);

		rightCol.add(sectionHeader("API Server"));
		rightCol.add(labelRow("Status:", apiStatusLabel));
		rightCol.add(labelRow("SSE Clients:", sseClientsLabel));

		rightCol.add(Box.createVerticalStrut(px(10)));

		rightCol.add(sectionHeader("Session"));
		rightCol.add(labelRow("Uptime:", sessionTimeLabel));
		rightCol.add(labelRow("XP Gained:", totalXpLabel));
		rightCol.add(labelRow("Tick Buffer:", tickBufferLabel));
		rightCol.add(labelRow("Actions:", actionBufferLabel));

		rightCol.add(Box.createVerticalStrut(px(10)));

		rightCol.add(sectionHeader("Quick Actions"));
		JPanel buttonRow = new JPanel(new GridLayout(1, 3, px(4), 0));
		buttonRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttonRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(28)));

		JButton saveBtn = actionButton("Save");
		saveBtn.addActionListener(e -> plugin.triggerSave());

		JButton snapshotBtn = actionButton("Snapshot");
		snapshotBtn.addActionListener(e -> copyDebugSnapshot());

		JButton screenshotBtn = actionButton("Screenshot");
		screenshotBtn.addActionListener(e -> takeScreenshot());

		buttonRow.add(saveBtn);
		buttonRow.add(snapshotBtn);
		buttonRow.add(screenshotBtn);
		rightCol.add(buttonRow);

		rightCol.add(Box.createVerticalGlue());

		columns.add(leftCol);
		columns.add(rightCol);

		add(columns, BorderLayout.CENTER);
	}

	public void refresh()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			playerNameLabel.setText("Not logged in");
			worldLabel.setText("—");
			combatLabel.setText("—");
			positionLabel.setText("—");
			hpBar.update(0, 1);
			prayerBar.update(0, 1);
			runBar.update(0, 100);
			specBar.update(0, 100);
			return;
		}

		Player local = client.getLocalPlayer();
		if (local != null)
		{
			playerNameLabel.setText(local.getName() != null ? local.getName() : "Unknown");
			combatLabel.setText(String.valueOf(local.getCombatLevel()));
			WorldPoint wp = local.getWorldLocation();
			if (wp != null)
			{
				positionLabel.setText(wp.getX() + ", " + wp.getY() + " (P" + wp.getPlane() + ")");
			}
		}

		worldLabel.setText("W" + client.getWorld());

		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int maxHp = client.getRealSkillLevel(Skill.HITPOINTS);
		hpBar.update(hp, maxHp);

		int prayer = client.getBoostedSkillLevel(Skill.PRAYER);
		int maxPrayer = client.getRealSkillLevel(Skill.PRAYER);
		prayerBar.update(prayer, maxPrayer);

		int run = client.getEnergy() / 100;
		runBar.update(run, 100);

		int spec = client.getVarpValue(48) / 10;
		specBar.update(spec, 100);

		// API status
		GameStateServer server = plugin.getApiServer();
		if (server != null)
		{
			apiStatusLabel.setText("Running on port " + config.apiPort());
			apiStatusLabel.setForeground(new Color(76, 175, 80));
			sseClientsLabel.setText(String.valueOf(server.getSseClientCount()));
		}
		else
		{
			apiStatusLabel.setText("Stopped");
			apiStatusLabel.setForeground(new Color(244, 67, 54));
			sseClientsLabel.setText("0");
		}

		// Session summary
		if (server != null)
		{
			long sessionMs = server.getSessionStartMs();
			if (sessionMs > 0)
			{
				long elapsed = System.currentTimeMillis() - sessionMs;
				long mins = elapsed / 60000;
				long secs = (elapsed / 1000) % 60;
				sessionTimeLabel.setText(String.format("%dm %ds", mins, secs));
			}
			else
			{
				sessionTimeLabel.setText("—");
			}

			int totalXp = server.getTotalXpGained(client);
			totalXpLabel.setText(String.format("%,d", totalXp));
		}

		TickStateBuffer tb = plugin.getTickBuffer();
		if (tb != null)
		{
			tickBufferLabel.setText(tb.filled() + " / " + tb.capacity());
		}

		if (plugin.getActionTracker() != null)
		{
			actionBufferLabel.setText(plugin.getActionTracker().filled() + " / " + plugin.getActionTracker().capacity());
		}
	}

	private void copyDebugSnapshot()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null)
		{
			JOptionPane.showMessageDialog(this, "API server not running", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}
		// Copy a quick status summary to clipboard
		StringBuilder sb = new StringBuilder();
		sb.append("=== OSRS MCP Debug Snapshot ===\n");
		sb.append("Time: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())).append("\n");
		sb.append("Player: ").append(playerNameLabel.getText()).append("\n");
		sb.append("World: ").append(worldLabel.getText()).append("\n");
		sb.append("Position: ").append(positionLabel.getText()).append("\n");
		sb.append("HP: ").append(hpBar.getText()).append("\n");
		sb.append("Prayer: ").append(prayerBar.getText()).append("\n");
		sb.append("Run: ").append(runBar.getText()).append("\n");
		sb.append("Spec: ").append(specBar.getText()).append("\n");
		sb.append("Tick Buffer: ").append(tickBufferLabel.getText()).append("\n");
		sb.append("Actions: ").append(actionBufferLabel.getText()).append("\n");

		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
			new StringSelection(sb.toString()), null);
		JOptionPane.showMessageDialog(this, "Snapshot copied to clipboard!", "Success", JOptionPane.INFORMATION_MESSAGE);
	}

	private void takeScreenshot()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null)
		{
			JOptionPane.showMessageDialog(this, "API server not running", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}
		// Trigger screenshot via the server's existing mechanism
		server.takeScreenshotToFile();
	}

	// === UI Helpers ===

	private static JLabel styledLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(Color.WHITE);
		label.setFont(label.getFont().deriveFont(Font.PLAIN, fontSize(11f)));
		return label;
	}

	private static JPanel labelRow(String labelText, JLabel valueLabel)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setMaximumSize(dim(600, 18));

		JLabel keyLabel = new JLabel(labelText);
		keyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		keyLabel.setFont(keyLabel.getFont().deriveFont(Font.PLAIN, fontSize(11f)));
		row.add(keyLabel, BorderLayout.WEST);
		row.add(valueLabel, BorderLayout.EAST);
		return row;
	}

	private static JPanel sectionHeader(String title)
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setMaximumSize(dim(600, 22));
		header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR));

		JLabel label = new JLabel(title);
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setFont(label.getFont().deriveFont(Font.BOLD, fontSize(11f)));
		header.add(label, BorderLayout.WEST);

		return header;
	}

	private static JButton actionButton(String text)
	{
		JButton btn = new JButton(text);
		btn.setFont(btn.getFont().deriveFont(Font.PLAIN, fontSize(10f)));
		btn.setFocusPainted(false);
		btn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		btn.setForeground(Color.WHITE);
		btn.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1),
			new EmptyBorder(px(3), px(6), px(3), px(6))
		));
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return btn;
	}

	/**
	 * A simple colored progress bar with label.
	 */
	private static class StatusBar extends JPanel
	{
		private final Color barColor;
		private final String prefix;
		private int current;
		private int max;
		private final JLabel textLabel;

		StatusBar(Color barColor, String prefix)
		{
			this.barColor = barColor;
			this.prefix = prefix;
			this.current = 0;
			this.max = 1;

			setLayout(new BorderLayout());
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setMaximumSize(dim(600, 16));
			setPreferredSize(new Dimension(0, px(16)));

			textLabel = new JLabel(prefix + ": 0/0");
			textLabel.setForeground(Color.WHITE);
			textLabel.setFont(textLabel.getFont().deriveFont(Font.PLAIN, fontSize(10f)));
			textLabel.setHorizontalAlignment(SwingConstants.CENTER);
			add(textLabel, BorderLayout.CENTER);
		}

		void update(int current, int max)
		{
			this.current = current;
			this.max = Math.max(max, 1);
			textLabel.setText(prefix + ": " + current + "/" + max);
			repaint();
		}

		String getText()
		{
			return current + "/" + max;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			int w = getWidth();
			int h = getHeight();

			// Background
			g.setColor(ColorScheme.DARKER_GRAY_COLOR);
			g.fillRect(0, 0, w, h);

			// Bar fill
			float pct = Math.min(1f, (float) current / max);
			int barWidth = (int) (w * pct);
			g.setColor(barColor);
			g.fillRect(0, 0, barWidth, h);
		}
	}
}
