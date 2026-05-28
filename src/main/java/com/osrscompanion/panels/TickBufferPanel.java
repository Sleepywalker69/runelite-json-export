package com.osrscompanion.panels;

import static com.osrscompanion.UiScale.*;

import com.osrscompanion.OsrsCompanionPlugin;
import com.osrscompanion.TickStateBuffer;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Map;

/**
 * Tick Buffer tab — timeline viewer for the 600-tick state buffer with delta highlights.
 * Shows summary cards, sparkline activity charts, and a JTextPane delta viewer.
 */
public class TickBufferPanel extends JPanel
{
	private final OsrsCompanionPlugin plugin;

	private final JSpinner tickCountSpinner;
	private final JCheckBox showNpcs;
	private final JCheckBox showPlayers;
	private final JCheckBox showSkills;
	private final JCheckBox showHits;
	private final JTextPane textPane;
	private final JLabel footerLabel = new JLabel("—");

	// Summary labels — Capacity card
	private final JLabel filledLabel  = PanelUtils.val("—");
	private final JLabel oldestLabel  = PanelUtils.val("—");
	private final JLabel newestLabel  = PanelUtils.val("—");
	private final JLabel memoryLabel  = PanelUtils.val("—", PanelUtils.MUTED);

	// Summary labels — Hitsplats card
	private final JLabel hitsMineLabel   = PanelUtils.val("0", PanelUtils.GREEN);
	private final JLabel hitsOtherLabel  = PanelUtils.val("0");
	private final JLabel hitsBlockLabel  = PanelUtils.val("0", PanelUtils.MUTED);
	private final JLabel hitsHealLabel   = PanelUtils.val("0");

	// Summary labels — Density card
	private final JLabel npcAvgLabel    = PanelUtils.val("—");
	private final JLabel playerAvgLabel = PanelUtils.val("—");
	private final JLabel activeLabel    = PanelUtils.val("—");
	private final JLabel idleLabel      = PanelUtils.val("—", PanelUtils.MUTED);

	// Sparkline panels
	private final SparklinePanel sparkEvents;
	private final SparklinePanel sparkHp;
	private final SparklinePanel sparkPrayer;
	private final SparklinePanel sparkRun;

	// StyledDocument styles for delta viewer
	private static final String S_TICK    = "tick";
	private static final String S_ADDED   = "added";
	private static final String S_REMOVED = "removed";
	private static final String S_CHANGED = "changed";
	private static final String S_MUTED   = "muted";

	private static final Color ADDED_COLOR   = new Color(76, 175, 80);
	private static final Color REMOVED_COLOR = new Color(244, 67, 54);
	private static final Color CHANGED_COLOR = new Color(255, 193, 7);

