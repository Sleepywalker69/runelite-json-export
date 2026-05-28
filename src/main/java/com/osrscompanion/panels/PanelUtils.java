package com.osrscompanion.panels;

import static com.osrscompanion.UiScale.*;

import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Shared visual building blocks for all GUI panels.
 * Produces components matching the HTML mockup's .card, .tag, .bar, .btn, .kv styles.
 *
 * Mockup reference: mockup.html CSS variables:
 *   --darker-gray: #1e1e1e   --dark-gray: #282828   --med-gray: #3e3e3e
 *   --light-gray: #b8b8b8    --brand-orange: #ff981f
 */
public final class PanelUtils
{
	// ── Colors ──────────────────────────────────────────────────────
	public static final Color FEED_BG     = new Color(0x14, 0x14, 0x14);   // #141414
	public static final Color CARD_BG     = ColorScheme.DARKER_GRAY_COLOR; // #1e1e1e
	public static final Color PAGE_BG     = ColorScheme.DARK_GRAY_COLOR;   // #282828
	public static final Color BAR_TRACK   = new Color(0x11, 0x11, 0x11);   // #111
	public static final Color MUTED       = new Color(0x77, 0x77, 0x77);   // #777
	public static final Color SUBTITLE_FG = new Color(0x8a, 0x8a, 0x8a);   // #8a8a8a
	public static final Color GREEN       = new Color(0x4c, 0xaf, 0x50);   // #4caf50
	public static final Color RED         = new Color(0xf4, 0x43, 0x36);   // #f44336
	public static final Color BLUE        = new Color(0x21, 0x96, 0xf3);   // #2196f3
	public static final Color YELLOW      = new Color(0xff, 0xc1, 0x07);   // #ffc107
	public static final Color GOLD        = new Color(0xf0, 0xc7, 0x5d);   // #f0c75d  (sender names)
	public static final Color SOURCE_BLUE = new Color(0x5f, 0xb7, 0xf7);   // #5fb7f7  (log source)

	// Bar colors
	public static final Color HP_RED      = new Color(0xdc, 0x32, 0x32);
	public static final Color PRAY_BLUE   = new Color(0x32, 0xb4, 0xdc);
	public static final Color RUN_YELLOW  = new Color(0xdc, 0xbe, 0x32);
	public static final Color SPEC_GREEN  = new Color(0x32, 0xdc, 0x64);
	public static final Color REC_RED     = new Color(0xdc, 0x32, 0x32);

	// Skill type bar colors
	public static final Color SKILL_COMBAT    = new Color(0x9c, 0x1a, 0x1a);
	public static final Color SKILL_GATHERING = new Color(0x4f, 0x7a, 0x18);
	public static final Color SKILL_ARTISAN   = new Color(0x7a, 0x48, 0x18);
	public static final Color SKILL_SUPPORT   = new Color(0x1a, 0x4f, 0x7a);

	// ── Badge presets ───────────────────────────────────────────────
	// Pre-blended against #1e1e1e background (Swing alpha on opaque labels is unreliable)
	public static final BadgeColor BADGE_GREEN  = new BadgeColor(
		new Color(0x6f, 0xce, 0x72), new Color(31, 47, 32),   new Color(52, 90, 53));
	public static final BadgeColor BADGE_BLUE   = new BadgeColor(
		new Color(0x5f, 0xb7, 0xf7), new Color(27, 42, 56),   new Color(35, 73, 106));
	public static final BadgeColor BADGE_YELLOW = new BadgeColor(
		new Color(0xf0, 0xc7, 0x5d), new Color(48, 44, 28),   new Color(72, 64, 33));
	public static final BadgeColor BADGE_GRAY   = new BadgeColor(
		new Color(0xb8, 0xb8, 0xb8), new Color(0x2a, 0x2a, 0x2a), new Color(0x3e, 0x3e, 0x3e));
	public static final BadgeColor BADGE_ORANGE = new BadgeColor(
		new Color(0xff, 0x98, 0x1f), new Color(48, 37, 27),   new Color(72, 54, 33));
	public static final BadgeColor BADGE_RED    = new BadgeColor(
		new Color(0xf4, 0x71, 0x68), new Color(52, 31, 30),   new Color(86, 45, 42));

