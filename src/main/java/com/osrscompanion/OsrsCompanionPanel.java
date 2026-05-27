package com.osrscompanion;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Sidebar panel for the OSRS MCP Companion plugin.
 *
 * The full UI now lives in {@link OsrsCompanionFrame}; this sidebar is just a
 * slim launcher: brand header, big "Open GUI" button, and a compact live
 * status block so the user can confirm the API/player/session state without
 * opening the window.
 */
public class OsrsCompanionPanel extends PluginPanel
{
	private final Client client;
	private final OsrsCompanionConfig config;
	private final OsrsCompanionPlugin plugin;

	private final JLabel apiValue     = valueLabel("—");
	private final JLabel playerValue  = valueLabel("—");
	private final JLabel worldValue   = valueLabel("—");
	private final JLabel sessionValue = valueLabel("—");
	private final JLabel sseValue     = valueLabel("—");
	private final JLabel bufferValue  = valueLabel("—");

	private final StateDot apiDot = new StateDot();

	private final Timer refreshTimer;

	public OsrsCompanionPanel(Client client, OsrsCompanionConfig config, OsrsCompanionPlugin plugin)
	{
		super(false);
		this.client = client;
		this.config = config;
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(12, 12, 12, 12));

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		body.add(buildBrand());
		body.add(Box.createVerticalStrut(14));
		body.add(buildOpenButton());
		body.add(Box.createVerticalStrut(16));
		body.add(buildStatusBlock());
		body.add(Box.createVerticalStrut(12));
		body.add(buildHint());
		body.add(Box.createVerticalGlue());

		add(body, BorderLayout.NORTH);

