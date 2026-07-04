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
		keyName = "showAnchor",
		name = "Show emitter markers",
		description = "Draw a small marker at each active emitter vertex, plus a diagnostics line"
	)
	default boolean showAnchor()
	{
		return true;
	}

	@Range(min = 50, max = 8000)
	@ConfigItem(
		position = 1,
		keyName = "maxParticles",
		name = "Max live particles",
		description = "Total particle budget across all emitters; higher values cost FPS"
	)
	default int maxParticles()
	{
		return 1500;
	}
}
