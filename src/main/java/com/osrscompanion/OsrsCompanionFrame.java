package com.osrscompanion;

import com.osrscompanion.panels.ActionLogPanel;
import com.osrscompanion.panels.ChatPanel;
import com.osrscompanion.panels.DashboardPanel;
import com.osrscompanion.panels.LogPanel;
import com.osrscompanion.panels.RecordingPanel;
import com.osrscompanion.panels.StatsPanel;
import com.osrscompanion.panels.TickBufferPanel;
import com.osrscompanion.panels.VarHistoryPanel;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.ImageUtil;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.MouseInputAdapter;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.osrscompanion.UiScale.*;

/**
 * Standalone GUI window for the OSRS MCP Companion plugin.
 *
 * Layout (BorderLayout):
 *   NORTH  - header bar (logo + title + live status pills)
 *   WEST   - vertical nav rail with the 8 sub-panel tabs
 *   CENTER - CardLayout swapping between the existing panel classes
 *   SOUTH  - status bar (player / tick / save state)
 *
 * All sizes are DPI-scaled via {@link UiScale}.
 *
 * Designed for JDK 11.
 */
public class OsrsCompanionFrame extends JFrame
{
	private static final Dimension DEFAULT_SIZE = dim(1100, 720);
	private static final Dimension MIN_SIZE = dim(880, 560);

	// Tab identifiers — order = display order in the nav rail.
	private static final String TAB_DASHBOARD = "Dashboard";
	private static final String TAB_RECORD    = "Record";
	private static final String TAB_ACTIONS   = "Actions";
	private static final String TAB_CHAT      = "Chat";
	private static final String TAB_LOGS      = "Logs";
	private static final String TAB_STATS     = "Stats";
	private static final String TAB_VARS      = "Vars";
	private static final String TAB_BUFFER    = "Buffer";

	private static final String[] TAB_ORDER = {
		TAB_DASHBOARD, TAB_RECORD, TAB_ACTIONS, TAB_CHAT,
		TAB_LOGS, TAB_STATS, TAB_VARS, TAB_BUFFER
	};

	private final Client client;
	private final OsrsCompanionConfig config;
	private final OsrsCompanionPlugin plugin;

	private final CardLayout cards = new CardLayout();
	private final JPanel cardHost = new JPanel(cards);
	private final Map<String, NavItem> navItems = new LinkedHashMap<>();
	private final Map<String, JPanel> tabPanels = new LinkedHashMap<>();

	private String activeTab = TAB_DASHBOARD;

	// Live header status
	private final StatusPill apiPill  = new StatusPill("API");
	private final StatusPill connPill = new StatusPill("Offline");
	private final StatusPill timePill = new StatusPill("00:00");

	// Footer
	private final JLabel footerPlayer = footerLabel("Not logged in");
	private final JLabel footerTick   = footerLabel("Tick —");
	private final JLabel footerBuffer = footerLabel("Buffer 0/0");
	private final JLabel footerSave   = footerLabel("Not saved yet");

	private final Timer headerTimer; // drives header + footer refresh every second

	// Sub-panels
	private final DashboardPanel  dashboardPanel;
	private final RecordingPanel  recordingPanel;
	private final ActionLogPanel  actionLogPanel;
	private final ChatPanel       chatPanel;
	private final LogPanel        logPanel;
	private final StatsPanel      statsPanel;
	private final VarHistoryPanel varHistoryPanel;
	private final TickBufferPanel tickBufferPanel;

