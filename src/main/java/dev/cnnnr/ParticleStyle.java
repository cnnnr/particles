package dev.cnnnr;

import java.util.Set;
import lombok.Getter;
import net.runelite.api.ModelData;

/**
 * Resolved runtime style for one emitter piece: pre-baked disc templates for
 * every (size variant, fade step) plus the emission parameters. Built by
 * {@link ParticleRenderer} from an {@link EmitterProfile}; immutable once built.
 */
@Getter
class ParticleStyle
{
	static final int FADE_STEPS = 12;
	/**
	 * Per-particle random size multipliers; variation makes the cloud read as
	 * organic instead of uniform beads.
	 */
	static final float[] SIZE_MULTIPLIERS = {0.65f, 1.0f, 1.4f};

	/**
	 * Unlit disc templates indexed [size variant][fade step], facing +z,
	 * centered on the origin.
	 */
	private final ModelData[][] templates;
	/**
	 * Pre-lit per-face colors, copied into batch canvases in place of
	 * per-tick relighting.
	 */
	private final int[] litColors1;
	private final int[] litColors2;
	private final int[] litColors3;
	private final float lifetimeSec;
	private final float particlesPerSecond;
	/**
	 * Particles per tile of anchor movement; 0 = off. See EmitterProfile.
	 */
	private final float trailDensity;
	private final float riseSpeed;
	private final float spreadSpeed;
	/**
	 * Downward acceleration in local units per second squared; see
	 * EmitterProfile.
	 */
	private final float gravity;
	/**
	 * Billboard elongation factor along screen velocity (1 = round); see
	 * EmitterProfile.
	 */
	private final float stretchFactor;
	/**
	 * The profile's base diameter; the renderer needs it to cap the stretch
	 * so an elongated billboard stays inside the batch bounds volume.
	 */
	private final int baseSize;
	private final float spawnJitter;
	private final int featherStrength;
	/**
	 * Midpoints inserted between mesh-adjacent emitter vertices;
	 * see EmitterProfile.
	 */
	private final int interpolation;
	/**
	 * Camera-ward render nudge in local units; see EmitterProfile.
	 */
	private final float depthBias;
	/**
	 * Lifetime multiplier while the wearer walks or runs; see EmitterProfile.
	 */
	private final float movementLifetimeScale;
	/**
	 * Fixed emit offset from the vertex in model space, applied before the
	 * player's facing rotation so it turns with them. X sideways, Y
	 * forward/back, Z up (positive).
	 */
	private final float offsetX;
	private final float offsetY;
	private final float offsetZ;
	private final Set<Integer> animationIds;
	/**
	 * Flattened [start0, end0, start1, end1, ...] frame windows for action
	 * animation matches, or null for all frames.
	 */
	private final int[] animFrameRanges;

	ParticleStyle(ModelData[][] templates, int[] litColors1, int[] litColors2, int[] litColors3,
		EmitterProfile profile)
	{
		this.templates = templates;
		this.litColors1 = litColors1;
		this.litColors2 = litColors2;
		this.litColors3 = litColors3;
		this.lifetimeSec = profile.getLifetimeMs() / 1000f;
		this.particlesPerSecond = profile.getParticlesPerSecond();
		this.trailDensity = profile.getTrailDensity();
		this.riseSpeed = profile.getRiseSpeed();
		this.spreadSpeed = profile.getSpreadSpeed();
		this.gravity = profile.getGravity();
		this.stretchFactor = 1f + Math.max(0, profile.getStretch()) / 100f;
		this.baseSize = profile.getSize();
		this.spawnJitter = profile.getSpawnJitter();
		this.featherStrength = profile.getFeatherStrength();
		this.interpolation = Math.max(0, Math.min(4, profile.getInterpolation()));
		this.depthBias = profile.getDepthBias();
		this.movementLifetimeScale = Math.max(10, Math.min(100, profile.getMovementLifetime())) / 100f;
		this.offsetX = profile.getOffsetX();
		this.offsetY = profile.getOffsetY();
		this.offsetZ = profile.getOffsetZ();
		this.animationIds = Set.copyOf(profile.getAnimationIds());
		this.animFrameRanges = parseFrameRanges(profile.getAnimFrames());
	}

	/**
	 * Parse "9-13, 15-19, 25" into flattened start/end pairs; single numbers
	 * become one-frame windows, junk tokens are ignored.
	 *
	 * @return null when no valid ranges (= all frames pass)
	 */
	private static int[] parseFrameRanges(String text)
	{
		if (text == null || text.isEmpty())
		{
			return null;
		}
		java.util.List<Integer> bounds = new java.util.ArrayList<>();
		for (String token : text.split(","))
		{
			try
			{
				String[] parts = token.trim().split("-", 2);
				if (parts[0].isEmpty())
				{
					continue;
				}
				int start = Integer.parseInt(parts[0].trim());
				int end = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : start;
				bounds.add(Math.min(start, end));
				bounds.add(Math.max(start, end));
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		if (bounds.isEmpty())
		{
			return null;
		}
		int[] ranges = new int[bounds.size()];
		for (int i = 0; i < ranges.length; i++)
		{
			ranges[i] = bounds.get(i);
		}
		return ranges;
	}

	/**
	 * The frame-window check alone, for spot anim gating where the graphic
	 * ID itself already gates. No windows = every frame passes.
	 */
	boolean frameMatches(int frame)
	{
		if (animFrameRanges == null)
		{
			return true;
		}
		for (int i = 0; i < animFrameRanges.length; i += 2)
		{
			if (frame >= animFrameRanges[i] && frame <= animFrameRanges[i + 1])
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Animation gate: emit only while the player's action or pose animation
	 * matches, with optional frame windows on action matches. An empty gate
	 * always passes; live particles are unaffected and fade out naturally.
	 */
	boolean animationMatches(int actionAnimation, int actionFrame, int poseAnimation)
	{
		if (animationIds.isEmpty())
		{
			return true;
		}
		if (animationIds.contains(actionAnimation))
		{
			if (animFrameRanges == null)
			{
				return true;
			}
			for (int i = 0; i < animFrameRanges.length; i += 2)
			{
				if (actionFrame >= animFrameRanges[i] && actionFrame <= animFrameRanges[i + 1])
				{
					return true;
				}
			}
			return false;
		}
		return animationIds.contains(poseAnimation);
	}
}
