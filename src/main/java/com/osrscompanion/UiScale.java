package com.osrscompanion;

import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.geom.AffineTransform;

/**
 * DPI-aware scaling utility for the GUI.
 *
 * Detects the OS display scaling factor (e.g. 1.0 at 100%, 1.5 at 150%,
 * 2.0 at 200%) and provides helper methods so every font size, component
 * dimension, and border inset scales correctly on HiDPI displays.
 */
public final class UiScale
{
	/** The computed display scale factor (1.0 = 96 DPI / 100%). */
	public static final float SCALE;

	static
	{
		float s = 1.0f;
		try
		{
			// Prefer the AffineTransform scale (accurate on modern Windows/macOS)
			GraphicsDevice gd = GraphicsEnvironment
				.getLocalGraphicsEnvironment()
				.getDefaultScreenDevice();
			GraphicsConfiguration gc = gd.getDefaultConfiguration();
			AffineTransform tx = gc.getDefaultTransform();
			s = (float) tx.getScaleX();
		}
		catch (Throwable ignored)
		{
			try
			{
				// Fallback: Toolkit screen resolution (96 = 100%)
				int dpi = Toolkit.getDefaultToolkit().getScreenResolution();
				s = dpi / 96.0f;
			}
			catch (Throwable ignored2)
			{
				s = 1.0f;
			}
		}

		// Clamp to reasonable range
		if (s < 1.0f) s = 1.0f;
		if (s > 4.0f) s = 4.0f;
		SCALE = s;
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

	/** Scale a dimension. */
	public static Dimension dim(int w, int h)
	{
		return new Dimension(Math.round(w * SCALE), Math.round(h * SCALE));
	}

	private UiScale() {}
}