	// Chat-specific badge backgrounds (blended against #141414)
	public static final BadgeColor CHAT_GAME   = new BadgeColor(
		new Color(0xb8, 0xb8, 0xb8), new Color(0x2a, 0x2a, 0x2a), new Color(0x3e, 0x3e, 0x3e));
	public static final BadgeColor CHAT_PUBLIC = new BadgeColor(
		Color.WHITE, new Color(0x1a, 0x1a, 0x1a), new Color(0x30, 0x30, 0x30));
	public static final BadgeColor CHAT_PRIV   = BADGE_GREEN;
	public static final BadgeColor CHAT_CLAN   = BADGE_BLUE;
	public static final BadgeColor CHAT_FILTER = BADGE_YELLOW;
	public static final BadgeColor CHAT_XP     = BADGE_ORANGE;

	// Log level badge colors (blended against #141414)
	public static final BadgeColor LOG_DEBUG = new BadgeColor(
		new Color(0x88, 0x88, 0x88), new Color(0x2a, 0x2a, 0x2a), new Color(0x3e, 0x3e, 0x3e));
	public static final BadgeColor LOG_INFO  = new BadgeColor(
		new Color(0x6f, 0xce, 0x72), new Color(23, 39, 24),   new Color(44, 82, 45));
	public static final BadgeColor LOG_WARN  = new BadgeColor(
		new Color(0xf0, 0xc7, 0x5d), new Color(40, 36, 20),   new Color(64, 56, 25));
	public static final BadgeColor LOG_ERROR = new BadgeColor(
		new Color(0xf4, 0x71, 0x68), new Color(44, 23, 22),   new Color(78, 37, 34));


	private PanelUtils() {}