		refreshTimer = new Timer(1000, e -> refresh());
		refreshTimer.start();
		refresh();
	}

	// ------------------------------------------------------------------
	//  Sections
	// ------------------------------------------------------------------

	private JPanel buildBrand()
	{
		JPanel brand = new JPanel(new BorderLayout(10, 0));
		brand.setBackground(ColorScheme.DARK_GRAY_COLOR);
		brand.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(0, 0, 10, 0)
		));
		brand.setMaximumSize(new Dimension(Short.MAX_VALUE, 50));

		brand.add(new LogoBadge(32), BorderLayout.WEST);

		JPanel titles = new JPanel();
		titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
		titles.setOpaque(false);

		JLabel name = new JLabel("MCP Companion");
		name.setForeground(Color.WHITE);
		name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));
		name.setAlignmentX(0);

		JLabel meta = new JLabel("Live game data &middot; port " + config.apiPort());
		meta.setForeground(new Color(0x7a, 0x7a, 0x7a));
		meta.setFont(meta.getFont().deriveFont(Font.PLAIN, 10f));
		meta.setAlignmentX(0);

		titles.add(name);
		titles.add(meta);
		brand.add(titles, BorderLayout.CENTER);
		return brand;
	}

	private JComponent buildOpenButton()
	{
		JButton open = new JButton("Open GUI");
		open.setFont(open.getFont().deriveFont(Font.BOLD, 13f));
		open.setForeground(new Color(0x1e, 0x1e, 0x1e));
		open.setBackground(ColorScheme.BRAND_ORANGE);
		open.setFocusPainted(false);
		open.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(0xc4, 0x70, 0x08), 1),
			new EmptyBorder(10, 14, 10, 14)
		));
		open.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		open.setAlignmentX(0);
		open.setMaximumSize(new Dimension(Short.MAX_VALUE, 40));
		open.setPreferredSize(new Dimension(200, 40));

		// Hover feedback
		Color base   = ColorScheme.BRAND_ORANGE;
		Color hover  = new Color(0xff, 0xae, 0x47);
		open.addMouseListener(new MouseAdapter()
		{
			@Override public void mouseEntered(MouseEvent e) { open.setBackground(hover); }
			@Override public void mouseExited(MouseEvent e)  { open.setBackground(base);  }
		});

		open.addActionListener((ActionEvent e) -> SwingUtilities.invokeLater(plugin::openGui));
		return open;
	}

	private JPanel buildStatusBlock()
	{
		JPanel block = new JPanel();
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		block.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Color.BLACK, 1),
			new EmptyBorder(10, 12, 10, 12)
		));
		block.setAlignmentX(0);
		block.setMaximumSize(new Dimension(Short.MAX_VALUE, 200));

		block.add(sectionTitle("Live status"));
		block.add(Box.createVerticalStrut(6));

		// API row gets a coloured dot prefix
		JPanel apiRow = new JPanel(new BorderLayout(6, 0));
		apiRow.setOpaque(false);
		apiRow.setMaximumSize(new Dimension(Short.MAX_VALUE, 18));
		JLabel apiKey = keyLabel("API server");
		JPanel apiRight = new JPanel();
		apiRight.setOpaque(false);
		apiRight.setLayout(new BoxLayout(apiRight, BoxLayout.X_AXIS));
		apiRight.add(apiDot);
		apiRight.add(Box.createHorizontalStrut(5));
		apiRight.add(apiValue);
		apiRow.add(apiKey, BorderLayout.WEST);
		apiRow.add(apiRight, BorderLayout.EAST);
		block.add(apiRow);

		block.add(kv("Player",      playerValue));
		block.add(kv("World",       worldValue));
		block.add(kv("Session",     sessionValue));
		block.add(kv("SSE clients", sseValue));
		block.add(kv("Tick buffer", bufferValue));
		return block;
	}

	private JComponent buildHint()
	{
		JLabel hint = new JLabel("<html><div style='color:#666;font-size:10px;line-height:1.5'>"
			+ "All controls have moved to the GUI window.<br>"
			+ "Click <b style='color:#ff981f'>Open GUI</b> above to launch it.</div></html>");
		hint.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(10, 0, 0, 0)
		));
		hint.setAlignmentX(0);
		return hint;
	}

	// ------------------------------------------------------------------
	//  Refresh loop
	// ------------------------------------------------------------------

	/** Kept for binary compatibility with the previous panel API. */
	public void refreshActiveTab()
	{
		refresh();
	}

	public void refresh()
	{
		GameStateServer server = plugin.getApiServer();
		if (server != null)
		{
			apiValue.setText("Running :" + config.apiPort());
			apiValue.setForeground(new Color(0x4c, 0xaf, 0x50));
			apiDot.setColor(new Color(0x4c, 0xaf, 0x50));
			sseValue.setText(String.valueOf(server.getSseClientCount()));

			long ms = server.getSessionStartMs();
			if (ms > 0)
			{
				long elapsed = System.currentTimeMillis() - ms;
				long h = elapsed / 3_600_000;
				long m = (elapsed / 60_000) % 60;
				long s = (elapsed / 1000)  % 60;
				sessionValue.setText(h > 0
					? String.format("%d:%02d:%02d", h, m, s)
					: String.format("%02d:%02d", m, s));
			}
			else
			{
				sessionValue.setText("—");
			}
		}
		else
		{
			apiValue.setText("Stopped");
			apiValue.setForeground(new Color(0xf4, 0x43, 0x36));
			apiDot.setColor(new Color(0xf4, 0x43, 0x36));
			sseValue.setText("0");
			sessionValue.setText("—");
		}

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			Player local = client.getLocalPlayer();
			playerValue.setText(local != null && local.getName() != null ? local.getName() : "Unknown");
			worldValue.setText("W" + client.getWorld());
		}
		else
		{
			playerValue.setText("Not logged in");
			worldValue.setText("—");
		}

		TickStateBuffer tb = plugin.getTickBuffer();
		if (tb != null)
		{
			bufferValue.setText(tb.filled() + " / " + tb.capacity());
		}
		else
		{
			bufferValue.setText("—");
		}
	}

	/** Called by the plugin when the panel is removed, so we stop the timer. */
	public void dispose()
	{
		if (refreshTimer != null) refreshTimer.stop();
	}

	// ------------------------------------------------------------------
	//  Helpers
	// ------------------------------------------------------------------

	private static JLabel sectionTitle(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(ColorScheme.BRAND_ORANGE);
		l.setFont(l.getFont().deriveFont(Font.BOLD, 10f));
		l.setBorder(new EmptyBorder(0, 0, 4, 0));
		l.setAlignmentX(0);
		return l;
	}

	private static JPanel kv(String key, JLabel value)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setMaximumSize(new Dimension(Short.MAX_VALUE, 18));
		row.add(keyLabel(key), BorderLayout.WEST);
		row.add(value, BorderLayout.EAST);
		return row;
	}

	private static JLabel keyLabel(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
		return l;
	}

	private static JLabel valueLabel(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(Color.WHITE);
		l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
		return l;
	}

	// ------------------------------------------------------------------
	//  Inner components
	// ------------------------------------------------------------------

	private static class StateDot extends JComponent
	{
		private Color color = new Color(0x66, 0x66, 0x66);
		StateDot()
		{
			Dimension d = new Dimension(8, 8);
			setPreferredSize(d); setMinimumSize(d); setMaximumSize(d);
		}
		void setColor(Color c) { this.color = c; repaint(); }
		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(color);
			g2.fillOval(0, getHeight() / 2 - 4, 8, 8);
			g2.dispose();
		}
	}

	private static class LogoBadge extends JComponent
	{
		LogoBadge(int size)
		{
			Dimension d = new Dimension(size, size);
			setPreferredSize(d); setMinimumSize(d); setMaximumSize(d);
		}
		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth(), h = getHeight();
			g2.setColor(new Color(0xff, 0x98, 0x1f));
			g2.fillRoundRect(0, 0, w, h, 7, 7);
			g2.setColor(new Color(0xc4, 0x70, 0x08));
			g2.drawRoundRect(0, 0, w - 1, h - 1, 7, 7);
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
}