	public OsrsCompanionFrame(Client client, OsrsCompanionConfig config, OsrsCompanionPlugin plugin)
	{
		super("OSRS MCP Companion");
		this.client = client;
		this.config = config;
		this.plugin = plugin;

		// Inherit RuneLite's window icons so the OS taskbar/dock matches.
		try
		{
			setIconImages(Arrays.asList(ClientUI.ICON_128, ClientUI.ICON_16));
		}
		catch (Throwable ignored)
		{
			try
			{
				setIconImage(ImageUtil.loadImageResource(OsrsCompanionFrame.class, "/icon.png"));
			}
			catch (Throwable ignored2) {}
		}

		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				close();
			}
		});

		setSize(DEFAULT_SIZE);
		setMinimumSize(MIN_SIZE);
		setLocationRelativeTo(null);

		// Build sub-panels (same instances the sidebar used).
		dashboardPanel  = new DashboardPanel(client, config, plugin);
		recordingPanel  = new RecordingPanel(plugin);
		actionLogPanel  = new ActionLogPanel(plugin);
		chatPanel       = new ChatPanel(plugin);
		logPanel        = new LogPanel(plugin);
		statsPanel      = new StatsPanel(client, plugin);
		varHistoryPanel = new VarHistoryPanel(plugin);
		tickBufferPanel = new TickBufferPanel(plugin);

		tabPanels.put(TAB_DASHBOARD, dashboardPanel);
		tabPanels.put(TAB_RECORD,    recordingPanel);
		tabPanels.put(TAB_ACTIONS,   actionLogPanel);
		tabPanels.put(TAB_CHAT,      chatPanel);
		tabPanels.put(TAB_LOGS,      logPanel);
		tabPanels.put(TAB_STATS,     statsPanel);
		tabPanels.put(TAB_VARS,      varHistoryPanel);
		tabPanels.put(TAB_BUFFER,    tickBufferPanel);

		// Assemble shell.
		JPanel root = new JPanel(new BorderLayout());
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.add(buildHeader(),  BorderLayout.NORTH);
		root.add(buildNavRail(), BorderLayout.WEST);
		root.add(buildContent(), BorderLayout.CENTER);
		root.add(buildFooter(),  BorderLayout.SOUTH);
		setContentPane(root);

		installShortcuts(root);
		selectTab(TAB_DASHBOARD);

		// 1Hz timer for header pills + footer (sub-panels refresh themselves on game ticks).
		headerTimer = new Timer(1000, e -> refreshChrome());
		headerTimer.setRepeats(true);
	}

	// ---------------------------------------------------------------------
	//  Public lifecycle (called from the plugin)
	// ---------------------------------------------------------------------

	/** Show the window and start the chrome refresh timer. */
	public void open()
	{
		setVisible(true);
		setExtendedState(getExtendedState() & ~JFrame.ICONIFIED);
		toFront();
		repaint();
		if (!headerTimer.isRunning())
		{
			headerTimer.start();
		}
		refreshActiveTab();
		refreshChrome();
	}

	/** Hide the window (state preserved) and stop the timer. */
	public void close()
	{
		setVisible(false);
		if (headerTimer.isRunning())
		{
			headerTimer.stop();
		}
		plugin.onGuiClosed();
	}

	/** Dispose entirely — called from the plugin's shutDown. */
	public void shutdown()
	{
		if (headerTimer != null) headerTimer.stop();
		setVisible(false);
		dispose();
	}

	/** Forward refresh calls from the plugin's game-tick handler to the active panel. */
	public void refreshActiveTab()
	{
		switch (activeTab)
		{
			case TAB_DASHBOARD: dashboardPanel.refresh();  break;
			case TAB_RECORD:    recordingPanel.refresh();  break;
			case TAB_ACTIONS:   actionLogPanel.refresh();  break;
			case TAB_CHAT:      chatPanel.refresh();       break;
			case TAB_LOGS:      logPanel.refresh();        break;
			case TAB_STATS:     statsPanel.refresh();      break;
			case TAB_VARS:      varHistoryPanel.refresh(); break;
			case TAB_BUFFER:    tickBufferPanel.refresh(); break;
			default: break;
		}
	}

	// ---------------------------------------------------------------------
	//  Header
	// ---------------------------------------------------------------------

	private JComponent buildHeader()
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, Color.BLACK),
			new EmptyBorder(px(8), px(16), px(8), px(16))
		));
		header.setPreferredSize(new Dimension(0, px(56)));

		JPanel left = new JPanel();
		left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
		left.setOpaque(false);

		left.add(new LogoBadge(px(36)));
		left.add(Box.createHorizontalStrut(px(12)));

		JPanel titles = new JPanel();
		titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
		titles.setOpaque(false);

		JLabel title = new JLabel("OSRS MCP Companion");
		title.setForeground(Color.WHITE);
		title.setFont(title.getFont().deriveFont(Font.BOLD, fontSize(14f)));
		title.setAlignmentX(0);

		JLabel sub = new JLabel("Live game data & recording bridge");
		sub.setForeground(new Color(0x88, 0x88, 0x88));
		sub.setFont(sub.getFont().deriveFont(Font.PLAIN, fontSize(11f)));
		sub.setAlignmentX(0);

		titles.add(title);
		titles.add(sub);
		left.add(titles);

		JPanel right = new JPanel();
		right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
		right.setOpaque(false);
		right.add(apiPill);
		right.add(Box.createHorizontalStrut(px(8)));
		right.add(connPill);
		right.add(Box.createHorizontalStrut(px(8)));
		right.add(timePill);

		header.add(left,  BorderLayout.WEST);
		header.add(right, BorderLayout.EAST);
		return header;
	}

	// ---------------------------------------------------------------------
	//  Nav rail
	// ---------------------------------------------------------------------

	private JComponent buildNavRail()
	{
		JPanel nav = new JPanel();
		nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
		nav.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		nav.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.BLACK));
		nav.setPreferredSize(new Dimension(px(168), 0));

		nav.add(Box.createVerticalStrut(px(8)));
		for (String name : TAB_ORDER)
		{
			NavItem item = new NavItem(name, glyphFor(name));
			item.setAlignmentX(0);
			item.addMouseListener(new MouseInputAdapter()
			{
				@Override public void mousePressed(MouseEvent e) { selectTab(name); }
			});
			navItems.put(name, item);
			nav.add(item);
		}
		nav.add(Box.createVerticalGlue());

		int footFontPx = Math.max(9, px(9));
		JLabel foot = new JLabel("<html><div style='color:#5e5e5e;font-size:" + footFontPx + "px;line-height:1.4'>"
			+ "jdk &middot; 11<br>~/.runelite/osrs-companion</div></html>");
		foot.setBorder(new EmptyBorder(px(10), px(14), px(10), px(14)));
		foot.setAlignmentX(0);
		nav.add(foot);

		return nav;
	}

	// ---------------------------------------------------------------------
	//  Center cards
	// ---------------------------------------------------------------------

	private JComponent buildContent()
	{
		cardHost.setBackground(ColorScheme.DARK_GRAY_COLOR);
		cardHost.setBorder(new EmptyBorder(0, 0, 0, 0));
		for (Map.Entry<String, JPanel> e : tabPanels.entrySet())
		{
			cardHost.add(e.getValue(), e.getKey());
		}
		return cardHost;
	}

	// ---------------------------------------------------------------------
	//  Footer
	// ---------------------------------------------------------------------

	private JComponent buildFooter()
	{
		JPanel footer = new JPanel();
		footer.setLayout(new BoxLayout(footer, BoxLayout.X_AXIS));
		footer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		footer.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, Color.BLACK),
			new EmptyBorder(px(4), px(14), px(4), px(14))
		));
		footer.setPreferredSize(new Dimension(0, px(26)));

		footer.add(footerPlayer);
		footer.add(sep());
		footer.add(footerTick);
		footer.add(sep());
		footer.add(footerBuffer);
		footer.add(sep());
		footer.add(footerSave);
		footer.add(Box.createHorizontalGlue());

		JLabel meta = footerLabel("JDK 11 · Swing");
		meta.setForeground(new Color(0x66, 0x66, 0x66));
		footer.add(meta);
		return footer;
	}

	private JLabel sep()
	{
		JLabel l = new JLabel("  |  ");
		l.setForeground(new Color(0x44, 0x44, 0x44));
		l.setFont(l.getFont().deriveFont(Font.PLAIN, fontSize(11f)));
		return l;
	}

	private static JLabel footerLabel(String s)
	{
		JLabel l = new JLabel(s);
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setFont(l.getFont().deriveFont(Font.PLAIN, fontSize(11f)));
		return l;
	}

	// ---------------------------------------------------------------------
	//  Tab selection
	// ---------------------------------------------------------------------

	private void selectTab(String name)
	{
		if (!tabPanels.containsKey(name)) return;
		activeTab = name;
		cards.show(cardHost, name);
		for (Map.Entry<String, NavItem> e : navItems.entrySet())
		{
			e.getValue().setActive(e.getKey().equals(name));
		}
		refreshActiveTab();
	}

	private void installShortcuts(JComponent root)
	{
		// Ctrl/Cmd+1..8 jumps tabs; Esc closes window.
		int mask = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
		for (int i = 0; i < TAB_ORDER.length; i++)
		{
			final String name = TAB_ORDER[i];
			KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_1 + i, mask);
			String key = "select-tab-" + i;
			root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, key);
			root.getActionMap().put(key, new AbstractAction()
			{
				@Override public void actionPerformed(java.awt.event.ActionEvent e) { selectTab(name); }
			});
		}
		KeyStroke esc = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(esc, "close-gui");
		root.getActionMap().put("close-gui", new AbstractAction()
		{
			@Override public void actionPerformed(java.awt.event.ActionEvent e) { close(); }
		});
	}

	// ---------------------------------------------------------------------
	//  Chrome refresh (header pills + footer)
	// ---------------------------------------------------------------------

	private void refreshChrome()
	{
		// API pill
		GameStateServer server = plugin.getApiServer();
		if (server != null)
		{
			apiPill.set("API · :" + config.apiPort(), StatusPill.Tone.OK);
		}
		else
		{
			apiPill.set("API · stopped", StatusPill.Tone.ERROR);
		}

		// Connection pill
		boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		if (loggedIn)
		{
			connPill.set("Connected · W" + client.getWorld(), StatusPill.Tone.OK);
		}
		else
		{
			connPill.set("Offline", StatusPill.Tone.IDLE);
		}

		// Session timer pill
		if (server != null && server.getSessionStartMs() > 0)
		{
			long elapsed = System.currentTimeMillis() - server.getSessionStartMs();
			long h = elapsed / 3_600_000;
			long m = (elapsed / 60_000) % 60;
			long s = (elapsed / 1000)  % 60;
			String t = (h > 0)
				? String.format("Session · %d:%02d:%02d", h, m, s)
				: String.format("Session · %02d:%02d", m, s);
			timePill.set(t, StatusPill.Tone.OK);
		}
		else
		{
			timePill.set("Session · 00:00", StatusPill.Tone.IDLE);
		}

		// Footer
		if (loggedIn)
		{
			Player local = client.getLocalPlayer();
			String name = (local != null && local.getName() != null) ? local.getName() : "Unknown";
			footerPlayer.setText(name + " · W" + client.getWorld());
			footerTick.setText("Tick " + client.getTickCount());
		}
		else
		{
			footerPlayer.setText("Not logged in");
			footerTick.setText("Tick —");
		}

		TickStateBuffer tb = plugin.getTickBuffer();
		if (tb != null)
		{
			footerBuffer.setText("Buffer " + tb.filled() + "/" + tb.capacity());
		}

		long lastSave = plugin.getLastSaveMs();
		if (lastSave > 0)
		{
			long ago = (System.currentTimeMillis() - lastSave) / 1000;
			if (ago < 60)        footerSave.setText("✓ Saved " + ago + "s ago");
			else if (ago < 3600) footerSave.setText("✓ Saved " + (ago / 60) + "m ago");
			else                 footerSave.setText("✓ Saved " + (ago / 3600) + "h ago");
			footerSave.setForeground(new Color(0x4c, 0xaf, 0x50));
		}
		else
		{
			footerSave.setText("Not saved yet");
			footerSave.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
	}

	// =====================================================================
	//  Inner UI components
	// =====================================================================

	/** Pill-shaped status indicator in the header (coloured dot + label). */
	private static class StatusPill extends JPanel
	{
		enum Tone { OK, ERROR, IDLE }

		private final JLabel text;
		private Tone tone = Tone.IDLE;

		StatusPill(String initial)
		{
			setLayout(new BorderLayout(px(6), 0));
			setOpaque(false);
			setBorder(new EmptyBorder(px(4), px(10), px(4), px(12)));
			setMaximumSize(dim(220, 30));
			setPreferredSize(dim(160, 30));

			text = new JLabel(initial);
			text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			text.setFont(text.getFont().deriveFont(Font.PLAIN, fontSize(11f)));
			add(new DotIcon(this::currentDotColor), BorderLayout.WEST);
			add(text, BorderLayout.CENTER);
		}

		void set(String label, Tone t)
		{
			this.tone = t;
			this.text.setText(label);
			repaint();
		}

		Color currentDotColor()
		{
			switch (tone)
			{
				case OK:    return new Color(0x4c, 0xaf, 0x50);
				case ERROR: return new Color(0xf4, 0x43, 0x36);
				default:    return new Color(0x66, 0x66, 0x66);
			}
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(ColorScheme.DARK_GRAY_COLOR);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), px(14), px(14));
			g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
			g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, px(14), px(14));
			g2.dispose();
			super.paintComponent(g);
		}
	}

	/** Tiny coloured dot — colour is fetched lazily so pill tone changes update it. */
	private static class DotIcon extends JComponent
	{
		private final java.util.function.Supplier<Color> color;
		DotIcon(java.util.function.Supplier<Color> color)
		{
			this.color = color;
			Dimension d = dim(10, 10);
			setPreferredSize(d);
		}
		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int s = Math.min(getWidth(), getHeight()) - 2;
			int x = (getWidth() - s) / 2;
			int y = (getHeight() - s) / 2;
			g2.setColor(color.get());
			g2.fillOval(x, y, s, s);
			g2.dispose();
		}
	}

	/** Logo badge — orange rounded square with an "M" — original, no game art. */
	private static class LogoBadge extends JComponent
	{
		LogoBadge(int size)
		{
			Dimension d = new Dimension(size, size);
			setPreferredSize(d);
			setMaximumSize(d);
			setMinimumSize(d);
		}
		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth(), h = getHeight();
			g2.setColor(new Color(0xff, 0x98, 0x1f));
			g2.fillRoundRect(0, 0, w, h, px(8), px(8));
			g2.setColor(new Color(0xc4, 0x70, 0x08));
			g2.drawRoundRect(0, 0, w - 1, h - 1, px(8), px(8));
			g2.setColor(new Color(0x1e, 0x1e, 0x1e));
			g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (h * 0.55f)));
			java.awt.FontMetrics fm = g2.getFontMetrics();
			String s = "M";
			int tx = (w - fm.stringWidth(s)) / 2;
			int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
			g2.drawString(s, tx, ty);
			g2.dispose();
		}
	}

	/** A nav rail entry: icon + label + active-state highlight + orange accent stripe. */
	private static class NavItem extends JPanel
	{
		private final JLabel label;
		private final GlyphIcon icon;
		private boolean active = false;

		NavItem(String text, GlyphIcon glyph)
		{
			setLayout(new BorderLayout(px(10), 0));
			setOpaque(true);
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setBorder(new EmptyBorder(px(8), px(14), px(8), px(12)));
			setMaximumSize(new Dimension(Short.MAX_VALUE, px(40)));
			setPreferredSize(dim(168, 40));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			this.icon = glyph;
			this.label = new JLabel(text);
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			label.setFont(label.getFont().deriveFont(Font.PLAIN, fontSize(12f)));

			add(icon, BorderLayout.WEST);
			add(label, BorderLayout.CENTER);

			addMouseListener(new MouseInputAdapter()
			{
				@Override public void mouseEntered(MouseEvent e)
				{
					if (!active)
					{
						setBackground(new Color(0x32, 0x32, 0x32));
					}
				}
				@Override public void mouseExited(MouseEvent e)
				{
					if (!active)
					{
						setBackground(ColorScheme.DARKER_GRAY_COLOR);
					}
				}
			});
		}

		void setActive(boolean active)
		{
			this.active = active;
			if (active)
			{
				setBackground(ColorScheme.DARK_GRAY_COLOR);
				label.setForeground(Color.WHITE);
				label.setFont(label.getFont().deriveFont(Font.BOLD, fontSize(12f)));
				icon.setActive(true);
			}
			else
			{
				setBackground(ColorScheme.DARKER_GRAY_COLOR);
				label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				label.setFont(label.getFont().deriveFont(Font.PLAIN, fontSize(12f)));
				icon.setActive(false);
			}
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			if (active)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setColor(new Color(0xff, 0x98, 0x1f));
				g2.fillRect(0, 0, px(3), getHeight());
				g2.dispose();
			}
		}
	}

	// ---------------------------------------------------------------------
	//  Glyph icons — drawn in code so no external asset dependencies.
	// ---------------------------------------------------------------------

	private static GlyphIcon glyphFor(String tab)
	{
		switch (tab)
		{
			case TAB_DASHBOARD: return new GlyphIcon(GlyphIcon.Kind.DASH);
			case TAB_RECORD:    return new GlyphIcon(GlyphIcon.Kind.REC);
			case TAB_ACTIONS:   return new GlyphIcon(GlyphIcon.Kind.PLAY);
			case TAB_CHAT:      return new GlyphIcon(GlyphIcon.Kind.CHAT);
			case TAB_LOGS:      return new GlyphIcon(GlyphIcon.Kind.LOG);
			case TAB_STATS:     return new GlyphIcon(GlyphIcon.Kind.STATS);
			case TAB_VARS:      return new GlyphIcon(GlyphIcon.Kind.VARS);
			case TAB_BUFFER:    return new GlyphIcon(GlyphIcon.Kind.BUFFER);
			default:            return new GlyphIcon(GlyphIcon.Kind.DASH);
		}
	}

	private static class GlyphIcon extends JComponent
	{
		enum Kind { DASH, REC, PLAY, CHAT, LOG, STATS, VARS, BUFFER }

		private final Kind kind;
		private boolean active = false;

		GlyphIcon(Kind kind)
		{
			this.kind = kind;
			Dimension d = dim(20, 20);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(d);
		}

		void setActive(boolean a) { this.active = a; repaint(); }

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(active ? new Color(0xff, 0x98, 0x1f) : ColorScheme.LIGHT_GRAY_COLOR);
			g2.setStroke(new BasicStroke(1.6f * SCALE));

			int w = getWidth(), h = getHeight();
			int p = (int) (3 * SCALE); // padding
			int x = p, y = p, iw = w - 2 * p, ih = h - 2 * p;

			switch (kind)
			{
				case DASH:
					int cell = (iw - 2) / 2;
					g2.drawRect(x, y, cell, cell);
					g2.drawRect(x + cell + 2, y, cell, cell);
					g2.drawRect(x, y + cell + 2, cell, cell);
					g2.drawRect(x + cell + 2, y + cell + 2, cell, cell);
					break;
				case REC:
					g2.fillOval(x + 1, y + 1, iw - 2, ih - 2);
					break;
				case PLAY:
					int[] xs = { x + 1, x + iw - 1, x + 1 };
					int[] ys = { y + 1, y + ih / 2, y + ih - 1 };
					g2.fillPolygon(xs, ys, 3);
					break;
				case CHAT:
					g2.drawRoundRect(x, y, iw, ih - 4, 4, 4);
					int[] tx = { x + 3, x + 7, x + 7 };
					int[] ty = { y + ih, y + ih - 5, y + ih - 5 };
					g2.fillPolygon(tx, ty, 3);
					break;
				case LOG:
					for (int i = 0; i < 4; i++)
					{
						int yy = y + i * ((ih - 1) / 3);
						int len = (i % 2 == 0) ? iw : (iw - 4);
						g2.drawLine(x, yy, x + len, yy);
					}
					break;
				case STATS:
					int bw = (iw - 6) / 4;
					int[] heights = { ih / 2, (ih * 3) / 4, ih / 3, ih - 2 };
					for (int i = 0; i < 4; i++)
					{
						int bx = x + i * (bw + 2);
						g2.fillRect(bx, y + ih - heights[i], bw, heights[i]);
					}
					break;
				case VARS:
					g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, iw - 2));
					java.awt.FontMetrics vfm = g2.getFontMetrics();
					String vs = "V";
					int vtx = x + (iw - vfm.stringWidth(vs)) / 2;
					int vty = y + (ih - vfm.getHeight()) / 2 + vfm.getAscent();
					g2.drawString(vs, vtx, vty);
					break;
				case BUFFER:
					int sw = (iw - 6) / 4;
					for (int i = 0; i < 4; i++)
					{
						int bx = x + i * (sw + 2);
						if (i < 3) g2.fillRect(bx, y + ih / 3, sw, ih / 3);
						else       g2.drawRect(bx, y + ih / 3, sw, ih / 3);
					}
					break;
			}
			g2.dispose();
		}
	}
}