	public TickBufferPanel(OsrsCompanionPlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(PanelUtils.PAGE_BG);
		setBorder(new EmptyBorder(px(16), px(20), px(16), px(20)));

		// Panel header
		JPanel head = PanelUtils.panelHead("Tick buffer", "last 600 ticks · ~10 minutes");
		head.setAlignmentX(LEFT_ALIGNMENT);
		add(head);
		add(PanelUtils.vgap(10));

		// ── Summary cards (grid-3) ──────────────────────────────────
		JPanel capCard = buildCapacityCard();
		JPanel hitCard = buildHitsplatCard();
		JPanel denCard = buildDensityCard();
		JPanel summaryRow = PanelUtils.grid3(capCard, hitCard, denCard);
		summaryRow.setAlignmentX(LEFT_ALIGNMENT);
		summaryRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(180)));
		add(summaryRow);
		add(PanelUtils.vgap(10));

		// ── Sparkline card ──────────────────────────────────────────
		JPanel sparkCard = PanelUtils.card();
		sparkCard.setLayout(new BoxLayout(sparkCard, BoxLayout.Y_AXIS));
		sparkCard.setAlignmentX(LEFT_ALIGNMENT);

		JLabel sparkHeader = PanelUtils.cardHeader("Activity over time");
		sparkCard.add(sparkHeader);
		sparkCard.add(PanelUtils.vgap(8));

		sparkEvents = new SparklinePanel("Events", ColorScheme.BRAND_ORANGE);
		sparkHp     = new SparklinePanel("HP", PanelUtils.HP_RED);
		sparkPrayer = new SparklinePanel("Prayer", PanelUtils.PRAY_BLUE);
		sparkRun    = new SparklinePanel("Run", PanelUtils.RUN_YELLOW);

		sparkCard.add(sparkEvents);
		sparkCard.add(PanelUtils.vgap(4));
		sparkCard.add(sparkHp);
		sparkCard.add(PanelUtils.vgap(4));
		sparkCard.add(sparkPrayer);
		sparkCard.add(PanelUtils.vgap(4));
		sparkCard.add(sparkRun);

		add(sparkCard);
		add(PanelUtils.vgap(10));

		// ── Controls bar ────────────────────────────────────────────
		JPanel controlBar = new JPanel(new BorderLayout(px(8), 0));
		controlBar.setOpaque(false);
		controlBar.setAlignmentX(LEFT_ALIGNMENT);
		controlBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(30)));

		JPanel leftControls = new JPanel(new FlowLayout(FlowLayout.LEFT, px(4), 0));
		leftControls.setOpaque(false);

		JLabel tickLabel = new JLabel("Last");
		tickLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		tickLabel.setFont(tickLabel.getFont().deriveFont(fontSize(10f)));
		leftControls.add(tickLabel);

		tickCountSpinner = new JSpinner(new SpinnerNumberModel(10, 2, 100, 5));
		tickCountSpinner.setPreferredSize(new Dimension(px(55), px(22)));
		tickCountSpinner.setFont(tickCountSpinner.getFont().deriveFont(fontSize(10f)));
		leftControls.add(tickCountSpinner);

		JLabel ticksSuffix = new JLabel("ticks");
		ticksSuffix.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		ticksSuffix.setFont(ticksSuffix.getFont().deriveFont(fontSize(10f)));
		leftControls.add(ticksSuffix);

		controlBar.add(leftControls, BorderLayout.WEST);

		JPanel filterChips = new JPanel(new FlowLayout(FlowLayout.LEFT, px(4), 0));
		filterChips.setOpaque(false);
		showNpcs    = chip("NPCs", true);
		showPlayers = chip("Players", true);
		showSkills  = chip("Skills", true);
		showHits    = chip("Hits", true);
		filterChips.add(showNpcs);
		filterChips.add(showPlayers);
		filterChips.add(showSkills);
		filterChips.add(showHits);
		controlBar.add(filterChips, BorderLayout.CENTER);

		JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, px(4), 0));
		rightBtns.setOpaque(false);
		JButton refreshBtn = PanelUtils.btn("Refresh");
		refreshBtn.addActionListener(e -> refresh());
		rightBtns.add(refreshBtn);
		JButton copyBtn = PanelUtils.btn("Copy");
		copyBtn.addActionListener(e -> copyDeltas());
		rightBtns.add(copyBtn);
		controlBar.add(rightBtns, BorderLayout.EAST);

		add(controlBar);
		add(PanelUtils.vgap(6));

		// ── Delta viewer (JTextPane in card) ────────────────────────
		JPanel deltaCard = PanelUtils.card();
		deltaCard.setLayout(new BorderLayout());
		deltaCard.setAlignmentX(LEFT_ALIGNMENT);

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
		deltaCard.add(scroll, BorderLayout.CENTER);

		add(deltaCard);
		add(PanelUtils.vgap(4));

		footerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		footerLabel.setFont(footerLabel.getFont().deriveFont(Font.PLAIN, fontSize(10f)));
		footerLabel.setAlignmentX(LEFT_ALIGNMENT);
		add(footerLabel);
	}

	private JPanel buildCapacityCard()
	{
		JPanel card = PanelUtils.card();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.add(PanelUtils.cardHeader("Capacity"));
		card.add(PanelUtils.vgap(6));
		card.add(PanelUtils.kvRow("Filled", filledLabel));
		card.add(PanelUtils.kvRow("Oldest tick", oldestLabel));
		card.add(PanelUtils.kvRow("Newest tick", newestLabel));
		card.add(PanelUtils.kvRow("Memory", memoryLabel));
		return card;
	}

	private JPanel buildHitsplatCard()
	{
		JPanel card = PanelUtils.card();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.add(PanelUtils.cardHeader("Hitsplats"));
		card.add(PanelUtils.vgap(6));
		card.add(PanelUtils.kvRow("Mine", hitsMineLabel));
		card.add(PanelUtils.kvRow("Others", hitsOtherLabel));
		card.add(PanelUtils.kvRow("Block", hitsBlockLabel));
		card.add(PanelUtils.kvRow("Heal", hitsHealLabel));
		return card;
	}

	private JPanel buildDensityCard()
	{
		JPanel card = PanelUtils.card();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.add(PanelUtils.cardHeader("Density"));
		card.add(PanelUtils.vgap(6));
		card.add(PanelUtils.kvRow("NPCs/tick avg", npcAvgLabel));
		card.add(PanelUtils.kvRow("Players/tick", playerAvgLabel));
		card.add(PanelUtils.kvRow("Active ticks", activeLabel));
		card.add(PanelUtils.kvRow("Idle ticks", idleLabel));
		return card;
	}

	private void initStyles(StyledDocument doc)
	{
		Style def = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
		Font mono = PanelUtils.monoFont(11f);

		Style tick = doc.addStyle(S_TICK, def);
		StyleConstants.setForeground(tick, PanelUtils.MUTED);
		StyleConstants.setFontFamily(tick, mono.getFamily());
		StyleConstants.setFontSize(tick, (int) fontSize(10f));

		Style added = doc.addStyle(S_ADDED, def);
		StyleConstants.setForeground(added, ADDED_COLOR);
		StyleConstants.setFontFamily(added, mono.getFamily());
		StyleConstants.setFontSize(added, (int) fontSize(11f));

		Style removed = doc.addStyle(S_REMOVED, def);
		StyleConstants.setForeground(removed, REMOVED_COLOR);
		StyleConstants.setFontFamily(removed, mono.getFamily());
		StyleConstants.setFontSize(removed, (int) fontSize(11f));

		Style changed = doc.addStyle(S_CHANGED, def);
		StyleConstants.setForeground(changed, CHANGED_COLOR);
		StyleConstants.setFontFamily(changed, mono.getFamily());
		StyleConstants.setFontSize(changed, (int) fontSize(11f));

		Style muted = doc.addStyle(S_MUTED, def);
		StyleConstants.setForeground(muted, ColorScheme.LIGHT_GRAY_COLOR);
		StyleConstants.setFontFamily(muted, mono.getFamily());
		StyleConstants.setFontSize(muted, (int) fontSize(10f));
	}

	@SuppressWarnings("unchecked")
	public void refresh()
	{
		TickStateBuffer buffer = plugin.getTickBuffer();
		if (buffer == null)
		{
			textPane.setText("");
			footerLabel.setText("Tick buffer not available");
			return;
		}

		// ── Update summary cards ────────────────────────────────────
		int filled = buffer.filled();
		int cap = buffer.capacity();
		filledLabel.setText(filled + " / " + cap);
		int[] range = buffer.range();
		if (range != null)
		{
			oldestLabel.setText(String.valueOf(range[0]));
			newestLabel.setText(String.valueOf(range[1]));
		}
		else
		{
			oldestLabel.setText("—");
			newestLabel.setText("—");
		}
		// Rough memory estimate: ~2KB per snapshot
		long memKb = (long) filled * 2;
		memoryLabel.setText(memKb > 1024 ? String.format("~%.1f MB", memKb / 1024.0) : "~" + memKb + " KB");

		// ── Compute hitsplat + density stats from buffer ────────────
		List<TickStateBuffer.TickSnapshot> allSnaps = buffer.getLastN(cap);
		int hitsMine = 0, hitsOther = 0, hitsBlock = 0, hitsHeal = 0;
		long totalNpcs = 0, totalPlayers = 0;
		int activeTicks = 0, idleTicks = 0;

		for (TickStateBuffer.TickSnapshot snap : allSnaps)
		{
			if (snap.hits != null)
			{
				for (Map<String, Object> hit : snap.hits)
				{
					boolean mine = Boolean.TRUE.equals(hit.get("isMine"));
					int amount = getInt(hit, "amount", 0);
					int type = getInt(hit, "type", -1);
					if (type == 3 || type == 4)
					{
						hitsHeal++;
					}
					else if (amount == 0)
					{
						hitsBlock++;
					}
					else if (mine)
					{
						hitsMine++;
					}
					else
					{
						hitsOther++;
					}
				}
			}
			totalNpcs += snap.npcs != null ? snap.npcs.size() : 0;
			totalPlayers += snap.otherPlayers != null ? snap.otherPlayers.size() : 0;

			boolean hasActivity = (snap.hits != null && !snap.hits.isEmpty())
				|| (snap.npcs != null && snap.npcs.size() > 0);
			if (hasActivity) activeTicks++;
			else idleTicks++;
		}

		hitsMineLabel.setText(String.valueOf(hitsMine));
		hitsOtherLabel.setText(String.valueOf(hitsOther));
		hitsBlockLabel.setText(String.valueOf(hitsBlock));
		hitsHealLabel.setText(String.valueOf(hitsHeal));

		int snapCount = Math.max(allSnaps.size(), 1);
		npcAvgLabel.setText(String.format("%.1f", (double) totalNpcs / snapCount));
		playerAvgLabel.setText(String.format("%.1f", (double) totalPlayers / snapCount));
		activeLabel.setText(String.valueOf(activeTicks));
		idleLabel.setText(String.valueOf(idleTicks));

		// ── Update sparklines ───────────────────────────────────────
		int sparkPoints = Math.min(allSnaps.size(), 100);
		int step = Math.max(1, allSnaps.size() / sparkPoints);

		float[] evData = new float[sparkPoints];
		float[] hpData = new float[sparkPoints];
		float[] prData = new float[sparkPoints];
		float[] ruData = new float[sparkPoints];

		for (int i = 0; i < sparkPoints; i++)
		{
			int idx = Math.min(i * step, allSnaps.size() - 1);
			TickStateBuffer.TickSnapshot s = allSnaps.get(idx);
			evData[i] = (s.npcs != null ? s.npcs.size() : 0)
				+ (s.otherPlayers != null ? s.otherPlayers.size() : 0)
				+ (s.hits != null ? s.hits.size() : 0);
			if (s.player != null)
			{
				hpData[i] = getInt(s.player, "health", 0);
				prData[i] = getInt(s.player, "prayer", 0);
				Number runVal = s.player.containsKey("runEnergy") ? (Number) s.player.get("runEnergy") : null;
				ruData[i] = runVal != null ? runVal.floatValue() : 0;
			}
		}

		sparkEvents.setData(evData);
		sparkHp.setData(hpData);
		sparkPrayer.setData(prData);
		sparkRun.setData(ruData);

		// ── Delta viewer ────────────────────────────────────────────
		int count = (int) tickCountSpinner.getValue();
		List<TickStateBuffer.TickSnapshot> snapshots = buffer.getLastN(count);

		StyledDocument doc = textPane.getStyledDocument();
		textPane.setText("");

		if (snapshots.size() < 2)
		{
			try
			{
				doc.insertString(0, "Need at least 2 ticks of data", doc.getStyle(S_MUTED));
			}
			catch (BadLocationException ignored) {}
			footerLabel.setText(filled + " / " + cap + " ticks buffered");
			return;
		}

		int deltaCount = 0;

		try
		{
			for (int i = 1; i < snapshots.size(); i++)
			{
				TickStateBuffer.TickSnapshot prev = snapshots.get(i - 1);
				TickStateBuffer.TickSnapshot curr = snapshots.get(i);

				// Player HP/Prayer changes
				if (showSkills.isSelected())
				{
					Map<String, Object> prevPlayer = prev.player;
					Map<String, Object> currPlayer = curr.player;
					if (prevPlayer != null && currPlayer != null)
					{
						int prevHp = getInt(prevPlayer, "health", -1);
						int currHp = getInt(currPlayer, "health", -1);
						if (prevHp != currHp && prevHp >= 0 && currHp >= 0)
						{
							int diff = currHp - prevHp;
							String sign = diff > 0 ? "+" : "";
							insertDelta(doc, curr.tick, "~",
								"Player HP: " + prevHp + " → " + currHp + " (" + sign + diff + ")",
								S_CHANGED);
							deltaCount++;
						}

						int prevPray = getInt(prevPlayer, "prayer", -1);
						int currPray = getInt(currPlayer, "prayer", -1);
						if (prevPray != currPray && prevPray >= 0 && currPray >= 0)
						{
							int diff = currPray - prevPray;
							String sign = diff > 0 ? "+" : "";
							insertDelta(doc, curr.tick, "~",
								"Prayer: " + prevPray + " → " + currPray + " (" + sign + diff + ")",
								S_CHANGED);
							deltaCount++;
						}
					}
				}

				// Skill XP changes
				if (showSkills.isSelected() && prev.skillXp != null && curr.skillXp != null)
				{
					for (int s = 0; s < Math.min(prev.skillXp.length, curr.skillXp.length); s++)
					{
						if (prev.skillXp[s] != curr.skillXp[s])
						{
							int gained = curr.skillXp[s] - prev.skillXp[s];
							if (gained > 0)
							{
								insertDelta(doc, curr.tick, "+",
									"Skill " + s + " XP: +" + String.format("%,d", gained),
									S_ADDED);
								deltaCount++;
							}
						}
					}
				}

				// NPC changes
				if (showNpcs.isSelected())
				{
					deltaCount += diffEntities(doc, prev.npcs, curr.npcs, curr.tick, "NPC");
				}

				// Other player changes
				if (showPlayers.isSelected())
				{
					deltaCount += diffEntities(doc, prev.otherPlayers, curr.otherPlayers, curr.tick, "Player");
				}

				// Hitsplat events
				if (showHits.isSelected() && curr.hits != null)
				{
					for (Map<String, Object> hit : curr.hits)
					{
						String actor = String.valueOf(hit.getOrDefault("actor", "?"));
						int amount = getInt(hit, "amount", 0);
						int type = getInt(hit, "type", -1);
						boolean mine = Boolean.TRUE.equals(hit.get("isMine"));
						String hitDesc = String.format("Hit %d on %s (type=%d, %s)",
							amount, actor, type, mine ? "ours" : "other");
						insertDelta(doc, curr.tick, "!", hitDesc, S_REMOVED);
						deltaCount++;
					}
				}

				if (deltaCount > 200) break;
			}
		}
		catch (BadLocationException ignored) {}

		if (deltaCount == 0)
		{
			try
			{
				doc.insertString(0, "No changes in last " + count + " ticks", doc.getStyle(S_MUTED));
			}
			catch (BadLocationException ignored) {}
		}

		footerLabel.setText(filled + " / " + cap + " ticks | " + deltaCount + " changes");
		textPane.setCaretPosition(0);
	}

	private void insertDelta(StyledDocument doc, int tick, String marker, String text, String style)
		throws BadLocationException
	{
		doc.insertString(doc.getLength(), String.format("[%-6d] ", tick), doc.getStyle(S_TICK));
		doc.insertString(doc.getLength(), marker + " ", doc.getStyle(style));

		String display = text.length() > 80 ? text.substring(0, 77) + "..." : text;
		doc.insertString(doc.getLength(), display + "\n", doc.getStyle(style));
	}

	@SuppressWarnings("unchecked")
	private int diffEntities(StyledDocument doc,
		List<Map<String, Object>> prevList,
		List<Map<String, Object>> currList,
		int tick, String entityType) throws BadLocationException
	{
		if (prevList == null || currList == null) return 0;
		int changes = 0;

		java.util.Set<String> prevIds = new java.util.HashSet<>();
		java.util.Map<String, Map<String, Object>> prevMap = new java.util.HashMap<>();
		for (Map<String, Object> e : prevList)
		{
			String key = getEntityKey(e);
			prevIds.add(key);
			prevMap.put(key, e);
		}

		java.util.Set<String> currIds = new java.util.HashSet<>();
		java.util.Map<String, Map<String, Object>> currMap = new java.util.HashMap<>();
		for (Map<String, Object> e : currList)
		{
			String key = getEntityKey(e);
			currIds.add(key);
			currMap.put(key, e);
		}

		for (String key : currIds)
		{
			if (!prevIds.contains(key))
			{
				Map<String, Object> e = currMap.get(key);
				String name = String.valueOf(e.getOrDefault("name", "?"));
				insertDelta(doc, tick, "+", entityType + " spawned: " + name, S_ADDED);
				changes++;
			}
		}

		for (String key : prevIds)
		{
			if (!currIds.contains(key))
			{
				Map<String, Object> e = prevMap.get(key);
				String name = String.valueOf(e.getOrDefault("name", "?"));
				insertDelta(doc, tick, "-", entityType + " despawned: " + name, S_REMOVED);
				changes++;
			}
		}

		for (String key : currIds)
		{
			if (prevIds.contains(key))
			{
				Map<String, Object> pe = prevMap.get(key);
				Map<String, Object> ce = currMap.get(key);
				int prevHp = getInt(pe, "hpRatio", -1);
				int currHp = getInt(ce, "hpRatio", -1);
				if (prevHp != currHp && prevHp >= 0 && currHp >= 0)
				{
					String name = String.valueOf(ce.getOrDefault("name", "?"));
					insertDelta(doc, tick, "~",
						entityType + " " + name + " HP: " + prevHp + " → " + currHp, S_CHANGED);
					changes++;
				}
			}
		}

		return Math.min(changes, 20);
	}

	private String getEntityKey(Map<String, Object> entity)
	{
		Object index = entity.get("index");
		Object id = entity.get("id");
		Object name = entity.get("name");
		if (index != null) return "idx:" + index;
		if (name != null && id != null) return name + "#" + id;
		return String.valueOf(entity.hashCode());
	}

	private void copyDeltas()
	{
		String selected = textPane.getSelectedText();
		if (selected != null && !selected.isEmpty())
		{
			textPane.copy();
			return;
		}
		// Copy all visible text
		String all = textPane.getText();
		if (all != null && !all.isEmpty())
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
				new StringSelection(all), null);
		}
	}

	private static int getInt(Map<String, Object> map, String key, int defaultVal)
	{
		Object val = map.get(key);
		if (val instanceof Number) return ((Number) val).intValue();
		return defaultVal;
	}

	private static JCheckBox chip(String label, boolean selected)
	{
		JCheckBox cb = new JCheckBox(label, selected);
		cb.setFont(cb.getFont().deriveFont(Font.PLAIN, fontSize(9f)));
		cb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		cb.setBackground(PanelUtils.PAGE_BG);
		cb.setFocusPainted(false);
		return cb;
	}

	// ── Sparkline mini chart ────────────────────────────────────────
	/**
	 * Custom JPanel that draws a polyline sparkline chart.
	 * #141414 background, colored stroke, label on the left.
	 */
	private static class SparklinePanel extends JPanel
	{
		private final String label;
		private final Color lineColor;
		private float[] data;

		SparklinePanel(String label, Color lineColor)
		{
			this.label = label.toUpperCase();
			this.lineColor = lineColor;
			this.data = new float[0];
			setPreferredSize(new Dimension(0, px(50)));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, px(50)));
			setAlignmentX(LEFT_ALIGNMENT);
			setBackground(PanelUtils.FEED_BG);
			setBorder(BorderFactory.createLineBorder(new Color(0x0a, 0x0a, 0x0a), 1));
		}

		void setData(float[] data)
		{
			this.data = data != null ? data : new float[0];
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			int w = getWidth(), h = getHeight();
			int labelW = px(60);
			int chartX = labelW;
			int chartW = w - labelW - px(4);
			int axisH = px(12);  // space for X-axis labels
			int chartH = h - px(4) - axisH;
			int chartY = px(4);

			// Draw label
			g2.setFont(getFont().deriveFont(Font.PLAIN, fontSize(10f)));
			g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			FontMetrics fm = g2.getFontMetrics();
			int ly = (h - fm.getHeight()) / 2 + fm.getAscent();
			g2.drawString(label, px(6), ly);

			// Draw chart area
			if (data.length < 2 || chartW <= 0)
			{
				g2.dispose();
				return;
			}

			// Find min/max for scaling
			float min = Float.MAX_VALUE, max = Float.MIN_VALUE;
			for (float v : data)
			{
				min = Math.min(min, v);
				max = Math.max(max, v);
			}
			float range = max - min;
			if (range < 1) range = 1;

			// Draw polyline
			g2.setColor(lineColor);
			g2.setStroke(new BasicStroke(2f));

			int[] xPoints = new int[data.length];
			int[] yPoints = new int[data.length];
			for (int i = 0; i < data.length; i++)
			{
				xPoints[i] = chartX + (int) ((float) i / (data.length - 1) * chartW);
				yPoints[i] = chartY + chartH - (int) (((data[i] - min) / range) * chartH);
			}
			g2.drawPolyline(xPoints, yPoints, data.length);

			// Subtle fill under the line
			g2.setColor(new Color(lineColor.getRed(), lineColor.getGreen(), lineColor.getBlue(), 30));
			int[] fillX = new int[data.length + 2];
			int[] fillY = new int[data.length + 2];
			System.arraycopy(xPoints, 0, fillX, 0, data.length);
			System.arraycopy(yPoints, 0, fillY, 0, data.length);
			fillX[data.length] = xPoints[data.length - 1];
			fillY[data.length] = chartY + chartH;
			fillX[data.length + 1] = xPoints[0];
			fillY[data.length + 1] = chartY + chartH;
			g2.fillPolygon(fillX, fillY, data.length + 2);

			// X-axis labels
			g2.setFont(getFont().deriveFont(Font.PLAIN, fontSize(8f)));
			g2.setColor(new Color(0x55, 0x55, 0x55));
			FontMetrics axFm = g2.getFontMetrics();
			int axY = chartY + chartH + axFm.getAscent() + px(2);
			String[] axLabels = {"t-600", "t-450", "t-300", "t-150", "now"};
			for (int i = 0; i < axLabels.length; i++)
			{
				float frac = (float) i / (axLabels.length - 1);
				int ax = chartX + (int) (frac * chartW) - axFm.stringWidth(axLabels[i]) / 2;
				ax = Math.max(chartX, Math.min(ax, chartX + chartW - axFm.stringWidth(axLabels[i])));
				g2.drawString(axLabels[i], ax, axY);
			}

			g2.dispose();
		}
	}
}
