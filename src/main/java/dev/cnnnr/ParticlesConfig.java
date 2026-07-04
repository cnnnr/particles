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

	enum Density
	{
		NORMAL("Normal", 1f),
		LOW("Low", 0.66f),
		VERY_LOW("Very low", 0.33f);

		private final String label;
		private final float factor;

		Density(String label, float factor)
		{
			this.label = label;
			this.factor = factor;
		}

		float getFactor()
		{
			return factor;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

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

	enum ApplyTo
	{
		EVERYONE("Everyone"),
		FRIENDS("Friends"),
		ME("Me");

		private final String label;

		ApplyTo(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigItem(
		position = 1,
		keyName = "applyTo",
		name = "Apply to",
		description = "Which players get particles: everyone, you and your friends, or only you"
	)
	default ApplyTo applyTo()
	{
		return ApplyTo.EVERYONE;
	}

	@ConfigItem(
		position = 2,
		keyName = "density",
		name = "Particle density",
		description = "Scales every preset's emission rate; lower for calmer effects or more FPS"
	)
	default Density density()
	{
		return Density.NORMAL;
	}

	@ConfigItem(
		position = 3,
		keyName = "fullSelfDensity",
		name = "Full density for me",
		description = "Keep your own character at full density even when Particle density is lowered"
	)
	default boolean fullSelfDensity()
	{
		return false;
	}

	@Range(min = 2, max = 64)
	@ConfigItem(
		position = 4,
		keyName = "effectRadius",
		name = "Effect radius",
		description = "Only players within this many tiles of you emit particles"
	)
	default int effectRadius()
	{
		return 32;
	}

	// keyName predates the rename; changing it would reset users' setting
	@ConfigItem(
		position = 6,
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
		position = 5,
		keyName = "maxParticles",
		name = "Max live particles",
		description = "Total particle budget across all emitters; higher values cost FPS"
	)
	default int maxParticles()
	{
		return 4096;
	}
}
