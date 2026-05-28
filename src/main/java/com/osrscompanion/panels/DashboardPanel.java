package com.osrscompanion.panels;

import static com.osrscompanion.UiScale.*;

import com.osrscompanion.ActionTracker;
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
 * Layout matches mockup: grid-2 top (Player + API), grid-3 bottom (Recent actions, Skill deltas, Last save).
 */
public class DashboardPanel extends JPanel
{
	private final Client client;
	private final OsrsCompanionConfig config;
	private final OsrsCompanionPlugin plugin;

	// Player KV values
	private final JLabel playerName  = PanelUtils.val("Not logged in");
	private final JLabel worldVal    = PanelUtils.val("—");
	private final JLabel combatVal   = PanelUtils.val("—");
	private final JLabel positionVal = PanelUtils.val("—");

	// Status bars
	private final PanelUtils.StatusBar hpBar     = new PanelUtils.StatusBar(PanelUtils.HP_RED);
	private final PanelUtils.StatusBar prayerBar = new PanelUtils.StatusBar(PanelUtils.PRAY_BLUE);
	private final PanelUtils.StatusBar runBar    = new PanelUtils.StatusBar(PanelUtils.RUN_YELLOW);
	private final PanelUtils.StatusBar specBar   = new PanelUtils.StatusBar(PanelUtils.SPEC_GREEN);

	// API KV values
	private final JLabel apiStatus    = PanelUtils.val("—", PanelUtils.GREEN);
	private final JLabel sseClients   = PanelUtils.val("—");
	private final JLabel recordingVal = PanelUtils.val("Off", PanelUtils.MUTED);
	private final JLabel uptimeVal    = PanelUtils.val("—");
	private final JLabel tickBufVal   = PanelUtils.val("—");
	private final JLabel actionBufVal = PanelUtils.val("—");
	private final JLabel xpSessionVal = PanelUtils.val("—");

	// Bottom row cards (dynamic content)
	private final JPanel recentActionsContent = new JPanel();
	private final JPanel skillDeltasContent   = new JPanel();
	private final JLabel saveStatus = PanelUtils.val("—", PanelUtils.GREEN);
	private final JLabel savePath   = PanelUtils.val("—");
	private final JLabel saveSize   = PanelUtils.val("—");
	private final JLabel saveWhen   = PanelUtils.val("—");

