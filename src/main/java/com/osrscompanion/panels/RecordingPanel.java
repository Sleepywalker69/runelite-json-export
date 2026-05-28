package com.osrscompanion.panels;

import static com.osrscompanion.UiScale.*;

import com.osrscompanion.GameStateServer;
import com.osrscompanion.OsrsCompanionPlugin;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.*;
import java.util.List;

/**
 * Recording tab — start/stop recording, choose duration and event types, view status.
 * Layout matches mockup: grid-2 top (Controls + Preset), full-width 4-col event grid below.
 */
public class RecordingPanel extends JPanel
{
	private final OsrsCompanionPlugin plugin;

	// Controls card KV
	private final RecordingDot recordingDot = new RecordingDot();
	private final JLabel statusVal  = PanelUtils.val("Idle", PanelUtils.MUTED);
	private final JLabel durationVal = PanelUtils.val("180 sec");
	private final JLabel elapsedVal = PanelUtils.val("—");
	private final JLabel eventsVal  = PanelUtils.val("0");
	private final JLabel fileSizeVal = PanelUtils.val("—");

	// Progress bar
	private final PanelUtils.StatusBar progressBar = new PanelUtils.StatusBar(PanelUtils.REC_RED);

	// Buttons
	private final JButton toggleBtn;
	private final JButton pauseBtn   = PanelUtils.btn("Pause");
	private final JButton revealBtn  = PanelUtils.btn("Reveal file");

	// Preset card
	private JSpinner durationSpinner;

	// Event types
	private static final String[] ALL_EVENT_TYPES = {
		"game_tick", "hitsplat", "animation_changed", "npc_spawned", "npc_despawned",
		"actor_death", "var_changed", "menu_clicked", "stat_changed", "item_changed",
		"interacting_changed", "object_spawned", "object_despawned", "projectile_spawned",
		"gfx_created", "chat_message", "sound_effect", "loot_received", "game_state_changed"
	};

	private static final Map<String, String[]> PRESETS = new LinkedHashMap<>();
	static
	{
		PRESETS.put("All", null);
		PRESETS.put("Boss", new String[]{
			"game_tick", "hitsplat", "animation_changed", "npc_spawned", "npc_despawned",
			"actor_death", "menu_clicked", "object_spawned", "object_despawned",
			"projectile_spawned", "gfx_created", "sound_effect", "chat_message",
			"loot_received", "game_state_changed"
		});
		PRESETS.put("Combat", new String[]{
			"game_tick", "hitsplat", "npc_spawned", "npc_despawned", "actor_death",
			"menu_clicked", "object_spawned", "object_despawned", "projectile_spawned",
			"gfx_created", "sound_effect"
		});
		PRESETS.put("Lite", new String[]{
			"game_tick", "hitsplat", "actor_death", "menu_clicked", "projectile_spawned"
		});
		PRESETS.put("Vars", new String[]{"var_changed", "game_tick"});
		PRESETS.put("Clicks", new String[]{"menu_clicked", "game_tick"});
	}

	private final Map<String, JCheckBox> eventCheckboxes = new LinkedHashMap<>();
	private String activePreset = "All";
	private final Map<String, JButton> presetButtons = new LinkedHashMap<>();

	// Recorded Events viewer
	private final JTextPane eventsPane;
	private final JLabel eventsFooterLabel = new JLabel("—");