	// ── Card container ──────────────────────────────────────────────
	/** .card { background: #1e1e1e; border: 1px solid #000; padding: 12px 14px; } */
	public static JPanel card()
	{
		JPanel p = new JPanel();
		p.setBackground(CARD_BG);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Color.BLACK, 1),
			new EmptyBorder(px(12), px(14), px(12), px(14))
		));
		return p;
	}

	// ── Card header ─────────────────────────────────────────────────
	/** .card h3 — BRAND_ORANGE, BOLD, uppercase, bottom border */
	public static JLabel cardHeader(String title)
	{
		JLabel l = new JLabel(title.toUpperCase());
		l.setForeground(ColorScheme.BRAND_ORANGE);
		l.setFont(l.getFont().deriveFont(Font.BOLD, fontSize(11f)));
		l.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(0, 0, px(6), 0)
		));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	// ── Panel header (page-level title + subtitle) ──────────────────
	/** .panel-head { border-bottom: 1px solid #3e3e3e; margin-bottom: 14px; } */
	public static JPanel panelHead(String title, String subtitle)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(0, 0, px(10), 0)
		));

		JLabel h = new JLabel(title);
		h.setForeground(Color.WHITE);
		h.setFont(h.getFont().deriveFont(Font.BOLD, fontSize(15f)));
		p.add(h, BorderLayout.WEST);

		JLabel sub = new JLabel(subtitle);
		sub.setForeground(SUBTITLE_FG);
		sub.setFont(sub.getFont().deriveFont(Font.PLAIN, fontSize(11f)));
		p.add(sub, BorderLayout.EAST);

		return p;
	}

	// ── KV row (single key-value pair) ──────────────────────────────
	/** Single key-value row for use inside a card. Key is LIGHT_GRAY, value is WHITE. */
	public static JPanel kvRow(String key, JLabel valueLabel)
	{
		JPanel row = new JPanel(new BorderLayout(px(12), 0));
		row.setOpaque(false);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, px(20)));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel k = new JLabel(key);
		k.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		k.setFont(k.getFont().deriveFont(Font.PLAIN, fontSize(12f)));
		k.setPreferredSize(new Dimension(px(110), px(18)));
		row.add(k, BorderLayout.WEST);

		valueLabel.setFont(valueLabel.getFont().deriveFont(Font.PLAIN, fontSize(12f)));
		valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		row.add(valueLabel, BorderLayout.CENTER);

		return row;
	}

	/** Convenience: create a value JLabel with the given text and color. */
	public static JLabel val(String text, Color fg)
	{
		JLabel l = new JLabel(text);
		l.setForeground(fg);
		return l;
	}
	public static JLabel val(String text)
	{
		return val(text, Color.WHITE);
	}

	// ── Badge / Tag ─────────────────────────────────────────────────
	/** .tag { padding: 1px 6px; font-size: 9px; font-weight: 700; uppercase; } */
	public static JLabel badge(String text, BadgeColor colors)
	{
		JLabel l = new JLabel(text.toUpperCase());
		l.setOpaque(true);
		l.setBackground(colors.bg);
		l.setForeground(colors.fg);
		l.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(colors.border, 1),
			new EmptyBorder(px(1), px(6), px(1), px(6))
		));
		l.setFont(l.getFont().deriveFont(Font.BOLD, fontSize(9f)));
		l.setHorizontalAlignment(SwingConstants.CENTER);
		return l;
	}

	// ── Buttons ─────────────────────────────────────────────────────
	/** .btn { background: #3e3e3e; border: 1px solid #000; color: #fff; } */
	public static JButton btn(String text)
	{
		JButton b = new JButton(text);
		b.setFont(b.getFont().deriveFont(Font.PLAIN, fontSize(11f)));
		b.setFocusPainted(false);
		b.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		b.setForeground(Color.WHITE);
		b.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Color.BLACK, 1),
			new EmptyBorder(px(6), px(12), px(6), px(12))
		));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		addHover(b, ColorScheme.MEDIUM_GRAY_COLOR, new Color(0x4e, 0x4e, 0x4e));
		return b;
	}

	/** .btn.primary { background: #ff981f; color: #1e1e1e; font-weight: 700; } */
	public static JButton btnPrimary(String text)
	{
		JButton b = btn(text);
		b.setFont(b.getFont().deriveFont(Font.BOLD));
		b.setBackground(ColorScheme.BRAND_ORANGE);
		b.setForeground(new Color(0x1e, 0x1e, 0x1e));
		b.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(0xc4, 0x70, 0x08), 1),
			new EmptyBorder(px(6), px(12), px(6), px(12))
		));
		addHover(b, ColorScheme.BRAND_ORANGE, new Color(0xff, 0xae, 0x47));
		return b;
	}

	/** .btn.danger { background: #6e2424; } */
	public static JButton btnDanger(String text)
	{
		JButton b = btn(text);
		Color base = new Color(0x6e, 0x24, 0x24);
		b.setBackground(base);
		addHover(b, base, new Color(0x8e, 0x34, 0x34));
		return b;
	}

	private static void addHover(JButton b, Color base, Color hover)
	{
		b.addMouseListener(new MouseAdapter()
		{
			@Override public void mouseEntered(MouseEvent e) { b.setBackground(hover); }
			@Override public void mouseExited(MouseEvent e)  { b.setBackground(base);  }
		});
	}

	// ── Monospace font ──────────────────────────────────────────────
	public static Font monoFont(float size)
	{
		Font f = new Font("Consolas", Font.PLAIN, (int) fontSize(size));
		if (!"Consolas".equalsIgnoreCase(f.getFamily()))
		{
			f = new Font(Font.MONOSPACED, Font.PLAIN, (int) fontSize(size));
		}
		return f;
	}

	// ── Grid helpers ────────────────────────────────────────────────
	/** Two-column grid with gap, matching .grid-2 */
	public static JPanel grid2(JComponent left, JComponent right)
	{
		JPanel p = new JPanel(new GridLayout(1, 2, px(14), 0));
		p.setOpaque(false);
		p.add(left);
		p.add(right);
		return p;
	}

	/** Three-column grid with gap, matching .grid-3 */
	public static JPanel grid3(JComponent a, JComponent b, JComponent c)
	{
		JPanel p = new JPanel(new GridLayout(1, 3, px(14), 0));
		p.setOpaque(false);
		p.add(a);
		p.add(b);
		p.add(c);
		return p;
	}

	// ── Right-click popup for JTextPane ─────────────────────────────
	public static void installTextPopup(JTextPane pane)
	{
		JPopupMenu popup = new JPopupMenu();
		JMenuItem copy = new JMenuItem("Copy");
		copy.addActionListener(e -> pane.copy());
		popup.add(copy);
		JMenuItem selectAll = new JMenuItem("Select All");
		selectAll.addActionListener(e -> pane.selectAll());
		popup.add(selectAll);
		pane.setComponentPopupMenu(popup);
	}

	// ── Status bar (HP / Prayer / Run / Spec / Progress) ────────────
	/**
	 * .bar { height: 16px; background: #111; border: 1px solid #000; }
	 * .fill (colored) + .lab (centered white bold text with shadow)
	 */
	public static class StatusBar extends JPanel
	{
		private final Color barColor;
		private String label;
		private float pct;

		public StatusBar(Color barColor)
		{
			this.barColor = barColor;
			this.label = "";
			this.pct = 0f;
			setPreferredSize(new Dimension(0, px(16)));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, px(16)));
			setBorder(BorderFactory.createLineBorder(Color.BLACK, 1));
			setAlignmentX(Component.LEFT_ALIGNMENT);
		}

		public void update(int current, int max, String prefix)
		{
			max = Math.max(max, 1);
			this.pct = Math.min(1f, (float) current / max);
			this.label = prefix + " · " + current + " / " + max;
			repaint();
		}

		public void update(int pctValue, String label)
		{
			this.pct = Math.min(1f, pctValue / 100f);
			this.label = label;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

			int w = getWidth(), h = getHeight();

			// Track background
			g2.setColor(BAR_TRACK);
			g2.fillRect(0, 0, w, h);

			// Colored fill
			int barW = (int) (w * pct);
			if (barW > 0)
			{
				g2.setColor(barColor);
				g2.fillRect(0, 0, barW, h);
			}

			// Centered text with shadow
			g2.setFont(getFont().deriveFont(Font.BOLD, fontSize(10f)));
			FontMetrics fm = g2.getFontMetrics();
			int tx = (w - fm.stringWidth(label)) / 2;
			int ty = (h - fm.getHeight()) / 2 + fm.getAscent();

			// Shadow
			g2.setColor(new Color(0, 0, 0, 180));
			g2.drawString(label, tx, ty + 1);

			// Text
			g2.setColor(Color.WHITE);
			g2.drawString(label, tx, ty);

			g2.dispose();
		}
	}

	// ── Badge color record ──────────────────────────────────────────
	public static class BadgeColor
	{
		public final Color fg;
		public final Color bg;
		public final Color border;

		public BadgeColor(Color fg, Color bg, Color border)
		{
			this.fg = fg;
			this.bg = bg;
			this.border = border;
		}
	}

	// ── Vertical spacing shortcut ───────────────────────────────────
	public static Component vgap(int pixels)
	{
		return Box.createVerticalStrut(px(pixels));
	}

	// ── Button row (FlowLayout LEFT) ────────────────────────────────
	public static JPanel btnRow(JButton... buttons)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, px(8), 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (JButton b : buttons)
		{
			row.add(b);
		}
		return row;
	}
}
