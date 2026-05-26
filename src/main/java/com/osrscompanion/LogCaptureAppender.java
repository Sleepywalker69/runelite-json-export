package com.osrscompanion;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

import java.util.*;

/**
 * Custom Logback appender that captures log events into a ring buffer.
 * Attached to the root logger at plugin startup, detached on shutdown.
 * Backs the /api/logs endpoint for reading RuneLite console output.
 */
public class LogCaptureAppender extends AppenderBase<ILoggingEvent>
{
	private final List<Map<String, Object>> buffer = Collections.synchronizedList(new ArrayList<>());
	private static final int MAX_BUFFER = 500;

	@Override
	protected void append(ILoggingEvent event)
	{
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("timestamp", event.getTimeStamp());
		entry.put("level", event.getLevel().toString());
		entry.put("loggerName", event.getLoggerName());
		entry.put("message", event.getFormattedMessage());

		IThrowableProxy tp = event.getThrowableProxy();
		if (tp != null)
		{
			entry.put("throwable", ThrowableProxyUtil.asString(tp));
		}

		synchronized (buffer)
		{
			buffer.add(entry);
			while (buffer.size() > MAX_BUFFER)
			{
				buffer.remove(0);
			}
		}
	}

	/**
	 * Get a snapshot of all buffered log entries.
	 */
	public List<Map<String, Object>> getEntries()
	{
		synchronized (buffer)
		{
			return new ArrayList<>(buffer);
		}
	}

	/**
	 * Get the current buffer size.
	 */
	public int size()
	{
		synchronized (buffer)
		{
			return buffer.size();
		}
	}
}