	public RecordingPanel(OsrsCompanionPlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(PanelUtils.PAGE_BG);
		setBorder(new EmptyBorder(px(16), px(20), px(16), px(20)));

		// Panel header
		JPanel head = PanelUtils.panelHead("Record", "capture a slice of game events to disk");
		head.setAlignmentX(LEFT_ALIGNMENT);
		add(head);
		add(PanelUtils.vgap(14));

		// ── Top row: grid-2 (Controls + Preset) ─────────────────────
		JPanel controlsCard = buildControlsCard();
		JPanel presetCard   = buildPresetCard();
		JPanel topRow = PanelUtils.grid2(controlsCard, presetCard);
		topRow.setAlignmentX(LEFT_ALIGNMENT);
		topRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(300)));
		add(topRow);
		add(PanelUtils.vgap(14));

		// ── Event Types card (full width, 4-col grid) ───────────────
		JPanel eventCard = buildEventTypesCard();
		eventCard.setAlignmentX(LEFT_ALIGNMENT);
		add(eventCard);
		add(PanelUtils.vgap(14));

		// ── Recorded Events viewer ──────────────────────────────────
		JPanel viewerCard = PanelUtils.card();
		viewerCard.setLayout(new BoxLayout(viewerCard, BoxLayout.Y_AXIS));
		viewerCard.setAlignmentX(LEFT_ALIGNMENT);
		viewerCard.add(PanelUtils.cardHeader("Recorded Events"));
		viewerCard.add(PanelUtils.vgap(8));

		JPanel viewerControls = new JPanel(new BorderLayout(px(4), 0));
		viewerControls.setOpaque(false);
		viewerControls.setAlignmentX(LEFT_ALIGNMENT);
		viewerControls.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(30)));

		JButton loadBtn = PanelUtils.btn("Load Events");
		loadBtn.addActionListener(e -> loadRecordedEvents());
		viewerControls.add(loadBtn, BorderLayout.WEST);

		JButton copyEventsBtn = PanelUtils.btn("Copy All");
		copyEventsBtn.addActionListener(e -> copyRecordedEvents());
		viewerControls.add(copyEventsBtn, BorderLayout.EAST);

		viewerCard.add(viewerControls);
		viewerCard.add(PanelUtils.vgap(4));

		eventsPane = new JTextPane();
		eventsPane.setEditable(false);
		eventsPane.setBackground(PanelUtils.FEED_BG);
		eventsPane.setFont(PanelUtils.monoFont(10f));
		eventsPane.setForeground(Color.WHITE);
		PanelUtils.installTextPopup(eventsPane);

		JScrollPane eventsScroll = new JScrollPane(eventsPane);
		eventsScroll.setBorder(BorderFactory.createLineBorder(Color.BLACK, 1));
		eventsScroll.setPreferredSize(new Dimension(0, px(200)));
		eventsScroll.getVerticalScrollBar().setUnitIncrement(px(16));
		eventsScroll.setAlignmentX(LEFT_ALIGNMENT);
		viewerCard.add(eventsScroll);
		viewerCard.add(PanelUtils.vgap(4));

		eventsFooterLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		eventsFooterLabel.setFont(eventsFooterLabel.getFont().deriveFont(Font.PLAIN, fontSize(10f)));
		eventsFooterLabel.setAlignmentX(LEFT_ALIGNMENT);
		viewerCard.add(eventsFooterLabel);

		add(viewerCard);
		add(Box.createVerticalGlue());

		// initialise toggle button reference
		toggleBtn = null; // assigned in buildControlsCard
	}

	// ── Controls Card ───────────────────────────────────────────────
	private JButton internalToggleBtn;

	private JPanel buildControlsCard()
	{
		JPanel card = PanelUtils.card();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

		card.add(PanelUtils.cardHeader("Controls"));
		card.add(PanelUtils.vgap(10));

		// Status row with recording dot
		JPanel statusRow = new JPanel(new BorderLayout(px(12), 0));
		statusRow.setOpaque(false);
		statusRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(20)));
		statusRow.setAlignmentX(LEFT_ALIGNMENT);
		JLabel statusKey = new JLabel("Status");
		statusKey.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusKey.setFont(statusKey.getFont().deriveFont(Font.PLAIN, fontSize(12f)));
		statusKey.setPreferredSize(new Dimension(px(110), px(18)));
		statusRow.add(statusKey, BorderLayout.WEST);
		JPanel statusRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, px(4), 0));
		statusRight.setOpaque(false);
		statusRight.add(recordingDot);
		statusRight.add(statusVal);
		statusRow.add(statusRight, BorderLayout.CENTER);
		card.add(statusRow);
		card.add(PanelUtils.kvRow("Duration",  durationVal));
		card.add(PanelUtils.kvRow("Elapsed",   elapsedVal));
		card.add(PanelUtils.kvRow("Events",    eventsVal));
		card.add(PanelUtils.kvRow("File size", fileSizeVal));
		card.add(PanelUtils.vgap(10));
		card.add(progressBar);
		card.add(PanelUtils.vgap(12));

		internalToggleBtn = PanelUtils.btnDanger("■ Stop");
		internalToggleBtn.setText("Start Recording");
		internalToggleBtn.setBackground(PanelUtils.GREEN);
		internalToggleBtn.addActionListener(e -> toggleRecording());

		JPanel btns = PanelUtils.btnRow(internalToggleBtn, pauseBtn, revealBtn);
		btns.setAlignmentX(LEFT_ALIGNMENT);
		card.add(btns);

		return card;
	}

	// ── Preset Card ─────────────────────────────────────────────────
	private JPanel buildPresetCard()
	{
		JPanel card = PanelUtils.card();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

		card.add(PanelUtils.cardHeader("Preset"));
		card.add(PanelUtils.vgap(10));

		// Preset buttons row
		JPanel presetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, px(4), px(2)));
		presetRow.setOpaque(false);
		presetRow.setAlignmentX(LEFT_ALIGNMENT);
		for (String preset : PRESETS.keySet())
		{
			JButton btn = "All".equals(preset) ? PanelUtils.btnPrimary(preset) : PanelUtils.btn(preset);
			btn.addActionListener(e -> applyPreset(preset));
			presetButtons.put(preset, btn);
			presetRow.add(btn);
		}
		card.add(presetRow);
		card.add(PanelUtils.vgap(10));

		// Duration spinner
		JPanel durRow = new JPanel(new BorderLayout(px(12), 0));
		durRow.setOpaque(false);
		durRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(24)));
		durRow.setAlignmentX(LEFT_ALIGNMENT);
		JLabel durLabel = new JLabel("Duration (sec):");
		durLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		durLabel.setFont(durLabel.getFont().deriveFont(fontSize(11f)));
		durationSpinner = new JSpinner(new SpinnerNumberModel(180, 30, 600, 30));
		durationSpinner.setPreferredSize(new Dimension(px(70), px(22)));
		durRow.add(durLabel, BorderLayout.WEST);
		durRow.add(durationSpinner, BorderLayout.EAST);
		card.add(durRow);
		card.add(PanelUtils.vgap(6));

		// KV info
		JLabel autoSave = PanelUtils.val("Every 30s");
		card.add(PanelUtils.kvRow("Auto-save", autoSave));
		JLabel compress = PanelUtils.val("gzip", PanelUtils.GREEN);
		card.add(PanelUtils.kvRow("Compress", compress));

		return card;
	}

	// ── Event Types Card (4-col grid) ───────────────────────────────
	private JPanel buildEventTypesCard()
	{
		JPanel card = PanelUtils.card();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

		card.add(PanelUtils.cardHeader("Event types"));
		card.add(PanelUtils.vgap(10));

		JPanel grid = new JPanel(new GridLayout(0, 4, px(14), px(6)));
		grid.setOpaque(false);
		grid.setAlignmentX(LEFT_ALIGNMENT);

		for (String type : ALL_EVENT_TYPES)
		{
			JCheckBox cb = new JCheckBox(type, true);
			cb.setFont(cb.getFont().deriveFont(Font.PLAIN, fontSize(11f)));
			cb.setForeground(new Color(0xc8, 0xc8, 0xc8));
			cb.setBackground(PanelUtils.CARD_BG);
			cb.setFocusPainted(false);
			eventCheckboxes.put(type, cb);
			grid.add(cb);
		}

		card.add(grid);
		return card;
	}

	// ── Refresh ─────────────────────────────────────────────────────
	public void refresh()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null)
		{
			statusVal.setText("API server not running");
			statusVal.setForeground(PanelUtils.RED);
			eventsVal.setText("—");
			elapsedVal.setText("—");
			progressBar.update(0, "—");
			return;
		}

		boolean recording = server.isRecording();
		recordingDot.setActive(recording);
		if (recording)
		{
			statusVal.setText("Recording");
			statusVal.setForeground(PanelUtils.REC_RED);
			internalToggleBtn.setText("■ Stop");
			internalToggleBtn.setBackground(new Color(0x6e, 0x24, 0x24));

			int eventCount = server.getRecordingEventCount();
			eventsVal.setText(String.format("%,d", eventCount));

			int[] tickInfo = server.getRecordingTickInfo();
			if (tickInfo != null)
			{
				int elapsed = tickInfo[0];
				int max = tickInfo[1];
				int pct = max > 0 ? (elapsed * 100 / max) : 0;
				int secsLeft = (int) ((max - elapsed) * 0.6);
				elapsedVal.setText(String.format("%02d:%02d / %02d:%02d",
					(int) (elapsed * 0.6) / 60, (int) (elapsed * 0.6) % 60,
					(int) (max * 0.6) / 60, (int) (max * 0.6) % 60));
				progressBar.update(pct, pct + "%");
			}
		}
		else
		{
			statusVal.setText("Idle");
			statusVal.setForeground(PanelUtils.MUTED);
			internalToggleBtn.setText("Start Recording");
			internalToggleBtn.setBackground(PanelUtils.GREEN);

			int eventCount = server.getRecordingEventCount();
			eventsVal.setText(eventCount > 0 ? String.format("%,d recorded", eventCount) : "0");
			elapsedVal.setText("—");
			progressBar.update(0, "—");
		}

		durationVal.setText((int) durationSpinner.getValue() + " sec");
	}

	// ── Recording control ───────────────────────────────────────────
	private void toggleRecording()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null)
		{
			JOptionPane.showMessageDialog(this, "API server not running", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		if (server.isRecording())
		{
			server.stopRecordingFromPanel();
		}
		else
		{
			int duration = (int) durationSpinner.getValue();
			Set<String> filter = getSelectedEventTypes();
			server.startRecordingFromPanel(duration, filter);
		}
		refresh();
	}

	private void applyPreset(String preset)
	{
		activePreset = preset;
		String[] types = PRESETS.get(preset);

		if (types == null)
		{
			for (JCheckBox cb : eventCheckboxes.values()) cb.setSelected(true);
		}
		else
		{
			Set<String> typeSet = new HashSet<>(Arrays.asList(types));
			for (Map.Entry<String, JCheckBox> entry : eventCheckboxes.entrySet())
			{
				entry.getValue().setSelected(typeSet.contains(entry.getKey()));
			}
		}

		// Update button styling
		for (Map.Entry<String, JButton> entry : presetButtons.entrySet())
		{
			JButton b = entry.getValue();
			if (entry.getKey().equals(preset))
			{
				b.setBackground(ColorScheme.BRAND_ORANGE);
				b.setForeground(new Color(0x1e, 0x1e, 0x1e));
				b.setFont(b.getFont().deriveFont(Font.BOLD));
			}
			else
			{
				b.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
				b.setForeground(Color.WHITE);
				b.setFont(b.getFont().deriveFont(Font.PLAIN));
			}
		}
	}

	private Set<String> getSelectedEventTypes()
	{
		Set<String> selected = new LinkedHashSet<>();
		boolean allSelected = true;
		for (Map.Entry<String, JCheckBox> entry : eventCheckboxes.entrySet())
		{
			if (entry.getValue().isSelected())
			{
				selected.add(entry.getKey());
			}
			else
			{
				allSelected = false;
			}
		}
		return allSelected ? null : selected;
	}

	// ── Events viewer ───────────────────────────────────────────────
	@SuppressWarnings("unchecked")
	private void loadRecordedEvents()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null)
		{
			eventsFooterLabel.setText("API server not running");
			return;
		}

		List<Map<String, Object>> events = server.getRecordingBufferCopy();
		StringBuilder sb = new StringBuilder();
		int max = Math.min(events.size(), 500);
		for (int i = 0; i < max; i++)
		{
			Map<String, Object> evt = events.get(i);
			String type = String.valueOf(evt.getOrDefault("type", "?"));
			int tick = evt.containsKey("tick") ? ((Number) evt.get("tick")).intValue() : 0;
			sb.append("[").append(tick).append("] ").append(type).append(": ");
			sb.append(buildEventSummary(type, evt)).append("\n");
		}

		eventsPane.setText(sb.toString());
		eventsPane.setCaretPosition(0);
		eventsFooterLabel.setText(max + " / " + events.size() + " events loaded");
	}

	private void copyRecordedEvents()
	{
		GameStateServer server = plugin.getApiServer();
		if (server == null) return;

		List<Map<String, Object>> events = server.getRecordingBufferCopy();
		StringBuilder sb = new StringBuilder();
		sb.append("=== Recorded Events (").append(events.size()).append(") ===\n");
		for (Map<String, Object> evt : events)
		{
			String type = String.valueOf(evt.getOrDefault("type", "?"));
			int tick = evt.containsKey("tick") ? ((Number) evt.get("tick")).intValue() : 0;
			sb.append("[").append(tick).append("] ").append(type).append(": ");
			sb.append(buildEventSummary(type, evt)).append("\n");
		}
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
			new StringSelection(sb.toString()), null);
	}

	@SuppressWarnings("unchecked")
	private String buildEventSummary(String type, Map<String, Object> evt)
	{
		switch (type)
		{
			case "hitsplat":
			{
				Object target = evt.get("target");
				String name = "?";
				if (target instanceof Map) { Object n = ((Map<?, ?>) target).get("name"); if (n != null) name = String.valueOf(n); }
				int amount = evt.containsKey("amount") ? ((Number) evt.get("amount")).intValue() : 0;
				return "Hit " + amount + " on " + name;
			}
			case "animation_changed":
			{
				Object actor = evt.get("actor");
				String name = "?";
				if (actor instanceof Map) { Object n = ((Map<?, ?>) actor).get("name"); if (n != null) name = String.valueOf(n); }
				int anim = evt.containsKey("animation") ? ((Number) evt.get("animation")).intValue() : -1;
				return name + " anim=" + anim;
			}
			case "menu_clicked":
			{
				Object option = evt.get("option");
				Object target = evt.get("target");
				return (option != null ? option : "") + " " + (target != null ? target : "");
			}
			case "npc_spawned": case "npc_despawned":
			{
				Object npc = evt.get("npc");
				if (npc instanceof Map) { Object n = ((Map<?, ?>) npc).get("name"); return n != null ? String.valueOf(n) : "?"; }
				return "?";
			}
			case "chat_message":
				return String.valueOf(evt.getOrDefault("message", ""));
			default:
				return type;
		}
	}

	// ── Recording dot indicator ─────────────────────────────────────
	/**
	 * Small 8×8 red circle indicator, visible only when recording.
	 * Mimics mockup's recording dot with glow effect.
	 */
	private static class RecordingDot extends JPanel
	{
		private boolean active = false;

		RecordingDot()
		{
			setOpaque(false);
			setPreferredSize(new Dimension(px(12), px(12)));
			setMaximumSize(new Dimension(px(12), px(12)));
		}

		void setActive(boolean active)
		{
			this.active = active;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			if (!active) return;

			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			int w = getWidth(), h = getHeight();
			int cx = w / 2, cy = h / 2;
			int r = px(4);

			// Glow
			g2.setColor(new Color(0xdc, 0x32, 0x32, 60));
			g2.fillOval(cx - r - 2, cy - r - 2, (r + 2) * 2, (r + 2) * 2);

			// Dot
			g2.setColor(PanelUtils.REC_RED);
			g2.fillOval(cx - r, cy - r, r * 2, r * 2);

			g2.dispose();
		}
	}
}
