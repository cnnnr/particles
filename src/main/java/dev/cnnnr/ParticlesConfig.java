package dev.cnnnr;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

/**
 * Global settings only; per-piece particle styles (color, size, rate, motion)
 * are edited in the vertex picker and stored with each emitter piece.
 */
@ConfigGroup(ParticlesConfig.GROUP)
public interface ParticlesConfig extends Config
{
	String GROUP = "cnnnr-particles";

	@ConfigItem(
			position = 0,
			keyName = "hideSidePanel",
			name = "Hide Side Panel",
			description = "Hides the sidebar icon"
	)
	default boolean hideSidePanel()
	{
		return false;
	}

	// keyName predates the rename; changing it would reset users' setting
	@ConfigItem(
		position = 1,
		keyName = "showAnchor",
		name = "Show Debug Text",
		description = "Draw a diagnostics line with live particle counts (plus emitter markers in developer mode)"
	)
	default boolean showAnchor()
	{
		return false;
	}

	@Range(min = 128, max = 8192)
	@ConfigItem(
		position = 2,
		keyName = "maxParticles",
		name = "Max live particles",
		description = "Total particle budget across all emitters; higher values cost FPS"
	)
	default int maxParticles()
	{
		return 4096;
	}
}
