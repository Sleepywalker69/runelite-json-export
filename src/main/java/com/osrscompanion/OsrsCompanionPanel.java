package com.osrscompanion;

import static com.osrscompanion.UiScale.sideFont;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

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
 * Sidebar launcher panel.
 *
 * The PluginPanel width is FIXED by RuneLite (~225px), so this file uses
 * literal pixel paddings — only font sizes scale (via sideFont()).
 * Scaling padding here would only steal width from the content.
 */
public class OsrsCompanionPanel extends PluginPanel
{
	private static final Color BG_BLOCK = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color SUBTLE   = new Color(0x7a, 0x7a, 0x7a);
	private static final Color OK_GREEN = new Color(0x4c, 0xaf, 0x50);
	private static final Color ERR_RED  = new Color(0xf4, 0x43, 0x36);
	private static final Color HINT_FG  = new Color(0x90, 0x90, 0x90);

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
		setBorder(new EmptyBorder(14, 10, 14, 10));

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		body.add(buildBrand());
		body.add(Box.createVerticalStrut(12));
		body.add(buildOpenButton());
		body.add(Box.createVerticalStrut(12));
		body.add(buildStatusBlock());
		body.add(Box.createVerticalStrut(10));
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
		JPanel brand = new JPanel(new BorderLayout(8, 0));
		brand.setBackground(ColorScheme.DARK_GRAY_COLOR);
		brand.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(0, 0, 8, 0)
		));
		brand.setMaximumSize(new Dimension(Short.MAX_VALUE, 46));

		brand.add(new LogoBadge(30), BorderLayout.WEST);

		JPanel titles = new JPanel();
		titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
		titles.setOpaque(false);

		JLabel name = new JLabel("MCP Companion");
		name.setForeground(Color.WHITE);
		name.setFont(name.getFont().deriveFont(Font.BOLD, sideFont(13f)));
		name.setAlignmentX(0);

		JLabel meta = new JLabel("Port " + config.apiPort());
		meta.setForeground(SUBTLE);
		meta.setFont(meta.getFont().deriveFont(Font.PLAIN, sideFont(10f)));
		meta.setAlignmentX(0);

		titles.add(name);
		titles.add(meta);
		brand.add(titles, BorderLayout.CENTER);
		brand.setAlignmentX(0);
		return brand;
	}

	private JComponent buildOpenButton()
	{
		JButton open = new JButton("⤢  Open GUI");
		open.setFont(open.getFont().deriveFont(Font.BOLD, sideFont(13f)));
		open.setForeground(new Color(0x1e, 0x1e, 0x1e));
		open.setBackground(ColorScheme.BRAND_ORANGE);
		open.setFocusPainted(false);
		open.setBorderPainted(false);
		open.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(0xc4, 0x70, 0x08), 1),
			new EmptyBorder(9, 12, 9, 12)
		));
		open.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		open.setAlignmentX(0);
		open.setMaximumSize(new Dimension(Short.MAX_VALUE, 38));
		open.setPreferredSize(new Dimension(200, 38));

		final Color base  = ColorScheme.BRAND_ORANGE;
		final Color hover = new Color(0xff, 0xae, 0x47);
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
		block.setOpaque(false);               // no card bg — bare rows on panel bg
		block.setBorder(new EmptyBorder(8, 0, 8, 0));  // match mockup: padding 8px 0
		block.setAlignmentX(0);

		// API row gets a coloured dot prefix
		JPanel apiRow = new JPanel(new BorderLayout(4, 0));
		apiRow.setOpaque(false);
		apiRow.setMaximumSize(new Dimension(Short.MAX_VALUE, 20));
		apiRow.setAlignmentX(0);
		apiRow.add(keyLabel("API server"), BorderLayout.WEST);
		JPanel apiRight = new JPanel();
		apiRight.setOpaque(false);
		apiRight.setLayout(new BoxLayout(apiRight, BoxLayout.X_AXIS));
		apiRight.add(apiDot);
		apiRight.add(Box.createHorizontalStrut(4));
		apiRight.add(apiValue);
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
		JLabel hint = new JLabel(
			"<html><body style='line-height:1.4'>All controls have moved to the GUI window. "
			+ "Click <b>Open GUI</b> to launch.</body></html>");
		hint.setForeground(HINT_FG);
		hint.setFont(hint.getFont().deriveFont(Font.PLAIN, sideFont(10f)));
		hint.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(10, 0, 0, 0)
		));
		hint.setAlignmentX(0);
		hint.setMaximumSize(new Dimension(Short.MAX_VALUE, 60));
		return hint;
	}

	// ------------------------------------------------------------------
	//  Refresh loop
	// ------------------------------------------------------------------

	public void refreshActiveTab() { refresh(); }

	public void refresh()
	{
		GameStateServer server = plugin.getApiServer();
		if (server != null)
		{
			apiValue.setText(":" + config.apiPort());
			apiValue.setForeground(Color.WHITE);
			apiDot.setColor(OK_GREEN);
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
			apiValue.setForeground(ERR_RED);
			apiDot.setColor(ERR_RED);
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
			playerValue.setText("Offline");
			worldValue.setText("—");
		}

		TickStateBuffer tb = plugin.getTickBuffer();
		if (tb != null)
		{
			bufferValue.setText(tb.filled() + "/" + tb.capacity());
		}
		else
		{
			bufferValue.setText("—");
		}
	}

	/** Called by the plugin when the panel is removed. */
	public void dispose()
	{
		if (refreshTimer != null) refreshTimer.stop();
	}

	// ------------------------------------------------------------------
	//  Helpers
	// ------------------------------------------------------------------

	private static JPanel kv(String key, JLabel value)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setMaximumSize(new Dimension(Short.MAX_VALUE, 20));
		row.setAlignmentX(0);
		row.add(keyLabel(key), BorderLayout.WEST);
		row.add(value, BorderLayout.EAST);
		return row;
	}

	private static JLabel keyLabel(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setFont(l.getFont().deriveFont(Font.PLAIN, sideFont(11f)));
		return l;
	}

	private static JLabel valueLabel(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(Color.WHITE);
		l.setFont(l.getFont().deriveFont(Font.PLAIN, sideFont(11f)));
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
			int s = getWidth();
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(color);
			g2.fillOval(0, getHeight() / 2 - s / 2, s, s);
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