	public DashboardPanel(Client client, OsrsCompanionConfig config, OsrsCompanionPlugin plugin)
	{
		this.client = client;
		this.config = config;
		this.plugin = plugin;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(PanelUtils.PAGE_BG);
		setBorder(new EmptyBorder(px(16), px(20), px(16), px(20)));

		// Panel header
		JPanel head = PanelUtils.panelHead("Dashboard", "refreshed every 3 ticks · 1.8s");
		head.setAlignmentX(LEFT_ALIGNMENT);
		add(head);
		add(PanelUtils.vgap(14));

		// ── Top row: grid-2 (Player + API) ──────────────────────────
		JPanel playerCard = buildPlayerCard();
		JPanel apiCard    = buildApiCard();
		JPanel topRow = PanelUtils.grid2(playerCard, apiCard);
		topRow.setAlignmentX(LEFT_ALIGNMENT);
		topRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(320)));
		add(topRow);
		add(PanelUtils.vgap(14));

		// ── Bottom row: grid-3 (Recent actions, Skill deltas, Last save)
		JPanel actionsCard   = buildRecentActionsCard();
		JPanel deltasCard    = buildSkillDeltasCard();
		JPanel saveCard      = buildLastSaveCard();
		JPanel bottomRow = PanelUtils.grid3(actionsCard, deltasCard, saveCard);
		bottomRow.setAlignmentX(LEFT_ALIGNMENT);
		bottomRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(240)));
		add(bottomRow);

		add(Box.createVerticalGlue());
	}

	// ── Player Card ─────────────────────────────────────────────────
	private JPanel buildPlayerCard()
	{
		JPanel card = PanelUtils.card();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

		card.add(PanelUtils.cardHeader("Player"));
		card.add(PanelUtils.vgap(10));
		card.add(PanelUtils.kvRow("Name",     playerName));
		card.add(PanelUtils.kvRow("World",    worldVal));
		card.add(PanelUtils.kvRow("Combat",   combatVal));
		card.add(PanelUtils.kvRow("Position", positionVal));
		card.add(PanelUtils.vgap(6));
		card.add(hpBar);
		card.add(PanelUtils.vgap(6));
		card.add(prayerBar);
		card.add(PanelUtils.vgap(6));
		card.add(runBar);
		card.add(PanelUtils.vgap(6));
		card.add(specBar);

		return card;
	}

	// ── API Server Card ─────────────────────────────────────────────
	private JPanel buildApiCard()
	{
		JPanel card = PanelUtils.card();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

		card.add(PanelUtils.cardHeader("API Server"));
		card.add(PanelUtils.vgap(10));
		card.add(PanelUtils.kvRow("Status",         apiStatus));
		card.add(PanelUtils.kvRow("SSE clients",    sseClients));
		card.add(PanelUtils.kvRow("Recording",      recordingVal));
		card.add(PanelUtils.kvRow("Uptime",         uptimeVal));
		card.add(PanelUtils.kvRow("Tick buffer",    tickBufVal));
		card.add(PanelUtils.kvRow("Action buffer",  actionBufVal));
		card.add(PanelUtils.kvRow("XP this session", xpSessionVal));
		card.add(PanelUtils.vgap(12));

		JButton saveBtn = PanelUtils.btnPrimary("Save now");
		saveBtn.addActionListener(e -> plugin.triggerSave());
		JButton snapBtn = PanelUtils.btn("Snapshot");
		snapBtn.addActionListener(e -> copyDebugSnapshot());
		JButton ssBtn = PanelUtils.btn("Screenshot");
		ssBtn.addActionListener(e -> takeScreenshot());

		JPanel btnRow = PanelUtils.btnRow(saveBtn, snapBtn, ssBtn);
		btnRow.setAlignmentX(LEFT_ALIGNMENT);
		card.add(btnRow);

		return card;
	}

	// ── Recent Actions Card ─────────────────────────────────────────
	private JPanel buildRecentActionsCard()
	{
		JPanel card = PanelUtils.card();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.add(PanelUtils.cardHeader("Recent actions"));
		card.add(PanelUtils.vgap(10));
		recentActionsContent.setLayout(new BoxLayout(recentActionsContent, BoxLayout.Y_AXIS));
		recentActionsContent.setOpaque(false);
		card.add(recentActionsContent);
		return card;
	}

	// ── Skill Deltas Card ───────────────────────────────────────────
	private JPanel buildSkillDeltasCard()
	{
		JPanel card = PanelUtils.card();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.add(PanelUtils.cardHeader("Skill deltas"));
		card.add(PanelUtils.vgap(10));
		skillDeltasContent.setLayout(new BoxLayout(skillDeltasContent, BoxLayout.Y_AXIS));
		skillDeltasContent.setOpaque(false);
		card.add(skillDeltasContent);
		return card;
	}

	// ── Last Save Card ──────────────────────────────────────────────
	private JPanel buildLastSaveCard()
	{
		JPanel card = PanelUtils.card();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.add(PanelUtils.cardHeader("Last save"));
		card.add(PanelUtils.vgap(10));
		card.add(PanelUtils.kvRow("Status", saveStatus));
		card.add(PanelUtils.kvRow("Path",   savePath));
		card.add(PanelUtils.kvRow("Size",   saveSize));
		card.add(PanelUtils.kvRow("When",   saveWhen));
		return card;
	}

	// ── Refresh ─────────────────────────────────────────────────────
	public void refresh()
	{
		// Player vitals
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			playerName.setText("Not logged in");
			worldVal.setText("—");
			combatVal.setText("—");
			positionVal.setText("—");
			hpBar.update(0, 1, "HP");
			prayerBar.update(0, 1, "Prayer");
			runBar.update(0, 100, "Run");
			specBar.update(0, 100, "Spec");
		}
		else
		{
			Player local = client.getLocalPlayer();
			if (local != null)
			{
				playerName.setText(local.getName() != null ? local.getName() : "Unknown");
				combatVal.setText(String.valueOf(local.getCombatLevel()));
				WorldPoint wp = local.getWorldLocation();
				if (wp != null)
				{
					positionVal.setText(wp.getX() + ", " + wp.getY() + " (P" + wp.getPlane() + ")");
				}
			}
			worldVal.setText("W" + client.getWorld());

			int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
			int maxHp = client.getRealSkillLevel(Skill.HITPOINTS);
			hpBar.update(hp, maxHp, "HP");

			int prayer = client.getBoostedSkillLevel(Skill.PRAYER);
			int maxPrayer = client.getRealSkillLevel(Skill.PRAYER);
			prayerBar.update(prayer, maxPrayer, "Prayer");

			int run = client.getEnergy() / 100;
			runBar.update(run, 100, "Run");

			int spec = client.getVarpValue(48) / 10;
			specBar.update(spec, 100, "Spec");
		}

		// API status
		GameStateServer server = plugin.getApiServer();
		if (server != null)
		{
			apiStatus.setText("Running · :" + config.apiPort());
			apiStatus.setForeground(PanelUtils.GREEN);
			sseClients.setText(String.valueOf(server.getSseClientCount()));
			recordingVal.setText(server.isRecording() ? "Recording" : "Off");
			recordingVal.setForeground(server.isRecording() ? PanelUtils.RED : PanelUtils.MUTED);

			long sessionMs = server.getSessionStartMs();
			if (sessionMs > 0)
			{
				long elapsed = System.currentTimeMillis() - sessionMs;
				long h = elapsed / 3_600_000;
				long m = (elapsed / 60_000) % 60;
				long s = (elapsed / 1000) % 60;
				uptimeVal.setText(String.format("%02d:%02d:%02d", h, m, s));
			}
			else
			{
				uptimeVal.setText("—");
			}

			int totalXp = server.getTotalXpGained(client);
			xpSessionVal.setText("+" + String.format("%,d", totalXp));
		}
		else
		{
			apiStatus.setText("Stopped");
			apiStatus.setForeground(PanelUtils.RED);
			sseClients.setText("0");
			recordingVal.setText("Off");
			recordingVal.setForeground(PanelUtils.MUTED);
			uptimeVal.setText("—");
			xpSessionVal.setText("—");
		}

		// Tick buffer + action buffer
		TickStateBuffer tb = plugin.getTickBuffer();
		tickBufVal.setText(tb != null ? tb.filled() + " / " + tb.capacity() : "—");
		ActionTracker at = plugin.getActionTracker();
		actionBufVal.setText(at != null ? at.filled() + " / " + at.capacity() : "—");

		// Recent actions (bottom card)
		refreshRecentActions();

		// Skill deltas (bottom card)
		refreshSkillDeltas();

		// Save status (bottom card)
		refreshSaveInfo();
	}

	private void refreshRecentActions()
	{
		recentActionsContent.removeAll();
		ActionTracker tracker = plugin.getActionTracker();
		if (tracker == null)
		{
			recentActionsContent.add(mutedLabel("No data"));
			recentActionsContent.revalidate();
			recentActionsContent.repaint();
			return;
		}

		java.util.List<ActionTracker.TrackedAction> actions = tracker.getActions(4, null, null);
		for (int i = actions.size() - 1; i >= 0; i--)
		{
			ActionTracker.TrackedAction a = actions.get(i);
			JPanel row = new JPanel(new BorderLayout(px(12), 0));
			row.setOpaque(false);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(18)));
			row.setAlignmentX(LEFT_ALIGNMENT);

			JLabel tick = new JLabel(String.valueOf(a.tick));
			tick.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			tick.setFont(tick.getFont().deriveFont(Font.PLAIN, fontSize(11f)));
			tick.setPreferredSize(new Dimension(px(36), px(16)));
			row.add(tick, BorderLayout.WEST);

			JLabel desc = new JLabel(a.action + " · " + a.target);
			desc.setForeground(Color.WHITE);
			desc.setFont(desc.getFont().deriveFont(Font.PLAIN, fontSize(11f)));
			row.add(desc, BorderLayout.CENTER);

			recentActionsContent.add(row);
		}
		if (actions.isEmpty())
		{
			recentActionsContent.add(mutedLabel("No actions yet"));
		}
		recentActionsContent.revalidate();
		recentActionsContent.repaint();
	}

	private void refreshSkillDeltas()
	{
		skillDeltasContent.removeAll();
		GameStateServer server = plugin.getApiServer();
		if (server == null || client.getGameState() != GameState.LOGGED_IN)
		{
			skillDeltasContent.add(mutedLabel("No data"));
			skillDeltasContent.revalidate();
			skillDeltasContent.repaint();
			return;
		}

		java.util.Map<String, Integer> baselines = server.getXpBaselinesCopy();
		int shown = 0;
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL || shown >= 5) continue;
			Integer baseline = baselines.get(skill.name());
			if (baseline == null) continue;
			int gained = client.getSkillExperience(skill) - baseline;
			if (gained <= 0) continue;

			JPanel row = new JPanel(new BorderLayout(px(12), 0));
			row.setOpaque(false);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(18)));
			row.setAlignmentX(LEFT_ALIGNMENT);

			JLabel name = new JLabel(capitalize(skill.name()));
			name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			name.setFont(name.getFont().deriveFont(Font.PLAIN, fontSize(12f)));
			row.add(name, BorderLayout.WEST);

			JLabel xp = new JLabel("+" + String.format("%,d", gained));
			xp.setForeground(PanelUtils.GREEN);
			xp.setFont(xp.getFont().deriveFont(Font.PLAIN, fontSize(12f)));
			xp.setHorizontalAlignment(SwingConstants.RIGHT);
			row.add(xp, BorderLayout.EAST);

			skillDeltasContent.add(row);
			shown++;
		}
		if (shown == 0)
		{
			skillDeltasContent.add(mutedLabel("No XP gained yet"));
		}
		skillDeltasContent.revalidate();
		skillDeltasContent.repaint();
	}

	private void refreshSaveInfo()
	{
		GameStateServer server = plugin.getApiServer();
		if (server != null)
		{
			saveStatus.setText("OK");
			saveStatus.setForeground(PanelUtils.GREEN);
			savePath.setText("~/.runelite/osrs-companion/");
			savePath.setFont(savePath.getFont().deriveFont(Font.PLAIN, fontSize(10f)));
		}
		else
		{
			saveStatus.setText("—");
			saveStatus.setForeground(PanelUtils.MUTED);
		}
	}

	// ── Actions ─────────────────────────────────────────────────────
	private void copyDebugSnapshot()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null)
		{
			JOptionPane.showMessageDialog(this, "API server not running", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("=== OSRS MCP Debug Snapshot ===\n");
		sb.append("Time: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())).append("\n");
		sb.append("Player: ").append(playerName.getText()).append("\n");
		sb.append("World: ").append(worldVal.getText()).append("\n");
		sb.append("Position: ").append(positionVal.getText()).append("\n");
		sb.append("API: ").append(apiStatus.getText()).append("\n");
		sb.append("Tick Buffer: ").append(tickBufVal.getText()).append("\n");
		sb.append("Actions: ").append(actionBufVal.getText()).append("\n");

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
		server.takeScreenshotToFile();
	}

	// ── Helpers ─────────────────────────────────────────────────────
	private static String capitalize(String s)
	{
		if (s == null || s.isEmpty()) return s;
		return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
	}

	private static JLabel mutedLabel(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(PanelUtils.MUTED);
		l.setFont(l.getFont().deriveFont(Font.PLAIN, fontSize(11f)));
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}
}
