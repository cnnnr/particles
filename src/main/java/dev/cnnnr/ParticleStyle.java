package dev.cnnnr;

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
	private final float lifetimeSec;
	private final float particlesPerSecond;
	private final float riseSpeed;
	private final float spreadSpeed;
	private final float spawnJitter;
	/**
	 * Fixed emit offset from the vertex in model space, applied before the
	 * player's facing rotation so it turns with them. X sideways, Y
	 * forward/back, Z up (positive).
	 */
	private final float offsetX;
	private final float offsetY;
	private final float offsetZ;

	ParticleStyle(ModelData[][] templates, EmitterProfile profile)
	{
		this.templates = templates;
		this.lifetimeSec = profile.getLifetimeMs() / 1000f;
		this.particlesPerSecond = profile.getParticlesPerSecond();
		this.riseSpeed = profile.getRiseSpeed();
		this.spreadSpeed = profile.getSpreadSpeed();
		this.spawnJitter = profile.getSpawnJitter();
		this.offsetX = profile.getOffsetX();
		this.offsetY = profile.getOffsetY();
		this.offsetZ = profile.getOffsetZ();
	}
}
