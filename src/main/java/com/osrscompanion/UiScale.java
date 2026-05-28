package com.osrscompanion;

import java.awt.Dimension;

/**
 * DPI scaling utility for the GUI.
 *
 * Modern Java 11+ handles DPI scaling natively via the JVM, so we default
 * to SCALE = 1.0 (no manual scaling). The user can override via the config
 * if their setup needs it, but auto-detect is gone — it caused double-scaling
 * and pixelated text on HiDPI monitors.
 *
 * {@link #px(int)}, {@link #fontSize(float)}, and {@link #dim(int,int)} are
 * kept as pass-through helpers so call sites don't need mass-editing if we
 * ever reintroduce optional scaling.
 */
public final class UiScale
{
	/** Pixel + font scale for the standalone frame. */
	public static float SCALE = 1.0f;

	/** Font-only scale for the fixed-width sidebar panel. */
	public static float FONT_SCALE = 1.0f;

	/**
	 * Initialise scale factors.
	 *
	 * @param configScale 0 or 1.0 = no scaling (recommended default).
	 *                    > 1.0 = explicit override (e.g. 1.25, 1.5).
	 */
	public static void init(double configScale)
	{
		if (configScale > 1.01)
		{
			SCALE = (float) configScale;
		}
		else
		{
			SCALE = 1.0f;
		}

		if (SCALE > 4.0f) SCALE = 4.0f;

		FONT_SCALE = 1.0f + (SCALE - 1.0f) * 0.85f;
		if (FONT_SCALE < 1.0f) FONT_SCALE = 1.0f;
	}

	/** Scale a pixel value. */
	public static int px(int base)
	{
		return Math.round(base * SCALE);
	}

	/** Scale a font size. */
	public static float fontSize(float base)
	{
		return base * SCALE;
	}

	/** Sidebar: scale a font more gently than the full UI scale. */
	public static float sideFont(float base)
	{
		return base * FONT_SCALE;
	}

	/** Scale a dimension. */
	public static Dimension dim(int w, int h)
	{
		return new Dimension(Math.round(w * SCALE), Math.round(h * SCALE));
	}

	private UiScale() {}
}
